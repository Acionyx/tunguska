package io.acionyx.tunguska.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import io.acionyx.tunguska.domain.CanonicalJson
import kotlinx.serialization.encodeToString

class AutomationRelayActivity : ComponentActivity() {
    private val settingsRepository by lazy { AutomationIntegrationRepository(applicationContext) }
    private val statusStore by lazy { AutomationRelayStatusStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val request = validateAutomationIntent(intent)) {
            is AutomationRequest.Invalid -> {
                Log.i(TAG, "Rejecting action='${intent.action.orEmpty()}' status=${request.result.status}")
                val result = recordResult(request.result, request.callerHint)
                statusStore.markRejected(
                    action = intent.action.orEmpty(),
                    callerHint = request.callerHint,
                    result = result,
                )
                setFinalResult(result)
                finish()
            }
            is AutomationRequest.Accepted -> {
                val accepted = statusStore.markAccepted(
                    action = request.action,
                    callerHint = request.callerHint,
                )
                Log.i(TAG, "Accepted action='${request.action}' requestId=${accepted.requestId}")
                ContextCompat.startForegroundService(
                    this,
                    AutomationRelayService.buildIntent(
                        context = this,
                        action = request.action,
                        requestId = accepted.requestId,
                        callerHint = request.callerHint,
                    ),
                )
                setAcceptedResult()
                finish()
            }
        }
    }

    private fun validateAutomationIntent(intent: Intent): AutomationRequest {
        val callerHint = intent.getStringExtra(AutomationRelayContract.EXTRA_CALLER_HINT)
        val action = intent.action.orEmpty()
        val settings = settingsRepository.load()
        if (!settings.enabled) {
            return AutomationRequest.Invalid(
                result = RuntimeAutomationResult(
                    status = AutomationCommandStatus.AUTOMATION_DISABLED,
                    summary = "Anubis automation is disabled in Tunguska.",
                ),
                callerHint = callerHint,
            )
        }

        val providedToken = intent.getStringExtra(AutomationRelayContract.EXTRA_AUTOMATION_TOKEN)
        if (!settingsRepository.validateToken(providedToken)) {
            return AutomationRequest.Invalid(
                result = RuntimeAutomationResult(
                    status = AutomationCommandStatus.INVALID_TOKEN,
                    summary = "The provided automation token does not match the current Tunguska integration token.",
                ),
                callerHint = callerHint,
            )
        }

        return when (action) {
            AutomationRelayContract.ACTION_START,
            AutomationRelayContract.ACTION_STOP,
            -> AutomationRequest.Accepted(
                action = action,
                callerHint = callerHint,
            )
            else -> AutomationRequest.Invalid(
                result = RuntimeAutomationResult(
                    status = AutomationCommandStatus.CONTROL_CHANNEL_ERROR,
                    summary = "Unsupported Tunguska automation action '$action'.",
                    error = "Unsupported action",
                ),
                callerHint = callerHint,
            )
        }
    }

    private fun setAcceptedResult() {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(
                    AutomationRelayContract.EXTRA_RESULT_STATUS,
                    AutomationRelayContract.RESULT_REQUEST_ACCEPTED,
                )
            },
        )
    }

    private fun setFinalResult(result: RuntimeAutomationResult) {
        setResult(
            if (result.status == AutomationCommandStatus.SUCCESS) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().apply {
                putExtra(AutomationRelayContract.EXTRA_RESULT_STATUS, result.status.name)
                result.error?.let { putExtra(AutomationRelayContract.EXTRA_RESULT_ERROR, it) }
                result.errorSection?.let { putExtra(AutomationRelayContract.EXTRA_RESULT_ERROR_SECTION, it) }
                result.errorFieldPath?.let { putExtra(AutomationRelayContract.EXTRA_RESULT_ERROR_FIELD_PATH, it) }
                result.runtimeMetadata?.let { metadata ->
                    putExtra(
                        AutomationRelayContract.EXTRA_RESULT_RUNTIME_METADATA_JSON,
                        CanonicalJson.instance.encodeToString(metadata),
                    )
                }
            },
        )
    }

    private fun recordResult(
        result: RuntimeAutomationResult,
        callerHint: String?,
    ): RuntimeAutomationResult {
        settingsRepository.recordResult(
            status = result.status,
            error = result.error ?: result.summary.takeIf { result.status != AutomationCommandStatus.SUCCESS },
            errorSection = result.errorSection,
            errorFieldPath = result.errorFieldPath,
            runtimeMetadata = result.runtimeMetadata,
            callerHint = callerHint,
        )
        return result
    }

    private sealed interface AutomationRequest {
        data class Accepted(
            val action: String,
            val callerHint: String?,
        ) : AutomationRequest

        data class Invalid(
            val result: RuntimeAutomationResult,
            val callerHint: String?,
        ) : AutomationRequest
    }

    private companion object {
        const val TAG: String = "AutomationRelay"
    }
}
