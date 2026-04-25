package io.acionyx.tunguska.vpnservice

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.OutboundSecurityId
import io.acionyx.tunguska.domain.OutboundTransportId
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.CompiledRuntimeAsset

object VpnRuntimeContract {
    const val MSG_GET_STATUS: Int = 1
    const val MSG_STAGE_PLAN: Int = 2
    const val MSG_START_RUNTIME: Int = 3
    const val MSG_STOP_RUNTIME: Int = 4
    const val MSG_PROBE_EGRESS_IP: Int = 5
    const val MSG_SET_CONFIGURED_RUNTIME_STRATEGY: Int = 6
    const val MSG_STATUS: Int = 100
    const val MSG_ERROR: Int = 101
    const val MSG_EGRESS_IP: Int = 102

    private const val KEY_CONFIG_HASH = "config_hash"
    private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    private const val KEY_DISALLOWED_PACKAGES = "disallowed_packages"
    private const val KEY_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
    private const val KEY_PROCESS_NAME_SUFFIX = "process_name_suffix"
    private const val KEY_PRESERVE_LOOPBACK = "preserve_loopback"
    private const val KEY_RUNTIME_MODE = "runtime_mode"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val KEY_ENGINE_ID = "engine_id"
    private const val KEY_ENGINE_FORMAT = "engine_format"
    private const val KEY_ENGINE_PAYLOAD = "engine_payload"
    private const val KEY_ENGINE_RUNTIME_ASSETS = "engine_runtime_assets"
    private const val KEY_PROFILE_CANONICAL_JSON = "profile_canonical_json"
    private const val KEY_REQUESTED_RUNTIME_STRATEGY = "requested_runtime_strategy"
    private const val KEY_PROFILE_PROTOCOL_ID = "profile_protocol_id"
    private const val KEY_PROFILE_TRANSPORT_ID = "profile_transport_id"
    private const val KEY_PROFILE_SECURITY_ID = "profile_security_id"
    private const val KEY_LANE_COMPATIBILITY_STATUS_LABEL = "lane_compatibility_status_label"
    private const val KEY_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE = "lane_compatibility_selected_summary_title"
    private const val KEY_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY = "lane_compatibility_recommended_strategy"
    private const val KEY_LANE_COMPATIBILITY_RECOMMENDATION = "lane_compatibility_recommendation"
    private const val KEY_CONFIGURED_LANE_COMPATIBILITY_STATUS_LABEL = "configured_lane_compatibility_status_label"
    private const val KEY_CONFIGURED_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE = "configured_lane_compatibility_selected_summary_title"
    private const val KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY = "configured_lane_compatibility_recommended_strategy"
    private const val KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDATION = "configured_lane_compatibility_recommendation"

