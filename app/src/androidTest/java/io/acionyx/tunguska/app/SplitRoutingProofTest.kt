package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplitRoutingProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun helper_app_tunnels_in_full_tunnel_mode() {
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_full_tunnel")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())
        harness.connectAndWait()

        val tunneledIp = harness.launchTrafficProbeAndReadIp("full_tunnel")
        assertNotEquals("Helper app stayed direct in full tunnel mode.", directIp, tunneledIp)
    }

    @Test
    fun helper_app_stays_direct_when_denylisted() {
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_denylist")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.DENYLIST_EXCLUDED_PROBE).canonicalJson())
        harness.connectAndWait()

        val denylistIp = harness.launchTrafficProbeAndReadIp("denylist_excluded")
        assertEquals("Helper app tunneled even though it was denylisted.", directIp, denylistIp)
    }

    @Test
    fun helper_app_tunnels_when_allowlisted() {
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_allowlist")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.ALLOWLIST_INCLUDED_PROBE).canonicalJson())
        harness.connectAndWait()

        val allowlistIp = harness.launchTrafficProbeAndReadIp("allowlist_included")
        assertNotEquals("Helper app stayed direct even though it was allowlisted.", directIp, allowlistIp)
    }
}
