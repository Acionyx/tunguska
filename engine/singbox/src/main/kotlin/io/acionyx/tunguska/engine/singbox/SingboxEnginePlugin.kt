package io.acionyx.tunguska.engine.singbox

import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EffectiveRoutingPolicyResolver
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.engine.api.CompiledRuntimeAsset
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.EngineCapabilityFeature
import io.acionyx.tunguska.engine.api.EngineCapabilityMatrix
import io.acionyx.tunguska.engine.api.EngineCapabilitySupport
import io.acionyx.tunguska.engine.api.EngineCapabilitySupportState
import io.acionyx.tunguska.engine.api.EngineCapabilities
import io.acionyx.tunguska.engine.api.EnginePlugin
import io.acionyx.tunguska.engine.api.InvalidProfileException
import io.acionyx.tunguska.engine.api.VpnDirectives
import java.net.URI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SingboxEnginePlugin : EnginePlugin {
    override val id: String = "singbox"
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsTunInbound = true,
        supportsVlessReality = true,
        supportsUdp = true,
        requiresLocalProxy = false,
        capabilityMatrix = EngineCapabilityMatrix(
            features = listOf(
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.VLESS_REALITY,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "Compiles the current VLESS + REALITY profile shape directly into sing-box.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.TCP_TRANSPORT,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "TCP transport is executed natively in the sing-box lane.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.UDP_RELAY,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "UDP stays on the native libbox path instead of riding a compatibility bridge.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.REALITY_SECURITY,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "REALITY fields map directly to the sing-box outbound configuration.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.TUN_INBOUND,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "Creates a native TUN inbound inside sing-box with no local proxy hop.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.ENCRYPTED_DNS,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "System, VPN, DoH, and DoT DNS modes compile directly into sing-box.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.PACKAGE_SPLIT_TUNNEL,
                    state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                    summary = "Per-app split tunnel works here, but allowlist mode still leaves the VPN control app outside the tunnel so the session can keep managing itself.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.DEFAULT_NETWORK_HANDOFF_RECOVERY,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "libbox tracks interface changes directly and does not need the xray restart monitor on network handoff.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.LOCAL_PROXY_BRIDGE,
                    state = EngineCapabilitySupportState.UNSUPPORTED,
                    summary = "This lane does not rely on a local SOCKS bridge between the VPN TUN and the outbound engine.",
                ),
                EngineCapabilitySupport(
                    feature = EngineCapabilityFeature.CANONICAL_PROFILE_COMPILATION,
                    state = EngineCapabilitySupportState.SUPPORTED,
                    summary = "Compiles the canonical profile directly without a fallback-only transformation step.",
                ),
            ),
        ),
    )

    override fun compile(profile: ProfileIr): CompiledEngineConfig {
        val issues = profile.validate()
        if (issues.isNotEmpty()) {
            throw InvalidProfileException(issues)
        }

        val effectiveRouting = EffectiveRoutingPolicyResolver.resolve(profile)
        val routeRuleSetAssets = collectRouteRuleSetAssets(effectiveRouting.rules)

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "warn")
                put("timestamp", true)
            })
            put("inbounds", buildJsonArray {
                add(tunInbound())
            })
            put("outbounds", buildJsonArray {
                add(proxyOutbound(profile))
                add(taggedOutbound("direct", "direct"))
            })
            put("route", route(profile, effectiveRouting.rules, routeRuleSetAssets))
            put("dns", dns(profile))
        }

        val payload = CanonicalJson.instance.encodeToString(JsonObject.serializer(), root)
        return CompiledEngineConfig(
            engineId = id,
            format = "application/json",
            payload = payload,
            configHash = CanonicalJson.sha256Hex(payload),
            vpnDirectives = VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = profile.vpn.splitTunnel,
                safeMode = profile.safety.safeMode,
            ),
            runtimeAssets = routeRuleSetAssets.map { CompiledRuntimeAsset(it.relativePath) },
        )
    }

    private fun tunInbound(): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("auto_route", true)
        put("strict_route", true)
        put("stack", "system")
        put("mtu", 9000)
        put("address", buildJsonArray {
            add(JsonPrimitive("172.19.0.1/30"))
            add(JsonPrimitive("fdfe:dcba:9876::1/126"))
        })
    }

    private fun proxyOutbound(profile: ProfileIr): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", "proxy")
        put("server", profile.outbound.address)
        put("server_port", profile.outbound.port)
        put("uuid", profile.outbound.uuid)
        put("network", "tcp")
        profile.outbound.flow?.let { put("flow", it) }
        put("tls", buildJsonObject {
            put("enabled", true)
            put("server_name", profile.outbound.serverName)
            put("curve_preferences", buildJsonArray {
                add(JsonPrimitive("X25519"))
            })
            put("utls", buildJsonObject {
                put("enabled", true)
                put("fingerprint", profile.outbound.utlsFingerprint)
            })
            put("reality", buildJsonObject {
                // Official libbox currently models client REALITY with only public_key + short_id.
                put("enabled", true)
                put("public_key", profile.outbound.realityPublicKey)
                put("short_id", profile.outbound.realityShortId)
            })
        })
    }

    private fun taggedOutbound(tag: String, type: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("type", type)
    }

    private fun route(
        profile: ProfileIr,
        effectiveRules: List<RouteRule>,
        routeRuleSetAssets: List<SingboxRouteRuleSetAsset>,
    ): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        put("default_domain_resolver", LOCAL_DNS_TAG)
        if (profile.routing.defaultAction != RouteAction.BLOCK) {
            put("final", outboundTagFor(profile.routing.defaultAction))
        }
        if (routeRuleSetAssets.isNotEmpty()) {
            put("rule_set", buildJsonArray {
                routeRuleSetAssets.forEach { asset ->
                    add(localRuleSet(asset))
                }
            })
        }
        put("rules", buildJsonArray {
            add(sniffRule())
            add(mandatoryLoopbackBypassRule())
            add(hijackDnsRule())
            effectiveRules.forEach { rule ->
                add(routeRule(rule))
            }
            if (profile.routing.defaultAction == RouteAction.BLOCK) {
                add(finalRejectRule())
            }
        })
    }

    private fun sniffRule(): JsonObject = buildJsonObject {
        put("action", "sniff")
    }

    private fun hijackDnsRule(): JsonObject = buildJsonObject {
        put("protocol", "dns")
        put("action", "hijack-dns")
    }

    private fun finalRejectRule(): JsonObject = buildJsonObject {
        put("action", "reject")
    }

    private fun mandatoryLoopbackBypassRule(): JsonObject = buildJsonObject {
        put("outbound", "direct")
        put("ip_cidr", buildJsonArray {
            add(JsonPrimitive("127.0.0.0/8"))
            add(JsonPrimitive("::1/128"))
        })
    }

    private fun localRuleSet(asset: SingboxRouteRuleSetAsset): JsonObject = buildJsonObject {
        put("tag", asset.tag)
        put("type", "local")
        put("format", "binary")
        put("path", asset.relativePath)
    }

    private fun routeRule(rule: RouteRule): JsonObject = buildJsonObject {
        when (rule.action) {
            RouteAction.BLOCK -> put("action", "reject")
            else -> put("outbound", outboundTagFor(rule.action))
        }
        if (rule.match.domainExact.isNotEmpty()) {
            put("domain", rule.match.domainExact.toJsonArray())
        }
        if (rule.match.domainSuffix.isNotEmpty()) {
            put("domain_suffix", rule.match.domainSuffix.toJsonArray())
        }
        if (rule.match.domainKeyword.isNotEmpty()) {
            put("domain_keyword", rule.match.domainKeyword.toJsonArray())
        }
        if (rule.match.ipCidrs.isNotEmpty()) {
            put("ip_cidr", rule.match.ipCidrs.toJsonArray())
        }
        if (rule.match.geoSites.isNotEmpty()) {
            put("geosite", rule.match.geoSites.toJsonArray())
        }
        if (rule.match.geoIps.isNotEmpty()) {
            put("rule_set", buildJsonArray {
                rule.match.geoIps
                    .asSequence()
                    .map(::normalizeGeoIpCode)
                    .distinct()
                    .sorted()
                    .forEach { code ->
                        add(JsonPrimitive(geoIpRuleSetTag(code)))
                    }
            })
        }
        if (rule.match.asns.isNotEmpty()) {
            put("asn", buildJsonArray {
                rule.match.asns.forEach { add(JsonPrimitive(it)) }
            })
        }
        if (rule.match.packageNames.isNotEmpty()) {
            put("package_name", rule.match.packageNames.toJsonArray())
        }
        if (rule.match.ports.isNotEmpty()) {
            put("port", buildJsonArray {
                rule.match.ports.forEach { add(JsonPrimitive(it)) }
            })
        }
        if (rule.match.protocols.isNotEmpty()) {
            put("network", buildJsonArray {
                rule.match.protocols.forEach { add(JsonPrimitive(it.name.lowercase())) }
            })
        }
    }

    private fun dns(profile: ProfileIr): JsonObject {
        val servers = mutableListOf<JsonElement>(localDnsServer())
        val finalServerTag = when (val mode = profile.dns) {
            is DnsMode.VpnDns -> {
                servers += mode.servers.mapIndexed { index, endpoint ->
                    dnsServerFromEndpoint(
                        tag = "$REMOTE_DNS_TAG_PREFIX$index",
                        endpoint = endpoint,
                        kindHint = null,
                    )
                }
                if (mode.servers.isEmpty()) {
                    LOCAL_DNS_TAG
                } else {
                    "${REMOTE_DNS_TAG_PREFIX}0"
                }
            }

            DnsMode.SystemDns -> LOCAL_DNS_TAG

            is DnsMode.CustomEncrypted -> {
                servers += mode.endpoints.mapIndexed { index, endpoint ->
                    dnsServerFromEndpoint(
                        tag = "$REMOTE_DNS_TAG_PREFIX$index",
                        endpoint = endpoint,
                        kindHint = mode.kind,
                    )
                }
                if (mode.endpoints.isEmpty()) {
                    LOCAL_DNS_TAG
                } else {
                    "${REMOTE_DNS_TAG_PREFIX}0"
                }
            }
        }

        return buildJsonObject {
            put("strategy", "prefer_ipv4")
            put("servers", JsonArray(servers))
            put("final", finalServerTag)
        }
    }

    private fun localDnsServer(): JsonObject = buildJsonObject {
        put("tag", LOCAL_DNS_TAG)
        put("type", "local")
    }

    private fun dnsServerFromEndpoint(
        tag: String,
        endpoint: String,
        kindHint: EncryptedDnsKind?,
    ): JsonObject {
        val normalized = endpoint.trim()
        require(normalized.isNotEmpty()) { "DNS endpoint must not be blank." }
        return when {
            normalized.equals("local", ignoreCase = true) -> localDnsServer()
            normalized.startsWith("https://", ignoreCase = true) -> dohDnsServer(tag, normalized)
            kindHint == EncryptedDnsKind.DOH -> {
                error("DoH DNS endpoints must use https:// URLs: $normalized")
            }

            normalized.startsWith("tls://", ignoreCase = true) -> dotDnsServer(tag, normalized.removePrefix("tls://"))
            kindHint == EncryptedDnsKind.DOT -> dotDnsServer(tag, normalized)
            else -> error("Unsupported DNS endpoint format: $normalized")
        }
    }

    private fun dohDnsServer(tag: String, endpoint: String): JsonObject {
        val uri = URI(endpoint)
        val host = uri.host ?: error("DoH endpoint host is missing: $endpoint")
        return buildJsonObject {
            put("type", "https")
            put("tag", tag)
            put("server", host)
            if (uri.port >= 0) {
                put("server_port", uri.port)
            }
            put("path", uri.rawPath.takeUnless { it.isNullOrBlank() } ?: "/dns-query")
            put("tls", tlsObjectForHost(host))
            host.takeIf { it.requiresDomainResolver() }?.let {
                put("domain_resolver", LOCAL_DNS_TAG)
            }
        }
    }

    private fun dotDnsServer(tag: String, endpoint: String): JsonObject {
        val (host, port) = parseHostAndPort(endpoint)
        return buildJsonObject {
            put("type", "tls")
            put("tag", tag)
            put("server", host)
            if (port != null) {
                put("server_port", port)
            }
            put("tls", tlsObjectForHost(host))
            host.takeIf { it.requiresDomainResolver() }?.let {
                put("domain_resolver", LOCAL_DNS_TAG)
            }
        }
    }

    private fun tlsObjectForHost(host: String): JsonObject = buildJsonObject {
        if (host.requiresDomainResolver()) {
            put("server_name", host)
        }
    }

    private fun parseHostAndPort(endpoint: String): Pair<String, Int?> {
        val normalized = if (endpoint.contains("://")) endpoint else "tls://$endpoint"
        val uri = URI(normalized)
        val host = uri.host ?: error("DNS endpoint host is missing: $endpoint")
        return host to uri.port.takeIf { it >= 0 }
    }

    private fun String.requiresDomainResolver(): Boolean {
        if (isBlank()) {
            return false
        }
        if (startsWith("[") && endsWith("]")) {
            return false
        }
        if (all { it.isDigit() || it == '.' }) {
            return false
        }
        if (contains(':') && none { it.isLetter() }) {
            return false
        }
        return true
    }

    private fun outboundTagFor(action: RouteAction): String = when (action) {
        RouteAction.PROXY -> "proxy"
        RouteAction.DIRECT -> "direct"
        RouteAction.BLOCK -> error("RouteAction.BLOCK must be encoded as a reject rule action.")
    }

    private fun collectRouteRuleSetAssets(rules: List<RouteRule>): List<SingboxRouteRuleSetAsset> = rules
        .asSequence()
        .flatMap { rule -> rule.match.geoIps.asSequence() }
        .map(::normalizeGeoIpCode)
        .distinct()
        .sorted()
        .map { code ->
            SingboxRouteRuleSetAsset(
                tag = geoIpRuleSetTag(code),
                relativePath = "rule-set/${geoIpRuleSetTag(code)}.srs",
            )
        }
        .toList()

    private fun normalizeGeoIpCode(value: String): String {
        val normalized = value.trim().lowercase()
        require(normalized.isNotBlank()) { "GeoIP code must not be blank." }
        require(normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "Unsupported sing-box GeoIP code '$value'."
        }
        return normalized
    }
}

private data class SingboxRouteRuleSetAsset(
    val tag: String,
    val relativePath: String,
)

private const val LOCAL_DNS_TAG = "dns-local"
private const val REMOTE_DNS_TAG_PREFIX = "dns-remote-"

private fun geoIpRuleSetTag(code: String): String = "geoip-$code"

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { value -> add(JsonPrimitive(value)) }
}
