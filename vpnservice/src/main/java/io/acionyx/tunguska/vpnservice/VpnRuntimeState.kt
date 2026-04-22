package io.acionyx.tunguska.vpnservice

enum class VpnRuntimePhase {
    IDLE,
    STAGED,
    START_REQUESTED,
    RUNNING,
    FAIL_CLOSED,
}

data class VpnRuntimeSnapshot(
    val phase: VpnRuntimePhase = VpnRuntimePhase.IDLE,
    val configHash: String? = null,
    val sessionLabel: String? = null,
    val activeStrategy: EmbeddedRuntimeStrategyId? = null,
    val engineId: String? = null,
    val engineFormat: String? = null,
    val compiledPayloadBytes: Int = 0,
    val allowCount: Int = 0,
    val denyCount: Int = 0,
    val routeCount: Int = 0,
    val excludedRouteCount: Int = 0,
    val mtu: Int? = null,
    val runtimeMode: TunnelSessionPlan.RuntimeMode = TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
    val auditStatus: RuntimeAuditStatus = RuntimeAuditStatus.NOT_RUN,
    val auditFindingCount: Int = 0,
    val lastAuditAtEpochMs: Long? = null,
    val lastAuditSummary: String? = null,
    val bootstrapStatus: TunnelBootstrapStatus = TunnelBootstrapStatus.NOT_ATTEMPTED,
    val lastBootstrapAtEpochMs: Long? = null,
    val lastBootstrapSummary: String? = null,
    val engineHostStatus: EmbeddedEngineHostStatus = EmbeddedEngineHostStatus.NOT_PREPARED,
    val lastEngineHostAtEpochMs: Long? = null,
    val lastEngineHostSummary: String? = null,
    val engineSessionStatus: EmbeddedEngineSessionStatus = EmbeddedEngineSessionStatus.NOT_STARTED,
    val lastEngineSessionAtEpochMs: Long? = null,
    val lastEngineSessionSummary: String? = null,
    val engineSessionHealthStatus: EmbeddedEngineSessionHealthStatus = EmbeddedEngineSessionHealthStatus.UNKNOWN,
    val lastEngineHealthAtEpochMs: Long? = null,
    val lastEngineHealthSummary: String? = null,
    val bridgePort: Int? = null,
    val xrayPid: Long? = null,
    val tun2socksPid: Long? = null,
    val ownPackageBypassesVpn: Boolean = false,
    val routedTrafficObserved: Boolean = false,
    val lastRoutedTrafficAtEpochMs: Long? = null,
    val dnsFailureObserved: Boolean = false,
    val lastDnsFailureSummary: String? = null,
    val recentXrayLogLines: List<String> = emptyList(),
    val recentNativeEvents: List<String> = emptyList(),
    val sessionWorkspacePath: String? = null,
    val lastError: String? = null,
)

object VpnRuntimeStore {
    private val lock = Any()

    private var stagedRequest: StagedRuntimeRequest? = null
    private var snapshot: VpnRuntimeSnapshot = VpnRuntimeSnapshot()

    fun snapshot(): VpnRuntimeSnapshot = synchronized(lock) { snapshot }

    fun stagedRequest(): StagedRuntimeRequest? = synchronized(lock) { stagedRequest }

    fun stage(request: StagedRuntimeRequest): VpnRuntimeSnapshot = synchronized(lock) {
        stagedRequest = request
        val plan = request.plan
        val spec = TunnelInterfacePlanner.plan(plan)
        snapshot = VpnRuntimeSnapshot(
            phase = VpnRuntimePhase.STAGED,
            configHash = plan.configHash,
            sessionLabel = spec.sessionLabel,
            activeStrategy = request.runtimeStrategy,
            engineId = request.compiledConfig.engineId,
            engineFormat = request.compiledConfig.format,
            compiledPayloadBytes = request.compiledConfig.payload.toByteArray(Charsets.UTF_8).size,
            allowCount = plan.allowedPackages.size,
            denyCount = plan.disallowedPackages.size,
            routeCount = spec.routes.size,
            excludedRouteCount = spec.excludedRoutes.size,
            mtu = spec.mtu,
            runtimeMode = plan.runtimeMode,
            bridgePort = null,
            xrayPid = null,
            tun2socksPid = null,
            ownPackageBypassesVpn = false,
            routedTrafficObserved = false,
            lastRoutedTrafficAtEpochMs = null,
            dnsFailureObserved = false,
            lastDnsFailureSummary = null,
            recentXrayLogLines = emptyList(),
            recentNativeEvents = emptyList(),
            lastError = null,
        )
        snapshot
    }