    private const val KEY_PHASE = "phase"
    private const val KEY_SESSION_LABEL = "session_label"
    private const val KEY_ACTIVE_STRATEGY = "active_strategy"
    private const val KEY_RUNTIME_CONFIG_SOURCE = "runtime_config_source"
    private const val KEY_CONFIGURED_STRATEGY = "configured_strategy"
    private const val KEY_CONFIGURED_RUNTIME_CONFIG_SOURCE = "configured_runtime_config_source"
    private const val KEY_COMPILED_PAYLOAD_BYTES = "compiled_payload_bytes"
    private const val KEY_ENGINE_HOST_STATUS = "engine_host_status"
    private const val KEY_LAST_ENGINE_HOST_AT = "last_engine_host_at"
    private const val KEY_LAST_ENGINE_HOST_SUMMARY = "last_engine_host_summary"
    private const val KEY_ENGINE_SESSION_STATUS = "engine_session_status"
    private const val KEY_LAST_ENGINE_SESSION_AT = "last_engine_session_at"
    private const val KEY_LAST_ENGINE_SESSION_SUMMARY = "last_engine_session_summary"
    private const val KEY_ENGINE_SESSION_HEALTH_STATUS = "engine_session_health_status"
    private const val KEY_LAST_ENGINE_HEALTH_AT = "last_engine_health_at"
    private const val KEY_LAST_ENGINE_HEALTH_SUMMARY = "last_engine_health_summary"
    private const val KEY_BRIDGE_PORT = "bridge_port"
    private const val KEY_XRAY_PID = "xray_pid"
    private const val KEY_TUN2SOCKS_PID = "tun2socks_pid"
    private const val KEY_OWN_PACKAGE_BYPASSES_VPN = "own_package_bypasses_vpn"
    private const val KEY_ROUTED_TRAFFIC_OBSERVED = "routed_traffic_observed"
    private const val KEY_LAST_ROUTED_TRAFFIC_AT = "last_routed_traffic_at"
    private const val KEY_DNS_FAILURE_OBSERVED = "dns_failure_observed"
    private const val KEY_LAST_DNS_FAILURE_SUMMARY = "last_dns_failure_summary"
    private const val KEY_RECENT_XRAY_LOG_LINES = "recent_xray_log_lines"
    private const val KEY_RECENT_NATIVE_EVENTS = "recent_native_events"
    private const val KEY_ALLOW_COUNT = "allow_count"
    private const val KEY_DENY_COUNT = "deny_count"
    private const val KEY_ROUTE_COUNT = "route_count"
    private const val KEY_EXCLUDED_ROUTE_COUNT = "excluded_route_count"
    private const val KEY_MTU = "mtu"
    private const val KEY_AUDIT_STATUS = "audit_status"
    private const val KEY_AUDIT_FINDING_COUNT = "audit_finding_count"
    private const val KEY_LAST_AUDIT_AT = "last_audit_at"
    private const val KEY_LAST_AUDIT_SUMMARY = "last_audit_summary"
    private const val KEY_BOOTSTRAP_STATUS = "bootstrap_status"
    private const val KEY_LAST_BOOTSTRAP_AT = "last_bootstrap_at"
    private const val KEY_LAST_BOOTSTRAP_SUMMARY = "last_bootstrap_summary"
    private const val KEY_SESSION_WORKSPACE_PATH = "session_workspace_path"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_ERROR_SECTION = "last_error_section"
    private const val KEY_LAST_ERROR_FIELD_PATH = "last_error_field_path"
    private const val KEY_ERROR_MESSAGE = "error_message"
    private const val KEY_EGRESS_IP_STATUS = "egress_ip_status"
    private const val KEY_EGRESS_IP_PUBLIC_IP = "egress_ip_public_ip"
    private const val KEY_EGRESS_IP_OBSERVED_AT = "egress_ip_observed_at"
    private const val KEY_EGRESS_IP_SUMMARY = "egress_ip_summary"

    fun statusMessage(snapshot: VpnRuntimeSnapshot): Message = Message.obtain(null, MSG_STATUS).apply {
        data = encodeSnapshot(snapshot)
    }

    fun errorMessage(message: String, snapshot: VpnRuntimeSnapshot): Message = Message.obtain(null, MSG_ERROR).apply {
        data = encodeSnapshot(snapshot).apply {
            putString(KEY_ERROR_MESSAGE, message)
        }
    }

    fun egressIpMessage(observation: RuntimeEgressIpObservation): Message = Message.obtain(null, MSG_EGRESS_IP).apply {
        data = encodeEgressIpObservation(observation)
    }

    fun stageRuntimeMessage(request: StagedRuntimeRequest, replyTo: Messenger): Message = Message.obtain(null, MSG_STAGE_PLAN).apply {
        data = encodeRequest(request)
        this.replyTo = replyTo
    }

    fun configuredRuntimeStrategyMessage(
        strategy: EmbeddedRuntimeStrategyId,
        laneCompatibility: RuntimeLaneCompatibilityMetadata,
        replyTo: Messenger,
    ): Message = Message.obtain(null, MSG_SET_CONFIGURED_RUNTIME_STRATEGY).apply {
        data = Bundle().apply {
            putString(KEY_CONFIGURED_STRATEGY, strategy.name)
            putConfiguredLaneCompatibility(laneCompatibility)
        }
        this.replyTo = replyTo
    }

    fun simpleMessage(what: Int, replyTo: Messenger): Message = Message.obtain(null, what).apply {
        this.replyTo = replyTo
    }

