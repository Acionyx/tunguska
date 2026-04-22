package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingboxChromeIpProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun chrome_shows_different_ip_with_singbox_selected_strategy() {
        harness.ensureRuntimeIdle()
        val directIp = harness.openChromeAndReadIp("direct")

        harness.launchTunguska()
        harness.selectRuntimeStrategy(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()
        harness.assertActiveRuntimeStrategy(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)

        val tunneledIp = harness.openChromeAndReadIp("vpn")
        assertNotEquals("Chrome kept the same public IP after sing-box VPN startup.", directIp, tunneledIp)

        harness.launchTunguska()
        harness.stopAndWaitForIdle()

        val directAfterStop = harness.openChromeAndReadIp("direct_after_stop")
        assertEquals("Public IP did not return to the direct value after stopping sing-box VPN.", directIp, directAfterStop)
    }
}