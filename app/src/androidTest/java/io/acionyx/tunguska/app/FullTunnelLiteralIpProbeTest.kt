package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullTunnelLiteralIpProbeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun helper_app_reaches_literal_ip_over_full_tunnel() {
        val literalIpUrl = "https://1.1.1.1/cdn-cgi/trace"

        harness.ensureRuntimeIdle()
        harness.launchTrafficProbeAndAssertSuccess("baseline_direct_literal_ip", literalIpUrl)

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())
        harness.connectAndWait()

        harness.launchTrafficProbeAndAssertSuccess("full_tunnel_literal_ip", literalIpUrl)
    }
}
