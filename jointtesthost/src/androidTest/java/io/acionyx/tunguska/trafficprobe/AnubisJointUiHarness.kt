package io.acionyx.tunguska.trafficprobe

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

class AnubisJointUiHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext.applicationContext
    private val device = UiDevice.getInstance(instrumentation)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val diagnosticsDirectory = File(appContext.filesDir, DIAGNOSTICS_DIRECTORY_NAME).apply {
        mkdirs()
    }
    private val diagnosticsMode = DiagnosticsMode.from(
        InstrumentationRegistry.getArguments().getString(DIAGNOSTICS_MODE_ARGUMENT),
    )

    fun prepareNeutralState() {
        device.executeShellCommand("am force-stop $CHROME_PACKAGE")
        device.executeShellCommand("am force-stop $ANUBIS_PACKAGE")
        device.executeShellCommand("am force-stop $TUNGUSKA_PACKAGE")
        enablePackage(TUNGUSKA_PACKAGE)
        waitForVpnTransportState(active = false, timeoutMillis = 15_000, failOnTimeout = true)
    }

    fun restoreInteractiveState() {
        device.executeShellCommand("am force-stop $ANUBIS_PACKAGE")
        device.executeShellCommand("am force-stop $TRAFFIC_PROBE_PACKAGE")
        enablePackage(TUNGUSKA_PACKAGE)
        enablePackage(ANUBIS_PACKAGE)
        enablePackage(TRAFFIC_PROBE_PACKAGE)
    }

    fun readAutomationTokenFixture(): String {
        val token = device.executeShellCommand("settings get global $AUTOMATION_TOKEN_SETTING").trim()
        assertTrue(
            "Automation token fixture was not written to settings global $AUTOMATION_TOKEN_SETTING.",
            token.isNotBlank() && token != "null",
        )
        captureStep("automation_token_fixture_read")
        return token
    }

    fun launchAnubis() {
        launchPackageViaShell(
            packageName = ANUBIS_PACKAGE,
            component = ANUBIS_COMPONENT,
            errorMessage = "Anubis did not reach the foreground.",
            alternateSuccessPackage = VPN_DIALOG_PACKAGE,
        )
        captureStep("anubis_launched")
    }

    fun ensureAnubisShizukuPermissionGranted() {
        launchAnubis()
        if (device.wait(Until.gone(By.textContains(ANUBIS_SHIZUKU_PERMISSION_TEXT)), 2_000)) {
            captureStep("anubis_shizuku_ready")
            return
        }
        device.executeShellCommand("pm grant $ANUBIS_PACKAGE $ANUBIS_SHIZUKU_PERMISSION")
        if (device.wait(Until.gone(By.textContains(ANUBIS_SHIZUKU_PERMISSION_TEXT)), 2_000)) {
            captureStep("anubis_shizuku_ready")
            return
        }
        clickTextContaining(ANUBIS_GRANT_PERMISSION_TEXT)
        val shizukuAllowButton = device.wait(Until.findObject(By.res("android:id/button1")), 10_000)
            ?: run {
                captureDiagnostics("anubis_shizuku_dialog_missing")
                fail("Expected the Shizuku permission dialog after requesting access from Anubis.")
                error("unreachable")
            }
        clickObject(shizukuAllowButton)
        launchAnubis()
        if (!device.wait(Until.gone(By.textContains(ANUBIS_SHIZUKU_PERMISSION_TEXT)), 10_000)) {
            captureDiagnostics("anubis_shizuku_permission_timeout")
            fail("Anubis still reports missing Shizuku permission after pm grant.")
        }
        captureStep("anubis_shizuku_ready")
    }

    fun configureAnubisTunguskaClient(token: String) {
        openAnubisVpnTab()
        clickTextContaining(ANUBIS_TUNGUSKA_LABEL, scrollAttempts = 4)
        openAnubisAutomationTokenEditor()
        requireEditText().text = token
        clickLowestTextContaining(
            text = ANUBIS_HOME_TAB_TEXT,
            minimumTop = device.displayHeight / 2,
        )
        captureStep("anubis_tunguska_configured")
    }

    fun addAppToAnubisGroup(groupLabel: String, searchQuery: String, resultText: String = searchQuery) {
        openAnubisHomeTab()
        if (isAnubisManagedAppVisible(resultText) || isAnubisManagedAppVisible(searchQuery)) {
            captureStep("anubis_group_${sanitizeLabel(groupLabel)}_configured")
            return
        }
        clickAnubisAddManagedApp(groupLabel)
        val searchField = requireEditText()
        searchField.text = searchQuery
        Thread.sleep(500)
        val candidate = waitForLowestVisibleText(
            texts = listOf(resultText, searchQuery).distinct(),
            minimumTop = searchField.visibleBounds.bottom + 1,
            timeoutMillis = 10_000,
            excludedTexts = listOf(ANUBIS_NO_RESULTS_TEXT_PREFIX),
        ) ?: run {
            captureDiagnostics("anubis_add_group_search_timeout")
            fail("Did not find Anubis add-to-group search result for '$searchQuery'.")
            error("unreachable")
        }
        clickObject(candidate)
        clickTextContaining(ANUBIS_DONE_BUTTON_TEXT)
        captureStep("anubis_group_${sanitizeLabel(groupLabel)}_configured")
    }

    fun toggleAnubisProtection(enabled: Boolean) {
        openAnubisHomeTab()
        val targetText = if (enabled) ANUBIS_PROTECTION_ENABLED_TEXT else ANUBIS_PROTECTION_DISABLED_TEXT
        if (device.hasObject(By.textContains(targetText))) {
            return
        }
        val toggle = waitForFirstCheckable(timeoutMillis = 10_000) ?: run {
            captureDiagnostics("anubis_toggle_missing")
            fail("Could not find the Anubis protection toggle.")
            error("unreachable")
        }
        clickObject(toggle)
        if (enabled) {
            confirmVpnPermissionIfPresent(timeoutMillis = 5_000)
        }
        if (!device.wait(Until.hasObject(By.textContains(targetText)), 5_000)) {
            openAnubisHomeTab()
            waitForFirstCheckable(timeoutMillis = 5_000)?.let(::clickObject)
            if (enabled) {
                confirmVpnPermissionIfPresent(timeoutMillis = 2_000)
            }
        }
        waitForVisibleText(targetText, timeoutMillis = 20_000)
        captureStep("anubis_toggle_${if (enabled) "enabled" else "disabled"}")
    }

    fun launchAnubisManagedApp(label: String, packageName: String? = null) {
        openAnubisHomeTab()
        clickTextContaining(label, scrollAttempts = 6)
        packageName?.let {
            assertTrue(
                "Managed app '$label' did not reach the foreground.",
                device.wait(Until.hasObject(By.pkg(it)), 15_000),
            )
        }
        captureStep("anubis_launch_${sanitizeLabel(label)}")
    }

    fun launchTunguska() {
        launchPackageViaShell(
            packageName = TUNGUSKA_PACKAGE,
            component = TUNGUSKA_COMPONENT,
            errorMessage = "Tunguska did not reach the foreground.",
        )
        waitForAnyVisibleText(
            texts = listOf(
                TUNGUSKA_BRAND_TEXT,
                TUNGUSKA_HOME_TITLE_TEXT,
                TUNGUSKA_HOME_HEADLINE_READY,
                TUNGUSKA_HOME_HEADLINE_RUNNING,
                TUNGUSKA_HOME_HEADLINE_CONNECTING,
                TUNGUSKA_HOME_HEADLINE_ATTENTION,
            ),
            timeoutMillis = 10_000,
        )
        captureStep("tunguska_launched")
    }

    fun waitForTunguskaPhaseVisible(phase: String) {
        launchTunguska()
        waitForAnyVisibleText(
            texts = tunguskaPhaseTexts(phase),
            timeoutMillis = 15_000,
        )
        captureStep("tunguska_phase_${sanitizeLabel(phase)}")
    }

    fun assertSelectedRuntimeStrategy() {
        val expectedStrategy = requestedRuntimeStrategy()
        openTunguskaAdvancedDiagnostics()
        waitForVisibleText(expectedStrategy, timeoutMillis = 15_000, scrollAttempts = 6)
        captureStep("tunguska_strategy_${sanitizeLabel(expectedStrategy.lowercase())}")
    }

    fun waitForTunguskaVpnServiceActive(timeoutMillis: Long = 15_000) {
        waitForTunguskaVpnServiceState(active = true, timeoutMillis = timeoutMillis)
    }

    fun waitForTunguskaVpnServiceInactive(timeoutMillis: Long = 20_000) {
        waitForTunguskaVpnServiceState(active = false, timeoutMillis = timeoutMillis)
    }

    fun waitForPackageEnabledState(packageName: String, enabled: Boolean, timeoutMillis: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (isPackageEnabled(packageName) == enabled) {
                return
            }
            Thread.sleep(250)
        }
        captureDiagnostics("package_enabled_state_timeout")
        fail("Expected package '$packageName' enabled=$enabled within timeout.")
    }

    fun waitForVpnTransportActive() {
        waitForVpnTransportState(active = true, timeoutMillis = 15_000, failOnTimeout = true)
    }

    fun waitForVpnTransportInactive() {
        waitForVpnTransportState(active = false, timeoutMillis = 15_000, failOnTimeout = true)
    }

    fun waitForForegroundTrafficProbeIp(label: String): String {
        assertTrue(
            "Traffic probe did not reach the foreground.",
            device.wait(Until.hasObject(By.pkg(TRAFFIC_PROBE_PACKAGE)), 10_000),
        )
        captureStep("trafficprobe_${label}_foreground")
        return when (val result = waitForProbeResult(packageName = TRAFFIC_PROBE_PACKAGE, label = label)) {
            is ProbeResult.Success -> result.ip
            is ProbeResult.Failure -> {
                captureDiagnostics("trafficprobe_${label}_foreground_failure")
                fail("Unable to read a public IP from the foreground trafficprobe. ${result.summary}")
                error("unreachable")
            }
        }
    }

    fun launchTrafficProbeAndReadIp(label: String): String {
        val failures = mutableListOf<String>()
        for (candidateUrl in PROBE_URL_CANDIDATES) {
            val urlLabel = sanitizeLabel(URI(candidateUrl).host ?: "probe")
            device.executeShellCommand(
                "am start -W -S -n $TRAFFIC_PROBE_COMPONENT " +
                    "--ez $TRAFFIC_PROBE_EXTRA_AUTO_PROBE true " +
                    "--es $TRAFFIC_PROBE_EXTRA_PROBE_URL $candidateUrl",
            )
            assertTrue(
                "Traffic probe did not reach the foreground.",
                device.wait(Until.hasObject(By.pkg(TRAFFIC_PROBE_PACKAGE)), 10_000),
            )
            captureStep("trafficprobe_${label}_${urlLabel}_launched")
            when (val result = waitForProbeResult(packageName = TRAFFIC_PROBE_PACKAGE, label = "trafficprobe_${label}_$urlLabel")) {
                is ProbeResult.Success -> return result.ip
                is ProbeResult.Failure -> failures += "${candidateUrl}: ${result.summary}"
            }
        }
        captureDiagnostics("trafficprobe_${label}_final_failure")
        fail("Unable to read a public IP in trafficprobe. ${failures.joinToString(" | ")}")
        error("unreachable")
    }

    fun waitForTrafficProbeIp(label: String, expectedIp: String, timeoutMillis: Long = 30_000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var attempt = 0
        var lastIp: String? = null
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            val observedIp = launchTrafficProbeAndReadIp("${label}_attempt_$attempt")
            if (observedIp == expectedIp) {
                return observedIp
            }
            lastIp = observedIp
            Thread.sleep(1_500)
        }
        captureDiagnostics("${label}_expected_ip_timeout")
        fail("Expected public IP '$expectedIp' within timeout. Last observed '$lastIp'.")
        error("unreachable")
    }

    fun captureDiagnostics(label: String) {
        captureVisualDiagnostics(label)
        writeCommandOutput(
            fileName = "$label-logcat.txt",
            command = "logcat -d -v threadtime -s " +
                "TunguskaVpnService:I XrayTun2Socks:I TunguskaVpnNative:I RuntimeAutomation:I " +
                "ActivityManager:I chromium:I *:S",
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
            command = "dumpsys activity services io.acionyx.tunguska sgnv.anubis.app io.acionyx.tunguska.trafficprobe",
        )
    }

    private fun launchPackageViaShell(
        packageName: String,
        component: String? = null,
        errorMessage: String,
        alternateSuccessPackage: String? = null,
    ) {
        val command = component?.let { "am start -W -n $it" }
            ?: "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        repeat(3) { attempt ->
            device.pressHome()
            device.executeShellCommand(command)
            if (device.wait(Until.hasObject(By.pkg(packageName)), 20_000)) {
                return
            }
            if (alternateSuccessPackage != null && device.hasObject(By.pkg(alternateSuccessPackage))) {
                return
            }
            if (attempt < 2) {
                Thread.sleep(1_000)
            }
        }
        captureDiagnostics("package_launch_timeout")
        fail(errorMessage)
    }

    private fun openAnubisHomeTab() {
        launchAnubis()
        clickLowestTextContaining(
            text = ANUBIS_HOME_TAB_TEXT,
            minimumTop = device.displayHeight / 2,
        )
    }

    private fun openAnubisVpnTab() {
        launchAnubis()
        clickLowestTextContaining(
            text = ANUBIS_VPN_TAB_TEXT,
            minimumTop = device.displayHeight / 2,
        )
    }

    private fun clickTextContaining(text: String, scrollAttempts: Int = 0) {
        val candidate = waitForTextObject(
            text = text,
            timeoutMillis = 10_000,
            scrollAttempts = scrollAttempts,
        ) ?: run {
            captureDiagnostics("text_click_timeout")
            fail("Expected visible text containing '$text' was not found.")
            error("unreachable")
        }
        clickObject(candidate)
    }

    private fun clickLowestTextContaining(text: String, minimumTop: Int) {
        val candidate = waitForLowestVisibleText(
            texts = listOf(text),
            minimumTop = minimumTop,
            timeoutMillis = 10_000,
        ) ?: run {
            captureDiagnostics("lowest_text_click_timeout")
            fail("Expected lowest visible text containing '$text' was not found.")
            error("unreachable")
        }
        clickObject(candidate)
    }

    private fun openAnubisAutomationTokenEditor() {
        if (device.hasObject(By.clazz("android.widget.EditText"))) {
            return
        }
        waitForTextObject(
            text = ANUBIS_AUTOMATION_TOKEN_LABEL,
            timeoutMillis = 2_000,
            scrollAttempts = 2,
        )?.let(::clickObject)
        if (device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 2_000)) {
            return
        }
        device.findObjects(By.descContains(ANUBIS_EXPAND_TEXT))
            .filter { candidate -> candidate.visibleBounds.top > device.displayHeight / 3 }
            .minByOrNull { candidate -> candidate.visibleBounds.top }
            ?.let(::clickObject)
    }

    private fun clickAnubisAddManagedApp(groupLabel: String) {
        waitForTextObject(
            text = "Добавить в «$groupLabel»",
            timeoutMillis = 2_000,
            scrollAttempts = 2,
        )?.let {
            clickObject(it)
            return
        }
        val addButton = device.wait(Until.findObject(By.descContains(ANUBIS_ADD_APP_DESCRIPTION)), 5_000)
            ?: run {
                captureDiagnostics("anubis_add_app_button_missing")
                fail("Expected Anubis add-app action to be visible.")
                error("unreachable")
            }
        clickObject(addButton)
    }

    private fun isAnubisManagedAppVisible(label: String): Boolean {
        return device.hasObject(By.textContains(label)) || device.hasObject(By.descContains(label))
    }

    private fun waitForVisibleText(text: String, timeoutMillis: Long, scrollAttempts: Int = 0) {
        repeat(scrollAttempts + 1) { attempt ->
            if (device.wait(Until.hasObject(By.textContains(text)), timeoutMillis)) {
                return
            }
            if (attempt < scrollAttempts) {
                swipeUp()
            }
        }
        captureDiagnostics("visible_text_timeout")
        fail("Expected visible text containing '$text' within timeout.")
    }

    private fun waitForAnyVisibleText(
        texts: List<String>,
        timeoutMillis: Long,
        scrollAttempts: Int = 0,
    ) {
        repeat(scrollAttempts + 1) { attempt ->
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                if (texts.any { candidate -> device.hasObject(By.textContains(candidate)) }) {
                    return
                }
                Thread.sleep(250)
            }
            if (attempt < scrollAttempts) {
                swipeUp()
            }
        }
        captureDiagnostics("visible_text_timeout")
        fail("Expected one of the visible texts within timeout: ${texts.joinToString()}.")
    }

    private fun waitForTextObject(text: String, timeoutMillis: Long, scrollAttempts: Int): UiObject2? {
        repeat(scrollAttempts + 1) { attempt ->
            val candidate = device.wait(Until.findObject(By.textContains(text)), timeoutMillis)
            if (candidate != null) {
                return candidate
            }
            if (attempt < scrollAttempts) {
                swipeUp()
            }
        }
        return null
    }

    private fun waitForLowestVisibleText(
        texts: List<String>,
        minimumTop: Int,
        timeoutMillis: Long,
        excludedTexts: List<String> = emptyList(),
    ): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            for (text in texts) {
                val matches = device.findObjects(By.textContains(text))
                    .filter { candidate ->
                        candidate.visibleBounds.top >= minimumTop &&
                            excludedTexts.none { excluded -> candidate.text?.contains(excluded) == true }
                    }
                    .sortedByDescending { it.visibleBounds.top }
                if (matches.isNotEmpty()) {
                    return matches.first()
                }
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun requireEditText(): UiObject2 = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        ?: run {
            captureDiagnostics("edit_text_missing")
            fail("Expected an EditText to be visible.")
            error("unreachable")
        }

    private fun waitForFirstCheckable(timeoutMillis: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            device.findObjects(By.checkable(true)).firstOrNull()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun clickObject(target: UiObject2) {
        runCatching { target.click() }
            .getOrElse {
                val bounds = target.visibleBounds
                device.click(bounds.centerX(), bounds.centerY())
            }
        Thread.sleep(500)
    }

    private fun swipeUp() {
        val displayWidth = device.displayWidth
        val displayHeight = device.displayHeight
        device.swipe(
            displayWidth / 2,
            (displayHeight * 0.8f).toInt(),
            displayWidth / 2,
            (displayHeight * 0.25f).toInt(),
            24,
        )
        Thread.sleep(500)
    }

    private fun confirmVpnPermissionIfPresent(timeoutMillis: Long = 10_000) {
        val button = device.wait(Until.findObject(By.res("android:id/button1")), timeoutMillis)
            ?: device.wait(Until.findObject(By.textContains("Allow")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("OK")), 2_000)
            ?: device.wait(Until.findObject(By.textContains("Continue")), 2_000)
        button?.click()
    }

    private fun isPackageEnabled(packageName: String): Boolean {
        return try {
            appContext.packageManager
                .getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                .enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun enablePackage(packageName: String) {
        device.executeShellCommand("pm enable --user 0 $packageName")
        device.executeShellCommand("cmd package enable $packageName")
    }

    private fun waitForProbeResult(packageName: String, label: String): ProbeResult {
        val timeoutMillis = 12_000L
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
                confirmVpnPermissionIfPresent(timeoutMillis = 1_000)
            }
            Thread.sleep(1_000)
        }
        captureDiagnostics("${label}_ip_timeout")
        return ProbeResult.Failure("Timed out waiting for a visible public IP in $packageName.")
    }

    private fun waitForVpnTransportState(active: Boolean, timeoutMillis: Long, failOnTimeout: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (isVpnTransportActive() == active) {
                return true
            }
            Thread.sleep(250)
        }
        if (failOnTimeout) {
            captureDiagnostics(if (active) "vpn_active_timeout" else "vpn_inactive_timeout")
            fail("Expected VPN transport active=$active within timeout.")
        }
        return false
    }

    private fun waitForTunguskaVpnServiceState(active: Boolean, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (isTunguskaVpnServiceActive() == active) {
                return
            }
            Thread.sleep(250)
        }
        captureDiagnostics(if (active) "tunguska_vpn_service_timeout" else "tunguska_vpn_service_still_active")
        fail("Expected Tunguska VPN service active=$active within timeout.")
    }

    private fun isVpnTransportActive(): Boolean {
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun isTunguskaVpnServiceActive(): Boolean {
        val services = device.executeShellCommand("dumpsys activity services $TUNGUSKA_PACKAGE")
        return services.contains(TUNGUSKA_VPN_SERVICE_COMPONENT) && services.contains("startRequested=true")
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

    private fun captureStep(label: String) {
        when (diagnosticsMode) {
            DiagnosticsMode.FULL -> captureVisualDiagnostics(label)
            DiagnosticsMode.FAST -> writeStepMarker(label)
        }
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

    private fun openTunguskaAdvancedDiagnostics() {
        openTunguskaSecurityScreen()
        if (device.hasObject(By.textContains(TUNGUSKA_DIAGNOSTICS_TITLE_TEXT))) {
            return
        }
        clickTextContaining(TUNGUSKA_OPEN_DIAGNOSTICS_TEXT, scrollAttempts = 8)
        waitForAnyVisibleText(
            texts = listOf(
                TUNGUSKA_DIAGNOSTICS_TITLE_TEXT,
                TUNGUSKA_RUNTIME_INTERNALS_TEXT,
            ),
            timeoutMillis = 10_000,
            scrollAttempts = 4,
        )
    }

    private fun openTunguskaSecurityScreen() {
        launchTunguska()
        if (device.hasObject(By.textContains(TUNGUSKA_SECURITY_CARD_TEXT))) {
            return
        }
        clickLowestTextContaining(
            text = TUNGUSKA_SECURITY_TAB_TEXT,
            minimumTop = device.displayHeight / 2,
        )
        waitForAnyVisibleText(
            texts = listOf(
                TUNGUSKA_SECURITY_CARD_TEXT,
                TUNGUSKA_OPEN_DIAGNOSTICS_TEXT,
                TUNGUSKA_AUTOMATION_CARD_TEXT,
            ),
            timeoutMillis = 10_000,
        )
    }

    private fun tunguskaPhaseTexts(phase: String): List<String> = when (phase.trim().uppercase()) {
        "IDLE" -> listOf(TUNGUSKA_HOME_HEADLINE_READY, TUNGUSKA_CONNECT_BUTTON_TEXT)
        "STAGED" -> listOf("Profile staged")
        "START_REQUESTED" -> listOf(TUNGUSKA_HOME_HEADLINE_CONNECTING)
        "RUNNING" -> listOf(TUNGUSKA_HOME_HEADLINE_RUNNING, TUNGUSKA_STOP_BUTTON_TEXT)
        "FAIL_CLOSED" -> listOf(TUNGUSKA_HOME_HEADLINE_ATTENTION, "Needs attention")
        else -> listOf(phase)
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

    private fun sensitiveFixtureTerms(): List<String> {
        val shareLink = shareLinkFromFixture().trim()
        if (shareLink.isBlank()) {
            return emptyList()
        }
        return buildList {
            addIfNotBlank(shareLink)
            addIfNotBlank(userFromShareLink(shareLink))
            addIfNotBlank(urlDecode(shareLink.substringAfter('#', "")))
            addIfNotBlank(queryParameter(shareLink, "sni"))
            addIfNotBlank(queryParameter(shareLink, "sid"))
            addIfNotBlank(queryParameter(shareLink, "pbk"))
            endpointFromShareLink(shareLink)?.let { endpoint ->
                addIfNotBlank(endpoint)
                addIfNotBlank(endpoint.substringBeforeLast(":", endpoint))
            }
        }.distinct().sortedByDescending(String::length)
    }

    private fun shareLinkFromFixture(): String {
        val hexValue = device.executeShellCommand("settings get global $PROFILE_SHARE_LINK_HEX_SETTING").trim()
        if (hexValue.isNotBlank() && hexValue != "null") {
            return decodeHex(hexValue)
        }
        val plainValue = device.executeShellCommand("settings get global $PROFILE_SHARE_LINK_SETTING").trim()
        return plainValue.takeUnless { it.isBlank() || it == "null" }.orEmpty()
    }

    private fun endpointFromShareLink(shareLink: String): String? {
        val match = ENDPOINT_REGEX.find(shareLink) ?: return null
        val host = match.groupValues.getOrNull(1).orEmpty()
        val port = match.groupValues.getOrNull(2).orEmpty()
        return if (host.isBlank() || port.isBlank()) null else "$host:$port"
    }

    private fun userFromShareLink(shareLink: String): String? {
        val match = Regex("""^[a-z]+://([^@/?#]+)@""", RegexOption.IGNORE_CASE).find(shareLink.trim())
            ?: return null
        return match.groupValues.getOrNull(1)
            ?.substringBefore(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun queryParameter(shareLink: String, name: String): String? {
        val query = shareLink.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) {
            return null
        }
        return query.split('&')
            .mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.isEmpty()) {
                    return@mapNotNull null
                }
                val key = urlDecode(parts[0])
                if (!key.equals(name, ignoreCase = true)) {
                    return@mapNotNull null
                }
                urlDecode(parts.getOrElse(1) { "" })
            }
            .firstOrNull()
    }

    private fun decodeHex(value: String): String = runCatching {
        value.chunked(2)
            .map { chunk -> chunk.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }.getOrDefault("")

    private fun urlDecode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

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
            "Status: error",
            "UnknownHostException",
        )
        return markers.firstOrNull { dump.contains(it, ignoreCase = true) }
    }

    private fun sanitizeLabel(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun requestedRuntimeStrategy(): String = InstrumentationRegistry.getArguments()
        .getString(RUNTIME_STRATEGY_ARGUMENT)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.uppercase()
        ?.takeIf { value -> value == RUNTIME_STRATEGY_XRAY || value == RUNTIME_STRATEGY_SINGBOX }
        ?: RUNTIME_STRATEGY_XRAY

    companion object {
        private const val ANUBIS_PACKAGE = "sgnv.anubis.app"
        private const val ANUBIS_COMPONENT = "sgnv.anubis.app/sgnv.anubis.app.ui.MainActivity"
        private const val ANUBIS_SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
        private const val TUNGUSKA_PACKAGE = "io.acionyx.tunguska"
        private const val TUNGUSKA_COMPONENT = "io.acionyx.tunguska/io.acionyx.tunguska.app.MainActivity"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val TRAFFIC_PROBE_PACKAGE = "io.acionyx.tunguska.trafficprobe"
        private const val TRAFFIC_PROBE_COMPONENT =
            "io.acionyx.tunguska.trafficprobe/io.acionyx.tunguska.trafficprobe.ProbeActivity"
        private const val TRAFFIC_PROBE_EXTRA_PROBE_URL = "io.acionyx.tunguska.trafficprobe.extra.PROBE_URL"
        private const val TRAFFIC_PROBE_EXTRA_AUTO_PROBE = "io.acionyx.tunguska.trafficprobe.extra.AUTO_PROBE"
        private const val VPN_DIALOG_PACKAGE = "com.android.vpndialogs"
        private const val TUNGUSKA_VPN_SERVICE_COMPONENT = ".vpnservice.TunguskaVpnService"
        private const val DIAGNOSTICS_DIRECTORY_NAME = "tunguska-smoke"
        private const val DIAGNOSTICS_MODE_ARGUMENT = "diagnostics_mode"
        private const val AUTOMATION_TOKEN_SETTING = "tunguska_automation_token"
        private const val PROFILE_SHARE_LINK_SETTING = "tunguska_profile_share_link"
        private const val PROFILE_SHARE_LINK_HEX_SETTING = "tunguska_profile_share_link_hex"
        private const val TUNGUSKA_BRAND_TEXT = "TUNGUSKA"
        private const val TUNGUSKA_HOME_TITLE_TEXT = "Protection center"
        private const val TUNGUSKA_HOME_HEADLINE_READY = "Ready to connect"
        private const val TUNGUSKA_HOME_HEADLINE_RUNNING = "Protected"
        private const val TUNGUSKA_HOME_HEADLINE_CONNECTING = "Connecting"
        private const val TUNGUSKA_HOME_HEADLINE_ATTENTION = "Attention required"
        private const val TUNGUSKA_SECURITY_TAB_TEXT = "Security"
        private const val TUNGUSKA_SECURITY_CARD_TEXT = "Security posture"
        private const val TUNGUSKA_AUTOMATION_CARD_TEXT = "Automation"
        private const val TUNGUSKA_OPEN_DIAGNOSTICS_TEXT = "Advanced diagnostics"
        private const val TUNGUSKA_DIAGNOSTICS_TITLE_TEXT = "Runtime controls"
        private const val TUNGUSKA_RUNTIME_INTERNALS_TEXT = "Runtime internals"
        private const val TUNGUSKA_CONNECT_BUTTON_TEXT = "Connect"
        private const val TUNGUSKA_STOP_BUTTON_TEXT = "Disconnect"
        private const val RUNTIME_STRATEGY_ARGUMENT = "runtime_strategy"
        private const val RUNTIME_STRATEGY_XRAY = "XRAY_TUN2SOCKS"
        private const val RUNTIME_STRATEGY_SINGBOX = "SINGBOX_EMBEDDED"
        private const val ANUBIS_HOME_TAB_TEXT = "Главная"
        private const val ANUBIS_VPN_TAB_TEXT = "VPN"
        private const val ANUBIS_DONE_BUTTON_TEXT = "Готово"
        private const val ANUBIS_TUNGUSKA_LABEL = "Tunguska"
        private const val ANUBIS_NO_RESULTS_TEXT_PREFIX = "Ничего не найдено по запросу"
        private const val ANUBIS_SHIZUKU_PERMISSION_TEXT = "Нет разрешения Shizuku"
        private const val ANUBIS_GRANT_PERMISSION_TEXT = "Разрешить"
        private const val ANUBIS_PROTECTION_ENABLED_TEXT = "ЗАЩИТА АКТИВНА"
        private const val ANUBIS_PROTECTION_DISABLED_TEXT = "ЗАЩИТА ОТКЛЮЧЕНА"
        private const val ANUBIS_AUTOMATION_TOKEN_LABEL = "Токен автоматизации"
        private const val ANUBIS_EXPAND_TEXT = "Развернуть"
        private const val ANUBIS_ADD_APP_DESCRIPTION = "Добавить приложение"
        private const val PROBE_URL_PRIMARY = "https://checkip.amazonaws.com/"
        private const val PROBE_URL_FALLBACK = "https://ifconfig.me/ip"
        private const val PROBE_URL_SECONDARY_FALLBACK = "https://api.ipify.org/"
        private val PROBE_URL_CANDIDATES = listOf(
            PROBE_URL_PRIMARY,
            PROBE_URL_FALLBACK,
            PROBE_URL_SECONDARY_FALLBACK,
        )
        private val SHARE_LINK_REGEX = Regex("""(?:vless|vmess|trojan|ss)://[^"\s<]+""", RegexOption.IGNORE_CASE)
        private val ENDPOINT_REGEX = Regex("""@([^:/?#]+):(\d+)""")
        private const val REDACTED_SHARE_LINK = "[REDACTED_SHARE_LINK]"
        private const val REDACTED_TOKEN = "[REDACTED]"
        private val IP_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val IP_ADDRESS_PATTERN: Pattern = Pattern.compile(IP_REGEX.pattern)
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
