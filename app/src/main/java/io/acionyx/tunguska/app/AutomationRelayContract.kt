package io.acionyx.tunguska.app

object AutomationRelayContract {
    const val ACTION_START: String = "io.acionyx.tunguska.action.AUTOMATION_START"
    const val ACTION_STOP: String = "io.acionyx.tunguska.action.AUTOMATION_STOP"
    const val RESULT_REQUEST_ACCEPTED: String = "REQUEST_ACCEPTED"

    const val EXTRA_AUTOMATION_TOKEN: String = "automation_token"
    const val EXTRA_CALLER_HINT: String = "caller_hint"
    const val EXTRA_RESULT_STATUS: String = "automation_status"
    const val EXTRA_RESULT_ERROR: String = "automation_error"
    const val EXTRA_RESULT_ERROR_SECTION: String = "automation_error_section"
    const val EXTRA_RESULT_ERROR_FIELD_PATH: String = "automation_error_field_path"
    const val EXTRA_RESULT_RUNTIME_METADATA_JSON: String = "automation_runtime_metadata_json"
}
