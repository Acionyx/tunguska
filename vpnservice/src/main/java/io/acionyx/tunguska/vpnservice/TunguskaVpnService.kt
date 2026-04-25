package io.acionyx.tunguska.vpnservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.SecureRandom
import java.util.LinkedHashSet
import java.util.concurrent.Executors

class TunguskaVpnService : VpnService() {
    private val runtimeAuditor = RuntimeListenerAuditor()
    private val handler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val healthCheckRandom = SecureRandom()
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val defaultNetworkChangeTracker = DefaultNetworkChangeTracker()
    private val runtimeWatchdog = RuntimeSessionWatchdog(
        healthProbe = ActiveRuntimeSessionStore::health,
        stopRuntime = ActiveRuntimeSessionStore::stop,
        onHealthObserved = VpnRuntimeStore::recordEngineHealth,
        onRuntimeStopped = VpnRuntimeStore::recordEngineSession,
        onFailClosed = VpnRuntimeStore::markFailClosed,
    )
    private val defaultNetworkRestartRunnable = Runnable { restartRuntimeAfterDefaultNetworkChange() }
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            val outcome = runtimeWatchdog.evaluate()
            if (outcome.failureReason != null) {
                unregisterDefaultNetworkMonitor()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            if (ActiveRuntimeSessionStore.isActive()) {
                handler.postDelayed(this, nextHealthCheckDelayMs())
            }
        }
    }
    @Volatile
    private var startInFlight: Boolean = false

    @Volatile
    private var startGeneration: Int = 0

    @Volatile
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                invalidatePendingStarts()
                cancelHealthChecks()
                unregisterDefaultNetworkMonitor()
                ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
                VpnRuntimeStore.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }

            ACTION_START_RUNTIME -> {
                if (ActiveRuntimeSessionStore.isActive()) {
                    updateDefaultNetworkMonitor(activeRecoveryStrategy())
                    scheduleHealthChecks()
                    return START_NOT_STICKY
                }
                if (startInFlight) {
                    return START_NOT_STICKY
                }
                val request = VpnRuntimeStore.stagedRequest() ?: return START_NOT_STICKY
                val snapshot = VpnRuntimeStore.snapshot()
                val generation = beginStart()
                startRuntimeForeground(buildNotification(intent))
                startupExecutor.execute {
                    Log.i(TAG, "Starting VPN runtime session in background generation=$generation")
                    val auditResult = runtimeAuditor.auditUid(applicationInfo.uid)
                    VpnRuntimeStore.recordAudit(auditResult)
                    if (!isCurrentGeneration(generation)) {
                        completeStart(generation)
                        return@execute
                    }
                    if (auditResult.status == RuntimeAuditStatus.FAIL) {
                        completeStart(generation)
                        handler.post {
                            cancelHealthChecks()
                            unregisterDefaultNetworkMonitor()
                            VpnRuntimeStore.markFailClosed(auditResult.summary)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        return@execute
                    }
                    val startOutcome = runtimeSessionController().start(
                        request = request,
                        sessionLabel = snapshot.sessionLabel ?: TunnelInterfacePlanner.plan(request.plan).sessionLabel,
                        runtimeDependencies = EmbeddedRuntimeDependencies(
                            context = applicationContext,
                            vpnService = this,
                        ),
                    )
                    if (!isCurrentGeneration(generation)) {
                        ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
                        completeStart(generation)
                        return@execute
                    }
                    completeStart(generation)
                    if (startOutcome.failureReason != null) {
                        Log.e(TAG, "VPN runtime start failed: ${startOutcome.failureReason}")
                        handler.post {
                            cancelHealthChecks()
                            unregisterDefaultNetworkMonitor()
                            VpnRuntimeStore.markFailClosed(startOutcome.failureReason)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        return@execute
                    }
                    handler.post {
                        updateDefaultNetworkMonitor(request.runtimeStrategy)
                        scheduleHealthChecks()
                    }
                }
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    private fun buildNotification(intent: Intent): Notification {
        ensureChannel()
        val hash = intent.getStringExtra(EXTRA_CONFIG_HASH).orEmpty().take(12)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.vpn_runtime_title))
            .setContentText(getString(R.string.vpn_runtime_message, hash))
            .setOngoing(true)
            .build()
    }

    private fun startRuntimeForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            return
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onRevoke() {
        super.onRevoke()
        invalidatePendingStarts()
        if (ActiveRuntimeSessionStore.isActive()) {
            cancelHealthChecks()
            unregisterDefaultNetworkMonitor()
            ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
            VpnRuntimeStore.markFailClosed("VPN permission was revoked while the runtime was active.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        invalidatePendingStarts()
        cancelHealthChecks()
        unregisterDefaultNetworkMonitor()
        if (ActiveRuntimeSessionStore.isActive()) {
            ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
            VpnRuntimeStore.markFailClosed("VPN service was destroyed while the runtime was active.")
        }
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun scheduleHealthChecks() {
        handler.removeCallbacks(healthCheckRunnable)
        handler.post(healthCheckRunnable)
    }

    private fun cancelHealthChecks() {
        handler.removeCallbacks(healthCheckRunnable)
    }

    private fun updateDefaultNetworkMonitor(strategy: EmbeddedRuntimeStrategyId?) {
        if (strategy == EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) {
            registerDefaultNetworkMonitor()
        } else {
            unregisterDefaultNetworkMonitor()
        }
    }

    private fun registerDefaultNetworkMonitor() {
        if (defaultNetworkCallback != null) {
            defaultNetworkChangeTracker.seed(resolveDefaultNetworkIdentity())
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handlePotentialDefaultNetworkChange("available")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handlePotentialDefaultNetworkChange("capabilities")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                handlePotentialDefaultNetworkChange("link-properties")
            }

            override fun onLost(network: Network) {
                handlePotentialDefaultNetworkChange("lost")
            }
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                defaultNetworkCallback = callback
                defaultNetworkChangeTracker.seed(resolveDefaultNetworkIdentity())
            }
            .onFailure { error ->
                Log.w(TAG, "Unable to register default network callback.", error)
                defaultNetworkChangeTracker.clear()
            }
    }

    private fun unregisterDefaultNetworkMonitor() {
        handler.removeCallbacks(defaultNetworkRestartRunnable)
        defaultNetworkChangeTracker.clear()
        val callback = defaultNetworkCallback ?: return
        defaultNetworkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun handlePotentialDefaultNetworkChange(reason: String) {
        if (activeRecoveryStrategy() != EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) {
            return
        }
        val identity = resolveDefaultNetworkIdentity()
        val shouldRestart = defaultNetworkChangeTracker.recordObservation(
            identity = identity,
            runtimeActive = ActiveRuntimeSessionStore.isActive(),
        )
        if (!shouldRestart) {
            return
        }
        val description = identity?.description() ?: "unavailable"
        Log.i(TAG, "Default network changed ($reason) to $description; scheduling runtime restart.")
        VpnRuntimeStore.recordRuntimeTelemetry(
            nativeEvent = "Detected default network change ($reason) to $description; scheduling xray runtime restart.",
        )
        handler.removeCallbacks(defaultNetworkRestartRunnable)
        handler.postDelayed(defaultNetworkRestartRunnable, DEFAULT_NETWORK_RESTART_DEBOUNCE_MS)
    }

    private fun restartRuntimeAfterDefaultNetworkChange() {
        if (!ActiveRuntimeSessionStore.isActive()) {
            return
        }
        if (activeRecoveryStrategy() != EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) {
            return
        }
        if (startInFlight) {
            handler.postDelayed(defaultNetworkRestartRunnable, DEFAULT_NETWORK_RESTART_DEBOUNCE_MS)
            return
        }
        val request = VpnRuntimeStore.stagedRequest() ?: return
        val snapshot = VpnRuntimeStore.snapshot()
        val identity = resolveDefaultNetworkIdentity() ?: return
        cancelHealthChecks()
        val generation = beginStart()
        startupExecutor.execute {
            Log.i(TAG, "Restarting VPN runtime after default network change to ${identity.description()} generation=$generation")
            VpnRuntimeStore.recordRuntimeTelemetry(
                nativeEvent = "Restarting runtime after default network change to ${identity.description()}.",
            )
            ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
            if (!isCurrentGeneration(generation)) {
                completeStart(generation)
                return@execute
            }
            VpnRuntimeStore.markStartRequested()
            val startOutcome = runtimeSessionController().start(
                request = request,
                sessionLabel = snapshot.sessionLabel ?: TunnelInterfacePlanner.plan(request.plan).sessionLabel,
                runtimeDependencies = EmbeddedRuntimeDependencies(
                    context = applicationContext,
                    vpnService = this,
                ),
            )
            if (!isCurrentGeneration(generation)) {
                ActiveRuntimeSessionStore.stop()?.let(VpnRuntimeStore::recordEngineSession)
                completeStart(generation)
                return@execute
            }
            completeStart(generation)
            if (startOutcome.failureReason != null) {
                Log.e(TAG, "VPN runtime restart failed after default network change: ${startOutcome.failureReason}")
                handler.post {
                    unregisterDefaultNetworkMonitor()
                    VpnRuntimeStore.markFailClosed("Default network change restart failed: ${startOutcome.failureReason}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@execute
            }
            handler.post {
                updateDefaultNetworkMonitor(request.runtimeStrategy)
                scheduleHealthChecks()
            }
        }
    }

    private fun activeRecoveryStrategy(): EmbeddedRuntimeStrategyId? =
        VpnRuntimeStore.snapshot().activeStrategy ?: VpnRuntimeStore.stagedRequest()?.runtimeStrategy

    private fun resolveDefaultNetworkIdentity(): DefaultNetworkIdentity? {
        val candidates = LinkedHashSet<Network>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork?.let(candidates::add)
        }
        candidates += runCatching { connectivityManager.allNetworks.toList() }.getOrDefault(emptyList())
        return candidates.firstNotNullOfOrNull(::defaultNetworkIdentity)
    }

    private fun defaultNetworkIdentity(network: Network): DefaultNetworkIdentity? {
        val capabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull() ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return null
        }
        val linkProperties = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull() ?: return null
        val interfaceName = linkProperties.interfaceName ?: return null
        return DefaultNetworkIdentity(
            networkHandle = network.networkHandle,
            interfaceName = interfaceName,
        )
    }

    private fun nextHealthCheckDelayMs(): Long {
        val jitterRange = (HEALTH_CHECK_JITTER_MS * 2 + 1).toInt()
        val jitter = healthCheckRandom.nextInt(jitterRange) - HEALTH_CHECK_JITTER_MS.toInt()
        return (HEALTH_CHECK_INTERVAL_MS + jitter).coerceAtLeast(MIN_HEALTH_CHECK_DELAY_MS)
    }

    @Synchronized
    private fun beginStart(): Int {
        startGeneration += 1
        startInFlight = true
        return startGeneration
    }

    @Synchronized
    private fun completeStart(generation: Int) {
        if (startGeneration == generation) {
            startInFlight = false
        }
    }

    @Synchronized
    private fun invalidatePendingStarts() {
        startGeneration += 1
        startInFlight = false
    }

    @Synchronized
    private fun isCurrentGeneration(generation: Int): Boolean = startGeneration == generation

    private fun runtimeSessionController(): RuntimeSessionController = RuntimeSessionController(
        engineHostRegistry = EmbeddedEngineHostRegistry(
            strategyPolicy = EmbeddedRuntimeStrategyPolicyStore.snapshot(),
        ),
        workspaceFactoryProvider = {
            EngineSessionWorkspaceFactory.fromContext(cacheDir = cacheDir)
        },
    )

    companion object {
        private const val TAG = "TunguskaVpnService"
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val HEALTH_CHECK_JITTER_MS = 2_000L
        private const val MIN_HEALTH_CHECK_DELAY_MS = 5_000L
        private const val DEFAULT_NETWORK_RESTART_DEBOUNCE_MS = 1_500L
        private const val ACTION_START_RUNTIME = "io.acionyx.tunguska.vpnservice.action.START_RUNTIME"
        private const val ACTION_STOP = "io.acionyx.tunguska.vpnservice.action.STOP"
        private const val EXTRA_CONFIG_HASH = "io.acionyx.tunguska.vpnservice.extra.CONFIG_HASH"
        private const val CHANNEL_ID = "tunguska.vpn.runtime"
        private const val NOTIFICATION_ID = 4041

        fun startRuntimeIntent(context: Context, configHash: String): Intent = Intent(context, TunguskaVpnService::class.java).apply {
            action = ACTION_START_RUNTIME
            putExtra(EXTRA_CONFIG_HASH, configHash)
        }

        fun stopIntent(context: Context): Intent = Intent(context, TunguskaVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