    fun encodeRequest(request: StagedRuntimeRequest): Bundle {
        val payloadBytes = request.compiledConfig.payload.toByteArray(Charsets.UTF_8).size
        require(payloadBytes <= MAX_COMPILED_PAYLOAD_BYTES) {
            "Compiled engine payload exceeds the $MAX_COMPILED_PAYLOAD_BYTES byte Binder staging limit."
        }
        require(request.plan.configHash == request.compiledConfig.configHash) {
            "Tunnel session plan hash does not match the compiled engine config hash."
        }
        return Bundle().apply {
            putString(KEY_ENGINE_ID, request.compiledConfig.engineId)
            putString(KEY_ENGINE_FORMAT, request.compiledConfig.format)
            putString(KEY_ENGINE_PAYLOAD, request.compiledConfig.payload)
            putStringArray(KEY_ENGINE_RUNTIME_ASSETS, encodeRuntimeAssets(request.compiledConfig.runtimeAssets))
            putString(KEY_REQUESTED_RUNTIME_STRATEGY, request.runtimeStrategy.name)
            putString(KEY_PROFILE_PROTOCOL_ID, request.profileProtocolId?.name)
            putString(KEY_PROFILE_TRANSPORT_ID, request.profileTransportId?.name)
            putString(KEY_PROFILE_SECURITY_ID, request.profileSecurityId?.name)
            putLaneCompatibility(request.laneCompatibility)
            request.profileCanonicalJson?.let { profileCanonicalJson ->
                val profileBytes = profileCanonicalJson.toByteArray(Charsets.UTF_8).size
                require(profileBytes <= MAX_PROFILE_JSON_BYTES) {
                    "Canonical profile payload exceeds the $MAX_PROFILE_JSON_BYTES byte Binder staging limit."
                }
                putString(KEY_PROFILE_CANONICAL_JSON, profileCanonicalJson)
            }
            putBoolean(KEY_SAFE_MODE, request.compiledConfig.vpnDirectives.safeMode)
            putAll(encodePlan(request.plan))
        }
    }

    fun encodePlan(plan: TunnelSessionPlan): Bundle = Bundle().apply {
        putString(KEY_CONFIG_HASH, plan.configHash)
        putString(KEY_PROCESS_NAME_SUFFIX, plan.processNameSuffix)
        putBoolean(KEY_PRESERVE_LOOPBACK, plan.preserveLoopback)
        putStringArray(KEY_ALLOWED_PACKAGES, plan.allowedPackages.toTypedArray())
        putStringArray(KEY_DISALLOWED_PACKAGES, plan.disallowedPackages.toTypedArray())
        putString(KEY_RUNTIME_MODE, plan.runtimeMode.name)
        putString(KEY_SPLIT_TUNNEL_MODE, encodeSplitTunnelMode(plan.splitTunnelMode))
    }

    fun decodeRequest(bundle: Bundle): StagedRuntimeRequest {
        val plan = decodePlan(bundle)
        val compiledConfig = CompiledEngineConfig(
            engineId = bundle.getString(KEY_ENGINE_ID).orEmpty(),
            format = bundle.getString(KEY_ENGINE_FORMAT).orEmpty(),
            payload = bundle.getString(KEY_ENGINE_PAYLOAD).orEmpty(),
            configHash = bundle.getString(KEY_CONFIG_HASH).orEmpty(),
            vpnDirectives = io.acionyx.tunguska.engine.api.VpnDirectives(
                preserveLoopback = plan.preserveLoopback,
                splitTunnelMode = plan.splitTunnelMode,
                safeMode = bundle.getBoolean(KEY_SAFE_MODE, true),
            ),
            runtimeAssets = decodeRuntimeAssets(bundle.getStringArray(KEY_ENGINE_RUNTIME_ASSETS)),
        )
        require(compiledConfig.engineId.isNotBlank()) {
            "Staged runtime request is missing the engine id."
        }
        require(compiledConfig.format.isNotBlank()) {
            "Staged runtime request is missing the engine format."
        }
        return StagedRuntimeRequest(
            plan = plan,
            compiledConfig = compiledConfig,
            profileCanonicalJson = bundle.getString(KEY_PROFILE_CANONICAL_JSON),
            runtimeStrategy = bundle.getString(KEY_REQUESTED_RUNTIME_STRATEGY)
                ?.let(EmbeddedRuntimeStrategyId::valueOf)
                ?: EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            profileProtocolId = bundle.getString(KEY_PROFILE_PROTOCOL_ID)?.let(OutboundProtocolId::valueOf),
            profileTransportId = bundle.getString(KEY_PROFILE_TRANSPORT_ID)?.let(OutboundTransportId::valueOf),
            profileSecurityId = bundle.getString(KEY_PROFILE_SECURITY_ID)?.let(OutboundSecurityId::valueOf),
            laneCompatibility = decodeLaneCompatibility(bundle),
        )
    }

