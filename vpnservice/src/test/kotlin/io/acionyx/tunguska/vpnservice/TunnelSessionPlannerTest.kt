package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.VpnDirectives
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class TunnelSessionPlannerTest {
    @After
    fun tearDown() {
        EmbeddedRuntimeStrategyPolicyStore.reset()
        ActiveRuntimeSessionStore.stop()
        VpnRuntimeStore.stop()
    }

    @Test
    fun `allowlist directives produce allowed packages`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.Allowlist(listOf("io.acionyx.allowed")),
                safeMode = true,
            ),
        )

        val plan = TunnelSessionPlanner.plan(compiled)

        assertEquals(listOf("io.acionyx.allowed"), plan.allowedPackages)
        assertTrue(plan.disallowedPackages.isEmpty())
        assertEquals(TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST, plan.runtimeMode)
        assertTrue(plan.splitTunnelMode is SplitTunnelMode.Allowlist)
    }

    @Test
    fun `denylist directives produce excluded packages`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.Denylist(listOf("io.acionyx.bank")),
                safeMode = true,
            ),
        )

        val plan = TunnelSessionPlanner.plan(compiled)

        assertEquals(listOf("io.acionyx.bank"), plan.disallowedPackages)
        assertTrue(plan.allowedPackages.isEmpty())
        assertTrue(plan.splitTunnelMode is SplitTunnelMode.Denylist)
    }

    @Test
    fun `staging creates runtime snapshot with tunnel metadata`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.Denylist(listOf("io.acionyx.bank")),
                safeMode = true,
            ),
        )
        val plan = TunnelSessionPlanner.plan(compiled)

        val snapshot = VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = compiled,
                profileProtocolId = OutboundProtocolId.VLESS_REALITY,
                profileTransportId = OutboundTransportId.TCP,
                profileSecurityId = OutboundSecurityId.REALITY,
                laneCompatibility = RuntimeLaneCompatibilityMetadata(
                    statusLabel = "Fallback with limits",
                    selectedSummaryTitle = "Selected lane can run this VLESS + REALITY over TCP shape, but with engine limits",
                    recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    recommendation = "Recommended lane: sing-box embedded. It supports this profile shape with fewer engine limits on Android.",
                ),
            ),
        )

        assertEquals(VpnRuntimePhase.STAGED, snapshot.phase)
        assertEquals("abc123", snapshot.configHash)
        assertEquals(OutboundProtocolId.VLESS_REALITY, snapshot.profileProtocolId)
        assertEquals(OutboundTransportId.TCP, snapshot.profileTransportId)
        assertEquals(OutboundSecurityId.REALITY, snapshot.profileSecurityId)
        assertEquals("Fallback with limits", snapshot.laneCompatibility?.statusLabel)
        assertEquals("Fallback with limits", snapshot.configuredLaneCompatibility?.statusLabel)
        assertEquals(
            "Selected lane can run this VLESS + REALITY over TCP shape, but with engine limits",
            snapshot.laneCompatibility?.selectedSummaryTitle,
        )
        assertEquals(
            "Selected lane can run this VLESS + REALITY over TCP shape, but with engine limits",
            snapshot.configuredLaneCompatibility?.selectedSummaryTitle,
        )
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, snapshot.laneCompatibility?.recommendedStrategyId)
        assertEquals(RuntimeConfigSource.CANONICAL_PROFILE_REBUILD, snapshot.runtimeConfigSource)
        assertEquals(RuntimeConfigSource.CANONICAL_PROFILE_REBUILD, snapshot.configuredRuntimeConfigSource)
        assertEquals("singbox", snapshot.engineId)
        assertEquals("application/json", snapshot.engineFormat)
        assertEquals(2, snapshot.compiledPayloadBytes)
        assertEquals(2, snapshot.routeCount)
        assertEquals(2, snapshot.excludedRouteCount)
        assertEquals(9000, snapshot.mtu)
    }

    @Test
    fun `staging records configured active runtime strategy`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        val plan = TunnelSessionPlanner.plan(compiled)

        val snapshot = VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = compiled,
                runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
        )

        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, snapshot.activeStrategy)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, snapshot.configuredStrategy)
        assertEquals(RuntimeConfigSource.STAGED_ENGINE_PAYLOAD, snapshot.runtimeConfigSource)
        assertEquals(RuntimeConfigSource.STAGED_ENGINE_PAYLOAD, snapshot.configuredRuntimeConfigSource)
    }

    @Test
    fun `configured next start strategy can differ from active strategy and survives stop`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        val plan = TunnelSessionPlanner.plan(compiled)

        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = compiled,
                runtimeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
        )

        val updated = VpnRuntimeStore.recordConfiguredStrategy(
            strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            laneCompatibility = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Clean match",
                selectedSummaryTitle = "Configured lane metadata",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = null,
            ),
        )

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, updated.activeStrategy)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, updated.configuredStrategy)
        assertEquals(RuntimeConfigSource.CANONICAL_PROFILE_REBUILD, updated.runtimeConfigSource)
        assertEquals(RuntimeConfigSource.STAGED_ENGINE_PAYLOAD, updated.configuredRuntimeConfigSource)
        assertEquals("Clean match", updated.configuredLaneCompatibility?.statusLabel)
        val stopped = VpnRuntimeStore.stop()
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, stopped.configuredStrategy)
        assertEquals(RuntimeConfigSource.STAGED_ENGINE_PAYLOAD, stopped.configuredRuntimeConfigSource)
        assertEquals("Clean match", stopped.configuredLaneCompatibility?.statusLabel)
    }

    @Test
    fun `builder applier preserves loopback exclusions and split tunnel lists`() {
        val plan = TunnelSessionPlanner.plan(
            sampleCompiled(
                VpnDirectives(
                    preserveLoopback = true,
                    splitTunnelMode = SplitTunnelMode.Allowlist(listOf("io.acionyx.allowed")),
                    safeMode = true,
                ),
            ),
        )
        val builder = FakeBuilderAdapter()

        TunnelBuilderApplier.apply(builder, plan)

        assertEquals("Tunguska abc123", builder.sessionLabel)
        assertEquals(9000, builder.mtu)
        assertEquals(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"), builder.addresses)
        assertEquals(listOf("0.0.0.0/0", "::/0"), builder.routes)
        assertEquals(listOf("127.0.0.0/8", "::1/128"), builder.excludedRoutes)
        assertEquals(listOf("io.acionyx.allowed"), builder.allowedApplications)
        assertTrue(builder.disallowedApplications.isEmpty())
    }

    @Test
    fun `planner omits excluded loopback routes when preserve loopback is disabled`() {
        val plan = TunnelSessionPlan(
            preserveLoopback = false,
            allowedPackages = emptyList(),
            disallowedPackages = emptyList(),
            splitTunnelMode = SplitTunnelMode.FullTunnel,
            runtimeMode = TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
            configHash = "abc123",
        )

        val spec = TunnelInterfacePlanner.plan(plan)

        assertTrue(spec.excludedRoutes.isEmpty())
    }

    @Test
    fun `runtime store transitions to fail closed and idle`() {
        val plan = TunnelSessionPlanner.plan(
            sampleCompiled(
                VpnDirectives(
                    preserveLoopback = true,
                    splitTunnelMode = SplitTunnelMode.FullTunnel,
                    safeMode = true,
                ),
            ),
        )

        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = sampleCompiled(
                    VpnDirectives(
                        preserveLoopback = true,
                        splitTunnelMode = SplitTunnelMode.FullTunnel,
                        safeMode = true,
                    ),
                ),
            ),
        )
        VpnRuntimeStore.markStartRequested()
        val failed = VpnRuntimeStore.markFailClosed("engine host missing")

        assertEquals(VpnRuntimePhase.FAIL_CLOSED, failed.phase)
        assertEquals("engine host missing", failed.lastError)
        assertEquals(VpnRuntimePhase.IDLE, VpnRuntimeStore.stop().phase)
    }

    @Test
    fun `runtime store records audit metadata`() {
        val plan = TunnelSessionPlanner.plan(
            sampleCompiled(
                VpnDirectives(
                    preserveLoopback = true,
                    splitTunnelMode = SplitTunnelMode.FullTunnel,
                    safeMode = true,
                ),
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = sampleCompiled(
                    VpnDirectives(
                        preserveLoopback = true,
                        splitTunnelMode = SplitTunnelMode.FullTunnel,
                        safeMode = true,
                    ),
                ),
            ),
        )

        val audited = VpnRuntimeStore.recordAudit(
            RuntimeListenerAuditResult(
                status = RuntimeAuditStatus.PASS,
                findings = emptyList(),
                summary = "No forbidden listeners detected.",
                auditedAtEpochMs = 1234L,
                socketCount = 0,
            ),
        )

        assertEquals(RuntimeAuditStatus.PASS, audited.auditStatus)
        assertEquals(0, audited.auditFindingCount)
        assertEquals(1234L, audited.lastAuditAtEpochMs)
        assertEquals("No forbidden listeners detected.", audited.lastAuditSummary)
    }

    @Test
    fun `runtime store records bootstrap metadata`() {
        val plan = TunnelSessionPlanner.plan(
            sampleCompiled(
                VpnDirectives(
                    preserveLoopback = true,
                    splitTunnelMode = SplitTunnelMode.FullTunnel,
                    safeMode = true,
                ),
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = plan,
                compiledConfig = sampleCompiled(
                    VpnDirectives(
                        preserveLoopback = true,
                        splitTunnelMode = SplitTunnelMode.FullTunnel,
                        safeMode = true,
                    ),
                ),
            ),
        )

        val bootstrapped = VpnRuntimeStore.recordBootstrap(
            TunnelBootstrapResult(
                status = TunnelBootstrapStatus.ESTABLISHED_AND_RELEASED,
                summary = "Established Tunguska abc123 and released the descriptor.",
                bootstrappedAtEpochMs = 5678L,
            ),
        )

        assertEquals(TunnelBootstrapStatus.ESTABLISHED_AND_RELEASED, bootstrapped.bootstrapStatus)
        assertEquals(5678L, bootstrapped.lastBootstrapAtEpochMs)
        assertEquals("Established Tunguska abc123 and released the descriptor.", bootstrapped.lastBootstrapSummary)
    }

    @Test
    fun `runtime store records engine host metadata`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = TunnelSessionPlanner.plan(compiled),
                compiledConfig = compiled,
            ),
        )

        val hosted = VpnRuntimeStore.recordEngineHost(
            EmbeddedEngineHostResult(
                status = EmbeddedEngineHostStatus.READY,
                summary = "Prepared embedded sing-box workspace.",
                preparedAtEpochMs = 9012L,
                workspacePath = "C:/tmp/runtime/session-1",
            ),
        )

        assertEquals(EmbeddedEngineHostStatus.READY, hosted.engineHostStatus)
        assertEquals(9012L, hosted.lastEngineHostAtEpochMs)
        assertEquals("Prepared embedded sing-box workspace.", hosted.lastEngineHostSummary)
        assertEquals("C:/tmp/runtime/session-1", hosted.sessionWorkspacePath)
    }

    @Test
    fun `runtime store records structured engine host failure metadata`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = TunnelSessionPlanner.plan(compiled),
                compiledConfig = compiled,
            ),
        )

        val hosted = VpnRuntimeStore.recordEngineHost(
            EmbeddedEngineHostResult(
                status = EmbeddedEngineHostStatus.FAILED,
                summary = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section (routing.rules): Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
                preparedAtEpochMs = 9012L,
                workspacePath = "C:/tmp/runtime/session-2",
                errorSection = "Routing",
                errorFieldPath = "routing.rules",
            ),
        )

        assertEquals(EmbeddedEngineHostStatus.FAILED, hosted.engineHostStatus)
        assertEquals("The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section (routing.rules): Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.", hosted.lastError)
        assertEquals("Routing", hosted.lastErrorSection)
        assertEquals("routing.rules", hosted.lastErrorFieldPath)
    }

    @Test
    fun `runtime store records structured engine session failure metadata`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = TunnelSessionPlanner.plan(compiled),
                compiledConfig = compiled,
            ),
        )

        val started = VpnRuntimeStore.recordEngineSession(
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.FAILED,
                summary = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section (routing.rules): Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
                observedAtEpochMs = 9020L,
                errorSection = "Routing",
                errorFieldPath = "routing.rules",
            ),
        )

        assertEquals(EmbeddedEngineSessionStatus.FAILED, started.engineSessionStatus)
        assertEquals("Routing", started.lastErrorSection)
        assertEquals("routing.rules", started.lastErrorFieldPath)
    }

    @Test
    fun `runtime store records engine session metadata and marks running`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = TunnelSessionPlanner.plan(compiled),
                compiledConfig = compiled,
            ),
        )

        val running = VpnRuntimeStore.recordEngineSession(
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STARTED,
                summary = "Embedded engine session started.",
                observedAtEpochMs = 2468L,
            ),
        )

        assertEquals(VpnRuntimePhase.RUNNING, running.phase)
        assertEquals(EmbeddedEngineSessionStatus.STARTED, running.engineSessionStatus)
        assertEquals(2468L, running.lastEngineSessionAtEpochMs)
        assertEquals("Embedded engine session started.", running.lastEngineSessionSummary)
    }

    @Test
    fun `runtime store records engine health metadata`() {
        val compiled = sampleCompiled(
            VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        VpnRuntimeStore.stage(
            StagedRuntimeRequest(
                plan = TunnelSessionPlanner.plan(compiled),
                compiledConfig = compiled,
            ),
        )

        val health = VpnRuntimeStore.recordEngineHealth(
            EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.HEALTHY,
                summary = "Embedded engine session is healthy.",
                observedAtEpochMs = 9753L,
            ),
        )

        assertEquals(EmbeddedEngineSessionHealthStatus.HEALTHY, health.engineSessionHealthStatus)
        assertEquals(9753L, health.lastEngineHealthAtEpochMs)
        assertEquals("Embedded engine session is healthy.", health.lastEngineHealthSummary)
    }

}

