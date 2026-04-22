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
