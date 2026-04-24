package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyAndroidImportCompatibilitySmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun synthetic_share_link_imports_on_legacy_android_runtime() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importShareLinkFromArgsOrDefault()
    }
}