    fun decodePlan(bundle: Bundle): TunnelSessionPlan {
        val allowedPackages = bundle.getStringArray(KEY_ALLOWED_PACKAGES)?.toList().orEmpty()
        val disallowedPackages = bundle.getStringArray(KEY_DISALLOWED_PACKAGES)?.toList().orEmpty()
        val splitTunnelMode = decodeSplitTunnelMode(
            value = bundle.getString(KEY_SPLIT_TUNNEL_MODE),
            allowedPackages = allowedPackages,
            disallowedPackages = disallowedPackages,
        )
        return TunnelSessionPlan(
            processNameSuffix = bundle.getString(KEY_PROCESS_NAME_SUFFIX).orEmpty().ifBlank { ":vpn" },
            preserveLoopback = bundle.getBoolean(KEY_PRESERVE_LOOPBACK, true),
            allowedPackages = allowedPackages,
            disallowedPackages = disallowedPackages,
            splitTunnelMode = splitTunnelMode,
            runtimeMode = bundle.getString(KEY_RUNTIME_MODE)
                ?.let(TunnelSessionPlan.RuntimeMode::valueOf)
                ?: TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
            configHash = bundle.getString(KEY_CONFIG_HASH).orEmpty(),
        )
    }

    fun encodeSnapshot(snapshot: VpnRuntimeSnapshot): Bundle = Bundle().apply {
        putString(KEY_PHASE, snapshot.phase.name)
        putString(KEY_CONFIG_HASH, snapshot.configHash)
        putString(KEY_SESSION_LABEL, snapshot.sessionLabel)
        putString(KEY_ACTIVE_STRATEGY, snapshot.activeStrategy?.name)
        putString(KEY_RUNTIME_CONFIG_SOURCE, snapshot.runtimeConfigSource?.name)
        putString(KEY_CONFIGURED_STRATEGY, snapshot.configuredStrategy?.name)
        putString(KEY_CONFIGURED_RUNTIME_CONFIG_SOURCE, snapshot.configuredRuntimeConfigSource?.name)
        putConfiguredLaneCompatibility(snapshot.configuredLaneCompatibility)
        putString(KEY_PROFILE_PROTOCOL_ID, snapshot.profileProtocolId?.name)
        putString(KEY_PROFILE_TRANSPORT_ID, snapshot.profileTransportId?.name)
        putString(KEY_PROFILE_SECURITY_ID, snapshot.profileSecurityId?.name)
        putLaneCompatibility(snapshot.laneCompatibility)
        putString(KEY_ENGINE_ID, snapshot.engineId)
        putString(KEY_ENGINE_FORMAT, snapshot.engineFormat)
        putInt(KEY_COMPILED_PAYLOAD_BYTES, snapshot.compiledPayloadBytes)
        putInt(KEY_ALLOW_COUNT, snapshot.allowCount)
        putInt(KEY_DENY_COUNT, snapshot.denyCount)
        putInt(KEY_ROUTE_COUNT, snapshot.routeCount)
        putInt(KEY_EXCLUDED_ROUTE_COUNT, snapshot.excludedRouteCount)
        putInt(KEY_MTU, snapshot.mtu ?: -1)
        putString(KEY_RUNTIME_MODE, snapshot.runtimeMode.name)
        putString(KEY_AUDIT_STATUS, snapshot.auditStatus.name)
        putInt(KEY_AUDIT_FINDING_COUNT, snapshot.auditFindingCount)
        putLong(KEY_LAST_AUDIT_AT, snapshot.lastAuditAtEpochMs ?: -1L)
        putString(KEY_LAST_AUDIT_SUMMARY, snapshot.lastAuditSummary)
        putString(KEY_BOOTSTRAP_STATUS, snapshot.bootstrapStatus.name)
        putLong(KEY_LAST_BOOTSTRAP_AT, snapshot.lastBootstrapAtEpochMs ?: -1L)
        putString(KEY_LAST_BOOTSTRAP_SUMMARY, snapshot.lastBootstrapSummary)
        putString(KEY_ENGINE_HOST_STATUS, snapshot.engineHostStatus.name)
        putLong(KEY_LAST_ENGINE_HOST_AT, snapshot.lastEngineHostAtEpochMs ?: -1L)
        putString(KEY_LAST_ENGINE_HOST_SUMMARY, snapshot.lastEngineHostSummary)
        putString(KEY_ENGINE_SESSION_STATUS, snapshot.engineSessionStatus.name)
        putLong(KEY_LAST_ENGINE_SESSION_AT, snapshot.lastEngineSessionAtEpochMs ?: -1L)
        putString(KEY_LAST_ENGINE_SESSION_SUMMARY, snapshot.lastEngineSessionSummary)
        putString(KEY_ENGINE_SESSION_HEALTH_STATUS, snapshot.engineSessionHealthStatus.name)
        putLong(KEY_LAST_ENGINE_HEALTH_AT, snapshot.lastEngineHealthAtEpochMs ?: -1L)
        putString(KEY_LAST_ENGINE_HEALTH_SUMMARY, snapshot.lastEngineHealthSummary)
        putInt(KEY_BRIDGE_PORT, snapshot.bridgePort ?: -1)
        putLong(KEY_XRAY_PID, snapshot.xrayPid ?: -1L)
        putLong(KEY_TUN2SOCKS_PID, snapshot.tun2socksPid ?: -1L)
        putBoolean(KEY_OWN_PACKAGE_BYPASSES_VPN, snapshot.ownPackageBypassesVpn)
        putBoolean(KEY_ROUTED_TRAFFIC_OBSERVED, snapshot.routedTrafficObserved)
        putLong(KEY_LAST_ROUTED_TRAFFIC_AT, snapshot.lastRoutedTrafficAtEpochMs ?: -1L)
        putBoolean(KEY_DNS_FAILURE_OBSERVED, snapshot.dnsFailureObserved)
        putString(KEY_LAST_DNS_FAILURE_SUMMARY, snapshot.lastDnsFailureSummary)
        putStringArrayList(KEY_RECENT_XRAY_LOG_LINES, ArrayList(snapshot.recentXrayLogLines))
        putStringArrayList(KEY_RECENT_NATIVE_EVENTS, ArrayList(snapshot.recentNativeEvents))
        putString(KEY_SESSION_WORKSPACE_PATH, snapshot.sessionWorkspacePath)
        putString(KEY_LAST_ERROR, snapshot.lastError)
        putString(KEY_LAST_ERROR_SECTION, snapshot.lastErrorSection)
        putString(KEY_LAST_ERROR_FIELD_PATH, snapshot.lastErrorFieldPath)
    }

