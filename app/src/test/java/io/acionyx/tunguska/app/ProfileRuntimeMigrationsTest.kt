package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.VlessRealityOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRuntimeMigrationsTest {
    @Test
    fun `migration rewrites legacy default doh over ip to system dns`() {
        val legacyProfile = sampleProfile(
            dns = DnsMode.VpnDns(
                servers = listOf(
                    "https://1.1.1.1/dns-query",
                    "https://1.0.0.1/dns-query",
                ),
            ),
        )

        val result = ProfileRuntimeMigrations.migrate(legacyProfile)

        assertEquals(DnsMode.SystemDns, result.profile.dns)
        assertNotNull(result.status)
        assertTrue(result.status!!.contains("System DNS"))
    }

    @Test
    fun `migration preserves non legacy dns configurations`() {
        val customProfile = sampleProfile(
            dns = DnsMode.VpnDns(
                servers = listOf("https://cloudflare-dns.com/dns-query"),
            ),
        )

        val result = ProfileRuntimeMigrations.migrate(customProfile)

        assertEquals(customProfile, result.profile)
        assertEquals(null, result.status)
    }

    private fun sampleProfile(dns: DnsMode): ProfileIr = ProfileIr(
        id = "alpha-secure",
        name = "Alpha Secure",
        outbound = VlessRealityOutbound(
            address = "20.166.236.185",
            port = 443,
            uuid = "4ba63ab2-2abc-48ee-9d61-b1247a7565f2",
            serverName = "www.microsoft.com",
            realityPublicKey = "69HNUl7KDDAnCPYBc6Yjp1KlUsw2bZ_z6vZ69W1Z300",
            realityShortId = "79",
            flow = null,
            utlsFingerprint = "chrome",
        ),
        dns = dns,
    )
}
