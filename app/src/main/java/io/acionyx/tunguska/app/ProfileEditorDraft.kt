package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DEFAULT_REALITY_SPIDER_X
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.ValidationIssue

internal val PROFILE_EDITOR_UTLS_FINGERPRINT_CHOICES: List<String> = listOf(
    "chrome",
    "firefox",
    "safari",
    "ios",
    "edge",
)

data class ProfileEditorDraft(
    val name: String,
    val address: String,
    val port: String,
    val uuid: String,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val realitySpiderX: String,
    val flowEnabled: Boolean,
    val utlsFingerprint: String,
    val dnsMode: ProfileEditorDnsMode,
    val dnsEncryptedKind: ProfileEditorEncryptedDnsKind,
    val dnsEndpoints: String,
    val defaultRouteAction: RouteAction,
    val splitTunnelMode: ProfileEditorSplitTunnelMode,
    val splitTunnelPackages: String,
    val safeMode: Boolean,
    val compatibilityLocalProxy: Boolean,
    val debugEndpointsEnabled: Boolean,
) {
    fun validationIssues(baseProfile: ProfileIr): List<ValidationIssue> {
        val parsedPort = port.trim().toIntOrNull()
            ?: return listOf(
                ValidationIssue(
                    field = "outbound.port",
                    message = "Port must be a whole number between 1 and 65535.",
                ),
            )
        val issues = toUpdatedProfile(baseProfile, parsedPort).validate().toMutableList()
        if (dnsMode != ProfileEditorDnsMode.SYSTEM && parseProfileEditorLineList(dnsEndpoints).isEmpty()) {
            issues += ValidationIssue(
                field = "dns.endpoints",
                message = "Add at least one DNS endpoint when system DNS is not selected.",
            )
        }
        if (splitTunnelMode != ProfileEditorSplitTunnelMode.FULL && parseProfileEditorLineList(splitTunnelPackages).isEmpty()) {
            issues += ValidationIssue(
                field = "vpn.splitTunnel.packageNames",
                message = "Add at least one Android package for allowlist or denylist mode.",
            )
        }
        return issues
    }

    fun previewProfile(baseProfile: ProfileIr): ProfileIr =
        toUpdatedProfile(baseProfile, port.trim().toIntOrNull() ?: baseProfile.outboundSummary.endpoint.port)

    fun toUpdatedProfile(baseProfile: ProfileIr): ProfileIr {
        val parsedPort = port.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Port must be a whole number between 1 and 65535.")
        return toUpdatedProfile(baseProfile, parsedPort)
    }

    private fun toUpdatedProfile(
        baseProfile: ProfileIr,
        parsedPort: Int,
    ): ProfileIr {
        val normalizedSpiderX = realitySpiderX.trim().ifBlank { DEFAULT_REALITY_SPIDER_X }
        val parsedDnsEndpoints = parseProfileEditorLineList(dnsEndpoints)
        val parsedSplitTunnelPackages = parseProfileEditorLineList(splitTunnelPackages)
        val outbound = toProtocolSpecificOutbound(
            baseProfile = baseProfile,
            parsedPort = parsedPort,
            normalizedSpiderX = normalizedSpiderX,
        )
        return baseProfile.copy(
            name = name.trim(),
            outbound = outbound,
            vpn = baseProfile.vpn.copy(
                splitTunnel = when (splitTunnelMode) {
                    ProfileEditorSplitTunnelMode.FULL -> SplitTunnelMode.FullTunnel
                    ProfileEditorSplitTunnelMode.ALLOWLIST -> SplitTunnelMode.Allowlist(parsedSplitTunnelPackages)
                    ProfileEditorSplitTunnelMode.DENYLIST -> SplitTunnelMode.Denylist(parsedSplitTunnelPackages)
                },
            ),
            routing = baseProfile.routing.copy(
                defaultAction = defaultRouteAction,
            ),
            dns = when (dnsMode) {
                ProfileEditorDnsMode.SYSTEM -> DnsMode.SystemDns
                ProfileEditorDnsMode.VPN -> DnsMode.VpnDns(servers = parsedDnsEndpoints)
                ProfileEditorDnsMode.CUSTOM -> DnsMode.CustomEncrypted(
                    kind = dnsEncryptedKind.toDomainKind(),
                    endpoints = parsedDnsEndpoints,
                )
            },
            safety = baseProfile.safety.copy(
                safeMode = safeMode,
                compatibilityLocalProxy = compatibilityLocalProxy,
                debugEndpointsEnabled = debugEndpointsEnabled,
            ),
        )
    }

    companion object {
        fun fromProfile(profile: ProfileIr): ProfileEditorDraft {
            val outboundFields = profile.toProtocolSpecificDraftFields()
            return ProfileEditorDraft(
            name = profile.name,
            address = outboundFields.address,
            port = outboundFields.port,
            uuid = outboundFields.uuid,
            serverName = outboundFields.serverName,
            realityPublicKey = outboundFields.realityPublicKey,
            realityShortId = outboundFields.realityShortId,
            realitySpiderX = outboundFields.realitySpiderX,
            flowEnabled = outboundFields.flowEnabled,
            utlsFingerprint = outboundFields.utlsFingerprint,
            dnsMode = when (val dns = profile.dns) {
                is DnsMode.SystemDns -> ProfileEditorDnsMode.SYSTEM
                is DnsMode.VpnDns -> ProfileEditorDnsMode.VPN
                is DnsMode.CustomEncrypted -> ProfileEditorDnsMode.CUSTOM
            },
            dnsEncryptedKind = when (val dns = profile.dns) {
                is DnsMode.CustomEncrypted -> dns.kind.toEditorKind()
                else -> ProfileEditorEncryptedDnsKind.DOH
            },
            dnsEndpoints = when (val dns = profile.dns) {
                is DnsMode.SystemDns -> ""
                is DnsMode.VpnDns -> dns.servers.toProfileEditorLineText()
                is DnsMode.CustomEncrypted -> dns.endpoints.toProfileEditorLineText()
            },
            defaultRouteAction = profile.routing.defaultAction,
            splitTunnelMode = profile.vpn.splitTunnel.toEditorMode(),
            splitTunnelPackages = profile.vpn.splitTunnel.toEditorPackagesText(),
            safeMode = profile.safety.safeMode,
            compatibilityLocalProxy = profile.safety.compatibilityLocalProxy,
            debugEndpointsEnabled = profile.safety.debugEndpointsEnabled,
        )
        }
    }
}

