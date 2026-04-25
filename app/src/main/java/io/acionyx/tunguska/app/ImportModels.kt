package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.ImportedProfile
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId

enum class ImportCaptureSource {
    MANUAL_TEXT,
    CAMERA_QR,
    IMAGE_QR,
}

data class ImportPreviewState(
    val source: ImportCaptureSource,
    val normalizedSourceSummary: String,
    val sourceScheme: String,
    val profileName: String,
    val protocolId: OutboundProtocolId,
    val shapeLabel: String,
    val protocolLabel: String,
    val transportLabel: String,
    val securityLabel: String,
    val endpointSummary: String,
    val profileHash: String,
    val compatibilityTitle: String,
    val compatibilityDetails: List<String> = emptyList(),
    val compatibilitySeverity: StrategyCompatibilitySeverity = StrategyCompatibilitySeverity.READY,
    val recommendedStrategyId: EmbeddedRuntimeStrategyId? = null,
    val recommendation: String? = null,
    val warnings: List<String> = emptyList(),
)

fun ImportedProfile.toImportPreviewState(
    source: ImportCaptureSource,
    selectedStrategyId: EmbeddedRuntimeStrategyId,
): ImportPreviewState {
    val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
        profile = profile,
        strategyId = selectedStrategyId,
    )
    val outboundSummary = profile.outboundSummary
    return ImportPreviewState(
        source = source,
        normalizedSourceSummary = this.source.summary,
        sourceScheme = this.source.rawScheme.ifBlank { this.source.normalizedScheme },
        profileName = profile.name,
        protocolId = outboundSummary.protocolId,
        shapeLabel = outboundSummary.shapeLabel,
        protocolLabel = outboundSummary.protocolLabel,
        transportLabel = outboundSummary.transportLabel,
        securityLabel = outboundSummary.securityLabel,
        endpointSummary = "${outboundSummary.endpoint.address}:${outboundSummary.endpoint.port}",
        profileHash = profile.canonicalHash(),
        compatibilityTitle = guidance.selectedSummary.title,
        compatibilityDetails = guidance.selectedSummary.details,
        compatibilitySeverity = guidance.selectedSummary.severity,
        recommendedStrategyId = guidance.recommendedStrategyId.takeIf { it != selectedStrategyId },
        recommendation = guidance.recommendation,
        warnings = warnings,
    )
}

fun ImportCaptureSource.summary(): String = when (this) {
    ImportCaptureSource.MANUAL_TEXT -> "manual share link"
    ImportCaptureSource.CAMERA_QR -> "camera QR scan"
    ImportCaptureSource.IMAGE_QR -> "image QR scan"
}
