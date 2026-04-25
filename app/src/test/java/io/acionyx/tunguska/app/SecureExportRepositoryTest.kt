package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.netpolicy.RoutePreviewOutcome
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.RuntimeConfigSource
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.RuntimeLaneCompatibilityMetadata
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureExportRepositoryTest {
    @Test
    fun `encrypted profile backup preserves full profile payload`() {
        val profile = defaultBootstrapProfile()
        val compiled = SingboxEnginePlugin().compile(profile)
        val repository = buildRepository()

        val artifact = repository.exportEncryptedProfileBackup(
            profile = profile,
            compiledConfig = compiled,
        )
        val loaded = repository.loadArtifact(Paths.get(artifact.path))

        assertEquals("profile_backup", artifact.artifactType)
        assertFalse(artifact.redacted)
        assertTrue(loaded.payloadJson.contains(profile.outbound.uuid))
        assertTrue(loaded.payloadJson.contains(profile.outbound.address))
        assertTrue(Files.exists(Paths.get(artifact.path)))
    }

    @Test
    fun `redacted diagnostic bundle strips raw server identifiers`() {
        val profile = defaultBootstrapProfile()
        val compiled = SingboxEnginePlugin().compile(profile)
        val repository = buildRepository()

        val artifact = repository.exportRedactedDiagnosticBundle(
            profile = profile,
            compiledConfig = compiled,
            tunnelPlanSummary = TunnelPlanSummary(
                processNameSuffix = ":vpn",
                preserveLoopback = true,
                splitTunnelMode = "Denylist",
                allowedPackageCount = 0,
                disallowedPackageCount = 1,
                runtimeMode = "FAIL_CLOSED_UNTIL_ENGINE_HOST",
            ),
            runtimeSnapshot = VpnRuntimeSnapshot(
                phase = VpnRuntimePhase.RUNNING,
                configHash = compiled.configHash,
                activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                runtimeConfigSource = RuntimeConfigSource.CANONICAL_PROFILE_REBUILD,
                configuredStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                configuredRuntimeConfigSource = RuntimeConfigSource.STAGED_ENGINE_PAYLOAD,
                configuredLaneCompatibility = RuntimeLaneCompatibilityMetadata(
                    statusLabel = "Clean match",
                    selectedSummaryTitle = "Configured next-start metadata title",
                    recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    recommendation = "Configured lane metadata recommendation.",
                ),
                profileProtocolId = OutboundProtocolId.VLESS_REALITY,
                profileTransportId = OutboundTransportId.TCP,
                profileSecurityId = OutboundSecurityId.REALITY,
                laneCompatibility = RuntimeLaneCompatibilityMetadata(
                    statusLabel = "Has limits",
                    selectedSummaryTitle = "Staged lane metadata title",
                    recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    recommendation = "Use the broader embedded lane after restaging.",
                ),
                lastError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
                lastErrorSection = "Routing",
                lastErrorFieldPath = "routing.rules",
            ),
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = "android-keystore:test",
                storagePath = "C:/private/profile.json.enc",
                status = "Loaded encrypted profile.",
                persistedProfileHash = profile.canonicalHash(),
            ),
            automationState = AutomationState(
                storagePath = "C:/private/automation.json.enc",
                keyReference = "android-keystore:test-automation",
                vpnPermissionReady = true,
                runtimeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                lastAutomationError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames. Review Routing in the profile editor.",
                lastAutomationErrorSection = "Routing",
                lastAutomationErrorFieldPath = "routing.rules",
                lastAutomationRuntimeMetadata = AutomationRuntimeMetadata(
                    laneLabel = "Xray + tun2socks",
                    laneStatus = "Fallback with limits",
                    laneSummaryTitle = "Active lane metadata title",
                    connectDecisionSummary = "Start failed on Xray + tun2socks. Recommended: Sing-box embedded.",
                    compilerPath = "Canonical profile rebuild",
                    nextStartLaneLabel = "Sing-box embedded",
                    nextStartLaneStatus = "Clean match",
                    nextStartCompilerPath = "Staged engine payload",
                ),
            ),
            routePreview = PreviewInputs(
                packageName = "io.acionyx.browser",
                destinationHost = "login.corp.example",
                destinationPort = "443",
            ),
            previewOutcome = RoutePreviewOutcome(
                action = RouteAction.PROXY,
                matchedRuleId = "corp-direct",
                reason = "Matched explicit routing rule.",
            ),
        )
        val loaded = repository.loadArtifact(Paths.get(artifact.path))

        assertEquals("diagnostic_bundle", artifact.artifactType)
        assertTrue(artifact.redacted)
        assertFalse(loaded.payloadJson.contains(profile.outbound.uuid))
        assertFalse(loaded.payloadJson.contains(profile.outbound.address))
        assertFalse(loaded.payloadJson.contains(profile.outbound.serverName))
        assertTrue(loaded.payloadJson.contains("\"outboundShape\":\"VLESS + REALITY over TCP\""))
        assertTrue(loaded.payloadJson.contains("\"outboundProtocol\":\"VLESS + REALITY\""))
        assertTrue(loaded.payloadJson.contains("\"outboundTransport\":\"TCP\""))
        assertTrue(loaded.payloadJson.contains("\"outboundSecurity\":\"REALITY\""))
        assertTrue(loaded.payloadJson.contains(compiled.configHash))
        assertTrue(loaded.payloadJson.contains("\"profileShape\":\"VLESS + REALITY over TCP\""))
        assertTrue(loaded.payloadJson.contains("\"profileProtocolId\":\"VLESS_REALITY\""))
        assertTrue(loaded.payloadJson.contains("\"profileTransportId\":\"TCP\""))
        assertTrue(loaded.payloadJson.contains("\"profileSecurityId\":\"REALITY\""))
        assertTrue(loaded.payloadJson.contains("\"compilerPath\":\"Canonical profile rebuild\""))
        assertTrue(loaded.payloadJson.contains("\"compilerPathSummary\":\"Rebuilds the canonical profile into the xray+tun2socks compatibility runtime config.\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartCompilerPath\":\"Staged engine payload\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartCompilerPathSummary\":\"Runs the staged sing-box engine payload directly.\""))
        assertTrue(loaded.payloadJson.contains("\"engineSessionHealthStatus\":\"UNKNOWN\""))
        assertTrue(loaded.payloadJson.contains("\"configuredStrategy\":\"SINGBOX_EMBEDDED\""))
        assertTrue(loaded.payloadJson.contains("\"laneStatus\":\"Has limits\""))
        assertTrue(loaded.payloadJson.contains("\"laneSummaryTitle\":\"Staged lane metadata title\""))
        assertTrue(loaded.payloadJson.contains("\"recommendedStrategy\":\"Recommended: Sing-box embedded\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartLaneStatus\":\"Clean match\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartLaneSummaryTitle\":\"Configured next-start metadata title\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartLaneRecommendation\":\"Configured lane metadata recommendation.\""))
        assertTrue(loaded.payloadJson.contains("Restage or reconnect to switch the running session to Sing-box embedded."))
        assertTrue(loaded.payloadJson.contains("\"laneRecommendation\":\"Use the broader embedded lane after restaging.\""))
        assertTrue(loaded.payloadJson.contains("\"lastErrorSection\":\"Routing\""))
        assertTrue(loaded.payloadJson.contains("\"lastErrorFieldPath\":\"routing.rules\""))
        assertTrue(loaded.payloadJson.contains("\"lastAutomationErrorSection\":\"Routing\""))
        assertTrue(loaded.payloadJson.contains("\"lastAutomationErrorFieldPath\":\"routing.rules\""))
        assertTrue(loaded.payloadJson.contains("\"lastAutomationRuntimeMetadata\":{"))
        assertTrue(loaded.payloadJson.contains("\"laneLabel\":\"Xray + tun2socks\""))
        assertTrue(loaded.payloadJson.contains("\"laneStatus\":\"Fallback with limits\""))
        assertTrue(loaded.payloadJson.contains("\"connectDecisionSummary\":\"Start failed on Xray + tun2socks. Recommended: Sing-box embedded.\""))
        assertTrue(loaded.payloadJson.contains("\"compilerPath\":\"Canonical profile rebuild\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartLaneLabel\":\"Sing-box embedded\""))
        assertTrue(loaded.payloadJson.contains("\"nextStartCompilerPath\":\"Staged engine payload\""))
    }

    @Test
    fun `redacted export prefers configured lane metadata when no active lane is running`() {
        val profile = defaultBootstrapProfile()
        val compiled = SingboxEnginePlugin().compile(profile)
        val repository = buildRepository()

        val artifact = repository.exportRedactedDiagnosticBundle(
            profile = profile,
            compiledConfig = compiled,
            tunnelPlanSummary = TunnelPlanSummary(
                processNameSuffix = ":vpn",
                preserveLoopback = true,
                splitTunnelMode = "FullTunnel",
                allowedPackageCount = 0,
                disallowedPackageCount = 0,
                runtimeMode = "FAIL_CLOSED_UNTIL_ENGINE_HOST",
            ),
            runtimeSnapshot = VpnRuntimeSnapshot(
                phase = VpnRuntimePhase.IDLE,
                configuredStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                configuredLaneCompatibility = RuntimeLaneCompatibilityMetadata(
                    statusLabel = "Clean match",
                    selectedSummaryTitle = "Configured next-start metadata title",
                    recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    recommendation = "Configured lane metadata recommendation.",
                ),
            ),
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = "android-keystore:test",
                storagePath = "C:/private/profile.json.enc",
                status = "Loaded encrypted profile.",
                persistedProfileHash = profile.canonicalHash(),
            ),
            automationState = AutomationState(
                storagePath = "C:/private/automation.json.enc",
                keyReference = "android-keystore:test-automation",
                vpnPermissionReady = true,
                runtimeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            routePreview = PreviewInputs(),
            previewOutcome = RoutePreviewOutcome(
                action = RouteAction.PROXY,
                matchedRuleId = null,
                reason = "No routing preview available.",
            ),
        )
        val loaded = repository.loadArtifact(Paths.get(artifact.path))

        assertTrue(loaded.payloadJson.contains("\"configuredStrategy\":\"SINGBOX_EMBEDDED\""))
        assertTrue(loaded.payloadJson.contains("\"laneStatus\":\"Clean match\""))
        assertTrue(loaded.payloadJson.contains("\"laneSummaryTitle\":\"Configured next-start metadata title\""))
        assertTrue(loaded.payloadJson.contains("\"laneRecommendation\":\"Configured lane metadata recommendation.\""))
    }

    private fun buildRepository(): SecureExportRepository = SecureExportRepository(
        Files.createTempDirectory("tunguska-exports"),
        SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        clock = { 1234L },
    )
}
