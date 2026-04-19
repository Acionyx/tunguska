package io.acionyx.tunguska.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus
import io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionStatus
import io.acionyx.tunguska.vpnservice.StagedRuntimeRequest
import io.acionyx.tunguska.vpnservice.TunnelSessionPlan
import io.acionyx.tunguska.vpnservice.TunnelSessionPlanner
import io.acionyx.tunguska.vpnservice.VpnRuntimeContract
import io.acionyx.tunguska.vpnservice.VpnRuntimeControlService
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RuntimeAutomationOrchestrator(
    context: Context,
    private val profileRepository: SecureProfileRepository = SecureProfileRepository(context),
    private val gatewayFactory: () -> RuntimeControlGateway = {
        MessengerRuntimeControlGateway(context)
    },
    private val permissionChecker: () -> Boolean = {
        VpnService.prepare(context) == null
    },
    private val plugin: SingboxEnginePlugin = SingboxEnginePlugin(),
    private val startReadyTimeoutMs: Long = 12_000L,
    private val readyPollIntervalMs: Long = 250L,
) {
    fun prepareProfile(profile: ProfileIr): PreparedRuntimeRequest {
        logInfo("Compiling stored profile '${profile.name}' for automation.")
        val compiledConfig = plugin.compile(profile)
        return PreparedRuntimeRequest(
            profile = profile,
            compiledConfig = compiledConfig,
            tunnelPlan = TunnelSessionPlanner.plan(compiledConfig),
            profileCanonicalJson = profile.canonicalJson(),
        )
    }

    fun prepareStoredProfile(): RuntimeAutomationResult {
        logInfo("Loading encrypted profile for automation.")
        val storedProfile = runCatching { profileRepository.reload() }
            .getOrElse { error ->
                return RuntimeAutomationResult(
                    status = AutomationCommandStatus.NO_STORED_PROFILE,
                    summary = "No encrypted Tunguska profile is sealed on this device.",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }

        logInfo("Preparing runtime request for stored profile '${storedProfile.profile.name}'.")
        val preparedRequest = runCatching { prepareProfile(storedProfile.profile) }
            .getOrElse { error ->
                return RuntimeAutomationResult(
                    status = AutomationCommandStatus.PROFILE_INVALID,
                    summary = "The stored profile could not be compiled into a runtime request.",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }

        return RuntimeAutomationResult(
            status = AutomationCommandStatus.SUCCESS,
            summary = "Prepared the stored profile for runtime automation.",
            preparedRequest = preparedRequest,
        )
    }

    fun refreshStatus(): RuntimeAutomationResult = gatewayUse { gateway ->
        val response = gateway.requestStatus()
        RuntimeAutomationResult(
            status = AutomationCommandStatus.SUCCESS,
            summary = "Loaded the current isolated runtime status.",
            snapshot = response.snapshot,
            error = response.error,
        )
    }

    fun startPreparedRuntime(request: PreparedRuntimeRequest): RuntimeAutomationResult {
        if (!permissionChecker()) {
            return RuntimeAutomationResult(
                status = AutomationCommandStatus.VPN_PERMISSION_REQUIRED,
                summary = "Grant Tunguska the Android VPN permission once before automation can start the runtime.",
            )
        }
        return gatewayUse { gateway ->
            logInfo("Requesting current runtime status before start.")
            val current = gateway.requestStatus().snapshot
            if (current.phase == VpnRuntimePhase.RUNNING) {
                logInfo("Runtime already reports RUNNING; verifying ready session.")
                val readyCurrent = awaitStartedRuntime(gateway, RuntimeGatewayResponse(current))
                val readyStatus = if (readyCurrent.error == null) {
                    AutomationCommandStatus.SUCCESS
                } else {
                    AutomationCommandStatus.RUNTIME_START_FAILED
                }
                return@gatewayUse RuntimeAutomationResult(
                    status = readyStatus,
                    summary = if (readyStatus == AutomationCommandStatus.SUCCESS) {
                        "The isolated Tunguska runtime is already active."
                    } else {
                        "The isolated Tunguska runtime did not expose a ready session."
                    },
                    snapshot = readyCurrent.snapshot,
                    error = readyCurrent.error ?: readyCurrent.snapshot.lastError,
                    preparedRequest = request,
                )
            }
            logInfo("Staging runtime request hash=${request.compiledConfig.configHash}.")
            val staged = gateway.stageRuntime(request.toStagedRuntimeRequest())
            if (staged.error != null) {
                return@gatewayUse RuntimeAutomationResult(
                    status = AutomationCommandStatus.CONTROL_CHANNEL_ERROR,
                    summary = "The isolated Tunguska runtime rejected the staged request.",
                    snapshot = staged.snapshot,
                    error = staged.error,
                    preparedRequest = request,
                )
            }
            logInfo("Sending start command to isolated runtime.")
            val started = awaitStartedRuntime(
                gateway = gateway,
                initial = gateway.startRuntime(),
            )
            val status = if (started.error == null && isRuntimeReady(started.snapshot)) {
                AutomationCommandStatus.SUCCESS
            } else {
                AutomationCommandStatus.RUNTIME_START_FAILED
            }
            RuntimeAutomationResult(
                status = status,
                summary = when (status) {
                    AutomationCommandStatus.SUCCESS -> "Started the isolated Tunguska runtime."
                    else -> "The isolated Tunguska runtime did not reach RUNNING."
                },
                snapshot = started.snapshot,
                error = started.error ?: started.snapshot.lastError,
                preparedRequest = request,
            )
        }
    }

    fun startStoredProfile(): RuntimeAutomationResult {
        val prepared = prepareStoredProfile()
        val request = prepared.preparedRequest ?: return prepared
        return startPreparedRuntime(request)
    }

    fun stopRuntime(): RuntimeAutomationResult = gatewayUse { gateway ->
        logInfo("Requesting current runtime status before stop.")
        val current = gateway.requestStatus().snapshot
        if (current.phase == VpnRuntimePhase.IDLE) {
            return@gatewayUse RuntimeAutomationResult(
                status = AutomationCommandStatus.SUCCESS,
                summary = "The isolated Tunguska runtime is already idle.",
                snapshot = current,
            )
        }
        logInfo("Sending stop command to isolated runtime.")
        val stopped = gateway.stopRuntime()
        val status = if (stopped.error == null && stopped.snapshot.phase == VpnRuntimePhase.IDLE) {
            AutomationCommandStatus.SUCCESS
        } else {
            AutomationCommandStatus.RUNTIME_STOP_FAILED
        }
        RuntimeAutomationResult(
            status = status,
            summary = when (status) {
                AutomationCommandStatus.SUCCESS -> "Stopped the isolated Tunguska runtime."
                else -> "The isolated Tunguska runtime did not return to IDLE."
            },
            snapshot = stopped.snapshot,
            error = stopped.error ?: stopped.snapshot.lastError,
        )
    }

    private inline fun gatewayUse(block: (RuntimeControlGateway) -> RuntimeAutomationResult): RuntimeAutomationResult {
        return runCatching {
            logInfo("Opening runtime control gateway.")
            gatewayFactory().use(block)
        }.getOrElse { error ->
            logError("Runtime control gateway failed.", error)
            RuntimeAutomationResult(
                status = AutomationCommandStatus.CONTROL_CHANNEL_ERROR,
                summary = "The Tunguska control channel could not complete the requested runtime operation.",
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun awaitStartedRuntime(
        gateway: RuntimeControlGateway,
        initial: RuntimeGatewayResponse,
    ): RuntimeGatewayResponse {
        var latest = initial
        logInfo(
            "Awaiting ready runtime session initialPhase=${initial.snapshot.phase} initialError=${initial.error ?: "<none>"}",
        )
        if (initial.error != null || isRuntimeTerminalFailure(initial.snapshot)) {
            return initial
        }
        if (isRuntimeReady(initial.snapshot)) {
            return initial
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startReadyTimeoutMs)
        while (System.nanoTime() < deadline) {
            Thread.sleep(readyPollIntervalMs)
            latest = gateway.requestStatus()
            logInfo(
                "Polled runtime phase=${latest.snapshot.phase} engine=${latest.snapshot.engineSessionStatus} health=${latest.snapshot.engineSessionHealthStatus} bridgePort=${latest.snapshot.bridgePort} error=${latest.error ?: "<none>"}",
            )
            if (latest.error != null || isRuntimeTerminalFailure(latest.snapshot) || isRuntimeReady(latest.snapshot)) {
                return latest
            }
        }

        return latest.copy(
            error = latest.error
                ?: latest.snapshot.lastError
                ?: "Timed out waiting for the isolated Tunguska runtime to expose a ready session.",
        )
    }

    private fun isRuntimeTerminalFailure(snapshot: VpnRuntimeSnapshot): Boolean {
        return snapshot.phase == VpnRuntimePhase.FAIL_CLOSED ||
            snapshot.engineSessionStatus == EmbeddedEngineSessionStatus.FAILED ||
            snapshot.engineSessionHealthStatus == EmbeddedEngineSessionHealthStatus.FAILED
    }

    private fun isRuntimeReady(snapshot: VpnRuntimeSnapshot): Boolean {
        if (snapshot.phase != VpnRuntimePhase.RUNNING) {
            return false
        }
        if (snapshot.engineSessionStatus != EmbeddedEngineSessionStatus.STARTED) {
            return false
        }
        if (snapshot.engineSessionHealthStatus == EmbeddedEngineSessionHealthStatus.FAILED) {
            return false
        }
        return snapshot.bridgePort != null
    }

    private companion object {
        const val TAG: String = "RuntimeAutomation"
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }
}

data class PreparedRuntimeRequest(
    val profile: ProfileIr,
    val compiledConfig: CompiledEngineConfig,
    val tunnelPlan: TunnelSessionPlan,
    val profileCanonicalJson: String,
) {
    fun toStagedRuntimeRequest(): StagedRuntimeRequest = StagedRuntimeRequest(
        plan = tunnelPlan,
        compiledConfig = compiledConfig,
        profileCanonicalJson = profileCanonicalJson,
    )
}

data class RuntimeAutomationResult(
    val status: AutomationCommandStatus,
    val summary: String,
    val error: String? = null,
    val snapshot: VpnRuntimeSnapshot? = null,
    val preparedRequest: PreparedRuntimeRequest? = null,
)

interface RuntimeControlGateway : AutoCloseable {
    fun requestStatus(): RuntimeGatewayResponse

    fun stageRuntime(request: StagedRuntimeRequest): RuntimeGatewayResponse

    fun startRuntime(): RuntimeGatewayResponse

    fun stopRuntime(): RuntimeGatewayResponse
}

data class RuntimeGatewayResponse(
    val snapshot: VpnRuntimeSnapshot,
    val error: String? = null,
)

private class MessengerRuntimeControlGateway(
    context: Context,
) : RuntimeControlGateway {
    private val appContext = context.applicationContext
    private val queue = LinkedBlockingQueue<RuntimeGatewayResponse>()
    private val replyMessenger = Messenger(ReplyHandler())
    private val boundLatch = CountDownLatch(1)

    private var bound: Boolean = false
    private var serviceMessenger: Messenger? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = service?.let(::Messenger)
            bound = serviceMessenger != null
            boundLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            bound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            boundLatch.countDown()
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            boundLatch.countDown()
        }
    }

    init {
        val intent = Intent(appContext, VpnRuntimeControlService::class.java)
        val bindingStarted = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        require(bindingStarted) {
            "Failed to start binding the isolated Tunguska runtime control service."
        }
        require(boundLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Timed out while binding the isolated Tunguska runtime control service."
        }
        requireNotNull(serviceMessenger) {
            "The isolated Tunguska runtime control service is not available."
        }
    }

    override fun requestStatus(): RuntimeGatewayResponse {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_GET_STATUS, replyMessenger))
        return await("status") { true }
    }

    override fun stageRuntime(request: StagedRuntimeRequest): RuntimeGatewayResponse {
        send(VpnRuntimeContract.stageRuntimeMessage(request, replyMessenger))
        return await("stage") { true }
    }

    override fun startRuntime(): RuntimeGatewayResponse {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_START_RUNTIME, replyMessenger))
        return await("start") { response ->
            response.error != null ||
                response.snapshot.phase == VpnRuntimePhase.RUNNING ||
                response.snapshot.phase == VpnRuntimePhase.FAIL_CLOSED ||
                response.snapshot.phase == VpnRuntimePhase.IDLE
        }
    }

    override fun stopRuntime(): RuntimeGatewayResponse {
        send(VpnRuntimeContract.simpleMessage(VpnRuntimeContract.MSG_STOP_RUNTIME, replyMessenger))
        return await("stop") { response ->
            response.error != null || response.snapshot.phase == VpnRuntimePhase.IDLE
        }
    }

    override fun close() {
        if (bound) {
            appContext.unbindService(connection)
            bound = false
        }
        serviceMessenger = null
        queue.clear()
    }

    private fun send(message: Message) {
        queue.clear()
        requireNotNull(serviceMessenger) {
            "The isolated Tunguska runtime control messenger is not connected."
        }.send(message)
    }

    private fun await(
        operation: String,
        predicate: (RuntimeGatewayResponse) -> Boolean,
    ): RuntimeGatewayResponse {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            when (operation) {
                "start" -> START_TIMEOUT_MS
                "stop" -> STOP_TIMEOUT_MS
                else -> REQUEST_TIMEOUT_MS
            },
        )
        while (System.nanoTime() < deadlineNanos) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            val response = queue.poll(remainingNanos, TimeUnit.NANOSECONDS)
                ?: break
            if (predicate(response)) {
                return response
            }
        }
        throw IllegalStateException("Timed out waiting for Tunguska runtime $operation response.")
    }

    private inner class ReplyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnRuntimeContract.MSG_STATUS,
                VpnRuntimeContract.MSG_ERROR,
                -> {
                    queue.offer(
                        RuntimeGatewayResponse(
                            snapshot = VpnRuntimeContract.decodeSnapshot(msg.data),
                            error = VpnRuntimeContract.decodeError(msg.data),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS: Long = 5_000L
        const val REQUEST_TIMEOUT_MS: Long = 5_000L
        const val START_TIMEOUT_MS: Long = 30_000L
        const val STOP_TIMEOUT_MS: Long = 15_000L
    }
}
