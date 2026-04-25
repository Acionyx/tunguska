package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.VpnDirectives
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class XrayCompatLaneMessagingTest {
    @Test
    fun `canonical payload summary includes staged outbound shape`() {
        val summary = XrayCompatLaneMessaging.canonicalPayloadRequiredSummary(sampleRequest())

        assertTrue(summary.contains("canonical profile payload"))
        assertTrue(summary.contains("VLESS + REALITY over TCP"))
    }

    @Test
    fun `decode failure summary includes staged outbound shape`() {
        val summary = XrayCompatLaneMessaging.canonicalDecodeFailureSummary(
            request = sampleRequest(),
            error = IllegalArgumentException("bad payload"),
        )

        assertTrue(summary.contains("bad payload"))
        assertTrue(summary.contains("VLESS + REALITY over TCP"))
    }

    @Test
    fun `validation and ready summaries use canonical outbound shape`() {
        val profile = sampleProfile()

        val validationSummary = XrayCompatLaneMessaging.validationFailureSummary(
            profile = profile,
            issuesSummary = "outbound.port: Port must be between 1 and 65535.",
        )
        val preparedSummary = XrayCompatLaneMessaging.preparedSummary(
            request = sampleRequest(),
            profile = profile,
        )

        assertTrue(validationSummary.contains("VLESS + REALITY over TCP"))
        assertTrue(preparedSummary.contains("VLESS + REALITY over TCP"))
        assertTrue(preparedSummary.contains("abc123"))
    }

    @Test
    fun `compile failure summary includes compiler section when available`() {
        val summary = XrayCompatLaneMessaging.compileFailureSummary(
            profile = sampleProfile(),
            error = XrayCompatCompileException(
                section = XrayCompatCompileSection.ROUTING,
                fieldPath = "routing.rules",
                message = "Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
            ),
        )

        assertTrue(summary.contains("VLESS + REALITY over TCP"))
        assertTrue(summary.contains("Routing section"))
        assertTrue(summary.contains("package-only"))
    }

    @Test
    fun `staged shape label omits duplicate security wording`() {
        assertEquals("VLESS + REALITY over TCP", XrayCompatLaneMessaging.stagedShapeLabel(sampleRequest()))
    }

    private fun sampleRequest(): StagedRuntimeRequest = StagedRuntimeRequest(
        plan = TunnelSessionPlan(
            preserveLoopback = true,
            allowedPackages = emptyList(),
            disallowedPackages = emptyList(),
            splitTunnelMode = SplitTunnelMode.FullTunnel,
            runtimeMode = TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
            configHash = "abc123",
        ),
        compiledConfig = CompiledEngineConfig(
            engineId = "singbox",
            format = "application/json",
            payload = "{}",
            configHash = "abc123",
            vpnDirectives = VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
            runtimeAssets = emptyList(),
        ),
        profileProtocolId = OutboundProtocolId.VLESS_REALITY,
        profileTransportId = OutboundTransportId.TCP,
        profileSecurityId = OutboundSecurityId.REALITY,
    )

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "alpha",
        name = "Alpha",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            realityShortId = "abcd1234",
        ),
    )
}