private data class ProtocolSpecificProfileEditorDraftFields(
    val address: String,
    val port: String,
    val uuid: String,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val realitySpiderX: String,
    val flowEnabled: Boolean,
    val utlsFingerprint: String,
)

private fun ProfileEditorDraft.toProtocolSpecificOutbound(
    baseProfile: ProfileIr,
    parsedPort: Int,
    normalizedSpiderX: String,
) = when (baseProfile.outboundProtocolId) {
    OutboundProtocolId.VLESS_REALITY -> baseProfile.outbound.copy(
        address = address.trim(),
        port = parsedPort,
        uuid = uuid.trim(),
        serverName = serverName.trim(),
        realityPublicKey = realityPublicKey.trim(),
        realityShortId = realityShortId.trim(),
        realitySpiderX = normalizedSpiderX.takeUnless { it == DEFAULT_REALITY_SPIDER_X },
        flow = if (flowEnabled) REQUIRED_VLESS_FLOW else null,
        utlsFingerprint = utlsFingerprint.trim().ifBlank { "chrome" },
    )
}

private fun ProfileIr.toProtocolSpecificDraftFields(): ProtocolSpecificProfileEditorDraftFields = when (outboundProtocolId) {
    OutboundProtocolId.VLESS_REALITY -> ProtocolSpecificProfileEditorDraftFields(
        address = outbound.address,
        port = outbound.port.toString(),
        uuid = outbound.uuid,
        serverName = outbound.serverName,
        realityPublicKey = outbound.realityPublicKey,
        realityShortId = outbound.realityShortId,
        realitySpiderX = outbound.effectiveRealitySpiderX(),
        flowEnabled = outbound.flow == REQUIRED_VLESS_FLOW,
        utlsFingerprint = outbound.utlsFingerprint,
    )
}

enum class ProfileEditorDnsMode {
    SYSTEM,
    VPN,
    CUSTOM,
}

enum class ProfileEditorEncryptedDnsKind {
    DOH,
    DOT,
}

enum class ProfileEditorSplitTunnelMode {
    FULL,
    ALLOWLIST,
    DENYLIST,
}

internal fun defaultProfileEditorVpnDnsEndpoints(): String = DnsMode.VpnDns().servers.toProfileEditorLineText()

internal fun defaultProfileEditorCustomDnsEndpoints(kind: ProfileEditorEncryptedDnsKind): String = when (kind) {
    ProfileEditorEncryptedDnsKind.DOH -> "https://1.1.1.1/dns-query"
    ProfileEditorEncryptedDnsKind.DOT -> "1.1.1.1"
}

private fun SplitTunnelMode.toEditorMode(): ProfileEditorSplitTunnelMode = when (this) {
    SplitTunnelMode.FullTunnel -> ProfileEditorSplitTunnelMode.FULL
    is SplitTunnelMode.Allowlist -> ProfileEditorSplitTunnelMode.ALLOWLIST
    is SplitTunnelMode.Denylist -> ProfileEditorSplitTunnelMode.DENYLIST
}

private fun SplitTunnelMode.toEditorPackagesText(): String = when (this) {
    SplitTunnelMode.FullTunnel -> ""
    is SplitTunnelMode.Allowlist -> packageNames.toProfileEditorLineText()
    is SplitTunnelMode.Denylist -> packageNames.toProfileEditorLineText()
}

private fun List<String>.toProfileEditorLineText(): String = joinToString(separator = "\n")

private fun parseProfileEditorLineList(raw: String): List<String> = raw
    .lineSequence()
    .flatMap { line -> line.split(',').asSequence() }
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toList()

private fun EncryptedDnsKind.toEditorKind(): ProfileEditorEncryptedDnsKind = when (this) {
    EncryptedDnsKind.DOH -> ProfileEditorEncryptedDnsKind.DOH
    EncryptedDnsKind.DOT -> ProfileEditorEncryptedDnsKind.DOT
}

private fun ProfileEditorEncryptedDnsKind.toDomainKind(): EncryptedDnsKind = when (this) {
    ProfileEditorEncryptedDnsKind.DOH -> EncryptedDnsKind.DOH
    ProfileEditorEncryptedDnsKind.DOT -> EncryptedDnsKind.DOT
}