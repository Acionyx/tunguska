package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromeIpProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun chrome_shows_different_ip_with_and_without_vpn() {
        val directIp = harness.openChromeAndReadIp("direct")
        harness.launchTunguska()
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()

        val tunneledIp = harness.openChromeAndReadIp("vpn")
        assertNotEquals("Chrome kept the same public IP after VPN startup.", directIp, tunneledIp)

        harness.launchTunguska()
        harness.stopAndWaitForIdle()

        val directAfterStop = harness.openChromeAndReadIp("direct_after_stop")
        assertEquals("Public IP did not return to the direct value after stopping VPN.", directIp, directAfterStop)
    }
}
