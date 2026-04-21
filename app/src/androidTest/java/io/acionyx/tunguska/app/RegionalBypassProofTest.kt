package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalBypassProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun new_profiles_default_to_russia_direct_and_preview_ru_and_non_ru_destinations() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())
        harness.openRegionalBypassConfig()

        harness.updateRoutePreview(destinationHost = "yandex.ru")
        harness.assertRoutePreviewDecision(action = "DIRECT", routeId = "__regional_bypass_russia__")
        harness.assertRoutePreviewReasonContains("Matched regional bypass preset 'Russia'.")

        harness.updateRoutePreview(destinationHost = "api.ipify.org")
        harness.assertRoutePreviewDecision(action = "PROXY", routeId = "default")
        harness.assertRoutePreviewReasonContains("No explicit rule matched; using routing default.")
        harness.assertRoutePreviewHintContains("geoip:ru")
    }

    @Test
    fun regional_bypass_controls_change_preview_for_russian_and_custom_domains() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())

        harness.setRussiaDirectEnabled(false)
        harness.updateRoutePreview(destinationHost = "yandex.ru")
        harness.assertRoutePreviewDecision(action = "PROXY", routeId = "default")
        harness.assertRoutePreviewReasonContains("No explicit rule matched; using routing default.")

        harness.addRegionalDirectDomain("example.com")
        harness.updateRoutePreview(destinationHost = "sub.example.com")
        harness.assertRoutePreviewDecision(action = "DIRECT", routeId = "__regional_bypass_custom_direct__")
        harness.assertRoutePreviewReasonContains("Matched a custom direct domain in Regional Bypass.")
    }
}