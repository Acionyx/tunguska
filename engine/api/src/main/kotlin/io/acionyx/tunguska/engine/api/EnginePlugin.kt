package io.acionyx.tunguska.engine.api

import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.ValidationIssue

interface EnginePlugin {
    val id: String
    val capabilities: EngineCapabilities

    fun compile(profile: ProfileIr): CompiledEngineConfig
}

data class EngineCapabilities(
    val supportsTunInbound: Boolean,
    val supportsVlessReality: Boolean,
    val supportsUdp: Boolean,
    val requiresLocalProxy: Boolean,
    val capabilityMatrix: EngineCapabilityMatrix = EngineCapabilityMatrix(),
)

data class EngineCapabilityMatrix(
    val features: List<EngineCapabilitySupport> = emptyList(),
) {
    fun supportFor(feature: EngineCapabilityFeature): EngineCapabilitySupport? =
        features.firstOrNull { it.feature == feature }

    fun supportsIn(category: EngineCapabilityCategory): List<EngineCapabilitySupport> =
        features.filter { it.feature.category == category }
}

enum class EngineCapabilityCategory {
    PROTOCOLS,
    TRANSPORTS,
    SECURITY,
    INBOUNDS,
    DNS,
    ROUTING,
    RUNTIME_BEHAVIOR,
    PROFILE_UTILITIES,
}

enum class EngineCapabilityFeature(
    val category: EngineCapabilityCategory,
    val label: String,
) {
    VLESS_REALITY(EngineCapabilityCategory.PROTOCOLS, "VLESS + REALITY"),
    TCP_TRANSPORT(EngineCapabilityCategory.TRANSPORTS, "TCP transport"),
    UDP_RELAY(EngineCapabilityCategory.TRANSPORTS, "UDP relay"),
    REALITY_SECURITY(EngineCapabilityCategory.SECURITY, "REALITY security"),
    TUN_INBOUND(EngineCapabilityCategory.INBOUNDS, "Native TUN inbound"),
    ENCRYPTED_DNS(EngineCapabilityCategory.DNS, "Encrypted DNS"),
    PACKAGE_SPLIT_TUNNEL(EngineCapabilityCategory.ROUTING, "Per-app split tunnel"),
    DEFAULT_NETWORK_HANDOFF_RECOVERY(EngineCapabilityCategory.RUNTIME_BEHAVIOR, "Network handoff recovery"),
    LOCAL_PROXY_BRIDGE(EngineCapabilityCategory.RUNTIME_BEHAVIOR, "Local proxy bridge"),
    CANONICAL_PROFILE_COMPILATION(EngineCapabilityCategory.PROFILE_UTILITIES, "Canonical profile compilation"),
}

enum class EngineCapabilitySupportState {
    SUPPORTED,
    SUPPORTED_WITH_LIMITS,
    DEGRADED_ON_FALLBACK,
    UNSUPPORTED,
}

data class EngineCapabilitySupport(
    val feature: EngineCapabilityFeature,
    val state: EngineCapabilitySupportState,
    val summary: String,
)

data class CompiledEngineConfig(
    val engineId: String,
    val format: String,
    val payload: String,
    val configHash: String,
    val vpnDirectives: VpnDirectives,
    val runtimeAssets: List<CompiledRuntimeAsset> = emptyList(),
)

data class CompiledRuntimeAsset(
    val relativePath: String,
)

data class VpnDirectives(
    val preserveLoopback: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.FullTunnel,
    val safeMode: Boolean = true,
)

class InvalidProfileException(
    val issues: List<ValidationIssue>,
) : IllegalArgumentException(
    buildString {
        append("Profile validation failed")
        if (issues.isNotEmpty()) {
            append(": ")
            append(issues.joinToString(separator = "; ") { "${it.field}: ${it.message}" })
        }
    },
)