private fun sampleCompiled(directives: VpnDirectives): CompiledEngineConfig = CompiledEngineConfig(
    engineId = "singbox",
    format = "application/json",
    payload = "{}",
    configHash = "abc123",
    vpnDirectives = directives,
)

private class FakeBuilderAdapter : TunnelBuilderAdapter {
    var sessionLabel: String? = null
    var mtu: Int? = null
    val addresses = mutableListOf<String>()
    val routes = mutableListOf<String>()
    val excludedRoutes = mutableListOf<String>()
    val allowedApplications = mutableListOf<String>()
    val disallowedApplications = mutableListOf<String>()

    override fun setSession(label: String) {
        sessionLabel = label
    }

    override fun setMtu(mtu: Int) {
        this.mtu = mtu
    }

    override fun addAddress(subnet: IpSubnet) {
        addresses += "${subnet.address}/${subnet.prefixLength}"
    }

    override fun addRoute(subnet: IpSubnet) {
        routes += "${subnet.address}/${subnet.prefixLength}"
    }

    override fun excludeRoute(subnet: IpSubnet) {
        excludedRoutes += "${subnet.address}/${subnet.prefixLength}"
    }

    override fun addAllowedApplication(packageName: String) {
        allowedApplications += packageName
    }

    override fun addDisallowedApplication(packageName: String) {
        disallowedApplications += packageName
    }
}
