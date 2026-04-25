package io.acionyx.tunguska.vpnservice

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.Executors

class VpnRuntimeControlService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val incomingMessenger = Messenger(IncomingHandler())
    private val runtimeAuditor = RuntimeListenerAuditor()
    private val egressProbeExecutor = Executors.newSingleThreadExecutor()
    private val periodicAuditRunnable = object : Runnable {
        override fun run() {
            runRuntimeAudit()
            handler.postDelayed(this, PERIODIC_AUDIT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        schedulePeriodicAudit()
        return incomingMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(periodicAuditRunnable)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicAuditRunnable)
        egressProbeExecutor.shutdownNow()
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnRuntimeContract.MSG_GET_STATUS -> {
                    reply(msg.replyTo, VpnRuntimeContract.statusMessage(runRuntimeAudit()))
                }

                VpnRuntimeContract.MSG_PROBE_EGRESS_IP -> {
                    val replyTo = msg.replyTo
                    egressProbeExecutor.execute {
                        val observation = runEngineEgressProbe()
                        handler.post {
                            reply(replyTo, VpnRuntimeContract.egressIpMessage(observation))
                        }
                    }
                }

                VpnRuntimeContract.MSG_SET_CONFIGURED_RUNTIME_STRATEGY -> {
                    runCatching {
                        VpnRuntimeStore.recordConfiguredStrategy(
                            strategy = VpnRuntimeContract.decodeConfiguredRuntimeStrategy(msg.data),
                            laneCompatibility = VpnRuntimeContract.decodeConfiguredLaneCompatibility(msg.data),
                        )
                        VpnRuntimeContract.statusMessage(runRuntimeAudit())
                    }.onSuccess { response ->
                        reply(msg.replyTo, response)
                    }.onFailure { error ->
                        reply(
                            msg.replyTo,
                            VpnRuntimeContract.errorMessage(
                                message = error.message ?: error.javaClass.simpleName,
                                snapshot = VpnRuntimeStore.snapshot(),
                            ),
                        )
                    }
                }

                VpnRuntimeContract.MSG_STAGE_PLAN -> {
                    if (ActiveRuntimeSessionStore.isActive()) {
                        reply(
                            msg.replyTo,
                            VpnRuntimeContract.errorMessage(
                                message = "Stop the active VPN runtime before staging a new request.",
                                snapshot = VpnRuntimeStore.snapshot(),
                            ),
                        )
                        return
                    }
                    runCatching {
                        VpnRuntimeStore.stage(VpnRuntimeContract.decodeRequest(msg.data))
                        VpnRuntimeContract.statusMessage(runRuntimeAudit())
                    }.onSuccess { response ->
                        reply(msg.replyTo, response)
                    }.onFailure { error ->
                        reply(
                            msg.replyTo,
                            VpnRuntimeContract.errorMessage(
                                message = error.message ?: error.javaClass.simpleName,
                                snapshot = VpnRuntimeStore.snapshot(),
                            ),
                        )
                    }
                }

                VpnRuntimeContract.MSG_START_RUNTIME -> {
                    if (ActiveRuntimeSessionStore.isActive()) {
                        reply(
                            msg.replyTo,
                            VpnRuntimeContract.errorMessage(
                                message = "The isolated VPN runtime is already active.",
                                snapshot = VpnRuntimeStore.snapshot(),
                            ),
                        )
                        return
                    }
                    val request = VpnRuntimeStore.stagedRequest()
                    if (request == null) {
                        reply(
                            msg.replyTo,
                            VpnRuntimeContract.errorMessage(
                                message = "No staged runtime request is available in the isolated VPN process.",
                                snapshot = VpnRuntimeStore.snapshot(),
                            ),
                        )
                        return
                    }

                    VpnRuntimeStore.markStartRequested()
                    val intent = TunguskaVpnService.startRuntimeIntent(
                        context = this@VpnRuntimeControlService,
                        configHash = request.compiledConfig.configHash,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    replyStatusSeries(
                        replyTo = msg.replyTo,
                        delayMillis = longArrayOf(250L, 1_500L, 5_000L),
                    )
                }

                VpnRuntimeContract.MSG_STOP_RUNTIME -> {
                    startService(TunguskaVpnService.stopIntent(this@VpnRuntimeControlService))
                    replyStatusSeries(
                        replyTo = msg.replyTo,
                        delayMillis = longArrayOf(100L, 500L),
                    )
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private fun replyStatusSeries(replyTo: Messenger?, delayMillis: LongArray) {
        if (replyTo == null) return
        delayMillis.forEach { delay ->
            handler.postDelayed(
                { reply(replyTo, VpnRuntimeContract.statusMessage(runRuntimeAudit())) },
                delay,
            )
        }
    }

    private fun reply(replyTo: Messenger?, message: Message) {
        if (replyTo == null) return
        runCatching { replyTo.send(message) }
    }

    private fun schedulePeriodicAudit() {
        handler.removeCallbacks(periodicAuditRunnable)
        handler.post(periodicAuditRunnable)
    }

    private fun runRuntimeAudit(): VpnRuntimeSnapshot {
        val result = runtimeAuditor.auditUid(applicationInfo.uid)
        return VpnRuntimeStore.recordAudit(result)
    }

    private fun runEngineEgressProbe(): RuntimeEgressIpObservation {
        val snapshot = VpnRuntimeStore.snapshot()
        if (snapshot.phase != VpnRuntimePhase.RUNNING) {
            return RuntimeEgressIpProbe.unavailable("VPN runtime is not running.")
        }
        return ActiveRuntimeSessionStore.observeEgressIp()
    }

    private companion object {
        const val PERIODIC_AUDIT_INTERVAL_MS: Long = 30_000L
    }
}
