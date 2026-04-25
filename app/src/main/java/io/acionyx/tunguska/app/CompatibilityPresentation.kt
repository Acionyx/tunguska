package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.RuntimeLaneCompatibilityMetadata
import io.acionyx.tunguska.vpnservice.RuntimeConfigSource

internal data class CompatibilityBadgeSet(
    val selectedLaneLabel: String,
    val statusLabel: String,
    val recommendedLaneLabel: String? = null,
)

internal data class RuntimeLaneSummaryLabels(
    val activeLaneLabel: String,
    val nextRestageLaneLabel: String? = null,
    val statusLabel: String,
    val recommendedLaneLabel: String? = null,
    val restageHint: String? = null,
)

internal data class RuntimeLaneDetail(
    val statusLabel: String,
    val summaryTitle: String,
    val recommendedLaneLabel: String? = null,
    val recommendation: String? = null,
)

internal data class RuntimeLanePresentation(
    val summaryLabels: RuntimeLaneSummaryLabels,
    val selectedSummaryTitle: String,
    val selectedSummaryDetails: List<String> = emptyList(),
    val recommendation: String? = null,
    val nextStartDetail: RuntimeLaneDetail? = null,
)

internal data class RuntimeSelectionState(
    val configuredStrategyId: EmbeddedRuntimeStrategyId,
    val activeStrategyId: EmbeddedRuntimeStrategyId? = null,
    val activeLaneLabel: String,
    val nextRestageLaneLabel: String? = null,
    val selectedSeverity: StrategyCompatibilitySeverity,
    val statusLabel: String,
    val recommendedLaneLabel: String? = null,
    val restageHint: String? = null,
    val selectedSummaryTitle: String,
    val selectedSummaryDetails: List<String> = emptyList(),
    val recommendation: String? = null,
    val nextStartDetail: RuntimeLaneDetail? = null,
    val homeStatusHint: String? = null,
    val connectDecisionHint: String? = null,
)

internal data class ConfiguredSelectionState(
    val selectedStrategyId: EmbeddedRuntimeStrategyId,
    val selectedLaneLabel: String,
    val selectedSeverity: StrategyCompatibilitySeverity,
    val statusLabel: String,
    val recommendedStrategyId: EmbeddedRuntimeStrategyId? = null,
    val recommendedLaneLabel: String? = null,
    val selectedSummaryTitle: String,
    val selectedSummaryDetails: List<String> = emptyList(),
    val recommendation: String? = null,
)

enum class ConnectDecisionPhase {
    STARTING,
    STARTED,
    FAILED,
}

internal data class ConnectDecisionState(
    val phase: ConnectDecisionPhase,
    val message: String,
)

internal fun profileCompatibilityBadges(
    selectedStrategyId: EmbeddedRuntimeStrategyId,
    guidance: ProfileCapabilityGuidance,
): CompatibilityBadgeSet = CompatibilityBadgeSet(
    selectedLaneLabel = when (selectedStrategyId) {
        EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> "sing-box embedded"
        EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> "xray+tun2socks"
    },
    statusLabel = when (guidance.selectedSummary.severity) {
        StrategyCompatibilitySeverity.READY -> "Ready"
        StrategyCompatibilitySeverity.ATTENTION -> "Has limits"
    },
    recommendedLaneLabel = guidance.recommendedStrategyId
        .takeIf { it != selectedStrategyId }
        ?.let {
            when (it) {
                EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> "Recommended: sing-box"
                EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> "Recommended: xray+tun2socks"
            }
        },
)

internal fun importCompatibilityBadges(
    selectedStrategyId: EmbeddedRuntimeStrategyId,
    severity: StrategyCompatibilitySeverity,
    recommendedStrategyId: EmbeddedRuntimeStrategyId?,
): CompatibilityBadgeSet = CompatibilityBadgeSet(
    selectedLaneLabel = titleCaseStrategyLabel(selectedStrategyId),
    statusLabel = when {
        severity == StrategyCompatibilitySeverity.READY -> "Clean match"
        recommendedStrategyId != null -> "Fallback with limits"
        else -> "Has limits"
    },
    recommendedLaneLabel = recommendedStrategyId?.let { "Recommended: ${titleCaseStrategyLabel(it)}" },
)

