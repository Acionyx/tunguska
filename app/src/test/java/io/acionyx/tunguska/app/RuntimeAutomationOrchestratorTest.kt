package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionStatus
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.RuntimeConfigSource
import io.acionyx.tunguska.vpnservice.RuntimeLaneCompatibilityMetadata
import io.acionyx.tunguska.vpnservice.StagedRuntimeRequest
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutomationOrchestratorTest {
    @Test
    fun `start returns vpn permission required before touching the runtime gateway`() {
        val gateway = FakeRuntimeGateway()
        val orchestrator = buildOrchestrator(
            permissionGranted = false,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(defaultBootstrapProfile()),
        )

        assertEquals(AutomationCommandStatus.VPN_PERMISSION_REQUIRED, result.status)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `start succeeds when runtime reaches running`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
            stageResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
            startResponse = RuntimeGatewayResponse(
                VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.RUNNING,
                    activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                    bridgePort = 1080,
                    xrayPid = 123L,
                    tun2socksPid = 456L,
                    engineSessionStatus = EmbeddedEngineSessionStatus.STARTED,
                ),
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(defaultBootstrapProfile()),
        )

        assertEquals(AutomationCommandStatus.SUCCESS, result.status)
        assertEquals(VpnRuntimePhase.RUNNING, result.snapshot?.phase)
        assertTrue(result.runtimeMetadata?.connectDecisionSummary?.startsWith("Started on Xray + tun2socks.") == true)
        assertEquals(listOf("status", "stage", "start"), gateway.calls)
    }

    @Test
    fun `start polls runtime status until xray session is fully ready`() {
        val gateway = FakeRuntimeGateway(
            statusResponses = ArrayDeque(
                listOf(
                    RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
                    RuntimeGatewayResponse(
                        VpnRuntimeSnapshot(
                            phase = VpnRuntimePhase.RUNNING,
                            activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                            engineSessionStatus = EmbeddedEngineSessionStatus.STARTED,
                        ),
                    ),
                    RuntimeGatewayResponse(
                        VpnRuntimeSnapshot(
                            phase = VpnRuntimePhase.RUNNING,
                            activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                            bridgePort = 1080,
                            xrayPid = 123L,
                            tun2socksPid = 456L,
                            engineSessionStatus = EmbeddedEngineSessionStatus.STARTED,
                        ),
                    ),
                ),
            ),
            stageResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
            startResponse = RuntimeGatewayResponse(
                VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.RUNNING,
                    activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                    engineSessionStatus = EmbeddedEngineSessionStatus.STARTED,
                ),
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(
                profile = defaultBootstrapProfile(),
                runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
        )

        assertEquals(AutomationCommandStatus.SUCCESS, result.status)
        assertEquals(listOf("status", "stage", "start", "status", "status"), gateway.calls)
    }

    @Test
    fun `start fails when runtime ends in fail closed`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
            stageResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
            startResponse = RuntimeGatewayResponse(
                snapshot = VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.FAIL_CLOSED,
                    lastError = "bootstrap failed",
                ),
                error = "bootstrap failed",
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(defaultBootstrapProfile()),
        )

        assertEquals(AutomationCommandStatus.RUNTIME_START_FAILED, result.status)
        assertEquals("bootstrap failed", result.error)
        assertTrue(result.runtimeMetadata?.connectDecisionSummary?.startsWith("Start failed on Xray + tun2socks.") == true)
    }

    @Test
    fun `start normalizes structured compile failures for automation callers`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
            stageResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
            startResponse = RuntimeGatewayResponse(
                snapshot = VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.FAIL_CLOSED,
                    lastError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
                    lastErrorSection = "Routing",
                    lastErrorFieldPath = "routing.rules",
                ),
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(defaultBootstrapProfile()),
        )

        assertEquals(AutomationCommandStatus.RUNTIME_START_FAILED, result.status)
        assertEquals(
            "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames. Review Routing in the profile editor.",
            result.error,
        )
        assertEquals("Routing", result.errorSection)
        assertEquals("routing.rules", result.errorFieldPath)
    }

    @Test
    fun `prepare profile preserves requested runtime strategy`() {
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = FakeRuntimeGateway(),
        )

        val prepared = orchestrator.prepareProfile(
            profile = defaultBootstrapProfile(),
            runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )
        val staged = prepared.toStagedRuntimeRequest()
        val expectedLaneCompatibility = stagedRuntimeLaneCompatibilityMetadata(
            selectedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = defaultBootstrapProfile(),
                strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
        )

        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, prepared.runtimeStrategy)
        assertEquals(expectedLaneCompatibility, prepared.laneCompatibility)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, staged.runtimeStrategy)
        assertEquals(expectedLaneCompatibility, staged.laneCompatibility)
        assertEquals(prepared.profileCanonicalJson, staged.profileCanonicalJson)
        assertEquals(io.acionyx.tunguska.domain.OutboundProtocolId.VLESS_REALITY, staged.profileProtocolId)
        assertEquals(io.acionyx.tunguska.domain.OutboundTransportId.TCP, staged.profileTransportId)
        assertEquals(io.acionyx.tunguska.domain.OutboundSecurityId.REALITY, staged.profileSecurityId)
    }

    @Test
    fun `prepare stored profile preserves requested runtime strategy`() {
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = FakeRuntimeGateway(),
        )

        val result = orchestrator.prepareStoredProfile(
            runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )

        assertEquals(AutomationCommandStatus.SUCCESS, result.status)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, result.preparedRequest?.runtimeStrategy)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, result.preparedRequest?.toStagedRuntimeRequest()?.runtimeStrategy)
    }

    @Test
    fun `start accepts ready sing-box runtime without bridge telemetry`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
            stageResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
            startResponse = RuntimeGatewayResponse(
                VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.RUNNING,
                    activeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    engineSessionStatus = EmbeddedEngineSessionStatus.STARTED,
                ),
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.startPreparedRuntime(
            orchestrator.prepareProfile(
                profile = defaultBootstrapProfile(),
                runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
        )

        assertEquals(AutomationCommandStatus.SUCCESS, result.status)
        assertEquals(VpnRuntimePhase.RUNNING, result.snapshot?.phase)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, result.snapshot?.activeStrategy)
        assertEquals(null, result.runtimeMetadata?.connectDecisionSummary)
    }

    @Test
    fun `refresh status exposes snapshot backed lane and compiler provenance`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(
                VpnRuntimeSnapshot(
                    phase = VpnRuntimePhase.RUNNING,
                    activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                    runtimeConfigSource = RuntimeConfigSource.CANONICAL_PROFILE_REBUILD,
                    configuredStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    configuredRuntimeConfigSource = RuntimeConfigSource.STAGED_ENGINE_PAYLOAD,
                    laneCompatibility = RuntimeLaneCompatibilityMetadata(
                        statusLabel = "Fallback with limits",
                        selectedSummaryTitle = "Active lane metadata title",
                        recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                        recommendation = "Use the broader embedded lane after restaging.",
                    ),
                    configuredLaneCompatibility = RuntimeLaneCompatibilityMetadata(
                        statusLabel = "Clean match",
                        selectedSummaryTitle = "Configured next-start metadata title",
                        recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                        recommendation = "Configured lane metadata recommendation.",
                    ),
                ),
            ),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.refreshStatus()

        assertEquals("Xray + tun2socks", result.runtimeMetadata?.laneLabel)
        assertEquals("Fallback with limits", result.runtimeMetadata?.laneStatus)
        assertEquals("Active lane metadata title", result.runtimeMetadata?.laneSummaryTitle)
        assertEquals("Use the broader embedded lane after restaging.", result.runtimeMetadata?.laneRecommendation)
        assertEquals("Canonical profile rebuild", result.runtimeMetadata?.compilerPath)
        assertEquals("Sing-box embedded", result.runtimeMetadata?.nextStartLaneLabel)
        assertEquals("Clean match", result.runtimeMetadata?.nextStartLaneStatus)
        assertEquals("Configured next-start metadata title", result.runtimeMetadata?.nextStartLaneSummaryTitle)
        assertEquals("Configured lane metadata recommendation.", result.runtimeMetadata?.nextStartLaneRecommendation)
        assertEquals("Staged engine payload", result.runtimeMetadata?.nextStartCompilerPath)
    }

    @Test
    fun `stop succeeds when runtime returns to idle`() {
        val gateway = FakeRuntimeGateway(
            statusResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.RUNNING)),
            stopResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
        )
        val orchestrator = buildOrchestrator(
            permissionGranted = true,
            gateway = gateway,
        )

        val result = orchestrator.stopRuntime()

        assertEquals(AutomationCommandStatus.SUCCESS, result.status)
        assertEquals(VpnRuntimePhase.IDLE, result.snapshot?.phase)
        assertEquals(listOf("status", "stop"), gateway.calls)
    }

    private fun buildOrchestrator(
        permissionGranted: Boolean,
        gateway: FakeRuntimeGateway,
    ): RuntimeAutomationOrchestrator {
        val profileRepository = SecureProfileRepository(
            path = Files.createTempDirectory("tunguska-runtime-profile").resolve("profile.json.enc"),
            cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        )
        profileRepository.reseal(defaultBootstrapProfile())
        return RuntimeAutomationOrchestrator(
            context = android.app.Application(),
            profileRepository = profileRepository,
            gatewayFactory = { gateway },
            permissionChecker = { permissionGranted },
            startReadyTimeoutMs = 1_000L,
            readyPollIntervalMs = 1L,
        )
    }
}

