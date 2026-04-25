package io.acionyx.tunguska.app

private const val ROUTING_RULES_FIELD_PATH = "(routing.rules)"
private const val DNS_ENDPOINTS_FIELD_PATH = "(dns.endpoints)"

internal fun runtimeFailureDisplayText(
    rawError: String?,
    section: String? = null,
    fieldPath: String? = null,
): String? {
    if (rawError == null) {
        return null
    }
    val normalizedFieldPath = fieldPath
        ?: when (section) {
            "Routing" -> "routing.rules"
            "DNS" -> "dns.endpoints"
            else -> null
        }
        ?: when {
            rawError.contains(ROUTING_RULES_FIELD_PATH) -> "routing.rules"
            rawError.contains(DNS_ENDPOINTS_FIELD_PATH) -> "dns.endpoints"
            else -> null
        }
    val guidance = when (normalizedFieldPath) {
        "routing.rules" -> "Review Routing in the profile editor."
        "dns.endpoints" -> "Review DNS handling in the profile editor."
        else -> null
    }
    val normalized = rawError
        .replace(ROUTING_RULES_FIELD_PATH, "")
        .replace(DNS_ENDPOINTS_FIELD_PATH, "")
        .replace("  ", " ")
        .replace("section :", "section:")
        .trim()
    return if (guidance != null) {
        "$normalized $guidance"
    } else {
        normalized
    }
}

internal fun runtimeFailureDiagnosticsRows(
    rawError: String?,
    section: String? = null,
    fieldPath: String? = null,
): List<Pair<String, String>> = buildList {
    runtimeFailureDisplayText(
        rawError = rawError,
        section = section,
        fieldPath = fieldPath,
    )?.let { rendered ->
        add("Failure guidance" to rendered)
    }
    section?.takeIf(String::isNotBlank)?.let { add("Error section" to it) }
    fieldPath?.takeIf(String::isNotBlank)?.let { add("Error field path" to it) }
}