    fun markStartRequested(): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = VpnRuntimePhase.START_REQUESTED,
            lastError = null,
        )
        snapshot
    }

    fun recordAudit(result: RuntimeListenerAuditResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            auditStatus = result.status,
            auditFindingCount = result.findings.size,
            lastAuditAtEpochMs = result.auditedAtEpochMs,
            lastAuditSummary = result.summary,
        )
        snapshot
    }

    fun recordBootstrap(result: TunnelBootstrapResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            bootstrapStatus = result.status,
            lastBootstrapAtEpochMs = result.bootstrappedAtEpochMs,
            lastBootstrapSummary = result.summary,
        )
        snapshot
    }

    fun recordEngineHost(result: EmbeddedEngineHostResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            engineHostStatus = result.status,
            lastEngineHostAtEpochMs = result.preparedAtEpochMs,
            lastEngineHostSummary = result.summary,
            sessionWorkspacePath = result.workspacePath,
        )
        snapshot
    }

    fun recordEngineSession(result: EmbeddedEngineSessionResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = if (result.status == EmbeddedEngineSessionStatus.STARTED) {
                VpnRuntimePhase.RUNNING
            } else {
                snapshot.phase
            },
            engineSessionStatus = result.status,
            lastEngineSessionAtEpochMs = result.observedAtEpochMs,
            lastEngineSessionSummary = result.summary,
            lastError = if (result.status == EmbeddedEngineSessionStatus.FAILED) {
                result.summary
            } else {
                snapshot.lastError
            },
        )
        snapshot
    }

    fun recordEngineHealth(result: EmbeddedEngineSessionHealthResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            engineSessionHealthStatus = result.status,
            lastEngineHealthAtEpochMs = result.observedAtEpochMs,
            lastEngineHealthSummary = result.summary,
            lastError = if (result.status == EmbeddedEngineSessionHealthStatus.FAILED) {
                result.summary
            } else {
                snapshot.lastError
            },
        )
        snapshot
    }

    fun recordRuntimeTelemetry(
        strategy: EmbeddedRuntimeStrategyId = snapshot.activeStrategy ?: EmbeddedRuntimeStrategyPolicyStore.activeStrategyId(),
        bridgePort: Int? = snapshot.bridgePort,
        xrayPid: Long? = snapshot.xrayPid,
        tun2socksPid: Long? = snapshot.tun2socksPid,
        ownPackageBypassesVpn: Boolean = snapshot.ownPackageBypassesVpn,
        routedTrafficObserved: Boolean = snapshot.routedTrafficObserved,
        lastRoutedTrafficAtEpochMs: Long? = snapshot.lastRoutedTrafficAtEpochMs,
        dnsFailureObserved: Boolean = snapshot.dnsFailureObserved,
        lastDnsFailureSummary: String? = snapshot.lastDnsFailureSummary,
        xrayLogLine: String? = null,
        nativeEvent: String? = null,
    ): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            activeStrategy = strategy,
            bridgePort = bridgePort,
            xrayPid = xrayPid,
            tun2socksPid = tun2socksPid,
            ownPackageBypassesVpn = ownPackageBypassesVpn,
            routedTrafficObserved = routedTrafficObserved,
            lastRoutedTrafficAtEpochMs = lastRoutedTrafficAtEpochMs,
            dnsFailureObserved = dnsFailureObserved,
            lastDnsFailureSummary = lastDnsFailureSummary,
            recentXrayLogLines = xrayLogLine
                ?.let { snapshot.recentXrayLogLines.appendBounded(it) }
                ?: snapshot.recentXrayLogLines,
            recentNativeEvents = nativeEvent
                ?.let { snapshot.recentNativeEvents.appendBounded(it) }
                ?: snapshot.recentNativeEvents,
        )
        snapshot
    }

    fun markFailClosed(reason: String): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = VpnRuntimePhase.FAIL_CLOSED,
            lastError = reason,
        )
        snapshot
    }

    fun stop(): VpnRuntimeSnapshot = synchronized(lock) {
        RuntimeSessionWorkspaceCleaner.delete(snapshot.sessionWorkspacePath)
        stagedRequest = null
        snapshot = VpnRuntimeSnapshot()
        snapshot
    }
}

private fun List<String>.appendBounded(value: String): List<String> = (this + value.trim().take(240)).takeLast(8)
