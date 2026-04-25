package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EffectiveRoutingPolicyResolver
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.outboundProtocolLabel
import io.acionyx.tunguska.domain.outboundSecurityLabel
import io.acionyx.tunguska.domain.outboundShapeLabel
import io.acionyx.tunguska.domain.outboundTransportLabel
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.netpolicy.RoutePreviewOutcome
import io.acionyx.tunguska.storage.EncryptedArtifactStore
import io.acionyx.tunguska.storage.LoadedArtifact
import io.acionyx.tunguska.storage.StoredArtifact
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import io.acionyx.tunguska.vpnservice.profileShapeLabel
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val EXPORT_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.export.master"
private const val EXPORT_ROOT_RELATIVE_PATH: String = "exports"

class SecureExportRepository(
    private val rootDir: Path,
    private val cipherBox: CipherBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        cipherBox: CipherBox = AndroidKeyStoreCipherBox(EXPORT_MASTER_KEY_ALIAS),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        rootDir = context.filesDir.toPath().resolve(EXPORT_ROOT_RELATIVE_PATH),
        cipherBox = cipherBox,
        clock = clock,
    )

    val exportRootPath: String = rootDir.toAbsolutePath().normalize().toString()
    val keyReference: String = "android-keystore:$EXPORT_MASTER_KEY_ALIAS"

    fun exportEncryptedProfileBackup(
        profile: ProfileIr,
        compiledConfig: CompiledEngineConfig,
    ): ExportArtifactRecord {
        val createdAtEpochMs = clock()
        val payload = ProfileBackupPayload(
            createdAtEpochMs = createdAtEpochMs,
            profileHash = profile.canonicalHash(),
            compiledConfigHash = compiledConfig.configHash,
            engineId = compiledConfig.engineId,
            configFormat = compiledConfig.format,
            profile = profile,
        )
        return writeArtifact(
            artifactType = PROFILE_BACKUP_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(payload),
            redacted = false,
            summary = "Wrote an encrypted full-profile backup envelope to app-private exports.",
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun exportRedactedDiagnosticBundle(
        profile: ProfileIr,
        compiledConfig: CompiledEngineConfig,
        tunnelPlanSummary: TunnelPlanSummary,
        runtimeSnapshot: VpnRuntimeSnapshot,
        profileStorage: ProfileStorageState,
        automationState: AutomationState,
        routePreview: PreviewInputs,
        previewOutcome: RoutePreviewOutcome,
    ): ExportArtifactRecord {
        val createdAtEpochMs = clock()
        val payload = DiagnosticBundlePayload(
            createdAtEpochMs = createdAtEpochMs,
            profileHash = profile.canonicalHash(),
            compiledConfigHash = compiledConfig.configHash,
            engineId = compiledConfig.engineId,
            configFormat = compiledConfig.format,
            profile = profile.toRedactedReport(),
            runtime = runtimeSnapshot.toRedactedReport(
                profile = profile,
                configuredStrategyId = automationState.runtimeStrategy,
            ),
            storage = profileStorage.toRedactedReport(),
            automation = automationState.toRedactedReport(),
            tunnelPlan = tunnelPlanSummary,
            routePreview = RedactedRoutePreviewReport(
                packageHash = routePreview.packageName.takeIf { it.isNotBlank() }?.redactedDigest(),
                destinationHostHash = routePreview.destinationHost.takeIf { it.isNotBlank() }?.redactedDigest(),
                destinationIpHash = routePreview.destinationIp.takeIf { it.isNotBlank() }?.redactedDigest(),
                destinationPort = routePreview.destinationPort.toIntOrNull(),
                action = previewOutcome.action.name,
                matchedRuleHash = previewOutcome.matchedRuleId?.redactedDigest(),
                runtimeDatasetHint = previewOutcome.runtimeDatasetHint,
            ),
        )
        return writeArtifact(
            artifactType = DIAGNOSTIC_BUNDLE_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(payload),
            redacted = true,
            summary = "Wrote a redacted audit bundle with runtime status and deterministic hashes.",
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun loadArtifact(path: Path): LoadedArtifact {
        return checkNotNull(buildStore(path).load()) {
            "No encrypted artifact is stored at ${path.toAbsolutePath().normalize()}."
        }
    }

    private fun writeArtifact(
        artifactType: String,
        payloadJson: String,
        redacted: Boolean,
        summary: String,
        createdAtEpochMs: Long,
    ): ExportArtifactRecord {
        val path = rootDir.resolve(buildFileName(artifactType, createdAtEpochMs, payloadJson))
        val stored = buildStore(path).save(
            artifactType = artifactType,
            payloadJson = payloadJson,
            redacted = redacted,
        )
        return stored.toRecord(summary)
    }

    private fun buildStore(path: Path): EncryptedArtifactStore = EncryptedArtifactStore(
        path = path,
        cipherBox = cipherBox,
        clock = clock,
    )

    private fun buildFileName(
        artifactType: String,
        createdAtEpochMs: Long,
        payloadJson: String,
    ): String = "$artifactType-$createdAtEpochMs-${CanonicalJson.sha256Hex(payloadJson).take(12)}.json.enc"

    private fun StoredArtifact.toRecord(summary: String): ExportArtifactRecord = ExportArtifactRecord(
        artifactType = artifactType,
        redacted = redacted,
        payloadHash = payloadHash,
        createdAtEpochMs = createdAtEpochMs,
        path = path.toString(),
        keyReference = keyReference,
        summary = summary,
    )

    companion object {
        private const val PROFILE_BACKUP_ARTIFACT_TYPE: String = "profile_backup"
        private const val DIAGNOSTIC_BUNDLE_ARTIFACT_TYPE: String = "diagnostic_bundle"
    }
}

data class ExportArtifactRecord(
    val artifactType: String,
    val redacted: Boolean,
    val payloadHash: String,
    val createdAtEpochMs: Long,
    val path: String,
    val keyReference: String?,
    val summary: String,
)

@Serializable
data class TunnelPlanSummary(
    val processNameSuffix: String,
    val preserveLoopback: Boolean,
    val splitTunnelMode: String,
    val allowedPackageCount: Int,
    val disallowedPackageCount: Int,
    val runtimeMode: String,
)

@Serializable
private data class ProfileBackupPayload(
    val createdAtEpochMs: Long,
    val profileHash: String,
    val compiledConfigHash: String,
    val engineId: String,
    val configFormat: String,
    val profile: ProfileIr,
)

@Serializable
private data class DiagnosticBundlePayload(
    val createdAtEpochMs: Long,
    val profileHash: String,
    val compiledConfigHash: String,
    val engineId: String,
    val configFormat: String,
    val profile: RedactedProfileReport,
    val runtime: RedactedRuntimeReport,
    val storage: RedactedStorageReport,
    val automation: RedactedAutomationReport,
    val tunnelPlan: TunnelPlanSummary,
    val routePreview: RedactedRoutePreviewReport,
)

@Serializable
private data class RedactedProfileReport(
    val profileIdHash: String,
    val profileName: String,
    val outboundShape: String,
    val outboundProtocol: String,
    val outboundTransport: String,
    val outboundSecurity: String,
    val outboundAddressHash: String,
    val outboundPort: Int,
    val serverNameHash: String,
    val uuidHash: String,
    val realityPublicKeyHash: String,
    val realityShortIdHash: String,
    val flow: String?,
    val utlsFingerprint: String,
    val splitTunnelMode: String,
    val allowPackageCount: Int,
    val denyPackageCount: Int,
    val routingDefaultAction: String,
    val proxyRuleCount: Int,
    val directRuleCount: Int,
    val blockRuleCount: Int,
    val regionalBypassPresetCount: Int,
    val regionalBypassPresetIds: List<String>,
    val regionalBypassCustomDirectCount: Int,
    val generatedRegionalRuleCount: Int,
    val dnsMode: String,
    val dnsEndpointHashes: List<String>,
    val safeMode: Boolean,
    val compatibilityLocalProxy: Boolean,
    val debugEndpointsEnabled: Boolean,
)

@Serializable
private data class RedactedRuntimeReport(
    val phase: String,
    val configHash: String?,
    val sessionLabelHash: String?,
    val activeStrategy: String?,
    val configuredStrategy: String,
    val compilerPath: String?,
    val compilerPathSummary: String?,
    val nextStartCompilerPath: String?,
    val nextStartCompilerPathSummary: String?,
    val profileShape: String?,
    val profileProtocolId: String?,
    val profileTransportId: String?,
    val profileSecurityId: String?,
    val engineId: String?,
    val engineFormat: String?,
    val compiledPayloadBytes: Int,
    val allowCount: Int,
    val denyCount: Int,
    val routeCount: Int,
    val excludedRouteCount: Int,
    val mtu: Int?,
    val runtimeMode: String,
    val auditStatus: String,
    val auditFindingCount: Int,
    val bootstrapStatus: String,
    val engineHostStatus: String,
    val engineSessionStatus: String,
    val engineSessionHealthStatus: String,
    val bridgePort: Int?,
    val xrayPid: Long?,
    val tun2socksPid: Long?,
    val ownPackageBypassesVpn: Boolean,
    val routedTrafficObserved: Boolean,
    val lastRoutedTrafficAtEpochMs: Long?,
    val dnsFailureObserved: Boolean,
    val lastDnsFailureSummary: String?,
    val recentXrayLogLines: List<String>,
    val recentNativeEvents: List<String>,
    val sessionWorkspacePresent: Boolean,
    val lastError: String?,
    val lastErrorSection: String?,
    val lastErrorFieldPath: String?,
    val laneStatus: String,
    val laneSummaryTitle: String,
    val recommendedStrategy: String?,
    val laneRecommendation: String?,
    val nextStartLaneStatus: String?,
    val nextStartLaneSummaryTitle: String?,
    val nextStartLaneRecommendedStrategy: String?,
    val nextStartLaneRecommendation: String?,
    val restageHint: String?,
)

@Serializable
private data class RedactedStorageReport(
    val backend: String,
    val keyReference: String,
    val storagePathHash: String,
    val persistedProfileHash: String?,
    val lastPersistedAt: String?,
    val status: String,
    val error: String?,
)

@Serializable
private data class RedactedAutomationReport(
    val enabled: Boolean,
    val vpnPermissionReady: Boolean,
    val lastAutomationStatus: String,
    val lastAutomationError: String?,
    val lastAutomationErrorSection: String?,
    val lastAutomationErrorFieldPath: String?,
    val lastAutomationRuntimeMetadata: AutomationRuntimeMetadata?,
    val lastAutomationAt: String?,
    val lastCallerHint: String?,
    val storagePathHash: String,
    val keyReference: String,
)

@Serializable
private data class RedactedRoutePreviewReport(
    val packageHash: String?,
    val destinationHostHash: String?,
    val destinationIpHash: String?,
    val destinationPort: Int?,
    val action: String,
    val matchedRuleHash: String?,
    val runtimeDatasetHint: String?,
)

private fun ProfileIr.toRedactedReport(): RedactedProfileReport {
    val effectiveRouting = EffectiveRoutingPolicyResolver.resolve(this)
    val splitTunnel = vpn.splitTunnel
    val outboundSummary = outboundSummary
    val allowCount = when (splitTunnel) {
        SplitTunnelMode.FullTunnel -> 0
        is SplitTunnelMode.Allowlist -> splitTunnel.packageNames.size
        is SplitTunnelMode.Denylist -> 0
    }
    val denyCount = when (splitTunnel) {
        SplitTunnelMode.FullTunnel -> 0
        is SplitTunnelMode.Allowlist -> 0
        is SplitTunnelMode.Denylist -> splitTunnel.packageNames.size
    }
    return RedactedProfileReport(
        profileIdHash = id.redactedDigest(),
        profileName = name,
        outboundShape = outboundSummary.shapeLabel,
        outboundProtocol = outboundSummary.protocolLabel,
        outboundTransport = outboundSummary.transportLabel,
        outboundSecurity = outboundSummary.securityLabel,
        outboundAddressHash = outboundSummary.endpoint.address.redactedDigest(),
        outboundPort = outboundSummary.endpoint.port,
        serverNameHash = outboundSummary.endpoint.serverName.redactedDigest(),
        uuidHash = outbound.uuid.redactedDigest(),
        realityPublicKeyHash = outbound.realityPublicKey.redactedDigest(),
        realityShortIdHash = outbound.realityShortId.redactedDigest(),
        flow = outboundSummary.flow,
        utlsFingerprint = outboundSummary.utlsFingerprint,
        splitTunnelMode = splitTunnel::class.simpleName.orEmpty(),
        allowPackageCount = allowCount,
        denyPackageCount = denyCount,
        routingDefaultAction = routing.defaultAction.name,
        proxyRuleCount = effectiveRouting.rules.count { it.action == RouteAction.PROXY },
        directRuleCount = effectiveRouting.rules.count { it.action == RouteAction.DIRECT },
        blockRuleCount = routing.rules.count { it.action == RouteAction.BLOCK },
        regionalBypassPresetCount = effectiveRouting.enabledRegionalPresets.size,
        regionalBypassPresetIds = effectiveRouting.enabledRegionalPresets.map { it.name },
        regionalBypassCustomDirectCount = effectiveRouting.normalizedCustomDirectDomains.size,
        generatedRegionalRuleCount = effectiveRouting.generatedRegionalRuleCount,
        dnsMode = dns::class.simpleName.orEmpty(),
        dnsEndpointHashes = when (val mode = dns) {
            is DnsMode.VpnDns -> mode.servers.map(String::redactedDigest)
            DnsMode.SystemDns -> emptyList()
            is DnsMode.CustomEncrypted -> mode.endpoints.map(String::redactedDigest)
        },
        safeMode = safety.safeMode,
        compatibilityLocalProxy = safety.compatibilityLocalProxy,
        debugEndpointsEnabled = safety.debugEndpointsEnabled,
    )
}

private fun VpnRuntimeSnapshot.toRedactedReport(
    profile: ProfileIr,
    configuredStrategyId: io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId,
): RedactedRuntimeReport {
    val effectiveConfiguredStrategy = configuredStrategy ?: configuredStrategyId
    val nextStartCompilerPath = configuredRuntimeConfigSource
        ?.takeIf { activeStrategy == null || configuredStrategy != activeStrategy || it != runtimeConfigSource }
    val guidance = StrategyCapabilityRegistry.evaluateProfileGuidance(
        profile = profile,
        strategyId = activeStrategy ?: effectiveConfiguredStrategy,
    )
    val lanePresentation = runtimeLanePresentation(
        activeStrategyId = activeStrategy,
        configuredStrategyId = effectiveConfiguredStrategy,
        guidance = guidance,
        stagedMetadata = laneCompatibility,
        configuredMetadata = configuredLaneCompatibility,
    )
    val laneSummary = lanePresentation.summaryLabels
    val nextStartDetail = lanePresentation.nextStartDetail
    return RedactedRuntimeReport(
        phase = phase.name,
        configHash = configHash,
        sessionLabelHash = sessionLabel?.redactedDigest(),
        activeStrategy = activeStrategy?.name,
        configuredStrategy = effectiveConfiguredStrategy.name,
        compilerPath = runtimeConfigSource?.let(::runtimeConfigSourceLabel),
        compilerPathSummary = runtimeConfigSource?.let(::runtimeConfigSourceSummary),
        nextStartCompilerPath = nextStartCompilerPath?.let(::runtimeConfigSourceLabel),
        nextStartCompilerPathSummary = nextStartCompilerPath?.let(::runtimeConfigSourceSummary),
        profileShape = profileShapeLabel(),
        profileProtocolId = profileProtocolId?.name,
        profileTransportId = profileTransportId?.name,
        profileSecurityId = profileSecurityId?.name,
        engineId = engineId,
        engineFormat = engineFormat,
        compiledPayloadBytes = compiledPayloadBytes,
        allowCount = allowCount,
        denyCount = denyCount,
        routeCount = routeCount,
        excludedRouteCount = excludedRouteCount,
        mtu = mtu,
        runtimeMode = runtimeMode.name,
        auditStatus = auditStatus.name,
        auditFindingCount = auditFindingCount,
        bootstrapStatus = bootstrapStatus.name,
        engineHostStatus = engineHostStatus.name,
        engineSessionStatus = engineSessionStatus.name,
        engineSessionHealthStatus = engineSessionHealthStatus.name,
        bridgePort = bridgePort,
        xrayPid = xrayPid,
        tun2socksPid = tun2socksPid,
        ownPackageBypassesVpn = ownPackageBypassesVpn,
        routedTrafficObserved = routedTrafficObserved,
        lastRoutedTrafficAtEpochMs = lastRoutedTrafficAtEpochMs,
        dnsFailureObserved = dnsFailureObserved,
        lastDnsFailureSummary = lastDnsFailureSummary,
        recentXrayLogLines = recentXrayLogLines,
        recentNativeEvents = recentNativeEvents,
        sessionWorkspacePresent = !sessionWorkspacePath.isNullOrBlank(),
        lastError = lastError,
        lastErrorSection = lastErrorSection,
        lastErrorFieldPath = lastErrorFieldPath,
        laneStatus = laneSummary.statusLabel,
        laneSummaryTitle = lanePresentation.selectedSummaryTitle,
        recommendedStrategy = laneSummary.recommendedLaneLabel,
        laneRecommendation = lanePresentation.recommendation,
        nextStartLaneStatus = nextStartDetail?.statusLabel,
        nextStartLaneSummaryTitle = nextStartDetail?.summaryTitle,
        nextStartLaneRecommendedStrategy = nextStartDetail?.recommendedLaneLabel,
        nextStartLaneRecommendation = nextStartDetail?.recommendation,
        restageHint = laneSummary.restageHint,
    )
}

private fun ProfileStorageState.toRedactedReport(): RedactedStorageReport = RedactedStorageReport(
    backend = backend,
    keyReference = keyReference,
    storagePathHash = storagePath.redactedDigest(),
    persistedProfileHash = persistedProfileHash,
    lastPersistedAt = lastPersistedAt,
    status = status,
    error = error,
)

private fun AutomationState.toRedactedReport(): RedactedAutomationReport = RedactedAutomationReport(
    enabled = enabled,
    vpnPermissionReady = vpnPermissionReady,
    lastAutomationStatus = lastAutomationStatus,
    lastAutomationError = lastAutomationError,
    lastAutomationErrorSection = lastAutomationErrorSection,
    lastAutomationErrorFieldPath = lastAutomationErrorFieldPath,
    lastAutomationRuntimeMetadata = lastAutomationRuntimeMetadata,
    lastAutomationAt = lastAutomationAt,
    lastCallerHint = lastCallerHint,
    storagePathHash = storagePath.redactedDigest(),
    keyReference = keyReference,
)

private fun String.redactedDigest(): String = "sha256:${CanonicalJson.sha256Hex(this).take(16)}:$length"