internal fun runtimeLaneSummaryLabels(
    activeStrategyId: EmbeddedRuntimeStrategyId?,
    configuredStrategyId: EmbeddedRuntimeStrategyId,
    guidance: ProfileCapabilityGuidance,
    stagedMetadata: RuntimeLaneCompatibilityMetadata? = null,
    configuredMetadata: RuntimeLaneCompatibilityMetadata? = null,
): RuntimeLaneSummaryLabels = runtimeLanePresentation(
    activeStrategyId = activeStrategyId,
    configuredStrategyId = configuredStrategyId,
    guidance = guidance,
    stagedMetadata = stagedMetadata,
    configuredMetadata = configuredMetadata,
).summaryLabels

internal fun runtimeLanePresentation(
    activeStrategyId: EmbeddedRuntimeStrategyId?,
    configuredStrategyId: EmbeddedRuntimeStrategyId,
    guidance: ProfileCapabilityGuidance,
    stagedMetadata: RuntimeLaneCompatibilityMetadata? = null,
    configuredMetadata: RuntimeLaneCompatibilityMetadata? = null,
): RuntimeLanePresentation {
    val effectiveActiveStrategy = activeStrategyId ?: configuredStrategyId
    val configuredStrategyDiffers = activeStrategyId != null && activeStrategyId != configuredStrategyId
    val selectedMetadata = if (activeStrategyId != null) {
        stagedMetadata
    } else {
        configuredMetadata ?: stagedMetadata
    }
    val recommendedStrategyId = selectedMetadata?.recommendedStrategyId ?: guidance.recommendedStrategyId
    val nextStartDetail = configuredMetadata
        ?.takeIf { configuredStrategyDiffers }
        ?.let { metadata ->
            RuntimeLaneDetail(
                statusLabel = metadata.statusLabel,
                summaryTitle = metadata.selectedSummaryTitle,
                recommendedLaneLabel = metadata.recommendedStrategyId
                    ?.takeIf { it != configuredStrategyId }
                    ?.let { "Recommended: ${titleCaseStrategyLabel(it)}" },
                recommendation = metadata.recommendation,
            )
        }
    return RuntimeLanePresentation(
        summaryLabels = RuntimeLaneSummaryLabels(
            activeLaneLabel = "Active: ${titleCaseStrategyLabel(effectiveActiveStrategy)}",
            nextRestageLaneLabel = configuredStrategyId.takeIf { configuredStrategyDiffers }
                ?.let { "Next restage: ${titleCaseStrategyLabel(it)}" },
            statusLabel = selectedMetadata?.statusLabel ?: when (guidance.selectedSummary.severity) {
            StrategyCompatibilitySeverity.READY -> "Clean match"
            StrategyCompatibilitySeverity.ATTENTION -> "Fallback with limits"
            },
            recommendedLaneLabel = recommendedStrategyId
                .takeIf { it != effectiveActiveStrategy }
                ?.let { "Recommended: ${titleCaseStrategyLabel(it)}" },
            restageHint = configuredStrategyId.takeIf { configuredStrategyDiffers }
                ?.let {
                    "Configured lane differs from the active runtime. Restage or reconnect to switch the running session to ${titleCaseStrategyLabel(it)}."
                },
        ),
        selectedSummaryTitle = selectedMetadata?.selectedSummaryTitle ?: guidance.selectedSummary.title,
        selectedSummaryDetails = guidance.selectedSummary.details,
        recommendation = selectedMetadata?.recommendation ?: guidance.recommendation,
        nextStartDetail = nextStartDetail,
    )
}

