package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.ImportedProfile
import io.acionyx.tunguska.domain.ImportedProfileFormat
import io.acionyx.tunguska.domain.ImportedProfileSource
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportModelsTest {
    @Test
    fun `import preview state exposes compatibility recommendation for selected lane`() {
        val preview = ImportedProfile(
            profile = sampleProfile().copy(
                dns = DnsMode.CustomEncrypted(
                    kind = EncryptedDnsKind.DOH,
                    endpoints = listOf("https://1.1.1.1/dns-query"),
                ),
            ),
            source = ImportedProfileSource(
                rawScheme = "vless",
                normalizedScheme = "vless",
                format = ImportedProfileFormat.VLESS_REALITY_URI,
                summary = "Validated a VLESS + REALITY over TCP share link.",
            ),
        ).toImportPreviewState(
            source = ImportCaptureSource.MANUAL_TEXT,
            selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        assertEquals(StrategyCompatibilitySeverity.ATTENTION, preview.compatibilitySeverity)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, preview.recommendedStrategyId)
    assertEquals(OutboundProtocolId.VLESS_REALITY, preview.protocolId)
        assertEquals("VLESS + REALITY over TCP", preview.shapeLabel)
        assertEquals("VLESS + REALITY", preview.protocolLabel)
        assertEquals("TCP", preview.transportLabel)
        assertEquals("REALITY", preview.securityLabel)
        assertTrue(preview.compatibilityDetails.isNotEmpty())
        assertTrue(preview.recommendation?.contains("Recommended lane", ignoreCase = true) == true)
    }

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "import-preview",
        name = "Import Preview",
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