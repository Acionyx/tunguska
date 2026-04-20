package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationIntegrationRepositoryTest {
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

        assertFalse(repository.validateToken(enabled.token!!.dropLast(1)))
        assertFalse(repository.validateToken(enabled.token!!.take(4)))
    }

    @Test
    fun `record result persists caller hint and error without exposing token requirement`() {
        val repository = buildRepository()
        repository.enable()

        val recorded = repository.recordResult(
            status = AutomationCommandStatus.RUNTIME_START_FAILED,
            error = "Timed out",
            callerHint = "anubis",
        )

        assertEquals(AutomationCommandStatus.RUNTIME_START_FAILED, recorded.lastAutomationStatus)
        assertEquals("Timed out", recorded.lastAutomationError)
        assertEquals("anubis", recorded.lastCallerHint)
        assertNotNull(recorded.lastAutomationAtEpochMs)
    }

    private fun buildRepository(): AutomationIntegrationRepository = AutomationIntegrationRepository(
        path = Files.createTempDirectory("tunguska-automation").resolve("anubis.json.enc"),
        cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        clock = { 1234L },
    )
}
