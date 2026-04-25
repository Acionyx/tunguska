package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationIntegrationRepositoryTest {
    @Test
    fun `load without release override keeps xray default`() {
        val repository = buildRepository()

        val loaded = repository.load()

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, loaded.runtimeStrategy)
    }

    @Test
    fun `enable generates and persists a token`() {
        val repository = buildRepository()

        val enabled = repository.enable()
        val loaded = repository.load()

        assertTrue(enabled.enabled)
        assertNotNull(enabled.token)
        assertEquals(enabled.token, loaded.token)
        assertTrue(repository.validateToken(enabled.token))
    }

    @Test
    fun `rotate token invalidates the previous token`() {
        val repository = buildRepository()
        val enabled = repository.enable()

        val rotated = repository.rotateToken()

        assertNotEquals(enabled.token, rotated.token)
        assertFalse(repository.validateToken(enabled.token))
        assertTrue(repository.validateToken(rotated.token))
    }

    @Test
    fun `disable clears the token and records disabled status`() {
        val repository = buildRepository()
        repository.enable()

        val disabled = repository.disable()

        assertFalse(disabled.enabled)
        assertNull(disabled.token)
        assertEquals(AutomationCommandStatus.AUTOMATION_DISABLED, disabled.lastAutomationStatus)
        assertFalse(repository.validateToken("anything"))
    }

    @Test
    fun `validate token rejects partial prefix matches`() {
        val repository = buildRepository()
        val enabled = repository.enable()
        val token = requireNotNull(enabled.token)

        assertFalse(repository.validateToken(token.dropLast(1)))
        assertFalse(repository.validateToken(token.take(4)))
    }

    @Test
    fun `record result persists caller hint and error without exposing token requirement`() {
        val repository = buildRepository()
        repository.enable()

        val recorded = repository.recordResult(
            status = AutomationCommandStatus.RUNTIME_START_FAILED,
            error = "Timed out",
            errorSection = "Routing",
            errorFieldPath = "routing.rules",
            runtimeMetadata = AutomationRuntimeMetadata(
                laneLabel = "Xray + tun2socks",
                laneStatus = "Fallback with limits",
                connectDecisionSummary = "Start failed on Xray + tun2socks. Recommended: Sing-box embedded.",
                compilerPath = "Canonical profile rebuild",
                nextStartLaneLabel = "Sing-box embedded",
            ),
            callerHint = "anubis",
        )

        assertEquals(AutomationCommandStatus.RUNTIME_START_FAILED, recorded.lastAutomationStatus)
        assertEquals("Timed out", recorded.lastAutomationError)
        assertEquals("Routing", recorded.lastAutomationErrorSection)
        assertEquals("routing.rules", recorded.lastAutomationErrorFieldPath)
        assertEquals("Xray + tun2socks", recorded.lastAutomationRuntimeMetadata?.laneLabel)
        assertEquals(
            "Start failed on Xray + tun2socks. Recommended: Sing-box embedded.",
            recorded.lastAutomationRuntimeMetadata?.connectDecisionSummary,
        )
        assertEquals("Canonical profile rebuild", recorded.lastAutomationRuntimeMetadata?.compilerPath)
        assertEquals("Sing-box embedded", recorded.lastAutomationRuntimeMetadata?.nextStartLaneLabel)
        assertEquals("anubis", recorded.lastCallerHint)
        assertNotNull(recorded.lastAutomationAtEpochMs)
    }

    @Test
    fun `runtime strategy selection persists across reload`() {
        val repository = buildRepository()

        val updated = repository.setRuntimeStrategy(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED)
        val loaded = repository.load()

        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, updated.runtimeStrategy)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, loaded.runtimeStrategy)
    }

    @Test
    fun `release override migrates existing installs to singbox once`() {
        val path = Files.createTempDirectory("tunguska-automation").resolve("anubis.json.enc")
        val cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey())
        val overrideGate = MutableRuntimeStrategyOverrideGate()
        buildRepository(path = path, cipherBox = cipherBox)
            .setRuntimeStrategy(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)
        val repository = buildRepository(
            path = path,
            cipherBox = cipherBox,
            runtimeStrategyOverrideGate = overrideGate,
        )

        val migrated = repository.load()
        val loadedAgain = repository.load()

        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, migrated.runtimeStrategy)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, loadedAgain.runtimeStrategy)
        assertEquals(1, overrideGate.appliedCount)
    }

    @Test
    fun `release override does not keep forcing singbox after first migration`() {
        val overrideGate = MutableRuntimeStrategyOverrideGate()
        val repository = buildRepository(runtimeStrategyOverrideGate = overrideGate)

        repository.load()
        repository.setRuntimeStrategy(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS)

        val loaded = repository.load()

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, loaded.runtimeStrategy)
        assertEquals(1, overrideGate.appliedCount)
    }

    private fun buildRepository(
        path: Path = Files.createTempDirectory("tunguska-automation").resolve("anubis.json.enc"),
        cipherBox: SoftwareAesGcmCipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        runtimeStrategyOverrideGate: RuntimeStrategyOverrideGate = DisabledRuntimeStrategyOverrideGate,
    ): AutomationIntegrationRepository = AutomationIntegrationRepository(
        path = path,
        cipherBox = cipherBox,
        clock = { 1234L },
        runtimeStrategyOverrideGate = runtimeStrategyOverrideGate,
    )

    private class MutableRuntimeStrategyOverrideGate : RuntimeStrategyOverrideGate {
        private var shouldApply: Boolean = true
        var appliedCount: Int = 0
            private set

        override fun shouldApply(): Boolean = shouldApply

        override fun markApplied() {
            shouldApply = false
            appliedCount += 1
        }
    }
}