    fun decodeSnapshot(bundle: Bundle): VpnRuntimeSnapshot = VpnRuntimeSnapshot(
        phase = bundle.getString(KEY_PHASE)?.let(VpnRuntimePhase::valueOf) ?: VpnRuntimePhase.IDLE,
        configHash = bundle.getString(KEY_CONFIG_HASH),
        sessionLabel = bundle.getString(KEY_SESSION_LABEL),
        activeStrategy = bundle.getString(KEY_ACTIVE_STRATEGY)?.let(EmbeddedRuntimeStrategyId::valueOf),
        runtimeConfigSource = bundle.getString(KEY_RUNTIME_CONFIG_SOURCE)?.let(RuntimeConfigSource::valueOf),
        configuredStrategy = bundle.getString(KEY_CONFIGURED_STRATEGY)?.let(EmbeddedRuntimeStrategyId::valueOf),
        configuredRuntimeConfigSource = bundle.getString(KEY_CONFIGURED_RUNTIME_CONFIG_SOURCE)?.let(RuntimeConfigSource::valueOf),
        configuredLaneCompatibility = decodeConfiguredLaneCompatibility(bundle),
        profileProtocolId = bundle.getString(KEY_PROFILE_PROTOCOL_ID)?.let(OutboundProtocolId::valueOf),
        profileTransportId = bundle.getString(KEY_PROFILE_TRANSPORT_ID)?.let(OutboundTransportId::valueOf),
        profileSecurityId = bundle.getString(KEY_PROFILE_SECURITY_ID)?.let(OutboundSecurityId::valueOf),
        laneCompatibility = decodeLaneCompatibility(bundle),
        engineId = bundle.getString(KEY_ENGINE_ID),
        engineFormat = bundle.getString(KEY_ENGINE_FORMAT),
        compiledPayloadBytes = bundle.getInt(KEY_COMPILED_PAYLOAD_BYTES, 0),
        allowCount = bundle.getInt(KEY_ALLOW_COUNT, 0),
        denyCount = bundle.getInt(KEY_DENY_COUNT, 0),
        routeCount = bundle.getInt(KEY_ROUTE_COUNT, 0),
        excludedRouteCount = bundle.getInt(KEY_EXCLUDED_ROUTE_COUNT, 0),
        mtu = bundle.getInt(KEY_MTU, -1).takeIf { it >= 0 },
        runtimeMode = bundle.getString(KEY_RUNTIME_MODE)
            ?.let(TunnelSessionPlan.RuntimeMode::valueOf)
            ?: TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
        auditStatus = bundle.getString(KEY_AUDIT_STATUS)
            ?.let(RuntimeAuditStatus::valueOf)
            ?: RuntimeAuditStatus.NOT_RUN,
        auditFindingCount = bundle.getInt(KEY_AUDIT_FINDING_COUNT, 0),
        lastAuditAtEpochMs = bundle.getLong(KEY_LAST_AUDIT_AT, -1L).takeIf { it >= 0L },
        lastAuditSummary = bundle.getString(KEY_LAST_AUDIT_SUMMARY),
        bootstrapStatus = bundle.getString(KEY_BOOTSTRAP_STATUS)
            ?.let(TunnelBootstrapStatus::valueOf)
            ?: TunnelBootstrapStatus.NOT_ATTEMPTED,
        lastBootstrapAtEpochMs = bundle.getLong(KEY_LAST_BOOTSTRAP_AT, -1L).takeIf { it >= 0L },
        lastBootstrapSummary = bundle.getString(KEY_LAST_BOOTSTRAP_SUMMARY),
        engineHostStatus = bundle.getString(KEY_ENGINE_HOST_STATUS)
            ?.let(EmbeddedEngineHostStatus::valueOf)
            ?: EmbeddedEngineHostStatus.NOT_PREPARED,
        lastEngineHostAtEpochMs = bundle.getLong(KEY_LAST_ENGINE_HOST_AT, -1L).takeIf { it >= 0L },
        lastEngineHostSummary = bundle.getString(KEY_LAST_ENGINE_HOST_SUMMARY),
        engineSessionStatus = bundle.getString(KEY_ENGINE_SESSION_STATUS)
            ?.let(EmbeddedEngineSessionStatus::valueOf)
            ?: EmbeddedEngineSessionStatus.NOT_STARTED,
        lastEngineSessionAtEpochMs = bundle.getLong(KEY_LAST_ENGINE_SESSION_AT, -1L).takeIf { it >= 0L },
        lastEngineSessionSummary = bundle.getString(KEY_LAST_ENGINE_SESSION_SUMMARY),
        engineSessionHealthStatus = bundle.getString(KEY_ENGINE_SESSION_HEALTH_STATUS)
            ?.let(EmbeddedEngineSessionHealthStatus::valueOf)
            ?: EmbeddedEngineSessionHealthStatus.UNKNOWN,
        lastEngineHealthAtEpochMs = bundle.getLong(KEY_LAST_ENGINE_HEALTH_AT, -1L).takeIf { it >= 0L },
        lastEngineHealthSummary = bundle.getString(KEY_LAST_ENGINE_HEALTH_SUMMARY),
        bridgePort = bundle.getInt(KEY_BRIDGE_PORT, -1).takeIf { it >= 0 },
        xrayPid = bundle.getLong(KEY_XRAY_PID, -1L).takeIf { it > 0L },
        tun2socksPid = bundle.getLong(KEY_TUN2SOCKS_PID, -1L).takeIf { it > 0L },
        ownPackageBypassesVpn = bundle.getBoolean(KEY_OWN_PACKAGE_BYPASSES_VPN, false),
        routedTrafficObserved = bundle.getBoolean(KEY_ROUTED_TRAFFIC_OBSERVED, false),
        lastRoutedTrafficAtEpochMs = bundle.getLong(KEY_LAST_ROUTED_TRAFFIC_AT, -1L).takeIf { it >= 0L },
        dnsFailureObserved = bundle.getBoolean(KEY_DNS_FAILURE_OBSERVED, false),
        lastDnsFailureSummary = bundle.getString(KEY_LAST_DNS_FAILURE_SUMMARY),
        recentXrayLogLines = bundle.getStringArrayList(KEY_RECENT_XRAY_LOG_LINES)?.toList().orEmpty(),
        recentNativeEvents = bundle.getStringArrayList(KEY_RECENT_NATIVE_EVENTS)?.toList().orEmpty(),
        sessionWorkspacePath = bundle.getString(KEY_SESSION_WORKSPACE_PATH),
        lastError = bundle.getString(KEY_LAST_ERROR),
        lastErrorSection = bundle.getString(KEY_LAST_ERROR_SECTION),
        lastErrorFieldPath = bundle.getString(KEY_LAST_ERROR_FIELD_PATH),
    )

