package io.acionyx.tunguska.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileIrTest {
    @Test
    fun `canonical serialization is deterministic`() {
        val profile = sampleProfile()

        val first = profile.canonicalJson()
        val second = profile.canonicalJson()

        assertEquals(first, second)
        assertEquals(profile.canonicalHash(), profile.canonicalHash())
    }

    @Test
    fun `validation rejects unsafe safe-mode combination`() {
        val profile = sampleProfile().copy(
            safety = SafetySettings(
                safeMode = true,
                compatibilityLocalProxy = true,
            ),
        )

        val issues = profile.validate()

        assertTrue(issues.any { it.field == "safety.compatibilityLocalProxy" })
    }

    @Test
    fun `reality spider path defaults to slash when absent`() {
        assertEquals("/", sampleProfile().outbound.effectiveRealitySpiderX())
    }

    @Test
    fun `reality spider path preserves explicit value`() {
        val profile = sampleProfile().copy(
            outbound = sampleProfile().outbound.copy(
                realitySpiderX = "/probe",
            ),
        )

        assertEquals("/probe", profile.outbound.effectiveRealitySpiderX())
    }

    @Test
    fun `canonical serialization supports custom encrypted dns`() {
        val profile = sampleProfile().copy(
            dns = DnsMode.CustomEncrypted(
                kind = EncryptedDnsKind.DOH,
                endpoints = listOf("https://1.1.1.1/dns-query"),
            ),
        )

        val canonicalJson = profile.canonicalJson()

        assertTrue(canonicalJson.contains("\"encryptedKind\":\"DOH\""))
        assertEquals(profile.canonicalHash(), profile.canonicalHash())
    }

    @Test
    fun `profile exposes outbound protocol label`() {
        assertEquals("VLESS + REALITY", sampleProfile().outboundProtocolLabel())
    }

    @Test
    fun `profile exposes endpoint summary`() {
        assertEquals("edge.example.com:443", sampleProfile().endpointSummary())
    }

    @Test
    fun `profile exposes outbound transport and security labels`() {
        assertEquals("TCP", sampleProfile().outboundTransportLabel())
        assertEquals("REALITY", sampleProfile().outboundSecurityLabel())
    }

    @Test
    fun `profile exposes outbound shape label`() {
        assertEquals("VLESS + REALITY over TCP", sampleProfile().outboundShapeLabel())
    }

    @Test
    fun `profile exposes protocol agnostic outbound summary`() {
        val summary = sampleProfile().outboundSummary

        assertEquals(OutboundProtocolId.VLESS_REALITY, summary.protocolId)
        assertEquals(OutboundTransportId.TCP, summary.transportId)
        assertEquals(OutboundSecurityId.REALITY, summary.securityId)
        assertEquals("VLESS + REALITY over TCP", summary.shapeLabel)
        assertEquals("edge.example.com", summary.endpoint.address)
        assertEquals(443, summary.endpoint.port)
        assertEquals("cdn.example.com", summary.endpoint.serverName)
        assertEquals("chrome", summary.utlsFingerprint)
    }

    @Test
    fun `shared outbound shape helper omits duplicate security wording`() {
        assertEquals(
            "VLESS + REALITY over TCP",
            outboundShapeLabel(
                protocolId = OutboundProtocolId.VLESS_REALITY,
                transportId = OutboundTransportId.TCP,
                securityId = OutboundSecurityId.REALITY,
            ),
        )
    }
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
    ),
    routing = RoutingPolicy(
        rules = listOf(
            RouteRule(
                id = "corp-direct",
                action = RouteAction.DIRECT,
                match = RouteMatch(domainSuffix = listOf("corp.example")),
            ),
        ),
    ),
)
