package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.engine.api.EngineCapabilityFeature
import io.acionyx.tunguska.engine.api.EngineCapabilitySupportState
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class StrategyCapabilityRegistryTest {
    @Test
    fun `registry returns both strategy profiles`() {
        assertNotNull(StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED))
        assertNotNull(StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS))
    }

    @Test
    fun `strategy summaries use current outbound shape label`() {
        val singboxProfile = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
        val xrayProtocolSupport = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.VLESS_REALITY)

        assertTrue(singboxProfile.summary.contains("VLESS + REALITY over TCP"))
        assertTrue(xrayProtocolSupport?.summary?.contains("VLESS + REALITY over TCP") == true)
    }

    @Test
    fun `singbox marks native tun inbound supported`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.TUN_INBOUND)

        assertEquals(EngineCapabilitySupportState.SUPPORTED, support?.state)
    }

    @Test
    fun `singbox marks local proxy bridge unsupported`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.LOCAL_PROXY_BRIDGE)

        assertEquals(EngineCapabilitySupportState.UNSUPPORTED, support?.state)
    }

    @Test
    fun `xray marks tun inbound degraded on fallback`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.TUN_INBOUND)

        assertEquals(EngineCapabilitySupportState.DEGRADED_ON_FALLBACK, support?.state)
    }

    @Test
    fun `xray marks network handoff recovery supported with limits`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.DEFAULT_NETWORK_HANDOFF_RECOVERY)

        assertEquals(EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS, support?.state)
    }

    @Test
    fun `xray marks canonical profile compilation supported with limits`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.CANONICAL_PROFILE_COMPILATION)

        assertEquals(EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS, support?.state)
    }

    @Test
    fun `xray limit copy explains user-facing udp tradeoff`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.UDP_RELAY)

        assertTrue(support?.summary?.contains("UDP works on this lane") == true)
        assertTrue(support?.summary?.contains("latency spikes", ignoreCase = true) == true)
    }

    @Test
    fun `xray split tunnel copy frames bypass as lane requirement`() {
        val support = StrategyCapabilityRegistry.profileFor(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
            .capabilityMatrix
            .supportFor(EngineCapabilityFeature.PACKAGE_SPLIT_TUNNEL)

        assertTrue(support?.summary?.contains("VPN control app", ignoreCase = true) == true)
        assertTrue(support?.summary?.contains("local bypass path", ignoreCase = true) == true)
    }

    @Test
    fun `profile compatibility flags xray lane as attention for encrypted dns`() {
        val summary = StrategyCapabilityRegistry.evaluateProfileCompatibility(
            profile = sampleProfile().copy(dns = DnsMode.CustomEncrypted(kind = io.acionyx.tunguska.domain.EncryptedDnsKind.DOH, endpoints = listOf("https://1.1.1.1/dns-query"))),
            strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        assertEquals(StrategyCompatibilitySeverity.ATTENTION, summary.severity)
        assertTrue(summary.details.any { it.contains("Encrypted DNS", ignoreCase = true) || it.contains("dns", ignoreCase = true) })
    }

    @Test
    fun `profile compatibility marks singbox lane ready for default profile`() {
        val summary = StrategyCapabilityRegistry.evaluateProfileCompatibility(
            profile = sampleProfile(),
            strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )

        assertEquals(StrategyCompatibilitySeverity.READY, summary.severity)
        assertTrue(summary.title.contains("VLESS + REALITY"))
        assertTrue(summary.title.contains("TCP"))
        assertTrue(summary.details.any { it.contains("VLESS", ignoreCase = true) || it.contains("REALITY", ignoreCase = true) })
        assertTrue(summary.details.any { it.contains("TCP", ignoreCase = true) })
    }

    @Test
    fun `profile guidance recommends singbox when xray is selected`() {
        val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
            profile = sampleProfile(),
            strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, guidance.recommendedStrategyId)
        assertTrue(guidance.recommendation?.contains("Recommended lane", ignoreCase = true) == true)
    }

    @Test
    fun `profile guidance adds dns section note for xray encrypted dns`() {
        val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
            profile = sampleProfile().copy(
                dns = DnsMode.CustomEncrypted(
                    kind = io.acionyx.tunguska.domain.EncryptedDnsKind.DOH,
                    endpoints = listOf("https://1.1.1.1/dns-query"),
                ),
            ),
            strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        assertEquals(
            ProfileGuidanceSection.DNS,
            guidance.sectionGuidance.first().section,
        )
    }

    @Test
    fun `profile guidance adds vpn policy note for allowlist mode`() {
        val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
            profile = sampleProfile().copy(
                vpn = sampleProfile().vpn.copy(
                    splitTunnel = SplitTunnelMode.Allowlist(listOf("com.android.chrome")),
                ),
            ),
            strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )

        assertTrue(guidance.sectionGuidance.any { it.section == ProfileGuidanceSection.VPN_POLICY })
    }

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "alpha",
        name = "Alpha",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "public-key",
            realityShortId = "abcd1234",
            realitySpiderX = null,
            flow = REQUIRED_VLESS_FLOW,
            utlsFingerprint = "chrome",
        ),
    )
}