    fun decodeError(bundle: Bundle): String? = bundle.getString(KEY_ERROR_MESSAGE)

    fun decodeConfiguredRuntimeStrategy(bundle: Bundle): EmbeddedRuntimeStrategyId = checkNotNull(
        bundle.getString(KEY_CONFIGURED_STRATEGY)?.let(EmbeddedRuntimeStrategyId::valueOf),
    ) {
        "Configured runtime strategy is missing from the control message."
    }

    fun decodeConfiguredLaneCompatibility(bundle: Bundle): RuntimeLaneCompatibilityMetadata? = decodeLaneCompatibility(
        bundle = bundle,
        statusLabelKey = KEY_CONFIGURED_LANE_COMPATIBILITY_STATUS_LABEL,
        selectedSummaryTitleKey = KEY_CONFIGURED_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE,
        recommendedStrategyKey = KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY,
        recommendationKey = KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDATION,
    )

    private fun Bundle.putLaneCompatibility(metadata: RuntimeLaneCompatibilityMetadata?) {
        putString(KEY_LANE_COMPATIBILITY_STATUS_LABEL, metadata?.statusLabel)
        putString(KEY_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE, metadata?.selectedSummaryTitle)
        putString(KEY_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY, metadata?.recommendedStrategyId?.name)
        putString(KEY_LANE_COMPATIBILITY_RECOMMENDATION, metadata?.recommendation)
    }

