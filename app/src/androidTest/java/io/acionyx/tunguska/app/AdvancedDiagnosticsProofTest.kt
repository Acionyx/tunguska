package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvancedDiagnosticsProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun advanced_diagnostics_renders_summary_cards_in_sequence() {
        openAdvancedDiagnostics(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)

        composeRule.onNodeWithTag(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD, useUnmergedTree = true)
            .performScrollTo()
        assertTrue(
            "Expected the strategy capability summary card in Advanced diagnostics.",
            hasNodeWithTag(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD),
        )
        composeRule.onNodeWithTag(UiTags.RUNTIME_LANE_SUMMARY_CARD, useUnmergedTree = true)
            .performScrollTo()
        assertTrue(
            "Expected the runtime lane summary card in Advanced diagnostics.",
            hasNodeWithTag(UiTags.RUNTIME_LANE_SUMMARY_CARD),
        )
        composeRule.onNodeWithText("Runtime internals", useUnmergedTree = true)
            .performScrollTo()
        assertTrue(
            "Expected the runtime internals card in Advanced diagnostics.",
            hasText("Runtime internals"),
        )
    }

    @Test
    fun runtime_lane_info_button_scrolls_back_to_capability_summary() {
        openAdvancedDiagnostics(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)

        composeRule.onNodeWithTag(UiTags.RUNTIME_LANE_SUMMARY_CARD, useUnmergedTree = true)
            .performScrollTo()
        assertTrue(
            "Expected the runtime lane summary card before tapping the diagnostics info button.",
            hasNodeWithTag(UiTags.RUNTIME_LANE_SUMMARY_CARD),
        )
        composeRule.onNodeWithTag(UiTags.RUNTIME_LANE_SUMMARY_INFO_BUTTON, useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        assertFalse(
            "Expected the diagnostics info button to scroll instead of opening the generic engine-limits dialog.",
            hasText("Engine limits"),
        )
        assertTrue(
            "Expected the diagnostics info button to bring the strategy capability summary card into view.",
            isNodeVisible(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD),
        )
    }

    @Test
    fun capability_limit_chip_reveals_inline_technical_limit() {
        openAdvancedDiagnostics(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)

        composeRule.onNodeWithTag(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD, useUnmergedTree = true)
            .performScrollTo()
        assertTrue(
            "Expected the strategy capability summary card before expanding a technical limit.",
            hasNodeWithTag(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD),
        )
        composeRule.onAllNodesWithText("Supported with limits", useUnmergedTree = true)
            .onFirst()
            .performScrollTo()
            .performClick()

        assertTrue(
            "Expected a technical limit panel after tapping a capability chip with limits.",
            hasText("Technical limit"),
        )
    }

    private fun openAdvancedDiagnostics(strategy: EmbeddedRuntimeStrategyId) {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.selectRuntimeStrategy(strategy)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UiTags.RUNTIME_LANE_SUMMARY_CARD, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hasNodeWithTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun isNodeVisible(tag: String): Boolean = runCatching {
        val nodeBounds = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot(useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        nodeBounds.bottom > rootBounds.top && nodeBounds.top < rootBounds.bottom
    }.getOrDefault(false)
}