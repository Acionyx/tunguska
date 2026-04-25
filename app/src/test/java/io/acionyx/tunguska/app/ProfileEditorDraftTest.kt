package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorDraftTest {
    @Test
    fun `draft initialization maps current outbound shape fields`() {
        val draft = ProfileEditorDraft.fromProfile(sampleProfile())

        assertEquals("edge.example.com", draft.address)
        assertEquals("443", draft.port)
        assertEquals("cdn.example.com", draft.serverName)
        assertTrue(draft.flowEnabled)
        assertEquals("chrome", draft.utlsFingerprint)
    }

    @Test
    fun `validation issues report non numeric port`() {
        val profile = sampleProfile()

        val issues = ProfileEditorDraft.fromProfile(profile)
            .copy(port = "invalid")
            .validationIssues(profile)

        assertEquals("outbound.port", issues.first().field)
    }

    @Test
    fun `updated profile collapses default spider path and flow when disabled`() {
        val profile = sampleProfile()

        val updated = ProfileEditorDraft.fromProfile(profile)
            .copy(
                flowEnabled = false,
                realitySpiderX = "/",
                utlsFingerprint = "firefox",
                safeMode = false,
                compatibilityLocalProxy = true,
                debugEndpointsEnabled = true,
            )
            .toUpdatedProfile(profile)

        assertNull(updated.outbound.flow)
        assertNull(updated.outbound.realitySpiderX)
        assertEquals("firefox", updated.outbound.utlsFingerprint)
        assertFalse(updated.safety.safeMode)
        assertTrue(updated.safety.compatibilityLocalProxy)
        assertTrue(updated.safety.debugEndpointsEnabled)
    }

    @Test
    fun `updated profile emits custom encrypted dns from typed draft`() {
        val profile = sampleProfile()

        val updated = ProfileEditorDraft.fromProfile(profile)
            .copy(
                dnsMode = ProfileEditorDnsMode.CUSTOM,
                dnsEncryptedKind = ProfileEditorEncryptedDnsKind.DOT,
                dnsEndpoints = "1.1.1.1\n1.0.0.1",
            )
            .toUpdatedProfile(profile)

        val dns = updated.dns as DnsMode.CustomEncrypted
        assertEquals(EncryptedDnsKind.DOT, dns.kind)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), dns.endpoints)
    }

    @Test
    fun `updated profile emits allowlist split tunnel from typed draft`() {
        val profile = sampleProfile()

        val updated = ProfileEditorDraft.fromProfile(profile)
            .copy(
                splitTunnelMode = ProfileEditorSplitTunnelMode.ALLOWLIST,
                splitTunnelPackages = "com.android.chrome\nio.acionyx.helper",
            )
            .toUpdatedProfile(profile)

        val splitTunnel = updated.vpn.splitTunnel as SplitTunnelMode.Allowlist
        assertEquals(listOf("com.android.chrome", "io.acionyx.helper"), splitTunnel.packageNames)
    }

    @Test
    fun `updated profile applies routing default action from typed draft`() {
        val profile = sampleProfile()

        val updated = ProfileEditorDraft.fromProfile(profile)
            .copy(defaultRouteAction = RouteAction.BLOCK)
            .toUpdatedProfile(profile)

        assertEquals(RouteAction.BLOCK, updated.routing.defaultAction)
    }

    @Test
    fun `validation issues require packages when split tunnel list mode is selected`() {
        val profile = sampleProfile()

        val issues = ProfileEditorDraft.fromProfile(profile)
            .copy(
                splitTunnelMode = ProfileEditorSplitTunnelMode.DENYLIST,
                splitTunnelPackages = "   ",
            )
            .validationIssues(profile)

        assertTrue(issues.any { it.field == "vpn.splitTunnel.packageNames" })
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