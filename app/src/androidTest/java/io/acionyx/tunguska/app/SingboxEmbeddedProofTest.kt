package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingboxEmbeddedProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun connects_with_singbox_selected_strategy() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.selectRuntimeStrategy(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()
        harness.assertActiveRuntimeStrategy(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
        harness.stopAndWaitForIdle()
    }
}