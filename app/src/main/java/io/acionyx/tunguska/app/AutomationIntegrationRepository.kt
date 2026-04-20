package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.storage.EncryptedArtifactStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val AUTOMATION_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.automation.master"
private const val AUTOMATION_SETTINGS_RELATIVE_PATH: String = "automation/anubis-integration.json.enc"
private const val AUTOMATION_ARTIFACT_TYPE: String = "automation_settings"
private const val AUTOMATION_TOKEN_BYTES: Int = 24

class AutomationIntegrationRepository(
    path: Path,
    cipherBox: CipherBox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val store = EncryptedArtifactStore(
        path = path,
        cipherBox = cipherBox,
        clock = clock,
    )

    constructor(
        context: Context,
        cipherBox: CipherBox = AndroidKeyStoreCipherBox(AUTOMATION_MASTER_KEY_ALIAS),
        clock: () -> Long = System::currentTimeMillis,
        secureRandom: SecureRandom = SecureRandom(),
    ) : this(
        path = context.filesDir.toPath().resolve(AUTOMATION_SETTINGS_RELATIVE_PATH),
        cipherBox = cipherBox,
        clock = clock,
        secureRandom = secureRandom,
    )

    val storagePath: String = store.path.toString()
    val keyReference: String = "android-keystore:$AUTOMATION_MASTER_KEY_ALIAS"

    fun load(): AutomationIntegrationSettings {
        val stored = store.load() ?: return AutomationIntegrationSettings()
        return CanonicalJson.instance.decodeFromString<StoredAutomationIntegration>(stored.payloadJson)
            .toSettings()
    }

    fun enable(): AutomationIntegrationSettings {
        val current = load()
        val enabled = current.copy(
            enabled = true,
            token = current.token ?: generateToken(),
            lastAutomationStatus = current.lastAutomationStatus,
            lastAutomationError = null,
        )
        return save(enabled)
    }

    fun disable(): AutomationIntegrationSettings = save(
        load().copy(
            enabled = false,
            token = null,
            lastAutomationStatus = AutomationCommandStatus.AUTOMATION_DISABLED,
            lastAutomationError = null,
            lastAutomationAtEpochMs = clock(),
            lastCallerHint = null,
        ),
    )

    fun rotateToken(): AutomationIntegrationSettings {
        val current = load()
        return save(
            current.copy(
            enabled = true,
            token = generateToken(),
            lastAutomationStatus = current.lastAutomationStatus,
            lastAutomationError = null,
            lastAutomationAtEpochMs = clock(),
            ),
        )
    }

    fun validateToken(candidate: String?): Boolean {
        val settings = load()
        val storedToken = settings.token
        return settings.enabled &&
            !storedToken.isNullOrBlank() &&
            !candidate.isNullOrBlank() &&
            MessageDigest.isEqual(
                storedToken.toByteArray(Charsets.UTF_8),
                candidate.toByteArray(Charsets.UTF_8),
            )
    }

    fun recordResult(
        status: AutomationCommandStatus,
        error: String? = null,
        callerHint: String? = null,
    ): AutomationIntegrationSettings = save(
        load().copy(
            lastAutomationStatus = status,
            lastAutomationError = error,
            lastAutomationAtEpochMs = clock(),
            lastCallerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
        ),
    )

    private fun save(settings: AutomationIntegrationSettings): AutomationIntegrationSettings {
        store.save(
            artifactType = AUTOMATION_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(settings.toStored()),
            redacted = true,
        )
        return settings
    }

    private fun generateToken(): String {
        val bytes = ByteArray(AUTOMATION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

enum class AutomationCommandStatus {
    NEVER_RUN,
    SUCCESS,
    AUTOMATION_DISABLED,
    INVALID_TOKEN,
    VPN_PERMISSION_REQUIRED,
    NO_STORED_PROFILE,
    PROFILE_INVALID,
    CONTROL_CHANNEL_ERROR,
    RUNTIME_START_FAILED,
    RUNTIME_STOP_FAILED,
}

data class AutomationIntegrationSettings(
    val enabled: Boolean = false,
    val token: String? = null,
    val lastAutomationStatus: AutomationCommandStatus = AutomationCommandStatus.NEVER_RUN,
    val lastAutomationError: String? = null,
    val lastAutomationAtEpochMs: Long? = null,
    val lastCallerHint: String? = null,
)

@Serializable
private data class StoredAutomationIntegration(
    val enabled: Boolean = false,
    val token: String? = null,
    val lastAutomationStatus: String = AutomationCommandStatus.NEVER_RUN.name,
    val lastAutomationError: String? = null,
    val lastAutomationAtEpochMs: Long? = null,
    val lastCallerHint: String? = null,
)

private fun StoredAutomationIntegration.toSettings(): AutomationIntegrationSettings = AutomationIntegrationSettings(
    enabled = enabled,
    token = token,
    lastAutomationStatus = lastAutomationStatus
        .let { value -> runCatching { AutomationCommandStatus.valueOf(value) }.getOrDefault(AutomationCommandStatus.NEVER_RUN) },
    lastAutomationError = lastAutomationError,
    lastAutomationAtEpochMs = lastAutomationAtEpochMs,
    lastCallerHint = lastCallerHint,
)

private fun AutomationIntegrationSettings.toStored(): StoredAutomationIntegration = StoredAutomationIntegration(
    enabled = enabled,
    token = token,
    lastAutomationStatus = lastAutomationStatus.name,
    lastAutomationError = lastAutomationError,
    lastAutomationAtEpochMs = lastAutomationAtEpochMs,
    lastCallerHint = lastCallerHint,
)
