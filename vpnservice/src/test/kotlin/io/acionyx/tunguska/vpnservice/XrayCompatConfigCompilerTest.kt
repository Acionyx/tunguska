package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.defaultRegionalBypass
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class XrayCompatConfigCompilerTest {
    @Test
    fun `compiler binds authenticated socks bridge to loopback only`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val settings = inbound.getValue("settings").jsonObject
        val account = settings.getValue("accounts").jsonArray.single().jsonObject

        assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
        assertEquals(25001, inbound.getValue("port").jsonPrimitive.content.toInt())
        assertEquals("password", settings.getValue("auth").jsonPrimitive.content)
        assertEquals("127.0.0.1", settings.getValue("ip").jsonPrimitive.content)
        assertTrue(settings.getValue("udp").jsonPrimitive.content.toBoolean())
        assertEquals("bridge-user", account.getValue("user").jsonPrimitive.content)
        assertEquals("bridge-pass", account.getValue("pass").jsonPrimitive.content)
    }

    @Test
    fun `compiler does not expose management or debug surfaces`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject

        assertFalse(root.containsKey("api"))
        assertFalse(root.containsKey("stats"))
        assertFalse(root.containsKey("reverse"))
        assertFalse(root.containsKey("observatory"))
        assertFalse(root.containsKey("burstObservatory"))
        assertEquals("warning", root.getValue("log").jsonObject.getValue("loglevel").jsonPrimitive.content)
    }

    @Test
    fun `compiler preserves proxy default route and custom direct bypass rules`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray

        assertTrue(
            rules.any { rule ->
                val json = rule.jsonObject
                json["outboundTag"]?.jsonPrimitive?.content == "direct" &&
                    json["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "bank.example" } == true
            },
        )
        assertTrue(
            rules.any { rule ->
                val json = rule.jsonObject
                json["outboundTag"]?.jsonPrimitive?.content == "proxy" &&
                    json["inboundTag"]?.jsonArray?.any { it.jsonPrimitive.content == "socks-in" } == true
            },
        )
    }

    @Test
    fun `compiler emits default reality spider path`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val reality = root.getValue("outbounds").jsonArray.first().jsonObject
            .getValue("streamSettings").jsonObject
            .getValue("realitySettings").jsonObject

        assertEquals("/", reality.getValue("spiderX").jsonPrimitive.content)
    }

    @Test
    fun `system dns uses built-in tcp resolvers and dns outbound interception`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile().copy(dns = DnsMode.SystemDns),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val dns = root.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content }
        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray

        assertEquals(listOf("tcp://1.1.1.1:53", "tcp://1.0.0.1:53"), servers)
        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)
        assertTrue(
            rules.any { rule ->
                val json = rule.jsonObject
                json["outboundTag"]?.jsonPrimitive?.content == "dns-out" &&
                    json["port"]?.jsonPrimitive?.content == "53" &&
                    json["network"]?.jsonPrimitive?.content == "udp,tcp"
            },
        )
    }

    @Test
    fun `compiler emits regional bypass rules before general proxy routing`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile().copy(
                routing = sampleProfile().routing.copy(
                    regionalBypass = defaultRegionalBypass(),
                ),
            ),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val routing = root.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray
        val regionalRuleIndex = rules.indexOfFirst { rule ->
            val json = rule.jsonObject
            val domain = json["domain"]?.jsonArray.orEmpty()
            val ip = json["ip"]?.jsonArray.orEmpty()
            domain.any { it.jsonPrimitive.content == "domain:xn--p1ai" } &&
                ip.any { it.jsonPrimitive.content == "geoip:ru" }
        }
        val defaultProxyIndex = rules.indexOfFirst { rule ->
            rule.jsonObject["outboundTag"]?.jsonPrimitive?.content == "proxy" &&
                rule.jsonObject["inboundTag"]?.jsonArray?.any { it.jsonPrimitive.content == "socks-in" } == true
        }

        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertTrue(regionalRuleIndex in 0 until defaultProxyIndex)
        assertTrue(
            rules[regionalRuleIndex].jsonObject.getValue("domain").jsonArray.any {
                it.jsonPrimitive.content == "domain:xn--p1ai"
            },
        )
        assertTrue(
            rules[regionalRuleIndex].jsonObject.getValue("ip").jsonArray.any {
                it.jsonPrimitive.content == "geoip:ru"
            },
        )
    }

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "alpha-secure",
        name = "Alpha Secure",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            realityShortId = "abcd1234",
            flow = null,
            utlsFingerprint = "chrome",
        ),
        vpn = VpnPolicy(
            splitTunnel = SplitTunnelMode.Denylist(
                packageNames = listOf("io.acionyx.bank"),
            ),
        ),
        routing = RoutingPolicy(
            defaultAction = RouteAction.PROXY,
            rules = listOf(
                RouteRule(
                    id = "bank-direct",
                    action = RouteAction.DIRECT,
                    match = RouteMatch(domainExact = listOf("bank.example")),
                ),
            ),
        ),
        dns = DnsMode.SystemDns,
    )
}
