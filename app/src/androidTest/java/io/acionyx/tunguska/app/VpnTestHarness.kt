package io.acionyx.tunguska.app

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
import io.acionyx.tunguska.vpnservice.TunnelSessionPlanner
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

internal class VpnTestHarness(
    private val composeRule: ComposeTestRule,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val appContext = instrumentation.targetContext.applicationContext
    private val exportRepository = SecureExportRepository(appContext)
    private val profileRepository = SecureProfileRepository(appContext)
    private val plugin = SingboxEnginePlugin()
    private val routePreviewEngine = RoutePreviewEngine()

    fun importShareLinkFromArgsOrDefault() {
        importPayload(shareLinkFromArgs())
    }

    fun importPayload(payload: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.IMPORT_DRAFT_FIELD, useUnmergedTree = true)
            .performTextReplacement(payload)
        device.pressBack()
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
        waitForAppliedProfile(payload)
        captureStep("post_confirm_import")
    }

    fun connectAndWait(expectedPhase: String = expectedPhaseFromArgs()) {
        composeRule.onNodeWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(UiTags.CONNECT_BUTTON))
        captureStep("pre_connect")
        tapConnectButton()
        confirmVpnPermissionIfPresent()
        captureStep("post_permission_dialog")
        waitForComposeText("Permission: granted", timeoutMillis = 20_000)
        if (hasComposeText("Phase: IDLE")) {
            tapConnectButton()
        }
        waitForRuntimePhase(expectedPhase)
        captureStep("post_connect")
    }

    fun stopAndWaitForIdle() {
        if (!composeRule.onAllNodesWithTag(UiTags.STOP_BUTTON, useUnmergedTree = true).fetchSemanticsNodes().any()) {
            return
        }
        composeRule.onNodeWithTag(UiTags.STOP_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        waitForRuntimePhase(VpnRuntimePhase.IDLE.name)
        captureStep("post_stop")
    }

    fun openChromeAndReadIp(label: String): String {
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
        device.executeShellCommand("am start -W -n $TUNGUSKA_COMPONENT")
        device.wait(Until.hasObject(By.pkg(TUNGUSKA_PACKAGE)), 10_000)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(UiTags.MAIN_SCROLL_COLUMN, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        captureStep("tunguska_launched")
    }

    fun captureDiagnostics(label: String) {
        device.executeShellCommand("mkdir -p $REMOTE_DIAGNOSTICS_DIRECTORY")
        device.executeShellCommand("uiautomator dump $REMOTE_DIAGNOSTICS_DIRECTORY/$label-window.xml")
        device.executeShellCommand("screencap -p $REMOTE_DIAGNOSTICS_DIRECTORY/$label-screen.png")
        device.executeShellCommand(
            "logcat -d -v threadtime -s " +
                "TunguskaVpnService:I XrayTun2Socks:I TunguskaVpnNative:I Tunguska:I chromium:I *:S " +
                "> $REMOTE_DIAGNOSTICS_DIRECTORY/$label-logcat.txt",
        )
        device.executeShellCommand(
            "dumpsys connectivity > $REMOTE_DIAGNOSTICS_DIRECTORY/$label-connectivity.txt",
        )
        device.executeShellCommand(
            "dumpsys vpn > $REMOTE_DIAGNOSTICS_DIRECTORY/$label-vpn.txt",
        )
        device.executeShellCommand(
            "dumpsys activity services io.acionyx.tunguska > $REMOTE_DIAGNOSTICS_DIRECTORY/$label-services.txt",
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
            ?: DEFAULT_SHARE_LINK
    }

    private fun expectedPhaseFromArgs(): String = InstrumentationRegistry.getArguments()
        .getString("expected_phase")
        ?: VpnRuntimePhase.RUNNING.name

    private fun confirmVpnPermissionIfPresent() {
        val button = device.wait(Until.findObject(By.res("android:id/button1")), 10_000)
            ?: device.wait(Until.findObject(By.textContains("Allow")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("OK")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("Continue")), 2_000)
        button?.click()
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

    private fun tapConnectButton() {
        composeRule.onNodeWithTag(UiTags.CONNECT_BUTTON, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    private fun waitForRuntimePhase(expectedPhase: String) {
        val phaseText = "Phase: $expectedPhase"
        val found = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                hasComposeText(phaseText)
            }
            true
        }.getOrDefault(false)
        if (!found) {
            captureDiagnostics("runtime_phase_timeout")
            fail("Expected runtime phase '$phaseText' was not observed within timeout.")
        }
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
            URLDecoder.decode(uri.rawFragment.orEmpty(), Charsets.UTF_8)
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

    private fun launchChrome(url: String) {
        device.executeShellCommand(
            "am start -W -n $CHROME_COMPONENT -a android.intent.action.VIEW -d $url",
        )
        val visible = device.wait(Until.hasObject(By.pkg(CHROME_PACKAGE)), 15_000)
        assertTrue("Chrome did not reach the foreground.", visible)
    }

    private fun waitForProbeResult(packageName: String, label: String): ProbeResult {
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            val objectMatch = device.findObject(By.text(IP_ADDRESS_PATTERN))
            if (objectMatch != null) {
                return ProbeResult.Success(objectMatch.text)
            }
            val multilineMatch = device.findObject(By.textContains("IP:"))
            if (multilineMatch != null) {
                IP_REGEX.find(multilineMatch.text)?.let { return ProbeResult.Success(it.value) }
            }

            val dump = device.executeShellCommand("uiautomator dump /dev/tty")
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
            routePreview = preview,
            previewOutcome = previewOutcome,
        )
        device.executeShellCommand("echo ${artifact.path} > $REMOTE_DIAGNOSTICS_DIRECTORY/$label-export-path.txt")
    }

    private fun captureStep(label: String) {
        device.executeShellCommand("mkdir -p $REMOTE_DIAGNOSTICS_DIRECTORY")
        device.executeShellCommand("uiautomator dump $REMOTE_DIAGNOSTICS_DIRECTORY/$label-window.xml")
        device.executeShellCommand("screencap -p $REMOTE_DIAGNOSTICS_DIRECTORY/$label-screen.png")
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
        )
        client.bind()
        try {
            assertTrue("Timed out waiting for runtime status.", latch.await(10, TimeUnit.SECONDS))
            return checkNotNull(snapshot) { "Runtime snapshot was not delivered." }
        } finally {
            client.unbind()
        }
    }

    companion object {
        private const val TUNGUSKA_PACKAGE = "io.acionyx.tunguska"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val TRAFFIC_PROBE_PACKAGE = "io.acionyx.tunguska.trafficprobe"
        private const val TUNGUSKA_COMPONENT = "io.acionyx.tunguska/io.acionyx.tunguska.app.MainActivity"
        private const val CHROME_COMPONENT = "com.android.chrome/com.google.android.apps.chrome.Main"
        private const val TRAFFIC_PROBE_COMPONENT =
            "io.acionyx.tunguska.trafficprobe/io.acionyx.tunguska.trafficprobe.ProbeActivity"
        private const val REMOTE_DIAGNOSTICS_DIRECTORY = "/sdcard/Download/tunguska-smoke"
        private const val PROBE_URL_PRIMARY = "https://api.ipify.org/"
        private const val PROBE_URL_FALLBACK = "https://ifconfig.me/ip"
        private val PROBE_URL_CANDIDATES = listOf(PROBE_URL_PRIMARY, PROBE_URL_FALLBACK)
        private val IP_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val IP_ADDRESS_PATTERN: Pattern = Pattern.compile(IP_REGEX.pattern)
        private const val DEFAULT_SHARE_LINK =
            "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443" +
                "?security=reality&sni=cdn.example.com&pbk=public+key&sid=abcd1234" +
                "&fp=chrome&flow=xtls-rprx-vision#Alpha%20Import"
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
