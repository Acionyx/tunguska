package io.acionyx.tunguska.vpnservice

data class RuntimeSessionStartOutcome(
    val failureReason: String? = null,
    val activeSession: ActiveRuntimeSession? = null,
)

class RuntimeSessionController(
    private val engineHostRegistry: EmbeddedEngineHostRegistry = EmbeddedEngineHostRegistry(),
    private val workspaceFactoryProvider: () -> EngineSessionWorkspaceFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun start(
        request: StagedRuntimeRequest,
        sessionLabel: String,
        runtimeDependencies: EmbeddedRuntimeDependencies = EmbeddedRuntimeDependencies(),
    ): RuntimeSessionStartOutcome {
        val hostPreparation = runCatching {
            engineHostRegistry.prepare(
                request = request,
                workspaceFactory = workspaceFactoryProvider(),
                sessionLabel = sessionLabel,
                strategyId = request.runtimeStrategy,
                runtimeDependencies = runtimeDependencies,
            )
        }.getOrElse { error ->
            EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.FAILED,
                    summary = error.message ?: error.javaClass.simpleName,
                    preparedAtEpochMs = clock(),
                ),
            )
        }
        VpnRuntimeStore.recordEngineHost(hostPreparation.result)
        if (hostPreparation.result.status != EmbeddedEngineHostStatus.READY || hostPreparation.session == null) {
            RuntimeSessionWorkspaceCleaner.delete(hostPreparation.result.workspacePath)
            return RuntimeSessionStartOutcome(failureReason = hostPreparation.result.summary)
        }

        val sessionStartResult = runCatching {
            hostPreparation.session.start()
        }.getOrElse { error ->
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.FAILED,
                summary = error.message ?: error.javaClass.simpleName,
                observedAtEpochMs = clock(),
            )
        }
        VpnRuntimeStore.recordEngineSession(sessionStartResult)
        if (sessionStartResult.status != EmbeddedEngineSessionStatus.STARTED) {
            runCatching { hostPreparation.session.stop() }
            RuntimeSessionWorkspaceCleaner.delete(hostPreparation.result.workspacePath)
            return RuntimeSessionStartOutcome(failureReason = sessionStartResult.summary)
        }

        val activeSession = ActiveRuntimeSession(
            engineSession = hostPreparation.session,
            workspacePath = hostPreparation.result.workspacePath,
            clock = clock,
        )
        ActiveRuntimeSessionStore.activate(activeSession)
        return RuntimeSessionStartOutcome(activeSession = activeSession)
    }
}
