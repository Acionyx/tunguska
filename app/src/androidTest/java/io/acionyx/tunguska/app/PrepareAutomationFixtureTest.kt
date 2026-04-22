package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepareAutomationFixtureTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun prepares_a_real_profile_and_writes_the_automation_token_fixture() {
        val runtimeStrategy = requestedRuntimeStrategy()
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.selectRuntimeStrategy(runtimeStrategy)
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()
        harness.assertActiveRuntimeStrategy(runtimeStrategy)
        harness.stopAndWaitForIdle()

        val token = harness.enableAutomationIntegrationViaUi()
        harness.writeAutomationTokenFixture(token)
    }

    private fun requestedRuntimeStrategy(): EmbeddedRuntimeStrategyId = InstrumentationRegistry.getArguments()
        .getString(RUNTIME_STRATEGY_ARGUMENT)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { value -> runCatching { EmbeddedRuntimeStrategyId.valueOf(value) }.getOrNull() }
        ?: EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS

    private companion object {
        private const val RUNTIME_STRATEGY_ARGUMENT = "runtime_strategy"
    }
}
