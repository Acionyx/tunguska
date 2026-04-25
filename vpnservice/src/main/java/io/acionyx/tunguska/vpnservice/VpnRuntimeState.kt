package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.domain.outboundShapeLabel

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
    val runtimeConfigSource: RuntimeConfigSource? = null,
    val configuredStrategy: EmbeddedRuntimeStrategyId? = null,
    val configuredRuntimeConfigSource: RuntimeConfigSource? = null,
    val configuredLaneCompatibility: RuntimeLaneCompatibilityMetadata? = null,
    val profileProtocolId: OutboundProtocolId? = null,
    val profileTransportId: OutboundTransportId? = null,
    val profileSecurityId: OutboundSecurityId? = null,
    val laneCompatibility: RuntimeLaneCompatibilityMetadata? = null,
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
    val lastErrorSection: String? = null,
    val lastErrorFieldPath: String? = null,
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
        val activeRuntimeConfigSource = runtimeConfigSourceFor(request.runtimeStrategy)
        snapshot = VpnRuntimeSnapshot(
            phase = VpnRuntimePhase.STAGED,
            configHash = plan.configHash,
            sessionLabel = spec.sessionLabel,
            activeStrategy = request.runtimeStrategy,
            runtimeConfigSource = activeRuntimeConfigSource,
            configuredStrategy = request.runtimeStrategy,
            configuredRuntimeConfigSource = activeRuntimeConfigSource,
            configuredLaneCompatibility = request.laneCompatibility,
            profileProtocolId = request.profileProtocolId,
            profileTransportId = request.profileTransportId,
            profileSecurityId = request.profileSecurityId,
            laneCompatibility = request.laneCompatibility,
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
            lastErrorSection = null,
            lastErrorFieldPath = null,
        )
        snapshot
    }

    fun markStartRequested(): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = VpnRuntimePhase.START_REQUESTED,
            lastError = null,
            lastErrorSection = null,
            lastErrorFieldPath = null,
        )
        snapshot
    }

    fun recordConfiguredStrategy(
        strategy: EmbeddedRuntimeStrategyId,
        laneCompatibility: RuntimeLaneCompatibilityMetadata? = null,
    ): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            configuredStrategy = strategy,
            configuredRuntimeConfigSource = runtimeConfigSourceFor(strategy),
            configuredLaneCompatibility = laneCompatibility ?: snapshot.configuredLaneCompatibility,
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
            lastError = if (result.status == EmbeddedEngineHostStatus.FAILED) result.summary else snapshot.lastError,
            lastErrorSection = if (result.status == EmbeddedEngineHostStatus.FAILED) result.errorSection else snapshot.lastErrorSection,
            lastErrorFieldPath = if (result.status == EmbeddedEngineHostStatus.FAILED) result.errorFieldPath else snapshot.lastErrorFieldPath,
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
            lastErrorSection = if (result.status == EmbeddedEngineSessionStatus.FAILED) result.errorSection else snapshot.lastErrorSection,
            lastErrorFieldPath = if (result.status == EmbeddedEngineSessionStatus.FAILED) result.errorFieldPath else snapshot.lastErrorFieldPath,
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
            lastErrorSection = if (result.status == EmbeddedEngineSessionHealthStatus.FAILED) null else snapshot.lastErrorSection,
            lastErrorFieldPath = if (result.status == EmbeddedEngineSessionHealthStatus.FAILED) null else snapshot.lastErrorFieldPath,
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
        snapshot = VpnRuntimeSnapshot(
            configuredStrategy = snapshot.configuredStrategy,
            configuredRuntimeConfigSource = snapshot.configuredRuntimeConfigSource,
            configuredLaneCompatibility = snapshot.configuredLaneCompatibility,
        )
        snapshot
    }
}

private fun List<String>.appendBounded(value: String): List<String> = (this + value.trim().take(240)).takeLast(8)

fun VpnRuntimeSnapshot.profileShapeLabel(): String? {
    val protocolId = profileProtocolId ?: return null
    val transportId = profileTransportId ?: return null
    return outboundShapeLabel(protocolId, transportId, profileSecurityId)
}
