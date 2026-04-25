package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.displayLabel
import io.acionyx.tunguska.domain.outboundProtocolLabel
import io.acionyx.tunguska.domain.outboundShapeLabel
import io.acionyx.tunguska.domain.outboundShapeLabel
import io.acionyx.tunguska.engine.api.EngineCapabilityFeature
import io.acionyx.tunguska.engine.api.EngineCapabilityMatrix
import io.acionyx.tunguska.engine.api.EngineCapabilitySupport
import io.acionyx.tunguska.engine.api.EngineCapabilitySupportState
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId

data class StrategyCapabilityProfile(
    val strategyId: EmbeddedRuntimeStrategyId,
    val title: String,
    val summary: String,
    val capabilityMatrix: EngineCapabilityMatrix,
)

enum class StrategyCompatibilitySeverity {
    READY,
    ATTENTION,
}

data class StrategyCompatibilitySummary(
    val title: String,
    val details: List<String>,
    val severity: StrategyCompatibilitySeverity,
)

enum class ProfileGuidanceSection {
    DNS,
    VPN_POLICY,
}

data class ProfileSectionGuidance(
    val section: ProfileGuidanceSection,
    val title: String,
    val details: List<String>,
    val severity: StrategyCompatibilitySeverity,
)

data class ProfileCapabilityGuidance(
    val selectedSummary: StrategyCompatibilitySummary,
    val recommendedStrategyId: EmbeddedRuntimeStrategyId,
    val recommendation: String?,
    val sectionGuidance: List<ProfileSectionGuidance>,
) {
    fun guidanceFor(section: ProfileGuidanceSection): ProfileSectionGuidance? =
        sectionGuidance.firstOrNull { it.section == section }
}

