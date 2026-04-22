package io.acionyx.tunguska.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.TunnelSessionPlan
import io.acionyx.tunguska.vpnservice.StagedRuntimeRequest
import io.acionyx.tunguska.vpnservice.VpnRuntimeContract
import io.acionyx.tunguska.vpnservice.VpnRuntimeControlService
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot

class VpnRuntimeClient(
    context: Context,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onStatus: (VpnRuntimeSnapshot, String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val replyMessenger = Messenger(ReplyHandler())

    private var serviceMessenger: Messenger? = null
    private var isBound: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = service?.let(::Messenger)
            onConnectionChanged(true)
            requestStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            onConnectionChanged(false)
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceMessenger = null
            isBound = false
            onConnectionChanged(false)
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceMessenger = null
            isBound = false
            onConnectionChanged(false)
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(appContext, VpnRuntimeControlService::class.java)
        isBound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            onConnectionChanged(false)
        }
    }

    fun unbind() {
        if (!isBound) return
        appContext.unbindService(connection)
        isBound = false
        serviceMessenger = null
        onConnectionChanged(false)
    }

    fun requestStatus() {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_GET_STATUS, replyMessenger))
    }

    fun stageRuntime(
        plan: TunnelSessionPlan,
        compiledConfig: CompiledEngineConfig,
        profileCanonicalJson: String,
        runtimeStrategy: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
    ) {
        send(
            VpnRuntimeContract.stageRuntimeMessage(
                request = StagedRuntimeRequest(
                    plan = plan,
                    compiledConfig = compiledConfig,
                    profileCanonicalJson = profileCanonicalJson,
                    runtimeStrategy = runtimeStrategy,
                ),
                replyTo = replyMessenger,
            ),
        )
    }

    fun startRuntime() {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_START_RUNTIME, replyMessenger))
    }

    fun stopRuntime() {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_STOP_RUNTIME, replyMessenger))
    }

    private fun send(message: Message) {
        runCatching { serviceMessenger?.send(message) }
    }

    private inner class ReplyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnRuntimeContract.MSG_STATUS -> {
                    onStatus(VpnRuntimeContract.decodeSnapshot(msg.data), null)
                }

                VpnRuntimeContract.MSG_ERROR -> {
                    onStatus(
                        VpnRuntimeContract.decodeSnapshot(msg.data),
                        VpnRuntimeContract.decodeError(msg.data),
                    )
                }
            }
        }
    }
}
