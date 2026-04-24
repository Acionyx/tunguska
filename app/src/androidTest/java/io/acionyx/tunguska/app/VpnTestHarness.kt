package io.acionyx.tunguska.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileImportParser
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.netpolicy.RoutePreviewEngine
import io.acionyx.tunguska.netpolicy.RoutePreviewRequest
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.TunnelSessionPlanner
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

internal class VpnTestHarness(
    private val composeRule: ComposeTestRule,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val appContext = instrumentation.targetContext.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val exportRepository = SecureExportRepository(appContext)
    private val profileRepository = SecureProfileRepository(appContext)
    private val automationRepository = AutomationIntegrationRepository(appContext)
    private val automationOrchestrator = RuntimeAutomationOrchestrator(appContext)
    private val plugin = SingboxEnginePlugin()
    private val routePreviewEngine = RoutePreviewEngine()
    private val diagnosticsDirectory = File(
        appContext.filesDir,
        DIAGNOSTICS_DIRECTORY_NAME,
    ).apply { mkdirs() }
    private val diagnosticsMode = DiagnosticsMode.from(
        InstrumentationRegistry.getArguments().getString(DIAGNOSTICS_MODE_ARGUMENT),
    )

    fun importShareLinkFromArgsOrDefault() {
        importPayload(shareLinkFromArgs())
    }

    fun importPayload(payload: String) {
        launchTunguska()
        openSection(UiTags.TAB_PROFILES)
        if (composeRule.onAllNodesWithTag(UiTags.IMPORT_DRAFT_FIELD, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            scrollToTag(UiTags.OPEN_PROFILE_IMPORT_BUTTON)
            composeRule.onNodeWithTag(UiTags.OPEN_PROFILE_IMPORT_BUTTON, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(UiTags.IMPORT_DRAFT_FIELD, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.IMPORT_DRAFT_FIELD, useUnmergedTree = true)
            .performTextReplacement(payload)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(UiTags.VALIDATE_IMPORT_BUTTON))
        composeRule.onNodeWithTag(UiTags.VALIDATE_IMPORT_BUTTON, useUnmergedTree = true)
            .performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(UiTags.CONFIRM_IMPORT_BUTTON, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            captureDiagnostics("post_validate")
            throw error
        }
        captureStep("post_validate")
        composeRule.onNodeWithTag(UiTags.CONFIRM_IMPORT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithTag(UiTags.BACK_BUTTON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag(UiTags.BACK_BUTTON, useUnmergedTree = true)
                .performClick()
            composeRule.waitForIdle()
        }
        waitForAppliedProfile(payload)
        captureStep("post_confirm_import")
    }

    fun connectAndWait(expectedPhase: String = expectedPhaseFromArgs()) {
        launchTunguska()
        openSection(UiTags.TAB_HOME)
        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(UiTags.CONNECT_BUTTON))
        captureStep("pre_connect")
        tapConnectButton()
        val permissionPromptAccepted = confirmVpnPermissionIfPresent()
        captureStep("post_permission_dialog")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            VpnService.prepare(appContext) == null
        }
        if (
            permissionPromptAccepted &&
            composeRule.onAllNodesWithTag(UiTags.CONNECT_BUTTON, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            tapConnectButton()
        }
        waitForRuntimePhase(expectedPhase, requireVisibleHomeUi = false)
        captureStep("post_connect")
    }

    fun waitForRuntimePhaseVisible(expectedPhase: String) {
        launchTunguska()
        openSection(UiTags.TAB_HOME)
        waitForRuntimePhase(expectedPhase, requireVisibleHomeUi = true)
    }

    fun stopAndWaitForIdle() {
        launchTunguska()
        openSection(UiTags.TAB_HOME)
        if (!composeRule.onAllNodesWithTag(UiTags.STOP_BUTTON, useUnmergedTree = true).fetchSemanticsNodes().any()) {
            return
        }
        composeRule.onNodeWithTag(UiTags.STOP_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        waitForRuntimePhase(
            expectedPhase = VpnRuntimePhase.IDLE.name,
            requireVisibleHomeUi = false,
        )
        captureStep("post_stop")
    }

    fun enableAutomationIntegrationViaUi(): String {
        launchTunguska()
        openSection(UiTags.TAB_SECURITY)
        if (composeRule.onAllNodesWithTag(UiTags.AUTOMATION_ENABLE_SWITCH, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            scrollToTag(UiTags.OPEN_AUTOMATION_BUTTON)
            composeRule.onNodeWithTag(UiTags.OPEN_AUTOMATION_BUTTON, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(UiTags.AUTOMATION_ENABLE_SWITCH, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (!automationRepository.load().enabled) {
            composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
                .performScrollToNode(hasTestTag(UiTags.AUTOMATION_ENABLE_SWITCH))
            composeRule.onNodeWithTag(UiTags.AUTOMATION_ENABLE_SWITCH, useUnmergedTree = true)
                .performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                automationRepository.load().enabled
            }
        }
        captureStep("automation_enabled")
        return checkNotNull(automationRepository.load().token) {
            "Automation token was not generated after enabling the integration."
        }
    }

    fun selectRuntimeStrategy(strategy: EmbeddedRuntimeStrategyId) {
        launchTunguska()
        openSection(UiTags.TAB_SECURITY)
        val strategyTag = when (strategy) {
            EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> UiTags.RUNTIME_STRATEGY_XRAY_BUTTON
            EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> UiTags.RUNTIME_STRATEGY_SINGBOX_BUTTON
        }
        if (composeRule.onAllNodesWithTag(strategyTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag(UiTags.SHOW_DIAGNOSTICS_BUTTON, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(strategyTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag(strategyTag, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            automationRepository.load().runtimeStrategy == strategy
        }
        captureStep("runtime_strategy_${strategy.name.lowercase()}")
    }

    fun assertActiveRuntimeStrategy(expected: EmbeddedRuntimeStrategyId) {
        val snapshot = requestRuntimeSnapshot()
        assertEquals(
            "Unexpected active runtime strategy.",
            expected,
            snapshot.activeStrategy,
        )
    }

    fun waitForHomeExitIp(expectedIp: String) {
        launchTunguska()
        openSection(UiTags.TAB_HOME)
        waitForComposeText("Exit IP $expectedIp", timeoutMillis = 45_000)
        captureStep("home_exit_ip_observed")
    }

    fun writeAutomationTokenFixture(token: String) {
        device.executeShellCommand("settings put global $AUTOMATION_TOKEN_SETTING $token")
        val storedToken = device.executeShellCommand("settings get global $AUTOMATION_TOKEN_SETTING").trim()
        assertTrue(
            "Automation token fixture was not written to settings global $AUTOMATION_TOKEN_SETTING.",
            storedToken == token,
        )
        captureStep("automation_token_written")
    }

    fun openRegionalBypassConfig() {
        launchTunguska()
        openSection(UiTags.TAB_ROUTING)
        scrollToTag(UiTags.CONFIGURE_REGIONAL_BYPASS_BUTTON)
        if (composeRule.onAllNodesWithTag(UiTags.RUSSIA_DIRECT_SWITCH, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag(UiTags.CONFIGURE_REGIONAL_BYPASS_BUTTON, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(UiTags.RUSSIA_DIRECT_SWITCH, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        captureStep("regional_bypass_config_open")
    }

    fun setRussiaDirectEnabled(enabled: Boolean) {
        openRegionalBypassConfig()
        scrollToTag(UiTags.RUSSIA_DIRECT_SWITCH)
        if (isToggleEnabled(UiTags.RUSSIA_DIRECT_SWITCH) != enabled) {
            composeRule.onNodeWithTag(UiTags.RUSSIA_DIRECT_SWITCH, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                isToggleEnabled(UiTags.RUSSIA_DIRECT_SWITCH) == enabled
            }
        }
        captureStep("regional_bypass_russia_${if (enabled) "enabled" else "disabled"}")
    }

    fun addRegionalDirectDomain(domain: String) {
        openRegionalBypassConfig()
        replaceTextField(UiTags.CUSTOM_DIRECT_DOMAIN_FIELD, domain)
        composeRule.onNodeWithTag(UiTags.ADD_DIRECT_DOMAIN_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        waitForComposeText(domain.lowercase(), timeoutMillis = 5_000)
        captureStep("regional_bypass_custom_domain_added")
    }

    fun updateRoutePreview(
        destinationHost: String,
        destinationIp: String = "",
        destinationPort: String = "443",
        packageName: String = "",
        protocol: NetworkProtocol = NetworkProtocol.TCP,
    ) {
        openRoutePreview()
        replaceTextField(UiTags.ROUTE_PREVIEW_PACKAGE_FIELD, packageName)
        replaceTextField(UiTags.ROUTE_PREVIEW_HOST_FIELD, destinationHost)
        replaceTextField(UiTags.ROUTE_PREVIEW_IP_FIELD, destinationIp)
        replaceTextField(UiTags.ROUTE_PREVIEW_PORT_FIELD, destinationPort)
        val protocolTag = when (protocol) {
            NetworkProtocol.TCP -> UiTags.ROUTE_PREVIEW_PROTOCOL_TCP_BUTTON
            NetworkProtocol.UDP -> UiTags.ROUTE_PREVIEW_PROTOCOL_UDP_BUTTON
        }
        composeRule.onNodeWithTag(protocolTag, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(UiTags.ROUTE_PREVIEW_TEST_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        closeDetailScreen()
        captureStep("regional_bypass_preview_updated")
    }

    fun assertDirectDomainVisible(label: String, source: String) {
        openRegionalBypassConfig()
        scrollToTag(UiTags.DIRECT_DOMAIN_LIST)
        waitForComposeText(label, timeoutMillis = 5_000)
        waitForComposeText(source, timeoutMillis = 5_000)
    }

    fun assertDirectDomainAbsent(label: String) {
        openRegionalBypassConfig()
        scrollToTag(UiTags.DIRECT_DOMAIN_LIST)
        val nodes = composeRule.onAllNodesWithText(label, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("Expected '$label' to be absent from the direct-domain list.", nodes.isEmpty())
    }

    fun assertRoutePreviewDecision(action: String, routeId: String) {
        val outcome = when (action.uppercase()) {
            "PROXY" -> "Would use VPN"
            "DIRECT" -> "Would go direct"
            "BLOCK" -> "Would be blocked"
            else -> error("Unsupported route action in test: $action")
        }
        val routeLabel = when (routeId) {
            "default" -> "Default VPN route"
            "__regional_bypass_russia__" -> "Russia direct preset"
            "__regional_bypass_custom_direct__" -> "Custom direct domain"
            else -> "Rule $routeId"
        }
        waitForComposeText(outcome, timeoutMillis = 5_000)
        waitForComposeText(routeLabel, timeoutMillis = 5_000)
    }

    fun assertRoutePreviewReasonContains(text: String) {
        waitForComposeText(text, timeoutMillis = 5_000)
    }

    fun assertRoutePreviewHintContains(text: String) {
        waitForComposeText(text, timeoutMillis = 5_000)
    }

    fun exportBackupThroughUiAndDismissShareSheet() {
        openSection(UiTags.TAB_SECURITY)
        scrollToTag(UiTags.EXPORT_BACKUP_BUTTON)
        composeRule.onNodeWithTag(UiTags.EXPORT_BACKUP_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        waitForSystemShareSheet()
        device.pressBack()
        launchTunguska()
        openSection(UiTags.TAB_SECURITY)
        waitForComposeText("Backup saved", timeoutMillis = 5_000)
        captureStep("backup_export_ui")
    }

    fun exportAuditThroughUiAndDismissShareSheet() {
        openSection(UiTags.TAB_SECURITY)
        scrollToTag(UiTags.EXPORT_AUDIT_BUTTON)
        composeRule.onNodeWithTag(UiTags.EXPORT_AUDIT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        waitForSystemShareSheet()
        device.pressBack()
        launchTunguska()
        openSection(UiTags.TAB_SECURITY)
        waitForComposeText("Audit saved", timeoutMillis = 5_000)
        captureStep("audit_export_ui")
    }

    fun assertBackupAndAuditStatesIndependent() {
        openSection(UiTags.TAB_SECURITY)
        waitForComposeText("Backup saved", timeoutMillis = 5_000)
        waitForComposeText("Audit saved", timeoutMillis = 5_000)
    }

    fun invokeAutomationStart(token: String, callerHint: String = "androidTest"): String {
        val output = device.executeShellCommand(
            "am start -W -n $AUTOMATION_COMPONENT " +
                "-a ${AutomationRelayContract.ACTION_START} " +
                "--es ${AutomationRelayContract.EXTRA_AUTOMATION_TOKEN} $token " +
                "--es ${AutomationRelayContract.EXTRA_CALLER_HINT} $callerHint",
        )
        captureStep("automation_start_invoked")
        return output
    }

    fun invokeAutomationStop(token: String, callerHint: String = "androidTest"): String {
        val output = device.executeShellCommand(
            "am start -W -n $AUTOMATION_COMPONENT " +
                "-a ${AutomationRelayContract.ACTION_STOP} " +
                "--es ${AutomationRelayContract.EXTRA_AUTOMATION_TOKEN} $token " +
                "--es ${AutomationRelayContract.EXTRA_CALLER_HINT} $callerHint",
        )
        captureStep("automation_stop_invoked")
        return output
    }

    fun waitForAutomationStatus(expectedStatus: AutomationCommandStatus) {
        waitForAutomationStatus(expectedStatus, previousTimestamp = null)
    }

    fun automationStatusTimestamp(): Long? = automationRepository.load().lastAutomationAtEpochMs

    fun waitForAutomationStatus(expectedStatus: AutomationCommandStatus, previousTimestamp: Long?) {
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val settings = automationRepository.load()
                settings.lastAutomationStatus == expectedStatus &&
                    (previousTimestamp == null || settings.lastAutomationAtEpochMs != previousTimestamp)
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("automation_status_timeout")
            fail("Expected automation status '${expectedStatus.name}' was not observed within timeout.")
        }
    }

    fun openChromeAndReadIp(label: String): String {
        resetChromeState()
        val failures = mutableListOf<String>()
        for (candidateUrl in PROBE_URL_CANDIDATES) {
            val urlLabel = sanitizeLabel(URI(candidateUrl).host ?: "probe")
            launchChrome(candidateUrl)
            dismissChromeFirstRunPrompts()
            captureStep("chrome_${label}_${urlLabel}_launched")
            when (val result = waitForProbeResult(packageName = CHROME_PACKAGE, label = "chrome_${label}_$urlLabel")) {
                is ProbeResult.Success -> return result.ip
                is ProbeResult.Failure -> failures += "${candidateUrl}: ${result.summary}"
            }
        }
        captureDiagnostics("chrome_${label}_final_failure")
        fail("Unable to read a public IP in Chrome. ${failures.joinToString(" | ")}")
        error("unreachable")
    }

    fun launchTrafficProbeAndReadIp(label: String): String {
        val failures = mutableListOf<String>()
        for (candidateUrl in PROBE_URL_CANDIDATES) {
            val urlLabel = sanitizeLabel(URI(candidateUrl).host ?: "probe")
            device.executeShellCommand(
                "am start -W -S -n $TRAFFIC_PROBE_COMPONENT " +
                    "--ez io.acionyx.tunguska.trafficprobe.extra.AUTO_PROBE true " +
                    "--es io.acionyx.tunguska.trafficprobe.extra.PROBE_URL $candidateUrl",
            )
            assertTrue(
                "Traffic probe did not reach the foreground.",
                device.wait(Until.hasObject(By.pkg(TRAFFIC_PROBE_PACKAGE)), 10_000),
            )
            captureStep("trafficprobe_${label}_${urlLabel}_launched")
            when (val result = waitForProbeResult(
                packageName = TRAFFIC_PROBE_PACKAGE,
                label = "trafficprobe_${label}_$urlLabel",
            )) {
                is ProbeResult.Success -> return result.ip
                is ProbeResult.Failure -> failures += "${candidateUrl}: ${result.summary}"
            }
        }
        captureDiagnostics("trafficprobe_${label}_final_failure")
        fail("Unable to read a public IP in trafficprobe. ${failures.joinToString(" | ")}")
        error("unreachable")
    }

    fun launchTunguska() {
        val alreadyAttached = runCatching {
            composeRule.onAllNodesWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)
        val foregroundVisible = device.hasObject(By.pkg(TUNGUSKA_PACKAGE))
        if (alreadyAttached && foregroundVisible) {
            composeRule.waitForIdle()
            return
        }
        launchPackage(
            packageName = TUNGUSKA_PACKAGE,
            errorMessage = "Tunguska did not reach the foreground.",
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        captureStep("tunguska_launched")
    }

    fun ensureRuntimeIdle() {
        device.executeShellCommand("am force-stop $TRAFFIC_PROBE_PACKAGE")
        device.executeShellCommand("am force-stop $CHROME_PACKAGE")
        runCatching {
            val status = automationOrchestrator.refreshStatus()
            val snapshot = status.snapshot
            if (snapshot != null && snapshot.phase != VpnRuntimePhase.IDLE) {
                val stop = automationOrchestrator.stopRuntime()
                check(stop.status == AutomationCommandStatus.SUCCESS) {
                    "Failed to stop the runtime before test start: ${stop.summary} (${stop.error ?: "no error"})"
                }
            }
        }.getOrElse { error ->
            captureDiagnostics("ensure_runtime_idle_failure")
            fail("Failed to reset the Tunguska runtime before test start: ${error.message}")
        }
        waitForVpnTransportState(active = false, timeoutMillis = 15_000, failOnTimeout = true)
    }

    fun captureDiagnostics(label: String) {
        captureVisualDiagnostics(label)
        writeCommandOutput(
            fileName = "$label-logcat.txt",
            command = "logcat -d -v threadtime -s " +
                "TunguskaVpnService:I XrayTun2Socks:I TunguskaVpnNative:I RuntimeAutomation:I Tunguska:I chromium:I *:S",
        )
        writeCommandOutput(
            fileName = "$label-connectivity.txt",
            command = "dumpsys connectivity",
        )
        writeCommandOutput(
            fileName = "$label-vpn.txt",
            command = "dumpsys vpn",
        )
        writeCommandOutput(
            fileName = "$label-services.txt",
            command = "dumpsys activity services io.acionyx.tunguska",
        )
        exportRedactedDiagnosticBundle(label)
    }

    fun buildProfile(mode: TestSplitMode): ProfileIr {
        val base = ProfileImportParser.parse(shareLinkFromArgs()).profile
        return when (mode) {
            TestSplitMode.FULL_TUNNEL -> base.copy(
                id = "test-full-tunnel",
                name = "Test Full Tunnel",
            )

            TestSplitMode.DENYLIST_EXCLUDED_PROBE -> base.copy(
                id = "test-denylist-probe",
                name = "Test Denylist Probe",
                vpn = io.acionyx.tunguska.domain.VpnPolicy(
                    splitTunnel = io.acionyx.tunguska.domain.SplitTunnelMode.Denylist(
                        packageNames = listOf(TRAFFIC_PROBE_PACKAGE),
                    ),
                ),
            )

            TestSplitMode.ALLOWLIST_INCLUDED_PROBE -> base.copy(
                id = "test-allowlist-probe",
                name = "Test Allowlist Probe",
                vpn = io.acionyx.tunguska.domain.VpnPolicy(
                    splitTunnel = io.acionyx.tunguska.domain.SplitTunnelMode.Allowlist(
                        packageNames = listOf(TRAFFIC_PROBE_PACKAGE),
                    ),
                ),
            )
        }
    }

    private fun shareLinkFromArgs(): String {
        val arguments = InstrumentationRegistry.getArguments()
        return arguments.getString("profile_share_link_hex")
            ?.let(::decodeHexUtf8)
            ?: arguments.getString("profile_share_link")
            ?: shareLinkFromDeviceSettings()
            ?: DEFAULT_SHARE_LINK
    }

    private fun expectedPhaseFromArgs(): String = InstrumentationRegistry.getArguments()
        .getString("expected_phase")
        ?: VpnRuntimePhase.RUNNING.name

    private fun confirmVpnPermissionIfPresent(timeoutMillis: Long = 10_000): Boolean {
        val button = device.wait(Until.findObject(By.res("android:id/button1")), timeoutMillis)
            ?: device.wait(Until.findObject(By.textContains("Allow")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("OK")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("Continue")), 2_000)
        return if (button != null) {
            button.click()
            true
        } else {
            false
        }
    }

    private fun dismissChromeFirstRunPrompts() {
        val promptTexts = listOf(
            "Accept & continue",
            "Accept",
            "Continue",
            "Use without an account",
            "No thanks",
            "Skip",
            "Not now",
        )
        repeat(6) {
            var tapped = false
            for (text in promptTexts) {
                val candidate = device.wait(Until.findObject(By.textContains(text)), 800)
                if (candidate != null) {
                    candidate.click()
                    tapped = true
                    Thread.sleep(800)
                }
            }
            if (!tapped) return
        }
    }

    private fun resetChromeState() {
        device.executeShellCommand("am force-stop $CHROME_PACKAGE")
        device.executeShellCommand("pm clear $CHROME_PACKAGE")
        Thread.sleep(750)
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun closeDetailScreen() {
        if (hasNodeWithTag(UiTags.BACK_BUTTON)) {
            clickNodeWithTag(UiTags.BACK_BUTTON)
        }
        composeRule.waitForIdle()
    }

    private fun waitForSystemShareSheet() {
        val resolverVisible = device.wait(Until.hasObject(By.pkg(INTENT_RESOLVER_PACKAGE)), 5_000)
        val currentFocus = device.executeShellCommand("dumpsys window")
            .lineSequence()
            .filter { it.contains("mCurrentFocus") }
            .lastOrNull()
            .orEmpty()
        val chooserFocused = currentFocus.contains("ResolverActivity") ||
            currentFocus.contains("ChooserActivity") ||
            currentFocus.contains(INTENT_RESOLVER_PACKAGE) ||
            currentFocus.contains("com.google.android.intentresolver")
        if (!resolverVisible && !chooserFocused) {
            captureDiagnostics("share_sheet_timeout")
            fail("Expected Android share sheet after export, but it did not open.")
        }
    }

    private fun openRoutePreview() {
        launchTunguska()
        openSection(UiTags.TAB_ROUTING)
        scrollToTag(UiTags.OPEN_ROUTE_PREVIEW_BUTTON)
        if (composeRule.onAllNodesWithTag(UiTags.ROUTE_PREVIEW_HOST_FIELD, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag(UiTags.OPEN_ROUTE_PREVIEW_BUTTON, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(UiTags.ROUTE_PREVIEW_HOST_FIELD, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        captureStep("route_preview_open")
    }

    private fun openSection(tag: String) {
        if (!hasNodeWithTag(tag) && hasNodeWithTag(UiTags.BACK_BUTTON)) {
            clickNodeWithTag(UiTags.BACK_BUTTON)
            composeRule.waitForIdle()
        }
        if (!hasNodeWithTag(tag)) {
            runCatching {
                scrollToTag(UiTags.BACK_BUTTON)
                clickNodeWithTag(UiTags.BACK_BUTTON)
                composeRule.waitForIdle()
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            hasNodeWithTag(tag)
        }
        clickNodeWithTag(tag)
        composeRule.waitForIdle()
    }

    private fun hasNodeWithTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()

    private fun clickNodeWithTag(tag: String) {
        val useUnmergedTree = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        composeRule.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
            .performClick()
    }

    private fun replaceTextField(tag: String, value: String) {
        scrollToTag(tag)
        composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement(value)
        composeRule.waitForIdle()
    }

    private fun isToggleEnabled(tag: String): Boolean =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState] == ToggleableState.On

    private fun tapConnectButton() {
        composeRule.onNodeWithTag(UiTags.CONNECT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    private fun waitForRuntimePhase(
        expectedPhase: String,
        requireVisibleHomeUi: Boolean,
    ) {
        val expected = VpnRuntimePhase.valueOf(expectedPhase)
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                val snapshotMatches = runCatching { requestRuntimeSnapshot().phase == expected }
                    .getOrDefault(false)
                val transportMatches = when (expected) {
                    VpnRuntimePhase.RUNNING -> isVpnTransportActive()
                    VpnRuntimePhase.IDLE -> !isVpnTransportActive()
                    else -> true
                }
                val uiMatches = when (expected) {
                    VpnRuntimePhase.IDLE ->
                        hasComposeText("Ready to connect") ||
                            composeRule.onAllNodesWithTag(UiTags.CONNECT_BUTTON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

                    VpnRuntimePhase.STAGED -> hasComposeText("Profile staged")
                    VpnRuntimePhase.START_REQUESTED -> hasComposeText("Connecting")
                    VpnRuntimePhase.RUNNING ->
                        hasComposeText("Protected") ||
                            composeRule.onAllNodesWithTag(UiTags.STOP_BUTTON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

                    VpnRuntimePhase.FAIL_CLOSED ->
                        hasComposeText("Attention required") || hasComposeText("Needs attention")
                }
                when {
                    !snapshotMatches -> false
                    requireVisibleHomeUi -> uiMatches
                    else -> transportMatches
                }
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("runtime_phase_timeout")
            fail("Expected runtime phase '$expectedPhase' was not observed within timeout.")
        }
    }

    private fun waitForVpnTransportState(
        active: Boolean,
        timeoutMillis: Long,
        failOnTimeout: Boolean,
    ): Boolean {
        val found = runCatching {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                if (isVpnTransportActive() == active) {
                    return@runCatching true
                }
                Thread.sleep(500)
            }
            false
        }.getOrDefault(false)
        if (!found && failOnTimeout) {
            captureDiagnostics("vpn_transport_state_timeout")
            fail("VPN transport active=$active was not observed within timeout.")
        }
        return found
    }

    private fun waitForComposeText(text: String, timeoutMillis: Long) {
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                hasComposeText(text)
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("compose_text_timeout")
            fail("Expected text '$text' was not observed within timeout.")
        }
    }

    private fun hasComposeText(text: String): Boolean = composeRule
        .onAllNodesWithText(text = text, substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun waitForAppliedProfile(payload: String) {
        val marker = deriveProfileMarker(payload) ?: return
        val found = device.wait(Until.hasObject(By.textContains(marker)), 10_000)
        if (!found) {
            captureDiagnostics("profile_apply_timeout")
            fail("Imported profile marker '$marker' was not observed after confirmation.")
        }
    }

    private fun deriveProfileMarker(payload: String): String? = runCatching {
        if (payload.trim().startsWith("{")) {
            ProfileImportParser.parse(payload).profile.name
        } else {
            val uri = URI(payload.trim())
            URLDecoder.decode(uri.rawFragment.orEmpty(), "UTF-8")
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun decodeHexUtf8(value: String): String {
        require(value.length % 2 == 0) { "Hex-encoded share link must have an even length." }
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val offset = index * 2
            bytes[index] = value.substring(offset, offset + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun shareLinkFromDeviceSettings(): String? {
        val hexValue = readGlobalSetting(PROFILE_SHARE_LINK_HEX_SETTING)
        if (!hexValue.isNullOrBlank()) {
            return decodeHexUtf8(hexValue)
        }
        return readGlobalSetting(PROFILE_SHARE_LINK_SETTING)
    }

    private fun readGlobalSetting(name: String): String? = runCatching {
        device.executeShellCommand("settings get global $name")
            .trim()
            .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
    }.getOrNull()

    private fun launchPackage(packageName: String, errorMessage: String) {
        val launchIntent = checkNotNull(
            appContext.packageManager.getLaunchIntentForPackage(packageName),
        ) {
            "Unable to resolve the launcher activity for $packageName."
        }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        val deadline = System.currentTimeMillis() + 60_000
        val launched = runCatching {
            while (System.currentTimeMillis() < deadline) {
                if (device.hasObject(By.pkg(packageName)) || foregroundWindowContains(packageName)) {
                    return@runCatching true
                }
                Thread.sleep(500)
            }
            false
        }.getOrDefault(false)
        assertTrue(errorMessage, launched)
    }

    private fun launchChrome(url: String) {
        device.executeShellCommand(
            "am start -W -n $CHROME_COMPONENT -a android.intent.action.VIEW -d $url",
        )
        val visible = device.wait(Until.hasObject(By.pkg(CHROME_PACKAGE)), 20_000) ||
            foregroundWindowContains(CHROME_PACKAGE)
        assertTrue("Chrome did not reach the foreground.", visible)
    }

    private fun foregroundWindowContains(packageName: String): Boolean = runCatching {
        device.executeShellCommand("dumpsys window")
            .contains(packageName, ignoreCase = true)
    }.getOrDefault(false)

    private fun waitForProbeResult(packageName: String, label: String): ProbeResult {
        val timeoutMillis = if (packageName == TRAFFIC_PROBE_PACKAGE) 12_000L else 15_000L
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val objectMatch = device.findObject(By.text(IP_ADDRESS_PATTERN))
            if (objectMatch != null) {
                return ProbeResult.Success(objectMatch.text)
            }
            val multilineMatch = device.findObject(By.textContains("IP:"))
            if (multilineMatch != null) {
                IP_REGEX.find(multilineMatch.text)?.let { return ProbeResult.Success(it.value) }
            }

            val dump = dumpWindowHierarchy()
            IP_REGEX.findAll(dump).lastOrNull()?.let { return ProbeResult.Success(it.value) }
            detectProbeFailure(dump)?.let {
                captureDiagnostics("${label}_probe_error")
                return ProbeResult.Failure(it)
            }

            if (packageName == CHROME_PACKAGE) {
                dismissChromeFirstRunPrompts()
            }
            Thread.sleep(1_000)
        }
        captureDiagnostics("${label}_ip_timeout")
        return ProbeResult.Failure("Timed out waiting for a visible public IP in $packageName.")
    }

    private fun exportRedactedDiagnosticBundle(label: String) {
        val stored = runCatching { profileRepository.reload() }
            .getOrElse { profileRepository.loadOrSeed(defaultBootstrapProfile()).storedProfile }
        val runtimeSnapshot = requestRuntimeSnapshot()
        val preview = PreviewInputs()
        val previewOutcome = routePreviewEngine.evaluate(
            profile = stored.profile,
            request = RoutePreviewRequest(
                packageName = preview.packageName.takeIf { it.isNotBlank() },
                destinationHost = preview.destinationHost.takeIf { it.isNotBlank() },
                destinationPort = preview.destinationPort.toIntOrNull(),
                protocol = NetworkProtocol.TCP,
            ),
        )
        val compiled = plugin.compile(stored.profile)
        val tunnelPlan = TunnelSessionPlanner.plan(compiled)
        val artifact = exportRepository.exportRedactedDiagnosticBundle(
            profile = stored.profile,
            compiledConfig = compiled,
            tunnelPlanSummary = TunnelPlanSummary(
                processNameSuffix = tunnelPlan.processNameSuffix,
                preserveLoopback = tunnelPlan.preserveLoopback,
                splitTunnelMode = tunnelPlan.splitTunnelMode::class.simpleName.orEmpty(),
                allowedPackageCount = tunnelPlan.allowedPackages.size,
                disallowedPackageCount = tunnelPlan.disallowedPackages.size,
                runtimeMode = tunnelPlan.runtimeMode.name,
            ),
            runtimeSnapshot = runtimeSnapshot,
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = profileRepository.keyReference,
                storagePath = profileRepository.storagePath,
                status = "androidTest capture",
                persistedProfileHash = stored.profile.canonicalHash(),
            ),
            automationState = AutomationState(
                storagePath = "androidTest",
                keyReference = "androidTest",
                vpnPermissionReady = true,
            ),
            routePreview = preview,
            previewOutcome = previewOutcome,
        )
        diagnosticsDirectory.resolve("$label-export-path.txt").writeText(artifact.path)
    }

    private fun captureStep(label: String) {
        when (diagnosticsMode) {
            DiagnosticsMode.FULL -> captureVisualDiagnostics(label)
            DiagnosticsMode.FAST -> writeStepMarker(label)
        }
    }

    private fun dumpWindowHierarchy(): String {
        val output = ByteArrayOutputStream()
        device.dumpWindowHierarchy(output)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun captureVisualDiagnostics(label: String) {
        val hierarchy = dumpWindowHierarchy()
        val containsSensitiveFixture = containsSensitiveFixture(hierarchy)
        writeWindowHierarchy(
            label = label,
            contents = redactSensitiveText(hierarchy),
        )
        if (containsSensitiveFixture) {
            diagnosticsDirectory.resolve("$label-screen.txt").writeText(
                "Screenshot skipped because the visible UI contained live share-link material.",
            )
        } else {
            writeScreenshot(label)
        }
    }

    private fun writeWindowHierarchy(label: String, contents: String) {
        diagnosticsDirectory.resolve("$label-window.xml").writeText(contents)
    }

    private fun writeScreenshot(label: String) {
        val target = diagnosticsDirectory.resolve("$label-screen.png")
        check(device.takeScreenshot(target)) {
            "Failed to capture screenshot to ${target.absolutePath}"
        }
    }

    private fun writeCommandOutput(fileName: String, command: String) {
        diagnosticsDirectory.resolve(fileName).writeText(
            redactSensitiveText(device.executeShellCommand(command)),
        )
    }

    private fun writeStepMarker(label: String) {
        diagnosticsDirectory.resolve("$label-step.txt").writeText(
            buildString {
                appendLine("label=$label")
                appendLine("timestamp_ms=${System.currentTimeMillis()}")
                appendLine("package=${device.currentPackageName.orEmpty()}")
            },
        )
    }

    private fun containsSensitiveFixture(text: String): Boolean =
        SHARE_LINK_REGEX.containsMatchIn(text) ||
            sensitiveFixtureTerms().any { text.contains(it) }

    private fun redactSensitiveText(text: String): String {
        var redacted = SHARE_LINK_REGEX.replace(text, REDACTED_SHARE_LINK)
        sensitiveFixtureTerms().forEach { term ->
            redacted = redacted.replace(term, REDACTED_TOKEN)
        }
        return redacted
    }

    private fun sensitiveFixtureTerms(): List<String> = runCatching {
        val shareLink = shareLinkFromArgs().trim()
        val profile = ProfileImportParser.parse(shareLink).profile
        buildList {
            addIfNotBlank(shareLink)
            addIfNotBlank(profile.id)
            addIfNotBlank(profile.name)
            addIfNotBlank(profile.outbound.uuid)
            addIfNotBlank(profile.outbound.address)
            addIfNotBlank(profile.outbound.serverName)
            addIfNotBlank(profile.outbound.realityPublicKey)
            addIfNotBlank(profile.outbound.realityShortId)
            addIfNotBlank("Endpoint: ${profile.outbound.address}:${profile.outbound.port}")
        }.distinct().sortedByDescending(String::length)
    }.getOrElse {
        emptyList()
    }

    private fun MutableList<String>.addIfNotBlank(value: String?) {
        value?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::add)
    }

    private fun detectProbeFailure(dump: String): String? {
        val markers = listOf(
            "This site can't be reached",
            "This site can’t be reached",
            "DNS_PROBE_FINISHED_NXDOMAIN",
            "ERR_NAME_NOT_RESOLVED",
            "Status: failed",
            "UnknownHostException",
        )
        return markers.firstOrNull { dump.contains(it, ignoreCase = true) }
    }

    private fun sanitizeLabel(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun requestRuntimeSnapshot(): VpnRuntimeSnapshot {
        val latch = CountDownLatch(1)
        var snapshot: VpnRuntimeSnapshot? = null
        val client = VpnRuntimeClient(
            context = appContext,
            onConnectionChanged = { _ -> },
            onStatus = { currentSnapshot, _ ->
                snapshot = currentSnapshot
                latch.countDown()
            },
            onEgressIpObservation = { _ -> },
        )
        client.bind()
        try {
            assertTrue("Timed out waiting for runtime status.", latch.await(10, TimeUnit.SECONDS))
            return checkNotNull(snapshot) { "Runtime snapshot was not delivered." }
        } finally {
            client.unbind()
        }
    }

    private fun isVpnTransportActive(): Boolean {
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    companion object {
        private const val TUNGUSKA_PACKAGE = "io.acionyx.tunguska"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val TRAFFIC_PROBE_PACKAGE = "io.acionyx.tunguska.trafficprobe"
        private const val INTENT_RESOLVER_PACKAGE = "com.android.intentresolver"
        private const val TUNGUSKA_COMPONENT = "io.acionyx.tunguska/io.acionyx.tunguska.app.MainActivity"
        private const val AUTOMATION_COMPONENT =
            "io.acionyx.tunguska/io.acionyx.tunguska.app.AutomationRelayActivity"
        private const val CHROME_COMPONENT = "com.android.chrome/com.google.android.apps.chrome.Main"
        private const val TRAFFIC_PROBE_COMPONENT =
            "io.acionyx.tunguska.trafficprobe/io.acionyx.tunguska.trafficprobe.ProbeActivity"
        private const val DIAGNOSTICS_DIRECTORY_NAME = "tunguska-smoke"
        private const val DIAGNOSTICS_MODE_ARGUMENT = "diagnostics_mode"
        private const val AUTOMATION_TOKEN_SETTING = "tunguska_automation_token"
        private const val PROFILE_SHARE_LINK_SETTING = "tunguska_profile_share_link"
        private const val PROFILE_SHARE_LINK_HEX_SETTING = "tunguska_profile_share_link_hex"
        private const val PROBE_URL_PRIMARY = "https://api.ipify.org/"
        private const val PROBE_URL_FALLBACK = "https://ifconfig.me/ip"
        private const val PROBE_URL_SECONDARY_FALLBACK = "https://checkip.amazonaws.com/"
        private val PROBE_URL_CANDIDATES = listOf(
            PROBE_URL_PRIMARY,
            PROBE_URL_FALLBACK,
            PROBE_URL_SECONDARY_FALLBACK,
        )
        private val SHARE_LINK_REGEX = Regex("""(?:vless|vmess|trojan|ss)://[^"\s<]+""", RegexOption.IGNORE_CASE)
        private const val REDACTED_SHARE_LINK = "[REDACTED_SHARE_LINK]"
        private const val REDACTED_TOKEN = "[REDACTED]"
        private val IP_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val IP_ADDRESS_PATTERN: Pattern = Pattern.compile(IP_REGEX.pattern)
        private const val DEFAULT_SHARE_LINK =
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public+key&sid=abcd1234" +
                "&fp=chrome&flow=xtls-rprx-vision#Alpha%20Import"
    }
}

private enum class DiagnosticsMode {
    FAST,
    FULL;

    companion object {
        fun from(raw: String?): DiagnosticsMode = when (raw?.trim()?.uppercase()) {
            "FULL" -> FULL
            else -> FAST
        }
    }
}

private sealed interface ProbeResult {
    data class Success(val ip: String) : ProbeResult

    data class Failure(val summary: String) : ProbeResult
}

internal enum class TestSplitMode {
    FULL_TUNNEL,
    DENYLIST_EXCLUDED_PROBE,
    ALLOWLIST_INCLUDED_PROBE,
}
