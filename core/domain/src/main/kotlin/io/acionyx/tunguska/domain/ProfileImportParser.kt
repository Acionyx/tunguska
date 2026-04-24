package io.acionyx.tunguska.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val MAX_IMPORT_URI_CHARS: Int = 8_192

enum class ImportedProfileFormat {
    VLESS_REALITY_URI,
    JSON_PROFILE,
}

data class ImportedProfileSource(
    val rawScheme: String,
    val normalizedScheme: String,
    val format: ImportedProfileFormat,
    val summary: String,
)

data class ImportedProfile(
    val profile: ProfileIr,
    val source: ImportedProfileSource,
    val warnings: List<String> = emptyList(),
)

class ProfileImportException(
    val issues: List<ValidationIssue>,
) : IllegalArgumentException(
    buildString {
        append("Profile import failed")
        if (issues.isNotEmpty()) {
            append(": ")
            append(issues.joinToString(separator = "; ") { "${it.field}: ${it.message}" })
        }
    },
)

object ProfileImportParser {
    fun parse(rawInput: String): ImportedProfile {
        val trimmed = rawInput.trim()
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseShareUri(trimmed)
            trimmed.startsWith("ess://", ignoreCase = true) -> parseShareUri(trimmed)
            trimmed.startsWith("{") -> parseJsonProfile(trimmed)
            else -> throw ProfileImportException(
                listOf(
                    issue(
                        "import.format",
                        "Supported import formats are vless:// or ess:// REALITY share links and canonical JSON profiles.",
                    ),
                ),
            )
        }
    }

    fun parseVlessUri(rawInput: String): ImportedProfile = parseShareUri(rawInput)

    private fun parseShareUri(rawInput: String): ImportedProfile {
        val input = rawInput.trim()
        if (input.isBlank()) {
            throw ProfileImportException(listOf(issue("import.uri", "Import payload must not be blank.")))
        }
        if (input.length > MAX_IMPORT_URI_CHARS) {
            throw ProfileImportException(
                listOf(issue("import.uri", "Import payload exceeds the $MAX_IMPORT_URI_CHARS character limit.")),
            )
        }
        val normalizedInput = normalizeShareUri(input)

        val uri = try {
            URI(normalizedInput)
        } catch (error: Exception) {
            throw ProfileImportException(listOf(issue("import.uri", "Import payload is not a valid URI.")))
        }

        val issues = mutableListOf<ValidationIssue>()
        val scheme = uri.scheme?.lowercase()
        if (scheme !in SUPPORTED_URI_SCHEMES) {
            issues += issue("import.scheme", "Only vless:// and ess:// REALITY share links are supported in v1.")
        }

        val userInfo = decodeComponent(uri.rawUserInfo.orEmpty())
        if (userInfo.isBlank()) {
            issues += issue("import.uuid", "VLESS URI must contain a UUID userinfo component.")
        }

        val host = uri.host.orEmpty()
        if (host.isBlank()) {
            issues += issue("import.address", "VLESS URI must contain a server host.")
        }

        val port = uri.port
        if (port !in 1..65535) {
            issues += issue("import.port", "VLESS URI must contain a port between 1 and 65535.")
        }

        val query = parseQuery(uri.rawQuery.orEmpty())
        val realitySecurity = query["security"]?.lowercase()
        if (realitySecurity != "reality") {
            issues += issue("import.security", "Only REALITY-secured VLESS URIs are accepted.")
        }

        val transportType = query["type"]?.lowercase()
        if (transportType != null && transportType != "tcp") {
            issues += issue("import.type", "Only TCP VLESS transport is accepted in v1.")
        }

        val encryption = query["encryption"]?.lowercase()
        if (encryption != null && encryption != "none") {
            issues += issue("import.encryption", "VLESS encryption must be omitted or set to 'none'.")
        }

        val flow = query["flow"]
        if (flow != null && flow != REQUIRED_VLESS_FLOW) {
            issues += issue("import.flow", "Only $REQUIRED_VLESS_FLOW is accepted in v1.")
        }

        if (isTruthy(query["allowInsecure"]) || isTruthy(query["insecure"])) {
            issues += issue("import.tls", "Insecure TLS flags are rejected.")
        }

        val serverName = query["sni"] ?: query["serverName"]
        if (serverName.isNullOrBlank()) {
            issues += issue("import.sni", "REALITY imports must specify an SNI/serverName.")
        }

        val publicKey = query["pbk"] ?: query["publicKey"]
        if (publicKey.isNullOrBlank()) {
            issues += issue("import.realityPublicKey", "REALITY imports must specify a public key.")
        }

        val shortId = query["sid"] ?: query["shortId"]
        if (shortId.isNullOrBlank()) {
            issues += issue("import.realityShortId", "REALITY imports must specify a short id.")
        }

        val spiderX = query["spx"] ?: query["spiderX"]

        if (issues.isNotEmpty()) {
            throw ProfileImportException(issues)
        }

        val warnings = mutableListOf<String>()
        if (normalizedInput != input) {
            warnings += "Normalized raw spaces in the imported share link."
        }
        val ignoredKeys = query.keys - SUPPORTED_QUERY_KEYS
        if (ignoredKeys.isNotEmpty()) {
            warnings += "Ignored unsupported query parameters: ${ignoredKeys.sorted().joinToString()}."
        }

        val name = decodeComponent(uri.rawFragment.orEmpty()).ifBlank {
            warnings += "URI fragment missing; using server host as the profile name."
            host
        }

        val profile = ProfileIr(
            id = "import-${CanonicalJson.sha256Hex(input).take(12)}",
            name = name,
            outbound = VlessRealityOutbound(
                address = host,
                port = port,
                uuid = userInfo,
                serverName = serverName.orEmpty(),
                realityPublicKey = publicKey.orEmpty(),
                realityShortId = shortId.orEmpty(),
                realitySpiderX = spiderX,
                flow = flow,
                utlsFingerprint = query["fp"] ?: query["fingerprint"] ?: "chrome",
            ),
            routing = RoutingPolicy(
                regionalBypass = defaultRegionalBypass(),
            ),
        )

        val validationIssues = profile.validate()
        if (validationIssues.isNotEmpty()) {
            throw ProfileImportException(validationIssues)
        }

        return ImportedProfile(
            profile = profile,
            source = ImportedProfileSource(
                rawScheme = scheme.orEmpty(),
                normalizedScheme = "vless",
                format = ImportedProfileFormat.VLESS_REALITY_URI,
                summary = when (scheme) {
                    "ess" -> "Validated an ess:// alias as a VLESS + REALITY share link."
                    else -> "Validated a VLESS + REALITY share link."
                },
            ),
            warnings = warnings.toList(),
        )
    }

    private fun normalizeShareUri(value: String): String = value.replace(" ", "%20")

    private fun parseJsonProfile(rawInput: String): ImportedProfile {
        val input = rawInput.trim()
        if (input.length > MAX_IMPORT_URI_CHARS) {
            throw ProfileImportException(
                listOf(issue("import.json", "Import payload exceeds the $MAX_IMPORT_URI_CHARS character limit.")),
            )
        }

        val rawJson = try {
            CanonicalJson.instance.decodeFromString<JsonObject>(input)
        } catch (_: Exception) {
            throw ProfileImportException(listOf(issue("import.json", "Import payload is not valid ProfileIr JSON.")))
        }

        val profile = try {
            CanonicalJson.instance.decodeFromString<ProfileIr>(input)
        } catch (_: Exception) {
            throw ProfileImportException(listOf(issue("import.json", "Import payload is not valid ProfileIr JSON.")))
        }
        val profileWithDefaults = if (rawJson.routingContainsRegionalBypass()) {
            profile
        } else {
            profile.copy(
                routing = profile.routing.copy(
                    regionalBypass = defaultRegionalBypass(),
                ),
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        issues += profileWithDefaults.validate()
        if (!profileWithDefaults.safety.safeMode) {
            issues += issue("import.safety.safeMode", "Imported profiles must keep safe mode enabled in v1.")
        }
        if (profileWithDefaults.safety.compatibilityLocalProxy) {
            issues += issue(
                "import.safety.compatibilityLocalProxy",
                "Imported profiles cannot enable compatibility localhost proxy mode.",
            )
        }
        if (profileWithDefaults.safety.debugEndpointsEnabled) {
            issues += issue(
                "import.safety.debugEndpointsEnabled",
                "Imported profiles cannot enable debug endpoints.",
            )
        }
        if (issues.isNotEmpty()) {
            throw ProfileImportException(issues)
        }

        return ImportedProfile(
            profile = profileWithDefaults,
            source = ImportedProfileSource(
                rawScheme = "json",
                normalizedScheme = "json",
                format = ImportedProfileFormat.JSON_PROFILE,
                summary = "Validated a canonical JSON profile.",
            ),
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) {
            return emptyMap()
        }
        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = decodeComponent(parts[0])
                val value = decodeComponent(parts.getOrElse(1) { "" })
                key to value
            }
    }

    private fun decodeComponent(value: String): String {
        return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }

    private fun isTruthy(value: String?): Boolean {
        return value?.lowercase() in setOf("1", "true", "yes", "on")
    }

    private fun issue(field: String, message: String): ValidationIssue = ValidationIssue(field, message)

    private val SUPPORTED_QUERY_KEYS = setOf(
        "allowInsecure",
        "encryption",
        "fingerprint",
        "flow",
        "fp",
        "insecure",
        "pbk",
        "publicKey",
        "security",
        "serverName",
        "shortId",
        "sid",
        "spiderX",
        "spx",
        "sni",
        "type",
    )

    private val SUPPORTED_URI_SCHEMES = setOf("vless", "ess")
}

private fun JsonObject.routingContainsRegionalBypass(): Boolean {
    val routing = this["routing"]?.jsonObject ?: return false
    return routing.containsKey("regionalBypass")
}