internal fun homeRuntimeLaneHint(
    activeStrategyId: EmbeddedRuntimeStrategyId?,
    configuredStrategyId: EmbeddedRuntimeStrategyId,
    guidance: ProfileCapabilityGuidance,
    stagedMetadata: RuntimeLaneCompatibilityMetadata? = null,
    configuredMetadata: RuntimeLaneCompatibilityMetadata? = null,
): String? {
    val presentation = runtimeLanePresentation(
        activeStrategyId = activeStrategyId,
        configuredStrategyId = configuredStrategyId,
        guidance = guidance,
        stagedMetadata = stagedMetadata,
        configuredMetadata = configuredMetadata,
    )
    val summary = presentation.summaryLabels
    return when {
        summary.nextRestageLaneLabel != null -> homeHintText(
            summary.nextRestageLaneLabel,
            presentation.nextStartDetail?.summaryTitle,
            presentation.nextStartDetail?.recommendation,
        )
        summary.statusLabel != "Clean match" -> homeHintText(
            presentation.selectedSummaryTitle,
            presentation.recommendation,
                summary.recommendedLaneLabel,
        ) ?: summary.statusLabel
        else -> null
    }
}

internal fun runtimeSelectionState(
    profile: ProfileIr,
    activeStrategyId: EmbeddedRuntimeStrategyId?,
    configuredStrategyId: EmbeddedRuntimeStrategyId,
    stagedMetadata: RuntimeLaneCompatibilityMetadata? = null,
    configuredMetadata: RuntimeLaneCompatibilityMetadata? = null,
): RuntimeSelectionState {
    val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
        profile = profile,
        strategyId = activeStrategyId ?: configuredStrategyId,
    )
    val presentation = runtimeLanePresentation(
        activeStrategyId = activeStrategyId,
        configuredStrategyId = configuredStrategyId,
        guidance = guidance,
        stagedMetadata = stagedMetadata,
        configuredMetadata = configuredMetadata,
    )
    val summary = presentation.summaryLabels
    return RuntimeSelectionState(
        configuredStrategyId = configuredStrategyId,
        activeStrategyId = activeStrategyId,
        activeLaneLabel = summary.activeLaneLabel,
        nextRestageLaneLabel = summary.nextRestageLaneLabel,
        selectedSeverity = guidance.selectedSummary.severity,
        statusLabel = summary.statusLabel,
        recommendedLaneLabel = summary.recommendedLaneLabel,
        restageHint = summary.restageHint,
        selectedSummaryTitle = presentation.selectedSummaryTitle,
        selectedSummaryDetails = presentation.selectedSummaryDetails,
        recommendation = presentation.recommendation,
        nextStartDetail = presentation.nextStartDetail,
        homeStatusHint = activeStrategyId?.let {
            homeRuntimeLaneHint(
                activeStrategyId = activeStrategyId,
                configuredStrategyId = configuredStrategyId,
                guidance = guidance,
                stagedMetadata = stagedMetadata,
                configuredMetadata = configuredMetadata,
            )
        },
        connectDecisionHint = if (activeStrategyId == null && summary.statusLabel != "Clean match") {
            homeHintText(
                presentation.selectedSummaryTitle,
                presentation.recommendation,
                summary.recommendedLaneLabel,
            )
        } else {
            null
        },
    )
}

internal fun configuredSelectionState(
    profile: ProfileIr,
    selectedStrategyId: EmbeddedRuntimeStrategyId,
): ConfiguredSelectionState {
    val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
        profile = profile,
        strategyId = selectedStrategyId,
    )
    val badges = profileCompatibilityBadges(
        selectedStrategyId = selectedStrategyId,
        guidance = guidance,
    )
    return ConfiguredSelectionState(
        selectedStrategyId = selectedStrategyId,
        selectedLaneLabel = badges.selectedLaneLabel,
        selectedSeverity = guidance.selectedSummary.severity,
        statusLabel = badges.statusLabel,
        recommendedStrategyId = guidance.recommendedStrategyId.takeIf { it != selectedStrategyId },
        recommendedLaneLabel = badges.recommendedLaneLabel,
        selectedSummaryTitle = guidance.selectedSummary.title,
        selectedSummaryDetails = guidance.selectedSummary.details,
        recommendation = guidance.recommendation,
    )
}

