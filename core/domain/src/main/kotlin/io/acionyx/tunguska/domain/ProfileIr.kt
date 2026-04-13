package io.acionyx.tunguska.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val REQUIRED_VLESS_FLOW: String = "xtls-rprx-vision"

@Serializable
data class ProfileIr(
    val id: String,
    val name: String,
    val outbound: VlessRealityOutbound,
    val vpn: VpnPolicy = VpnPolicy(),
    val routing: RoutingPolicy = RoutingPolicy(),
    val dns: DnsMode = DnsMode.SystemDns,
    val safety: SafetySettings = SafetySettings(),
) {
    fun validate(): List<ValidationIssue> = buildList {
        if (id.isBlank()) add(ValidationIssue("id", "Profile id must not be blank."))
        if (name.isBlank()) add(ValidationIssue("name", "Profile name must not be blank."))
        addAll(outbound.validate())
        addAll(vpn.validate())
        addAll(routing.validate())
        addAll(dns.validate())
        addAll(safety.validate())
    }

    fun canonicalJson(): String = CanonicalJson.encodeProfile(this)

    fun canonicalHash(): String = CanonicalJson.sha256Hex(canonicalJson())
}

@Serializable
data class VlessRealityOutbound(
    val address: String,
    val port: Int,
    val uuid: String,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val flow: String? = null,
    val utlsFingerprint: String = "chrome",
) {
    fun validate(): List<ValidationIssue> = buildList {
        if (address.isBlank()) add(ValidationIssue("outbound.address", "Server address must not be blank."))
        if (port !in 1..65535) add(ValidationIssue("outbound.port", "Port must be between 1 and 65535."))
        if (uuid.isBlank()) add(ValidationIssue("outbound.uuid", "UUID must not be blank."))
        if (serverName.isBlank()) add(ValidationIssue("outbound.serverName", "Server name must not be blank."))
        if (realityPublicKey.isBlank()) {
            add(ValidationIssue("outbound.realityPublicKey", "REALITY public key must not be blank."))
        }
        if (realityShortId.isBlank()) {
            add(ValidationIssue("outbound.realityShortId", "REALITY short id must not be blank."))
        }
        if (flow != null && flow != REQUIRED_VLESS_FLOW) {
            add(ValidationIssue("outbound.flow", "Only $REQUIRED_VLESS_FLOW is accepted in v1."))
        }
    }
}

@Serializable
data class VpnPolicy(
    val splitTunnel: SplitTunnelMode = SplitTunnelMode.FullTunnel,
) {
    fun validate(): List<ValidationIssue> = splitTunnel.validate()
}

@Serializable
sealed interface SplitTunnelMode {
    fun validate(): List<ValidationIssue>

    @Serializable
    @SerialName("full_tunnel")
    data object FullTunnel : SplitTunnelMode {
        override fun validate(): List<ValidationIssue> = emptyList()
    }

    @Serializable
    @SerialName("allowlist")
    data class Allowlist(
        val packageNames: List<String>,
    ) : SplitTunnelMode {
        override fun validate(): List<ValidationIssue> = validatePackages("vpn.splitTunnel.packageNames", packageNames)
    }

    @Serializable
    @SerialName("denylist")
    data class Denylist(
        val packageNames: List<String>,
    ) : SplitTunnelMode {
        override fun validate(): List<ValidationIssue> = validatePackages("vpn.splitTunnel.packageNames", packageNames)
    }
}

@Serializable
data class RoutingPolicy(
    val defaultAction: RouteAction = RouteAction.PROXY,
    val rules: List<RouteRule> = emptyList(),
) {
    fun validate(): List<ValidationIssue> = buildList {
        val duplicateIds = rules.groupingBy(RouteRule::id).eachCount().filterValues { it > 1 }.keys
        duplicateIds.forEach { id ->
            add(ValidationIssue("routing.rules", "Duplicate route rule id '$id'."))
        }
        rules.forEachIndexed { index, rule ->
            addAll(rule.validate(index))
        }
    }
}