object StrategyCapabilityRegistry {
    private val profiles: Map<EmbeddedRuntimeStrategyId, StrategyCapabilityProfile> = mapOf(
        EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED to StrategyCapabilityProfile(
            strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            title = "sing-box embedded",
            summary = "Primary embedded lane for the current ${currentPrimaryOutboundShapeLabel()} profile shape, with the fewest limits on DNS, routing, and runtime behavior.",
            capabilityMatrix = SingboxEnginePlugin().capabilities.capabilityMatrix,
        ),
        EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS to StrategyCapabilityProfile(
            strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            title = "xray+tun2socks",
            summary = "Compatibility lane for the current ${currentPrimaryOutboundShapeLabel()} profile shape. It runs with more limits on DNS, routing, and runtime behavior than sing-box embedded.",
            capabilityMatrix = EngineCapabilityMatrix(
                features = listOf(
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.VLESS_REALITY,
                        state = EngineCapabilitySupportState.SUPPORTED,
                        summary = "Supports the current ${currentPrimaryOutboundShapeLabel()} profile shape.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.TCP_TRANSPORT,
                        state = EngineCapabilitySupportState.SUPPORTED,
                        summary = "TCP is the supported transport path in this lane.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.UDP_RELAY,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "UDP works on this lane, but it rides the compatibility bridge instead of a direct engine path, so latency spikes and heavy UDP flows recover less cleanly than on sing-box embedded.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.REALITY_SECURITY,
                        state = EngineCapabilitySupportState.SUPPORTED,
                        summary = "REALITY is supported for the current profile shape.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.TUN_INBOUND,
                        state = EngineCapabilitySupportState.DEGRADED_ON_FALLBACK,
                        summary = "This lane still carries the VPN session, but the TUN is handled through tun2socks before traffic reaches xray, so advanced inbound behavior is less direct than on sing-box embedded.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.ENCRYPTED_DNS,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "Encrypted DNS works only when the configured DoH or DoT endpoints fit the xray compatibility compiler; shapes outside that subset are rejected before connect.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.PACKAGE_SPLIT_TUNNEL,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "Per-app split tunnel works, but allowlist mode must leave the VPN control app outside the tunnel, so this lane always reserves one local bypass path for itself.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.DEFAULT_NETWORK_HANDOFF_RECOVERY,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "After Wi-Fi or cellular handoff, this lane can take longer to settle because both the compatibility bridge and the xray session need to recover together.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.LOCAL_PROXY_BRIDGE,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "Traffic crosses an authenticated local compatibility bridge between tun2socks and xray here, instead of staying inside one embedded engine path end to end.",
                    ),
                    EngineCapabilitySupport(
                        feature = EngineCapabilityFeature.CANONICAL_PROFILE_COMPILATION,
                        state = EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS,
                        summary = "This lane starts only when the selected profile shape and routing rules fit the xray compatibility compiler; unsupported rule criteria are rejected before runtime starts.",
                    ),
                ),
            ),
        ),
    )

    fun profileFor(strategyId: EmbeddedRuntimeStrategyId): StrategyCapabilityProfile = profiles.getValue(strategyId)

    fun evaluateProfileCompatibility(
        profile: ProfileIr,
        strategyId: EmbeddedRuntimeStrategyId,
    ): StrategyCompatibilitySummary {
        val capabilityProfile = profileFor(strategyId)
        val relevantSupports = buildList {
            capabilityProfile.capabilityMatrix.supportFor(protocolCapability(profile.outboundProtocolId))?.let(::add)
            capabilityProfile.capabilityMatrix.supportFor(transportCapability(profile.outboundTransportId))?.let(::add)
            capabilityProfile.capabilityMatrix.supportFor(securityCapability(profile.outboundSecurityId))?.let(::add)
            capabilityProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.TUN_INBOUND)?.let(::add)
            capabilityProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.DEFAULT_NETWORK_HANDOFF_RECOVERY)?.let(::add)
            capabilityProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.CANONICAL_PROFILE_COMPILATION)?.let(::add)
            if (profile.dns !is DnsMode.SystemDns) {
                capabilityProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.ENCRYPTED_DNS)?.let(::add)
            }
            if (profile.vpn.splitTunnel != SplitTunnelMode.FullTunnel) {
                capabilityProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.PACKAGE_SPLIT_TUNNEL)?.let(::add)
            }
        }.distinctBy { it.feature }

        val severity = if (relevantSupports.any { it.state != EngineCapabilitySupportState.SUPPORTED }) {
            StrategyCompatibilitySeverity.ATTENTION
        } else {
            StrategyCompatibilitySeverity.READY
        }
        val title = when (severity) {
            StrategyCompatibilitySeverity.READY -> "Selected lane fully supports this ${profile.outboundShapeLabel()} shape"
            StrategyCompatibilitySeverity.ATTENTION -> "Selected lane can run this ${profile.outboundShapeLabel()} shape, but with engine limits"
        }
        return StrategyCompatibilitySummary(
            title = title,
            details = listOf(capabilityProfile.summary) + relevantSupports.map { it.summary },
            severity = severity,
        )
    }

    fun evaluateProfileGuidance(
        profile: ProfileIr,
        strategyId: EmbeddedRuntimeStrategyId,
    ): ProfileCapabilityGuidance {
        val selectedSummary = evaluateProfileCompatibility(profile, strategyId)
        val recommendedStrategyId = recommendedStrategyFor(profile)
        val recommendation = if (recommendedStrategyId != strategyId) {
            val recommendedProfile = profileFor(recommendedStrategyId)
            "Recommended lane: ${recommendedProfile.title}. It supports this profile shape with fewer engine limits on Android."
        } else {
            null
        }
        val sectionGuidance = buildList {
            val selectedProfile = profileFor(strategyId)
            if (profile.dns !is DnsMode.SystemDns) {
                val dnsSupport = selectedProfile.capabilityMatrix.supportFor(EngineCapabilityFeature.ENCRYPTED_DNS)
                if (dnsSupport != null && dnsSupport.state != EngineCapabilitySupportState.SUPPORTED) {
                    add(
                        ProfileSectionGuidance(
                            section = ProfileGuidanceSection.DNS,
                            title = "DNS support is more limited on this lane",
                            details = listOf(dnsSupport.summary),
                            severity = StrategyCompatibilitySeverity.ATTENTION,
                        ),
                    )
                }
            }
            when (profile.vpn.splitTunnel) {
                is SplitTunnelMode.Allowlist -> {
                    add(
                        ProfileSectionGuidance(
                            section = ProfileGuidanceSection.VPN_POLICY,
                            title = "Allowlist mode must keep Tunguska outside the tunnel",
                            details = listOf(
                                "Allowlist mode keeps the Tunguska app outside the VPN so runtime control stays available.",
                            ),
                            severity = StrategyCompatibilitySeverity.ATTENTION,
                        ),
                    )
                }
                is SplitTunnelMode.Denylist -> {
                    if (strategyId == EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) {
                        add(
                            ProfileSectionGuidance(
                                section = ProfileGuidanceSection.VPN_POLICY,
                                title = "Split tunnel is more limited on this lane",
                                details = listOf(
                                    "xray+tun2socks can apply per-app routing, but with fewer routing capabilities than sing-box embedded.",
                                ),
                                severity = StrategyCompatibilitySeverity.ATTENTION,
                            ),
                        )
                    }
                }
                SplitTunnelMode.FullTunnel -> Unit
            }
        }
        return ProfileCapabilityGuidance(
            selectedSummary = selectedSummary,
            recommendedStrategyId = recommendedStrategyId,
            recommendation = recommendation,
            sectionGuidance = sectionGuidance,
        )
    }

    fun recommendedStrategyFor(profile: ProfileIr): EmbeddedRuntimeStrategyId = listOf(
        EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
    ).minBy { strategyId ->
        compatibilitySeverityRank(evaluateProfileCompatibility(profile, strategyId).severity)
    }

    private fun compatibilitySeverityRank(severity: StrategyCompatibilitySeverity): Int = when (severity) {
        StrategyCompatibilitySeverity.READY -> 0
        StrategyCompatibilitySeverity.ATTENTION -> 1
    }

    private fun protocolCapability(protocolId: OutboundProtocolId): EngineCapabilityFeature = when (protocolId) {
        OutboundProtocolId.VLESS_REALITY -> EngineCapabilityFeature.VLESS_REALITY
    }

    private fun transportCapability(transportId: OutboundTransportId): EngineCapabilityFeature = when (transportId) {
        OutboundTransportId.TCP -> EngineCapabilityFeature.TCP_TRANSPORT
    }

    private fun securityCapability(securityId: OutboundSecurityId): EngineCapabilityFeature = when (securityId) {
        OutboundSecurityId.REALITY -> EngineCapabilityFeature.REALITY_SECURITY
    }

    private fun currentPrimaryOutboundShapeLabel(): String = buildString {
        append(outboundShapeLabel(OutboundProtocolId.VLESS_REALITY, OutboundTransportId.TCP, OutboundSecurityId.REALITY))
    }
}