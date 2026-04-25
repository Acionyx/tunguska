package io.acionyx.tunguska.trafficprobe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnubisJointUiProofTest {
    private val harness = AnubisJointUiHarness()

    @Test
    fun anubis_ui_can_freeze_unfreeze_tunguska_and_route_real_traffic() {
        try {
            harness.prepareNeutralState()
            val directIp = harness.launchTrafficProbeAndReadIp("anubis_direct_baseline")
            val token = harness.readAutomationTokenFixture()

            harness.launchAnubis()
            harness.ensureAnubisShizukuPermissionGranted()
            harness.configureAnubisTunguskaClient(token)
            harness.addAppToAnubisGroup(
                groupLabel = "Запуск с VPN",
                searchQuery = "io.acionyx.tunguska.trafficprobe",
                resultText = "Traffic Probe",
            )

            harness.toggleAnubisProtection(enabled = true)
            harness.waitForVpnTransportActive()
            harness.waitForTunguskaVpnServiceActive()
            harness.assertSelectedRuntimeStrategy()

            harness.toggleAnubisProtection(enabled = false)
            harness.waitForVpnTransportInactive()
            harness.waitForTunguskaVpnServiceInactive()
            harness.waitForPackageEnabledState(packageName = "io.acionyx.tunguska", enabled = false)

            harness.launchAnubisManagedApp(
                "Traffic Probe",
                packageName = "io.acionyx.tunguska.trafficprobe",
            )
            harness.waitForVpnTransportActive()
            harness.waitForTunguskaVpnServiceActive()
            harness.assertSelectedRuntimeStrategy()

            val tunneledIp = harness.launchTrafficProbeAndReadIp("anubis_launch_vpn")
            assertNotEquals("Anubis launch-with-VPN kept the same public IP.", directIp, tunneledIp)

            harness.toggleAnubisProtection(enabled = false)
            harness.waitForVpnTransportInactive()
            harness.waitForTunguskaVpnServiceInactive()
            harness.waitForPackageEnabledState(packageName = "io.acionyx.tunguska", enabled = false)

            val directAfterStop = harness.waitForTrafficProbeIp(
                label = "anubis_direct_after_stop",
                expectedIp = directIp,
                timeoutMillis = 45_000,
            )
            assertEquals("Anubis stop did not restore the direct public IP.", directIp, directAfterStop)
        } finally {
            harness.restoreInteractiveState()
        }
    }
}