@Serializable
data class RouteRule(
    val id: String,
    val action: RouteAction,
    val match: RouteMatch,
) {
    fun validate(index: Int): List<ValidationIssue> = buildList {
        if (id.isBlank()) add(ValidationIssue("routing.rules[$index].id", "Route rule id must not be blank."))
        if (!match.hasCriteria()) {
            add(ValidationIssue("routing.rules[$index].match", "Route rule must declare at least one criterion."))
        }
        match.ports.filterNot { it in 1..65535 }.forEach { invalidPort ->
            add(ValidationIssue("routing.rules[$index].match.ports", "Invalid route port '$invalidPort'."))
        }
    }
}

@Serializable
data class RouteMatch(
    val domainExact: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    val geoSites: List<String> = emptyList(),
    val geoIps: List<String> = emptyList(),
    val asns: List<Long> = emptyList(),
    val packageNames: List<String> = emptyList(),
    val ports: List<Int> = emptyList(),
    val protocols: List<NetworkProtocol> = emptyList(),
) {
    fun hasCriteria(): Boolean = listOf(
        domainExact,
        domainSuffix,
        domainKeyword,
        ipCidrs,
        geoSites,
        geoIps,
        asns,
        packageNames,
        ports,
        protocols,
    ).any { it.isNotEmpty() }
}

@Serializable
enum class RouteAction {
    PROXY,
    DIRECT,
    BLOCK,
}

@Serializable
enum class NetworkProtocol {
    TCP,
    UDP,
}

@Serializable
sealed interface DnsMode {
    fun validate(): List<ValidationIssue>

    @Serializable
    @SerialName("vpn_dns")
    data class VpnDns(
        val servers: List<String> = listOf(
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
        ),
    ) : DnsMode {
        override fun validate(): List<ValidationIssue> = validateNonBlank("dns.servers", servers)
    }

    @Serializable
    @SerialName("system_dns")
    data object SystemDns : DnsMode {
        override fun validate(): List<ValidationIssue> = emptyList()
    }

    @Serializable
    @SerialName("custom_encrypted")
    data class CustomEncrypted(
        val kind: EncryptedDnsKind,
        val endpoints: List<String>,
    ) : DnsMode {
        override fun validate(): List<ValidationIssue> = buildList {
            addAll(validateNonBlank("dns.endpoints", endpoints))
            if (endpoints.isEmpty()) {
                add(ValidationIssue("dns.endpoints", "Custom encrypted DNS requires at least one endpoint."))
            }
        }
    }
}

@Serializable
enum class EncryptedDnsKind {
    DOH,
    DOT,
}

@Serializable
data class SafetySettings(
    val safeMode: Boolean = true,
    val compatibilityLocalProxy: Boolean = false,
    val debugEndpointsEnabled: Boolean = false,
) {
    fun validate(): List<ValidationIssue> = buildList {
        if (safeMode && compatibilityLocalProxy) {
            add(
                ValidationIssue(
                    "safety.compatibilityLocalProxy",
                    "Compatibility local proxy cannot be enabled while safe mode is active.",
                ),
            )
        }
        if (safeMode && debugEndpointsEnabled) {
            add(
                ValidationIssue(
                    "safety.debugEndpointsEnabled",
                    "Debug endpoints cannot be enabled while safe mode is active.",
                ),
            )
        }
    }
}

@Serializable
data class ValidationIssue(
    val field: String,
    val message: String,
)

private fun validatePackages(field: String, packageNames: List<String>): List<ValidationIssue> = buildList {
    addAll(validateNonBlank(field, packageNames))
    val duplicatePackages = packageNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    duplicatePackages.forEach { duplicate ->
        add(ValidationIssue(field, "Duplicate package '$duplicate'."))
    }
}

private fun validateNonBlank(field: String, values: List<String>): List<ValidationIssue> = buildList {
    values.forEachIndexed { index, value ->
        if (value.isBlank()) {
            add(ValidationIssue("$field[$index]", "Value must not be blank."))
        }
    }
}
