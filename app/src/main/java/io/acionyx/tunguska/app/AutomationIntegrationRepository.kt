package io.acionyx.tunguska.app

import android.content.Context
import android.content.SharedPreferences
import io.acionyx.tunguska.BuildConfig
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.storage.EncryptedArtifactStore
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
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
private const val RUNTIME_STRATEGY_OVERRIDE_PREFS_NAME: String = "automation.runtime_strategy_override"
private const val RUNTIME_STRATEGY_OVERRIDE_APPLIED_VERSION_CODE_KEY: String = "applied_version_code"
private const val RUNTIME_STRATEGY_OVERRIDE_TARGET_VERSION_CODE: Int = 13

class AutomationIntegrationRepository(
    path: Path,
    cipherBox: CipherBox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val runtimeStrategyOverrideGate: RuntimeStrategyOverrideGate = DisabledRuntimeStrategyOverrideGate,
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
        runtimeStrategyOverrideGate = SharedPreferencesRuntimeStrategyOverrideGate(
            preferences = context.getSharedPreferences(RUNTIME_STRATEGY_OVERRIDE_PREFS_NAME, Context.MODE_PRIVATE),
            currentVersionCode = BuildConfig.VERSION_CODE,
        ),
    )

    val storagePath: String = store.path.toString()
    val keyReference: String = "android-keystore:$AUTOMATION_MASTER_KEY_ALIAS"

    fun load(): AutomationIntegrationSettings {
        val stored = store.load()
        val settings = stored?.let {
            CanonicalJson.instance.decodeFromString<StoredAutomationIntegration>(it.payloadJson)
                .toSettings()
        } ?: AutomationIntegrationSettings()
        return applyRuntimeStrategyReleaseOverride(settings)
    }

    private fun applyRuntimeStrategyReleaseOverride(settings: AutomationIntegrationSettings): AutomationIntegrationSettings {
        if (!runtimeStrategyOverrideGate.shouldApply()) {
            return settings
        }
        val overridden = save(settings.copy(runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED))
        runtimeStrategyOverrideGate.markApplied()
        return overridden
    }

    fun enable(): AutomationIntegrationSettings {
        val current = load()
        val enabled = current.copy(
            enabled = true,
            token = current.token ?: generateToken(),
            lastAutomationStatus = current.lastAutomationStatus,
            lastAutomationError = null,
            lastAutomationErrorSection = null,
            lastAutomationErrorFieldPath = null,
            lastAutomationRuntimeMetadata = current.lastAutomationRuntimeMetadata,
        )
        return save(enabled)
    }

    fun disable(): AutomationIntegrationSettings = save(
        load().copy(
            enabled = false,
            token = null,
            lastAutomationStatus = AutomationCommandStatus.AUTOMATION_DISABLED,
            lastAutomationError = null,
            lastAutomationErrorSection = null,
            lastAutomationErrorFieldPath = null,
            lastAutomationRuntimeMetadata = null,
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
            lastAutomationErrorSection = null,
            lastAutomationErrorFieldPath = null,
            lastAutomationRuntimeMetadata = current.lastAutomationRuntimeMetadata,
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
        errorSection: String? = null,
        errorFieldPath: String? = null,
        runtimeMetadata: AutomationRuntimeMetadata? = null,
        callerHint: String? = null,
    ): AutomationIntegrationSettings = save(
        load().copy(
            lastAutomationStatus = status,
            lastAutomationError = error,
            lastAutomationErrorSection = errorSection,
            lastAutomationErrorFieldPath = errorFieldPath,
            lastAutomationRuntimeMetadata = runtimeMetadata,
            lastAutomationAtEpochMs = clock(),
            lastCallerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
        ),
    )

    fun setRuntimeStrategy(strategy: EmbeddedRuntimeStrategyId): AutomationIntegrationSettings = save(
        load().copy(
            runtimeStrategy = strategy,
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
    val runtimeStrategy: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
    val lastAutomationStatus: AutomationCommandStatus = AutomationCommandStatus.NEVER_RUN,
    val lastAutomationError: String? = null,
    val lastAutomationErrorSection: String? = null,
    val lastAutomationErrorFieldPath: String? = null,
    val lastAutomationRuntimeMetadata: AutomationRuntimeMetadata? = null,
    val lastAutomationAtEpochMs: Long? = null,
    val lastCallerHint: String? = null,
)

@Serializable
private data class StoredAutomationIntegration(
    val enabled: Boolean = false,
    val token: String? = null,
    val runtimeStrategy: String = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS.name,
    val lastAutomationStatus: String = AutomationCommandStatus.NEVER_RUN.name,
    val lastAutomationError: String? = null,
    val lastAutomationErrorSection: String? = null,
    val lastAutomationErrorFieldPath: String? = null,
    val lastAutomationRuntimeMetadata: AutomationRuntimeMetadata? = null,
    val lastAutomationAtEpochMs: Long? = null,
    val lastCallerHint: String? = null,
)

private fun StoredAutomationIntegration.toSettings(): AutomationIntegrationSettings = AutomationIntegrationSettings(
    enabled = enabled,
    token = token,
    runtimeStrategy = runtimeStrategy
        .let { value -> runCatching { EmbeddedRuntimeStrategyId.valueOf(value) }.getOrDefault(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) },
    lastAutomationStatus = lastAutomationStatus
        .let { value -> runCatching { AutomationCommandStatus.valueOf(value) }.getOrDefault(AutomationCommandStatus.NEVER_RUN) },
    lastAutomationError = lastAutomationError,
    lastAutomationErrorSection = lastAutomationErrorSection,
    lastAutomationErrorFieldPath = lastAutomationErrorFieldPath,
    lastAutomationRuntimeMetadata = lastAutomationRuntimeMetadata,
    lastAutomationAtEpochMs = lastAutomationAtEpochMs,
    lastCallerHint = lastCallerHint,
)

private fun AutomationIntegrationSettings.toStored(): StoredAutomationIntegration = StoredAutomationIntegration(
    enabled = enabled,
    token = token,
    runtimeStrategy = runtimeStrategy.name,
    lastAutomationStatus = lastAutomationStatus.name,
    lastAutomationError = lastAutomationError,
    lastAutomationErrorSection = lastAutomationErrorSection,
    lastAutomationErrorFieldPath = lastAutomationErrorFieldPath,
    lastAutomationRuntimeMetadata = lastAutomationRuntimeMetadata,
    lastAutomationAtEpochMs = lastAutomationAtEpochMs,
    lastCallerHint = lastCallerHint,
)

interface RuntimeStrategyOverrideGate {
    fun shouldApply(): Boolean

    fun markApplied()
}

object DisabledRuntimeStrategyOverrideGate : RuntimeStrategyOverrideGate {
    override fun shouldApply(): Boolean = false

    override fun markApplied() = Unit
}

class SharedPreferencesRuntimeStrategyOverrideGate(
    private val preferences: SharedPreferences,
    private val currentVersionCode: Int,
    private val targetVersionCode: Int = RUNTIME_STRATEGY_OVERRIDE_TARGET_VERSION_CODE,
) : RuntimeStrategyOverrideGate {
    override fun shouldApply(): Boolean {
        if (currentVersionCode != targetVersionCode) {
            return false
        }
        return preferences.getInt(RUNTIME_STRATEGY_OVERRIDE_APPLIED_VERSION_CODE_KEY, -1) != currentVersionCode
    }

    override fun markApplied() {
        if (currentVersionCode != targetVersionCode) {
            return
        }
        preferences.edit()
            .putInt(RUNTIME_STRATEGY_OVERRIDE_APPLIED_VERSION_CODE_KEY, currentVersionCode)
            .apply()
    }
}