    private fun Bundle.putConfiguredLaneCompatibility(metadata: RuntimeLaneCompatibilityMetadata?) {
        putString(KEY_CONFIGURED_LANE_COMPATIBILITY_STATUS_LABEL, metadata?.statusLabel)
        putString(KEY_CONFIGURED_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE, metadata?.selectedSummaryTitle)
        putString(KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY, metadata?.recommendedStrategyId?.name)
        putString(KEY_CONFIGURED_LANE_COMPATIBILITY_RECOMMENDATION, metadata?.recommendation)
    }

    private fun decodeLaneCompatibility(bundle: Bundle): RuntimeLaneCompatibilityMetadata? = decodeLaneCompatibility(
        bundle = bundle,
        statusLabelKey = KEY_LANE_COMPATIBILITY_STATUS_LABEL,
        selectedSummaryTitleKey = KEY_LANE_COMPATIBILITY_SELECTED_SUMMARY_TITLE,
        recommendedStrategyKey = KEY_LANE_COMPATIBILITY_RECOMMENDED_STRATEGY,
        recommendationKey = KEY_LANE_COMPATIBILITY_RECOMMENDATION,
    )

    private fun decodeLaneCompatibility(
        bundle: Bundle,
        statusLabelKey: String,
        selectedSummaryTitleKey: String,
        recommendedStrategyKey: String,
        recommendationKey: String,
    ): RuntimeLaneCompatibilityMetadata? {
        val statusLabel = bundle.getString(statusLabelKey)
        val selectedSummaryTitle = bundle.getString(selectedSummaryTitleKey)
        val recommendedStrategyId = bundle.getString(recommendedStrategyKey)
            ?.let(EmbeddedRuntimeStrategyId::valueOf)
        val recommendation = bundle.getString(recommendationKey)
        if (statusLabel.isNullOrBlank() && selectedSummaryTitle.isNullOrBlank() && recommendedStrategyId == null && recommendation == null) {
            return null
        }
        if (statusLabel.isNullOrBlank() || selectedSummaryTitle.isNullOrBlank()) {
            return null
        }
        return RuntimeLaneCompatibilityMetadata(
            statusLabel = statusLabel,
            selectedSummaryTitle = selectedSummaryTitle,
            recommendedStrategyId = recommendedStrategyId,
            recommendation = recommendation,
        )
    }

