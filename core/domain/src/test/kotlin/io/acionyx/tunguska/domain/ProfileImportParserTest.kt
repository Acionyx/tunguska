package io.acionyx.tunguska.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileImportParserTest {
    @Test
    fun `parses supported vless reality uri`() {
        val imported = ProfileImportParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public+key&sid=abcd1234" +
                "&fp=chrome&flow=xtls-rprx-vision#Alpha%20Import",
        )

        assertEquals("Alpha Import", imported.profile.name)
        assertEquals("edge.example.com", imported.profile.outbound.address)
        assertEquals(443, imported.profile.outbound.port)
        assertEquals("public+key", imported.profile.outbound.realityPublicKey)
        assertEquals("abcd1234", imported.profile.outbound.realityShortId)
        assertEquals(null, imported.profile.outbound.realitySpiderX)
        assertEquals("vless", imported.source.rawScheme)
        assertEquals("vless", imported.source.normalizedScheme)
        assertTrue(imported.warnings.isEmpty())
        assertEquals(listOf(RegionalBypassPresetId.RUSSIA), imported.profile.routing.regionalBypass.enabledPresets)
    }

    @Test
    fun `parses supported ess reality uri as vless alias`() {
        val imported = ProfileImportParser.parse(
            "ess://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?type=tcp&encryption=none&security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234#Alias",
        )

        assertEquals("Alias", imported.profile.name)
        assertEquals("ess", imported.source.rawScheme)
        assertEquals("vless", imported.source.normalizedScheme)
        assertEquals(ImportedProfileFormat.VLESS_REALITY_URI, imported.source.format)
    }

    @Test
    fun `rejects insecure tls flags`() {
        val error = assertFailsWith<ProfileImportException> {
            ProfileImportParser.parse(
                "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                    "?security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234&allowInsecure=1",
            )
        }

        assertTrue(error.issues.any { it.field == "import.tls" })
    }

    @Test
    fun `rejects unsupported transport types`() {
        val error = assertFailsWith<ProfileImportException> {
            ProfileImportParser.parse(
                "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                    "?security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234&type=ws",
            )
        }

        assertTrue(error.issues.any { it.field == "import.type" })
    }

    @Test
    fun `parses supported reality spider path`() {
        val imported = ProfileImportParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234&spx=%2Fprobe#Alpha",
        )

        assertEquals("/probe", imported.profile.outbound.realitySpiderX)
        assertTrue(imported.warnings.isEmpty())
    }

    @Test
    fun `warns when unsupported query parameters are ignored`() {
        val imported = ProfileImportParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234&foo=client-id#Alpha",
        )

        assertTrue(imported.warnings.any { it.contains("foo") })
    }

    @Test
    fun `preserves utf8 fragment name handling`() {
        val imported = ProfileImportParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public-key&sid=abcd1234#Импорт%20Профиля",
        )

        assertEquals("Импорт Профиля", imported.profile.name)
    }

    @Test
    fun `parses canonical json profile`() {
        val profile = sampleProfile()
        val profileJsonWithoutRegionalBypass = """
            {
              "id": "${profile.id}",
              "name": "${profile.name}",
              "outbound": {
                "address": "${profile.outbound.address}",
                "port": ${profile.outbound.port},
                "uuid": "${profile.outbound.uuid}",
                "serverName": "${profile.outbound.serverName}",
                "realityPublicKey": "${profile.outbound.realityPublicKey}",
                "realityShortId": "${profile.outbound.realityShortId}",
                "utlsFingerprint": "${profile.outbound.utlsFingerprint}"
              },
              "vpn": {
                "splitTunnel": {
                  "kind": "full_tunnel"
                }
              },
              "routing": {
                "defaultAction": "PROXY",
                "rules": []
              },
              "dns": {
                "kind": "system_dns"
              },
              "safety": {
                "safeMode": true,
                "compatibilityLocalProxy": false,
                "debugEndpointsEnabled": false
              }
            }
        """.trimIndent()

        val imported = ProfileImportParser.parse(profileJsonWithoutRegionalBypass)

        assertEquals(listOf(RegionalBypassPresetId.RUSSIA), imported.profile.routing.regionalBypass.enabledPresets)
        assertEquals(ImportedProfileFormat.JSON_PROFILE, imported.source.format)
    }

    @Test
    fun `preserves explicit empty regional bypass from json`() {
        val profile = sampleProfile().copy(
            routing = RoutingPolicy(
                regionalBypass = RegionalBypassSettings(),
            ),
        )

        val imported = ProfileImportParser.parse(profile.canonicalJson())

        assertTrue(imported.profile.routing.regionalBypass.enabledPresets.isEmpty())
    }

    @Test
    fun `rejects json profiles that disable safe mode`() {
        val unsafeProfile = sampleProfile().copy(
            safety = SafetySettings(
                safeMode = false,
                compatibilityLocalProxy = false,
                debugEndpointsEnabled = false,
            ),
        )

        val error = assertFailsWith<ProfileImportException> {
            ProfileImportParser.parse(unsafeProfile.canonicalJson())
        }

        assertTrue(error.issues.any { it.field == "import.safety.safeMode" })
    }
}

private fun sampleProfile(): ProfileIr = ProfileIr(
    id = "import-json",
    name = "Import Json",
    outbound = VlessRealityOutbound(
        address = "edge.example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        serverName = "cdn.example.com",
        realityPublicKey = "public-key",
        realityShortId = "abcd1234",
    ),
    safety = SafetySettings(
        safeMode = true,
        compatibilityLocalProxy = false,
        debugEndpointsEnabled = false,
    ),
)
