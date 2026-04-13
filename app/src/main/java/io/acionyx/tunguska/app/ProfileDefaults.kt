package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SafetySettings
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy

internal fun defaultBootstrapProfile(): ProfileIr = ProfileIr(
    id = "alpha-secure",
    name = "Alpha Secure",
    outbound = VlessRealityOutbound(
        address = "edge.example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        serverName = "cdn.example.com",
        realityPublicKey = "public-key",
        realityShortId = "abcd1234",
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
                id = "corp-direct",
                action = RouteAction.DIRECT,
                match = RouteMatch(
                    domainSuffix = listOf("corp.example"),
                    ports = listOf(443),
                ),
            ),
            RouteRule(
                id = "ads-block",
                action = RouteAction.BLOCK,
                match = RouteMatch(
                    domainKeyword = listOf("ads", "tracking"),
                ),
            ),
        ),
    ),
    dns = DnsMode.SystemDns,
    safety = SafetySettings(
        safeMode = true,
        compatibilityLocalProxy = false,
        debugEndpointsEnabled = false,
    ),
)