    fun encodeEgressIpObservation(observation: RuntimeEgressIpObservation): Bundle = Bundle().apply {
        putString(KEY_EGRESS_IP_STATUS, observation.status.name)
        putString(KEY_EGRESS_IP_PUBLIC_IP, observation.publicIp)
        putLong(KEY_EGRESS_IP_OBSERVED_AT, observation.observedAtEpochMs)
        putString(KEY_EGRESS_IP_SUMMARY, observation.summary)
    }

    fun decodeEgressIpObservation(bundle: Bundle): RuntimeEgressIpObservation = RuntimeEgressIpObservation(
        status = bundle.getString(KEY_EGRESS_IP_STATUS)
            ?.let(RuntimeEgressIpObservationStatus::valueOf)
            ?: RuntimeEgressIpObservationStatus.UNAVAILABLE,
        publicIp = bundle.getString(KEY_EGRESS_IP_PUBLIC_IP),
        observedAtEpochMs = bundle.getLong(KEY_EGRESS_IP_OBSERVED_AT, System.currentTimeMillis()),
        summary = bundle.getString(KEY_EGRESS_IP_SUMMARY),
    )

    internal fun encodeRuntimeAssets(runtimeAssets: List<CompiledRuntimeAsset>): Array<String> = runtimeAssets
        .map(CompiledRuntimeAsset::relativePath)
        .toTypedArray()

    internal fun decodeRuntimeAssets(encoded: Array<String>?): List<CompiledRuntimeAsset> = encoded
        ?.map(::CompiledRuntimeAsset)
        .orEmpty()

    private fun encodeSplitTunnelMode(mode: SplitTunnelMode): String = when (mode) {
        SplitTunnelMode.FullTunnel -> "FULL"
        is SplitTunnelMode.Allowlist -> "ALLOW"
        is SplitTunnelMode.Denylist -> "DENY"
    }

    private fun decodeSplitTunnelMode(
        value: String?,
        allowedPackages: List<String>,
        disallowedPackages: List<String>,
    ): SplitTunnelMode = when (value) {
        "ALLOW" -> SplitTunnelMode.Allowlist(allowedPackages)
        "DENY" -> SplitTunnelMode.Denylist(disallowedPackages)
        else -> SplitTunnelMode.FullTunnel
    }

    private const val MAX_COMPILED_PAYLOAD_BYTES: Int = 262_144
    private const val MAX_PROFILE_JSON_BYTES: Int = 65_536
}
