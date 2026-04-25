package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.RuntimeLaneCompatibilityMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityPresentationTest {
    @Test
    fun `profile compatibility badges capture xray fallback wording`() {
        val badges = profileCompatibilityBadges(
            selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
        )

        assertEquals("xray+tun2socks", badges.selectedLaneLabel)
        assertEquals("Has limits", badges.statusLabel)
        assertEquals("Recommended: sing-box", badges.recommendedLaneLabel)
    }

    @Test
    fun `import compatibility badges capture fallback wording`() {
        val badges = importCompatibilityBadges(
            selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            severity = StrategyCompatibilitySeverity.ATTENTION,
            recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )

        assertEquals("Xray + tun2socks", badges.selectedLaneLabel)
        assertEquals("Fallback with limits", badges.statusLabel)
        assertEquals("Recommended: Sing-box embedded", badges.recommendedLaneLabel)
    }

    @Test
    fun `runtime lane summary distinguishes active and next restage lanes`() {
        val summary = runtimeLaneSummaryLabels(
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
        )

        assertEquals("Active: Xray + tun2socks", summary.activeLaneLabel)
        assertEquals("Next restage: Sing-box embedded", summary.nextRestageLaneLabel)
        assertEquals("Fallback with limits", summary.statusLabel)
        assertEquals("Recommended: Sing-box embedded", summary.recommendedLaneLabel)
        assertTrue(summary.restageHint?.contains("Restage or reconnect") == true)
    }

    @Test
    fun `runtime lane summary omits restage note when configured lane is already active`() {
        val summary = runtimeLaneSummaryLabels(
            activeStrategyId = null,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
        )

        assertEquals("Active: Sing-box embedded", summary.activeLaneLabel)
        assertNull(summary.nextRestageLaneLabel)
        assertEquals("Clean match", summary.statusLabel)
        assertNull(summary.recommendedLaneLabel)
        assertNull(summary.restageHint)
    }

    @Test
    fun `runtime lane presentation prefers snapshot metadata when available`() {
        val presentation = runtimeLanePresentation(
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            stagedMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Staged lane metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Use the broader lane after restaging.",
            ),
        )

        assertEquals("Active: Xray + tun2socks", presentation.summaryLabels.activeLaneLabel)
        assertEquals("Next restage: Sing-box embedded", presentation.summaryLabels.nextRestageLaneLabel)
        assertEquals("Has limits", presentation.summaryLabels.statusLabel)
        assertEquals("Recommended: Sing-box embedded", presentation.summaryLabels.recommendedLaneLabel)
        assertEquals("Staged lane metadata title", presentation.selectedSummaryTitle)
        assertEquals("Use the broader lane after restaging.", presentation.recommendation)
    }

    @Test
    fun `home lane hint prefers next restage label when active lane differs`() {
        val hint = homeRuntimeLaneHint(
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            configuredMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Clean match",
                selectedSummaryTitle = "Configured next-start metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Configured lane metadata recommendation.",
            ),
        )

        assertEquals(
            "Next restage: Sing-box embedded. Configured next-start metadata title. Configured lane metadata recommendation.",
            hint,
        )
    }

    @Test
    fun `home lane hint surfaces fallback recommendation for active compatibility lane`() {
        val hint = homeRuntimeLaneHint(
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            stagedMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Staged lane metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Use the broader lane after restaging.",
            ),
        )

        assertEquals(
            "Staged lane metadata title. Use the broader lane after restaging. Recommended: Sing-box embedded.",
            hint,
        )
    }

    @Test
    fun `runtime lane presentation prefers configured metadata when there is no active lane`() {
        val presentation = runtimeLanePresentation(
            activeStrategyId = null,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
            configuredMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Configured next-start metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                recommendation = "Configured lane metadata recommendation.",
            ),
        )

        assertEquals("Has limits", presentation.summaryLabels.statusLabel)
        assertEquals("Configured next-start metadata title", presentation.selectedSummaryTitle)
        assertEquals("Configured lane metadata recommendation.", presentation.recommendation)
    }

    @Test
    fun `runtime lane presentation exposes next start detail when active and configured lanes differ`() {
        val presentation = runtimeLanePresentation(
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
                profile = sampleProfile(),
                strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            stagedMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Active lane metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Active lane metadata recommendation.",
            ),
            configuredMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Clean match",
                selectedSummaryTitle = "Configured next-start metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Configured lane metadata recommendation.",
            ),
        )

        assertEquals("Has limits", presentation.summaryLabels.statusLabel)
        assertEquals("Active lane metadata title", presentation.selectedSummaryTitle)
        assertEquals("Configured next-start metadata title", presentation.nextStartDetail?.summaryTitle)
        assertEquals("Clean match", presentation.nextStartDetail?.statusLabel)
        assertEquals("Configured lane metadata recommendation.", presentation.nextStartDetail?.recommendation)
    }

    @Test
    fun `configured selection state reuses configured lane compatibility summary`() {
        val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
            profile = sampleProfile(),
            strategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        val selection = configuredSelectionState(
            profile = sampleProfile(),
            selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
        )

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, selection.selectedStrategyId)
        assertEquals("xray+tun2socks", selection.selectedLaneLabel)
        assertEquals(StrategyCompatibilitySeverity.ATTENTION, selection.selectedSeverity)
        assertEquals("Has limits", selection.statusLabel)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, selection.recommendedStrategyId)
        assertEquals("Recommended: sing-box", selection.recommendedLaneLabel)
        assertEquals(guidance.selectedSummary.title, selection.selectedSummaryTitle)
        assertEquals(guidance.selectedSummary.details, selection.selectedSummaryDetails)
        assertEquals(guidance.recommendation, selection.recommendation)
    }

    @Test
    fun `import configured selection state reuses import compatibility payload`() {
        val selection = importConfiguredSelectionState(
            selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            severity = StrategyCompatibilitySeverity.ATTENTION,
            selectedSummaryTitle = "Import preview compatibility title",
            selectedSummaryDetails = listOf("First detail", "Second detail"),
            recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            recommendation = "Import preview recommendation.",
        )

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, selection.selectedStrategyId)
        assertEquals("Xray + tun2socks", selection.selectedLaneLabel)
        assertEquals(StrategyCompatibilitySeverity.ATTENTION, selection.selectedSeverity)
        assertEquals("Fallback with limits", selection.statusLabel)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, selection.recommendedStrategyId)
        assertEquals("Recommended: Sing-box embedded", selection.recommendedLaneLabel)
        assertEquals("Import preview compatibility title", selection.selectedSummaryTitle)
        assertEquals(listOf("First detail", "Second detail"), selection.selectedSummaryDetails)
        assertEquals("Import preview recommendation.", selection.recommendation)
    }

    @Test
    fun `connect decision state captures degraded lane start transition`() {
        val decision = connectDecisionState(
            selection = configuredSelectionState(
                profile = sampleProfile(),
                selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            phase = ConnectDecisionPhase.STARTING,
        )

        assertEquals(ConnectDecisionPhase.STARTING, decision?.phase)
        assertTrue(decision?.message?.startsWith("Starting Xray + tun2socks.") == true)
        assertTrue(decision?.message?.contains("Recommended: sing-box.") == true)
    }

    @Test
    fun `connect decision state omits clean match starts`() {
        val decision = connectDecisionState(
            selection = configuredSelectionState(
                profile = sampleProfile(),
                selectedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
            phase = ConnectDecisionPhase.STARTING,
        )

        assertNull(decision)
    }

    @Test
    fun `connect decision state captures degraded lane start failure`() {
        val decision = connectDecisionState(
            selection = configuredSelectionState(
                profile = sampleProfile(),
                selectedStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            phase = ConnectDecisionPhase.FAILED,
        )

        assertEquals(ConnectDecisionPhase.FAILED, decision?.phase)
        assertTrue(decision?.message?.startsWith("Start failed on Xray + tun2socks.") == true)
        assertTrue(decision?.message?.contains("Recommended: sing-box.") == true)
    }

    @Test
    fun `runtime selection state exposes connect decision hint when next start lane is degraded`() {
        val selection = runtimeSelectionState(
            profile = sampleProfile(),
            activeStrategyId = null,
            configuredStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Configured next-start metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Configured lane metadata recommendation.",
            ),
        )

        assertEquals(
            "Configured next-start metadata title. Configured lane metadata recommendation. Recommended: Sing-box embedded.",
            selection.connectDecisionHint,
        )
        assertNull(selection.homeStatusHint)
    }

    @Test
    fun `runtime selection state preserves home hint for active versus next restage lane`() {
        val selection = runtimeSelectionState(
            profile = sampleProfile(),
            activeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            configuredStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            stagedMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Has limits",
                selectedSummaryTitle = "Active lane metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Active lane metadata recommendation.",
            ),
            configuredMetadata = RuntimeLaneCompatibilityMetadata(
                statusLabel = "Clean match",
                selectedSummaryTitle = "Configured next-start metadata title",
                recommendedStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                recommendation = "Configured lane metadata recommendation.",
            ),
        )

        assertEquals("Active: Xray + tun2socks", selection.activeLaneLabel)
        assertEquals("Next restage: Sing-box embedded", selection.nextRestageLaneLabel)
        assertEquals(StrategyCompatibilitySeverity.ATTENTION, selection.selectedSeverity)
        assertEquals("Recommended: Sing-box embedded", selection.recommendedLaneLabel)
        assertTrue(selection.restageHint?.contains("Restage or reconnect") == true)
        assertEquals("Configured next-start metadata title", selection.nextStartDetail?.summaryTitle)
        assertEquals("Clean match", selection.nextStartDetail?.statusLabel)
        assertEquals(
            "Next restage: Sing-box embedded. Configured next-start metadata title. Configured lane metadata recommendation.",
            selection.homeStatusHint,
        )
        assertNull(selection.connectDecisionHint)
    }

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "compatibility-presentation",
        name = "Compatibility Presentation",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "public-key",
            realityShortId = "abcd1234",
            realitySpiderX = null,
            flow = REQUIRED_VLESS_FLOW,
            utlsFingerprint = "chrome",
        ),
    )
}