internal fun importConfiguredSelectionState(
    selectedStrategyId: EmbeddedRuntimeStrategyId,
    severity: StrategyCompatibilitySeverity,
    selectedSummaryTitle: String,
    selectedSummaryDetails: List<String> = emptyList(),
    recommendedStrategyId: EmbeddedRuntimeStrategyId? = null,
    recommendation: String? = null,
): ConfiguredSelectionState {
    val badges = importCompatibilityBadges(
        selectedStrategyId = selectedStrategyId,
        severity = severity,
        recommendedStrategyId = recommendedStrategyId,
    )
    return ConfiguredSelectionState(
        selectedStrategyId = selectedStrategyId,
        selectedLaneLabel = badges.selectedLaneLabel,
        selectedSeverity = severity,
        statusLabel = badges.statusLabel,
        recommendedStrategyId = recommendedStrategyId,
        recommendedLaneLabel = badges.recommendedLaneLabel,
        selectedSummaryTitle = selectedSummaryTitle,
        selectedSummaryDetails = selectedSummaryDetails,
        recommendation = recommendation,
    )
}

internal fun connectDecisionState(
    selection: ConfiguredSelectionState,
    phase: ConnectDecisionPhase,
): ConnectDecisionState? {
    if (selection.recommendedStrategyId == null) {
        return null
    }
    val consequence = homeHintText(
        selection.selectedSummaryTitle,
        selection.recommendation,
        selection.recommendedLaneLabel,
    ) ?: return null
    val outcomePrefix = when (phase) {
        ConnectDecisionPhase.STARTING -> "Starting ${titleCaseStrategyLabel(selection.selectedStrategyId)}"
        ConnectDecisionPhase.STARTED -> "Started on ${titleCaseStrategyLabel(selection.selectedStrategyId)}"
        ConnectDecisionPhase.FAILED -> "Start failed on ${titleCaseStrategyLabel(selection.selectedStrategyId)}"
    }
    return ConnectDecisionState(
        phase = phase,
        message = homeHintText(outcomePrefix, consequence) ?: outcomePrefix,
    )
}

internal fun stagedRuntimeLaneCompatibilityMetadata(
    selectedStrategyId: EmbeddedRuntimeStrategyId,
    guidance: ProfileCapabilityGuidance,
): RuntimeLaneCompatibilityMetadata = RuntimeLaneCompatibilityMetadata(
    statusLabel = when {
        guidance.selectedSummary.severity == StrategyCompatibilitySeverity.READY -> "Clean match"
        guidance.recommendedStrategyId != selectedStrategyId -> "Fallback with limits"
        else -> "Has limits"
    },
    selectedSummaryTitle = guidance.selectedSummary.title,
    recommendedStrategyId = guidance.recommendedStrategyId,
    recommendation = guidance.recommendation,
)

private fun titleCaseStrategyLabel(strategyId: EmbeddedRuntimeStrategyId): String = when (strategyId) {
    EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> "Xray + tun2socks"
    EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> "Sing-box embedded"
}

internal fun runtimeConfigSourceLabel(source: RuntimeConfigSource): String = when (source) {
    RuntimeConfigSource.CANONICAL_PROFILE_REBUILD -> "Canonical profile rebuild"
    RuntimeConfigSource.STAGED_ENGINE_PAYLOAD -> "Staged engine payload"
}

internal fun runtimeConfigSourceSummary(source: RuntimeConfigSource): String = when (source) {
    RuntimeConfigSource.CANONICAL_PROFILE_REBUILD ->
        "Rebuilds the canonical profile into the xray+tun2socks compatibility runtime config."
    RuntimeConfigSource.STAGED_ENGINE_PAYLOAD ->
        "Runs the staged sing-box engine payload directly."
}

private fun homeHintText(vararg parts: String?): String? {
    val normalizedParts = parts
        .mapNotNull { part -> part?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } }
        .distinct()
    if (normalizedParts.isEmpty()) {
        return null
    }
    return normalizedParts.joinToString(separator = ". ", postfix = ".")
}