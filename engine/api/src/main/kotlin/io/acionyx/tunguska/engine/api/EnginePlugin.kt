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
