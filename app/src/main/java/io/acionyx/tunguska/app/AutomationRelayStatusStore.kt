package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.storage.EncryptedArtifactStore
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val AUTOMATION_STATUS_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.automation.status.master"
private const val AUTOMATION_STATUS_RELATIVE_PATH: String = "automation/relay-status.json.enc"
private const val AUTOMATION_STATUS_ARTIFACT_TYPE: String = "automation_status"

class AutomationRelayStatusStore(
    path: Path,
    cipherBox: CipherBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = EncryptedArtifactStore(
        path = path,
        cipherBox = cipherBox,
        clock = clock,
    )
    private val lock = Any()

    constructor(
        context: Context,
        cipherBox: CipherBox = AndroidKeyStoreCipherBox(AUTOMATION_STATUS_MASTER_KEY_ALIAS),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        path = context.filesDir.toPath().resolve(AUTOMATION_STATUS_RELATIVE_PATH),
        cipherBox = cipherBox,
        clock = clock,
    ) {
        deleteLegacyPlaintextStatus(context)
    }

    val storagePath: String = store.path.toString()
    val keyReference: String = "android-keystore:$AUTOMATION_STATUS_MASTER_KEY_ALIAS"

    fun load(): AutomationRelayStatusRecord? = synchronized(lock) {
        store.load()?.let { stored ->
            CanonicalJson.instance.decodeFromString<AutomationRelayStatusRecord>(stored.payloadJson)
        }
    }

    fun markAccepted(
        action: String,
        callerHint: String?,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = UUID.randomUUID().toString(),
                action = action,
                status = AutomationRelayProgressStatus.REQUEST_ACCEPTED,
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markCompleted(
        accepted: AutomationRelayStatusRecord,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return markCompleted(
            requestId = accepted.requestId,
            action = accepted.action,
            callerHint = accepted.callerHint,
            result = result,
        )
    }

    fun markProgress(
        requestId: String,
        action: String,
        callerHint: String?,
        summary: String,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = requestId,
                action = action,
                status = AutomationRelayProgressStatus.REQUEST_ACCEPTED,
                summary = summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markCompleted(
        requestId: String,
        action: String,
        callerHint: String?,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = requestId,
                action = action,
                status = result.status.name,
                runtimePhase = result.snapshot?.phase?.name,
                error = result.error,
                errorSection = result.errorSection,
                errorFieldPath = result.errorFieldPath,
                runtimeMetadata = result.runtimeMetadata,
                summary = result.summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markRejected(
        action: String,
        callerHint: String?,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = UUID.randomUUID().toString(),
                action = action.ifBlank { AutomationRelayProgressStatus.UNSPECIFIED_ACTION },
                status = result.status.name,
                runtimePhase = result.snapshot?.phase?.name,
                error = result.error,
                errorSection = result.errorSection,
                errorFieldPath = result.errorFieldPath,
                runtimeMetadata = result.runtimeMetadata,
                summary = result.summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    private fun write(record: AutomationRelayStatusRecord): AutomationRelayStatusRecord = synchronized(lock) {
        store.save(
            artifactType = AUTOMATION_STATUS_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(record),
            redacted = true,
        )
        record
    }

    private fun deleteLegacyPlaintextStatus(context: Context) {
        sequenceOf(
            context.externalMediaDirs.firstOrNull()?.resolve("automation")?.resolve(LEGACY_STATUS_FILE_NAME),
            context.getExternalFilesDir("automation")?.resolve(LEGACY_STATUS_FILE_NAME),
        )
            .filterNotNull()
            .forEach { candidate ->
                runCatching {
                    if (candidate.exists()) {
                        candidate.delete()
                    }
                }
            }
    }
}

private object AutomationRelayProgressStatus {
    const val REQUEST_ACCEPTED: String = "REQUEST_ACCEPTED"
    const val UNSPECIFIED_ACTION: String = "UNSPECIFIED_ACTION"
}

private const val LEGACY_STATUS_FILE_NAME: String = "relay-status.json"

@Serializable
data class AutomationRelayStatusRecord(
    val requestId: String,
    val action: String,
    val status: String,
    val runtimePhase: String? = null,
    val error: String? = null,
    val errorSection: String? = null,
    val errorFieldPath: String? = null,
    val runtimeMetadata: AutomationRuntimeMetadata? = null,
    val summary: String? = null,
    val callerHint: String? = null,
    val updatedAtEpochMs: Long,
)
