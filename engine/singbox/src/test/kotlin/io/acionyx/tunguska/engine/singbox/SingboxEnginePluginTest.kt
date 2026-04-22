package io.acionyx.tunguska.engine.singbox

import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.defaultRegionalBypass
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SingboxEnginePluginTest {
    private val plugin = SingboxEnginePlugin()

    @Test
    fun `compiler emits deterministic payload and directives`() {
        val compiled = plugin.compile(sampleProfile())
        val json = CanonicalJson.instance.parseToJsonElement(compiled.payload).jsonObject
        val route = json.getValue("route").jsonObject
        val rules = route.getValue("rules").jsonArray
        val outbound = json.getValue("outbounds").jsonArray.first().jsonObject
        val dns = json.getValue("dns").jsonObject

        assertEquals("singbox", compiled.engineId)
        assertEquals(compiled.configHash, CanonicalJson.sha256Hex(compiled.payload))
        assertEquals("sniff", rules.first().jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("direct", rules[1].jsonObject.getValue("outbound").jsonPrimitive.content)
        assertEquals("hijack-dns", rules[2].jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("proxy", route.getValue("final").jsonPrimitive.content)
        assertEquals("edge.example.com", outbound.getValue("server").jsonPrimitive.content)
        assertEquals("dns-remote-0", dns.getValue("final").jsonPrimitive.content)
        assertEquals(SplitTunnelMode.Denylist(listOf("io.acionyx.excluded")), compiled.vpnDirectives.splitTunnelMode)
        assertFalse(compiled.payload.contains("allowInsecure"))
        assertFalse(compiled.payload.contains("\"type\":\"dns\""))
    }

    @Test
    fun `compiler maps geoip routing to local sing-box rule-set assets`() {
        val compiled = plugin.compile(
            sampleProfile().copy(
                routing = sampleProfile().routing.copy(
                    regionalBypass = defaultRegionalBypass(),
                ),
            ),
        )
        val json = CanonicalJson.instance.parseToJsonElement(compiled.payload).jsonObject
        val route = json.getValue("route").jsonObject
        val ruleSets = route.getValue("rule_set").jsonArray
        val regionalRule = route.getValue("rules").jsonArray.first { rule ->
            rule.jsonObject["domain_suffix"]?.jsonArray?.any {
                it.jsonPrimitive.content == "xn--p1ai"
            } == true
        }.jsonObject

        assertEquals(listOf("rule-set/geoip-ru.srs"), compiled.runtimeAssets.map { it.relativePath })
        assertEquals("geoip-ru", ruleSets.single().jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("local", ruleSets.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("binary", ruleSets.single().jsonObject.getValue("format").jsonPrimitive.content)
        assertEquals("rule-set/geoip-ru.srs", ruleSets.single().jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(listOf("geoip-ru"), regionalRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(regionalRule.containsKey("geoip"))
    }

    @Test
    fun `compiler emits libbox compatible reality block`() {
        val compiled = plugin.compile(
            sampleProfile().copy(
                outbound = sampleProfile().outbound.copy(
                    realitySpiderX = "/probe",
                ),
            ),
        )

        val json = CanonicalJson.instance.parseToJsonElement(compiled.payload).jsonObject
        val reality = json.getValue("outbounds").jsonArray.first().jsonObject
            .getValue("tls").jsonObject
            .getValue("reality").jsonObject

        assertEquals("public-key", reality.getValue("public_key").jsonPrimitive.content)
        assertEquals("abcd1234", reality.getValue("short_id").jsonPrimitive.content)
        assertFalse(reality.containsKey("spider_x"))
        assertFalse(reality.containsKey("spiderX"))
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
    vpn = VpnPolicy(
        splitTunnel = SplitTunnelMode.Denylist(listOf("io.acionyx.excluded")),
    ),
    dns = DnsMode.CustomEncrypted(
        kind = EncryptedDnsKind.DOH,
        endpoints = listOf("https://dns.example/dns-query"),
    ),
    routing = RoutingPolicy(
        rules = listOf(
            RouteRule(
                id = "corp-direct",
                action = RouteAction.DIRECT,
                match = RouteMatch(
                    domainSuffix = listOf("corp.example"),
                    ports = listOf(443),
                    protocols = listOf(NetworkProtocol.TCP),
                ),
            ),
        ),
    ),
)
