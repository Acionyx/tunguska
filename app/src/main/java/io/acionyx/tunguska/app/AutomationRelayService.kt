package io.acionyx.tunguska.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import io.acionyx.tunguska.R

class AutomationRelayService : Service() {
    private val settingsRepository by lazy { AutomationIntegrationRepository(applicationContext) }
    private val orchestrator by lazy { RuntimeAutomationOrchestrator(applicationContext) }
    private val statusStore by lazy { AutomationRelayStatusStore(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Created automation relay service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_REQUEST_ACTION).orEmpty()
        val callerHint = intent?.getStringExtra(EXTRA_CALLER_HINT)
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()

        if (action.isBlank() || requestId.isBlank()) {
            Log.w(TAG, "Ignoring automation service start with missing action/requestId")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        promoteToForeground(action)
        Log.i(TAG, "Running action='$action' requestId=$requestId")
        serviceScope.launch {
            statusStore.markProgress(
                requestId = requestId,
                action = action,
                callerHint = callerHint,
                summary = "Automation relay short-service is running.",
            )
            val result = runCatching {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    when (action) {
                        AutomationRelayContract.ACTION_START -> {
                            val runtimeStrategy = settingsRepository.load().runtimeStrategy
                            Log.i(TAG, "Calling startStoredProfile() requestId=$requestId")
                            statusStore.markProgress(
                                requestId = requestId,
                                action = action,
                                callerHint = callerHint,
                                summary = "Preparing and starting the stored Tunguska runtime profile with $runtimeStrategy.",
                            )
                            orchestrator.startStoredProfile(runtimeStrategy = runtimeStrategy).also {
                                Log.i(TAG, "startStoredProfile() finished requestId=$requestId status=${it.status}")
                            }
                        }

                        AutomationRelayContract.ACTION_STOP -> {
                            Log.i(TAG, "Calling stopRuntime() requestId=$requestId")
                            statusStore.markProgress(
                                requestId = requestId,
                                action = action,
                                callerHint = callerHint,
                                summary = "Stopping the isolated Tunguska runtime.",
                            )
                            orchestrator.stopRuntime().also {
                                Log.i(TAG, "stopRuntime() finished requestId=$requestId status=${it.status}")
                            }
                        }

                        else -> RuntimeAutomationResult(
                            status = AutomationCommandStatus.CONTROL_CHANNEL_ERROR,
                            summary = "Unsupported Tunguska automation action '$action'.",
                            error = "Unsupported action",
                        )
                    }
                }
            }.getOrElse { error ->
                val status = when (error) {
                    is TimeoutCancellationException -> AutomationCommandStatus.CONTROL_CHANNEL_ERROR
                    else -> AutomationCommandStatus.CONTROL_CHANNEL_ERROR
                }
                Log.e(TAG, "Automation relay failed requestId=$requestId", error)
                RuntimeAutomationResult(
                    status = status,
                    summary = "The Tunguska automation relay did not complete the requested action.",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
            settingsRepository.recordResult(
                status = result.status,
                error = result.error ?: result.summary.takeIf { result.status != AutomationCommandStatus.SUCCESS },
                callerHint = callerHint,
            )
            statusStore.markCompleted(
                requestId = requestId,
                action = action,
                callerHint = callerHint,
                result = result,
            )
            Log.i(TAG, "Completed action='$action' requestId=$requestId status=${result.status}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying automation relay service")
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        Log.e(TAG, "Automation relay short-service timed out startId=$startId")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun promoteToForeground(action: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tunguska_notification)
            .setContentTitle(getString(R.string.automation_notification_title))
            .setContentText(
                when (action) {
                    AutomationRelayContract.ACTION_START -> getString(R.string.automation_notification_start_text)
                    AutomationRelayContract.ACTION_STOP -> getString(R.string.automation_notification_stop_text)
                    else -> getString(R.string.automation_notification_generic_text)
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.automation_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.automation_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val TAG: String = "AutomationRelay"
        private const val CHANNEL_ID: String = "automation-relay"
        private const val NOTIFICATION_ID: Int = 4042
        private const val REQUEST_TIMEOUT_MS: Long = 20_000L
        private const val EXTRA_REQUEST_ACTION: String = "request_action"
        private const val EXTRA_REQUEST_ID: String = "request_id"
        private const val EXTRA_CALLER_HINT: String = AutomationRelayContract.EXTRA_CALLER_HINT

        fun buildIntent(
            context: Context,
            action: String,
            requestId: String,
            callerHint: String?,
        ): Intent = Intent(context, AutomationRelayService::class.java).apply {
            putExtra(EXTRA_REQUEST_ACTION, action)
            putExtra(EXTRA_REQUEST_ID, requestId)
            callerHint?.let { putExtra(EXTRA_CALLER_HINT, it) }
        }
    }
}