private class FakeRuntimeGateway(
    statusResponses: ArrayDeque<RuntimeGatewayResponse> = ArrayDeque(),
    private val statusResponse: RuntimeGatewayResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot()),
    private val stageResponse: RuntimeGatewayResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.STAGED)),
    private val startResponse: RuntimeGatewayResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.RUNNING)),
    private val stopResponse: RuntimeGatewayResponse = RuntimeGatewayResponse(VpnRuntimeSnapshot(phase = VpnRuntimePhase.IDLE)),
) : RuntimeControlGateway {
    val calls: MutableList<String> = mutableListOf()
    private val queuedStatusResponses = statusResponses

    override fun requestStatus(): RuntimeGatewayResponse {
        calls += "status"
        return if (queuedStatusResponses.isNotEmpty()) {
            queuedStatusResponses.removeFirst()
        } else {
            statusResponse
        }
    }

    override fun stageRuntime(request: StagedRuntimeRequest): RuntimeGatewayResponse {
        calls += "stage"
        return stageResponse
    }

    override fun startRuntime(): RuntimeGatewayResponse {
        calls += "start"
        return startResponse
    }

    override fun stopRuntime(): RuntimeGatewayResponse {
        calls += "stop"
        return stopResponse
    }

    override fun close() = Unit
}
