package io.acionyx.tunguska.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.FileProvider
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.acionyx.tunguska.domain.ImportedProfile
import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileImportParser
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RegionalBypassPresetId
import io.acionyx.tunguska.domain.defaultRegionalBypass
import io.acionyx.tunguska.domain.normalizeDomainForRouting
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.netpolicy.RoutePreviewEngine
import io.acionyx.tunguska.netpolicy.RoutePreviewOutcome
import io.acionyx.tunguska.netpolicy.RoutePreviewRequest
import io.acionyx.tunguska.storage.StoredProfile
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.RuntimeEgressIpObservation
import io.acionyx.tunguska.vpnservice.RuntimeEgressIpObservationStatus
import io.acionyx.tunguska.vpnservice.TunnelSessionPlan
import io.acionyx.tunguska.vpnservice.TunnelSessionPlanner
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val plugin = SingboxEnginePlugin()
    private val previewEngine = RoutePreviewEngine()
    private val bootstrapProfile = defaultBootstrapProfile()
    private val defaultPreview = PreviewInputs()
    private val profileRepository = SecureProfileRepository(application)
    private val automationRepository = AutomationIntegrationRepository(application)
    private val regionalBypassPromptStore = RegionalBypassPromptStore(application)
    private val exportRepository = SecureExportRepository(application)
    private val subscriptionRepository = SecureSubscriptionRepository(application)
    private val egressIpObservationRepository = EgressIpObservationRepository()
    private val subscriptionUpdateScheduler = SubscriptionUpdateScheduler(application)
    private val subscriptionNotificationPublisher = SubscriptionNotificationPublisher(application)
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val observationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimeAutomationOrchestrator = RuntimeAutomationOrchestrator(
        context = application,
        profileRepository = profileRepository,
    )
    private val runtimeClient = VpnRuntimeClient(
        context = application,
        onConnectionChanged = { connected ->
            uiState = uiState.copy(controlChannelConnected = connected)
            if (connected) {
                synchronizeConfiguredRuntimeStrategy()
            }
        },
        onStatus = { snapshot, error ->
            Log.i(
                TAG,
                "Runtime status phase=${snapshot.phase} " +
                    "engineSession=${snapshot.engineSessionStatus} " +
                    "health=${snapshot.engineSessionHealthStatus} " +
                    "error='${error ?: snapshot.lastError ?: "none"}'",
            )
            val clearSessionDecision =
                uiState.connectDecisionPhase == ConnectDecisionPhase.STARTED &&
                    snapshot.phase != VpnRuntimePhase.RUNNING
            uiState = uiState.copy(
                runtimeSnapshot = snapshot,
                controlError = error ?: snapshot.lastError,
                connectDecisionPhase = if (clearSessionDecision) null else uiState.connectDecisionPhase,
                connectDecisionMessage = if (clearSessionDecision) null else uiState.connectDecisionMessage,
            )
            synchronizeEgressObservation(snapshot)
        },
        onEgressIpObservation = ::handleRuntimeEgressIpObservation,
    )
    private val runtimeStatusRefreshLoop = object : Runnable {
        override fun run() {
            refreshRuntimeStatus()
            mainHandler.postDelayed(this, RUNTIME_STATUS_REFRESH_INTERVAL_MS)
        }
    }
    private val egressProbeTimeoutRunnable = Runnable {
        if (!egressProbeInFlight || activeEgressProbePhase != VpnRuntimePhase.RUNNING) {
            return@Runnable
        }
        egressProbeInFlight = false
        activeEgressProbePhase = null
        uiState = uiState.copy(
            egressObservation = EgressObservationState(
                phase = EgressObservationPhase.ERROR,
                error = "Engine-routed exit IP detection timed out.",
            ),
        )
        drainPendingEgressRefresh()
    }
    @Volatile private var egressProbeInFlight: Boolean = false
    private var nextShareRequestId: Long = 1L
    private var pendingSubscriptionProfiles: List<ProfileIr> = emptyList()
    private var pendingSubscriptionSelectedIndex: Int = 0
    private var pendingSubscriptionConfig: SubscriptionConfig? = null
    private var subscriptionEventLog: SubscriptionEventLog = SubscriptionEventLog()
    private var subscriptionNotificationLedger: SubscriptionNotificationLedger = SubscriptionNotificationLedger()
    private var pendingImportedProfile: ImportedProfile? = null
    private var lastObservedRuntimePhase: VpnRuntimePhase = VpnRuntimePhase.IDLE
    private var activeEgressProbePhase: VpnRuntimePhase? = null
    @Volatile private var pendingForcedEgressRefresh: Boolean = false

    var uiState: MainUiState by mutableStateOf(initialState())
        private set

    init {
        refreshSubscriptionNotificationStatus()
        loadAutomationIntegration()
        loadEncryptedProfile()
        loadSubscriptionConfig()
        runtimeClient.bind()
        startRuntimeStatusRefreshLoop()
        maybeRefreshEgressObservation(force = true)
    }

    fun updatePreview(
        packageName: String = uiState.routePreview.packageName,
        destinationHost: String = uiState.routePreview.destinationHost,
        destinationIp: String = uiState.routePreview.destinationIp,
        destinationPort: String = uiState.routePreview.destinationPort,
        protocol: NetworkProtocol = uiState.routePreview.protocol,
    ) {
        val preview = PreviewInputs(
            packageName = packageName,
            destinationHost = destinationHost,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            protocol = protocol,
        )
        uiState = uiState.copy(
            routePreview = preview,
            routePreviewStale = uiState.routePreviewLastTested == null || preview != uiState.routePreviewLastTested,
        )
    }

    fun testRoutePreview() {
        val preview = uiState.routePreview
        uiState = uiState.copy(
            routePreviewLastTested = preview,
            routePreviewLastTestedAtEpochMs = System.currentTimeMillis(),
            previewOutcome = evaluatePreview(preview, uiState.profile),
            routePreviewStale = false,
        )
    }

    fun markVpnPermissionGranted() {
        uiState = uiState.copy(vpnPermissionGranted = true)
        refreshAutomationIntegrationStatus()
    }

    fun setAutomationEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) {
                automationRepository.enable()
            } else {
                automationRepository.disable()
            }
        }.onSuccess { settings ->
            applyAutomationSettings(
                settings = settings,
                status = if (enabled) {
                    "Enabled Anubis automation and generated a token."
                } else {
                    "Disabled Anubis automation and invalidated the token."
                },
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                automationState = uiState.automationState.copy(
                    status = null,
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun rotateAutomationToken() {
        runCatching { automationRepository.rotateToken() }
            .onSuccess { settings ->
                applyAutomationSettings(
                    settings = settings,
                    status = "Rotated the Anubis automation token.",
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    automationState = uiState.automationState.copy(
                        status = null,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    private fun synchronizeConfiguredRuntimeStrategy() {
        runtimeClient.syncConfiguredRuntimeStrategy(
            profile = uiState.profile,
            strategy = uiState.automationState.runtimeStrategy,
        )
    }

    fun copyAutomationToken() {
        val token = uiState.automationState.rawToken
        if (token.isNullOrBlank()) {
            uiState = uiState.copy(
                automationState = uiState.automationState.copy(
                    status = null,
                    error = "Enable Anubis automation before copying the token.",
                ),
            )
            return
        }
        val clipboardManager = getApplication<Application>()
            .getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(
            ClipData.newPlainText("Tunguska Anubis token", token),
        )
        mainHandler.postDelayed(
            {
                val currentText = clipboardManager
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(getApplication())
                    ?.toString()
                if (currentText == token) {
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            clipboardManager.clearPrimaryClip()
                        } else {
                            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            },
            AUTOMATION_TOKEN_CLIPBOARD_CLEAR_DELAY_MS,
        )
        uiState = uiState.copy(
            automationState = uiState.automationState.copy(
                status = "Copied the Anubis automation token to the clipboard for 10 seconds.",
                error = null,
            ),
        )
    }

    fun refreshAutomationIntegrationStatus() {
        runCatching { automationRepository.load() }
            .onSuccess { settings ->
                applyAutomationSettings(
                    settings = settings,
                    status = uiState.automationState.status,
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    automationState = uiState.automationState.copy(
                        status = null,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun setRuntimeStrategy(strategy: EmbeddedRuntimeStrategyId) {
        runCatching { automationRepository.setRuntimeStrategy(strategy) }
            .onSuccess { settings ->
                applyAutomationSettings(
                    settings = settings,
                    status = when (strategy) {
                        EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> "Selected runtime lane: xray+tun2socks."
                        EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> "Selected runtime lane: sing-box embedded."
                    },
                )
                uiState = uiState.copy(
                    connectDecisionPhase = null,
                    connectDecisionMessage = null,
                )
                refreshStagedImportPreview(settings.runtimeStrategy)
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    automationState = uiState.automationState.copy(
                        status = null,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun saveProfileEditorDraft(draft: ProfileEditorDraft) {
        val validationIssues = draft.validationIssues(uiState.profile)
        if (validationIssues.isNotEmpty()) {
            uiState = uiState.copy(
                profileEditorStatus = null,
                profileEditorError = validationIssues.first().message,
            )
            return
        }

        mutateProfile(
            status = "Saved the active profile from the advanced editor.",
            transform = { profile -> draft.toUpdatedProfile(profile) },
            onSuccess = { status ->
                uiState = uiState.copy(
                    profileEditorStatus = status,
                    profileEditorError = null,
                )
            },
            onFailure = { error ->
                uiState = uiState.copy(
                    profileEditorStatus = null,
                    profileEditorError = error.message ?: error.javaClass.simpleName,
                )
            },
        )
    }

    fun setRussiaDirectEnabled(enabled: Boolean) {
        mutateProfile(
            status = if (enabled) {
                "Enabled Russia direct bypass."
            } else {
                "Disabled Russia direct bypass."
            },
            transform = { profile ->
                val regionalBypass = profile.routing.regionalBypass
                val enabledPresets = regionalBypass.enabledPresets.toMutableSet().apply {
                    if (enabled) {
                        add(RegionalBypassPresetId.RUSSIA)
                    } else {
                        remove(RegionalBypassPresetId.RUSSIA)
                    }
                }.toList()
                profile.copy(
                    routing = profile.routing.copy(
                        regionalBypass = regionalBypass.copy(
                            enabledPresets = enabledPresets,
                        ),
                    ),
                )
            },
        )
    }

    fun addRegionalDirectDomain(rawDomain: String) {
        val normalizedDomain = runCatching { normalizeDomainForRouting(rawDomain) }
            .getOrElse { error ->
                uiState = uiState.copy(
                    regionalBypassStatus = null,
                    regionalBypassError = error.message ?: error.javaClass.simpleName,
                )
                return
            }
        mutateProfile(
            status = "Added '$normalizedDomain' to always-direct domains.",
            transform = { profile ->
                val regionalBypass = profile.routing.regionalBypass
                profile.copy(
                    routing = profile.routing.copy(
                        regionalBypass = regionalBypass.copy(
                            customDirectDomains = (regionalBypass.customDirectDomains + normalizedDomain).distinct(),
                        ),
                    ),
                )
            },
        )
    }

    fun removeRegionalDirectDomain(domain: String) {
        mutateProfile(
            status = "Removed '$domain' from always-direct domains.",
            transform = { profile ->
                val regionalBypass = profile.routing.regionalBypass
                profile.copy(
                    routing = profile.routing.copy(
                        regionalBypass = regionalBypass.copy(
                            customDirectDomains = regionalBypass.customDirectDomains
                                .map(::normalizeDomainForRouting)
                                .filterNot { it == domain },
                        ),
                    ),
                )
            },
        )
    }

    fun decideRegionalBypassPrompt(enableRussiaDirect: Boolean) {
        regionalBypassPromptStore.markDecisionRecorded(uiState.profile.id)
        uiState = uiState.copy(showRegionalBypassPrompt = false)
        if (enableRussiaDirect) {
            mutateProfile(
                status = "Enabled Russia direct bypass for this existing profile.",
                transform = { profile ->
                    profile.copy(
                        routing = profile.routing.copy(
                            regionalBypass = defaultRegionalBypass(),
                        ),
                    )
                },
            )
        } else {
            uiState = uiState.copy(
                regionalBypassStatus = "Kept the current routing policy without enabling Russia direct.",
                regionalBypassError = null,
                showRegionalBypassPrompt = false,
            )
        }
    }

    fun updateImportDraft(importDraft: String) {
        pendingImportedProfile = null
        uiState = uiState.copy(
            importDraft = importDraft,
            importPreview = null,
            importError = null,
        )
    }

    fun stageImportDraft() {
        val draft = uiState.importDraft.trim()
        if (draft.isBlank()) {
            uiState = uiState.copy(
                importStatus = null,
                importPreview = null,
                importError = "Paste a supported share link or canonical JSON profile before validating.",
            )
            return
        }
        stageImportPayload(
            payload = draft,
            source = ImportCaptureSource.MANUAL_TEXT,
        )
    }

    fun stageQrImportPayload(payload: String, source: ImportCaptureSource) {
        require(source != ImportCaptureSource.MANUAL_TEXT) {
            "QR import payloads must be tagged as camera or image imports."
        }
        stageImportPayload(payload = payload, source = source)
    }

    fun confirmStagedImport() {
        val imported = pendingImportedProfile
        if (imported == null) {
            uiState = uiState.copy(
                importStatus = null,
                importError = "Validate a profile import before confirming it.",
            )
            return
        }
        runCatching {
            imported to profileRepository.reseal(imported.profile)
        }.onSuccess { (stagedImport, storedProfile) ->
            regionalBypassPromptStore.markDecisionRecorded(storedProfile.profile.id)
            Log.i(
                TAG,
                "Confirmed staged import profile='${storedProfile.profile.name}' hash='${storedProfile.profileHash.take(12)}'.",
            )
            applyStoredProfile(
                storedProfile = storedProfile,
                status = "Imported a validated profile into encrypted storage.",
            )
            clearStagedImport()
            val importStatus = buildString {
                append("Imported '${storedProfile.profile.name}' from ${stagedImport.source.rawScheme.ifBlank { "json" }}.")
                if (stagedImport.warnings.isNotEmpty()) {
                    append(' ')
                    append(stagedImport.warnings.joinToString(separator = " "))
                }
            }
            uiState = uiState.copy(
                importDraft = "",
                importPreview = null,
                importStatus = importStatus,
                importError = null,
            )
        }.onFailure { error ->
            Log.e(TAG, "Confirm import failed.", error)
            uiState = uiState.copy(
                importStatus = null,
                importError = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun discardStagedImport() {
        clearStagedImport()
        uiState = uiState.copy(
            importPreview = null,
            importStatus = "Discarded the staged import preview.",
            importError = null,
        )
    }

    fun reportImportError(message: String) {
        clearStagedImport()
        uiState = uiState.copy(
            importPreview = null,
            importStatus = null,
            importError = message,
        )
    }

    fun updateSubscriptionUrlDraft(subscriptionUrlDraft: String) {
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                urlDraft = subscriptionUrlDraft,
                error = null,
            ),
        )
    }

    fun updateSubscriptionPinDraft(subscriptionPinDraft: String) {
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                pinDraft = subscriptionPinDraft,
                error = null,
            ),
        )
    }

    fun updateSubscriptionPublisherPinDraft(subscriptionPublisherPinDraft: String) {
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                publisherPinDraft = subscriptionPublisherPinDraft,
                error = null,
            ),
        )
    }

    fun saveSubscriptionSource() {
        val draft = uiState.subscriptionState.urlDraft.trim()
        if (draft.isBlank()) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription source save failed.",
                    error = "Paste an HTTPS subscription URL before saving.",
                ),
            )
            return
        }
        val parsedPins = runCatching {
            SubscriptionPinPolicy.normalizeInput(uiState.subscriptionState.pinDraft)
        }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription source save failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        val parsedPublisherPins = runCatching {
            SubscriptionPinPolicy.normalizeInput(uiState.subscriptionState.publisherPinDraft)
        }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription source save failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        runCatching {
            subscriptionRepository.saveSourceUrl(
                sourceUrl = draft,
                expectedSpkiPins = parsedPins,
                expectedPublisherPins = parsedPublisherPins,
            )
        }.onSuccess { config ->
            subscriptionUpdateScheduler.reconcile(config)
            refreshSubscriptionEventLog()
            refreshSubscriptionNotificationLedger()
            val restoredPendingStatus = restorePendingSubscriptionUpdate(config)
            uiState = uiState.copy(
                subscriptionState = subscriptionStateFromConfig(
                    config = config,
                    status = buildString {
                        append(
                            if (config.expectedSpkiPins.isEmpty()) {
                                if (config.expectedPublisherPins.isEmpty()) {
                                    "Saved encrypted subscription source metadata."
                                } else {
                                    "Saved encrypted subscription source metadata and enforced ${config.expectedPublisherPins.size} publisher pin(s)."
                                }
                            } else {
                                buildString {
                                    append("Saved encrypted subscription source metadata and enforced ${config.expectedSpkiPins.size} TLS identity pin(s)")
                                    if (config.expectedPublisherPins.isNotEmpty()) {
                                        append(" plus ${config.expectedPublisherPins.size} publisher pin(s)")
                                    }
                                    append(".")
                                }
                            },
                        )
                        restoredPendingStatus?.let {
                            append(' ')
                            append(it)
                        }
                    },
                    urlDraft = "",
                    pinDraft = "",
                    publisherPinDraft = "",
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription source save failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun setSubscriptionScheduleMode(scheduleMode: SubscriptionScheduleMode) {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription schedule update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription schedule update failed.",
                    error = "Save a subscription source before configuring background updates.",
                ),
            )
            return
        }
        runCatching { subscriptionRepository.updateSchedule(config, scheduleMode) }
            .onSuccess { updatedConfig ->
                subscriptionUpdateScheduler.reconcile(updatedConfig)
                refreshSubscriptionEventLog()
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = updatedConfig,
                        status = updatedConfig.lastScheduledSummary
                            ?: "Updated the subscription background schedule.",
                        urlDraft = "",
                        pinDraft = "",
                        publisherPinDraft = "",
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "Subscription schedule update failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun setSubscriptionUpdateAlertsEnabled(enabled: Boolean) {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert policy update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert policy update failed.",
                    error = "Save a subscription source before configuring alert policy.",
                ),
            )
            return
        }
        runCatching {
            subscriptionRepository.updateNotificationPolicy(
                config = config,
                scheduledUpdateAlertsEnabled = enabled,
                scheduledFailureAlertThreshold = config.scheduledFailureAlertThreshold,
            )
        }.onSuccess { updatedConfig ->
            refreshSubscriptionEventLog()
            uiState = uiState.copy(
                subscriptionState = subscriptionStateFromConfig(
                    config = updatedConfig,
                    status = "Updated the subscription alert policy.",
                    urlDraft = "",
                    pinDraft = "",
                    publisherPinDraft = "",
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert policy update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun setSubscriptionFailureAlertThreshold(threshold: Int) {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert threshold update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert threshold update failed.",
                    error = "Save a subscription source before configuring alert thresholds.",
                ),
            )
            return
        }
        runCatching {
            subscriptionRepository.updateNotificationPolicy(
                config = config,
                scheduledUpdateAlertsEnabled = config.scheduledUpdateAlertsEnabled,
                scheduledFailureAlertThreshold = threshold,
            )
        }.onSuccess { updatedConfig ->
            refreshSubscriptionEventLog()
            uiState = uiState.copy(
                subscriptionState = subscriptionStateFromConfig(
                    config = updatedConfig,
                    status = "Updated the subscription alert threshold.",
                    urlDraft = "",
                    pinDraft = "",
                    publisherPinDraft = "",
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert threshold update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun clearSubscriptionInbox() {
        runCatching {
            subscriptionRepository.clearEventLog()
            refreshSubscriptionEventLog()
        }.onSuccess {
            val config = runCatching { subscriptionRepository.loadConfig() }.getOrNull()
            uiState = uiState.copy(
                subscriptionState = if (config == null) {
                    uiState.subscriptionState.copy(
                        status = "Cleared the encrypted subscription inbox.",
                        eventCount = subscriptionEventLog.entries.size,
                        latestEventAt = null,
                        latestEventSummary = null,
                        inboxEntries = emptyList(),
                        error = null,
                    )
                } else {
                    subscriptionStateFromConfig(
                        config = config,
                        status = "Cleared the encrypted subscription inbox.",
                        urlDraft = "",
                        pinDraft = "",
                        publisherPinDraft = "",
                    )
                },
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription inbox clear failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun clearSubscriptionAlertLedger() {
        runCatching {
            subscriptionRepository.clearNotificationLedger()
            refreshSubscriptionNotificationLedger()
        }.onSuccess {
            val config = runCatching { subscriptionRepository.loadConfig() }.getOrNull()
            uiState = uiState.copy(
                subscriptionState = if (config == null) {
                    uiState.subscriptionState.copy(
                        status = "Cleared the subscription alert delivery ledger.",
                        notificationLedgerStoragePath = subscriptionRepository.notificationLedgerStoragePath,
                        lastAlertStatus = subscriptionNotificationLedger.lastEvaluationStatus.name,
                        lastAlertStatusSummary = subscriptionNotificationLedger.lastEvaluationSummary,
                        lastAlertEvaluatedAt = subscriptionNotificationLedger.lastEvaluatedAtEpochMs?.formatTimestamp(),
                        lastAlertDeliveredAt = subscriptionNotificationLedger.lastDeliveredAtEpochMs?.formatTimestamp(),
                        lastAlertDeliveredTarget = subscriptionNotificationLedger.lastDeliveredDestination?.alertLabel(),
                        lastAlertDeliveredSummary = subscriptionNotificationLedger.lastDeliveredSummary,
                        lastAlertOpenedAt = subscriptionNotificationLedger.lastOpenedAtEpochMs?.formatTimestamp(),
                        lastAlertOpenedTarget = subscriptionNotificationLedger.lastOpenedDestination?.alertLabel(),
                        lastAlertOpenedSummary = subscriptionNotificationLedger.lastOpenedSummary,
                        error = null,
                    )
                } else {
                    subscriptionStateFromConfig(
                        config = config,
                        status = "Cleared the subscription alert delivery ledger.",
                    )
                },
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription alert ledger clear failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun trustObservedSubscriptionIdentity() {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "TLS identity pin update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "TLS identity pin update failed.",
                    error = "Save and fetch a subscription source before pinning its observed TLS identity.",
                ),
            )
            return
        }
        runCatching { subscriptionRepository.trustObservedIdentity(config) }
            .onSuccess { updatedConfig ->
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = updatedConfig,
                        status = "Pinned the last observed TLS identity for future subscription updates.",
                        urlDraft = "",
                        pinDraft = "",
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "TLS identity pin update failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun refreshSubscriptionNotificationStatus() {
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                notificationsEnabled = subscriptionNotificationPublisher.areNotificationsEnabled(),
            ),
        )
    }

    fun openSubscriptionNotificationRoute(route: SubscriptionNotificationRoute) {
        val attentionToken = System.currentTimeMillis()
        runCatching { subscriptionRepository.markNotificationOpened(route) }
        refreshSubscriptionNotificationLedger()
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrNull()
        if (config != null) {
            refreshSubscriptionEventLog()
            restorePendingSubscriptionUpdate(config)
        }
        val pendingProfilesAvailable = pendingSubscriptionProfiles.isNotEmpty()
        val status = when (route.destination) {
            SubscriptionNotificationDestination.REVIEW_UPDATE -> {
                if (pendingProfilesAvailable) {
                    "Opened from a scheduled update alert. Review the staged subscription update before applying it."
                } else {
                    "Opened from a scheduled update alert, but no staged pending subscription update is currently available."
                }
            }
            SubscriptionNotificationDestination.REVIEW_INBOX ->
                "Opened from a scheduled failure alert. Review the latest encrypted inbox entry before retrying."
        }
        val updatedState = if (config == null) {
            uiState.subscriptionState.copy(
                status = status,
                latestEventSummary = route.summary,
                notificationAttention = route,
                notificationAttentionToken = attentionToken,
                error = null,
            )
        } else {
            subscriptionStateFromConfig(
                config = config,
                status = status,
            ).copy(
                notificationAttention = route,
                notificationAttentionToken = attentionToken,
            )
        }
        uiState = uiState.copy(subscriptionState = updatedState)
    }

    fun dismissSubscriptionNotificationAttention() {
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                notificationAttention = null,
                notificationAttentionToken = null,
            ),
        )
    }

    fun trustObservedSubscriptionPublisherIdentity() {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Publisher pin update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Publisher pin update failed.",
                    error = "Save and fetch a signed subscription source before pinning its observed publisher identity.",
                ),
            )
            return
        }
        runCatching { subscriptionRepository.trustObservedPublisherIdentity(config) }
            .onSuccess { updatedConfig ->
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = updatedConfig,
                        status = updatedConfig.lastPublisherSummary
                            ?: "Updated the trusted publisher identity set for future signed subscription updates.",
                        urlDraft = "",
                        pinDraft = "",
                        publisherPinDraft = "",
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "Publisher pin update failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun clearPinnedSubscriptionPublisherIdentity() {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Publisher pin clear failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Publisher pin clear failed.",
                    error = "Save a subscription source before clearing its publisher pin.",
                ),
            )
            return
        }
        runCatching { subscriptionRepository.clearPublisherPins(config) }
            .onSuccess { updatedConfig ->
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = updatedConfig,
                        status = "Cleared the explicit publisher pin for this subscription source.",
                        urlDraft = "",
                        pinDraft = "",
                        publisherPinDraft = "",
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "Publisher pin clear failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun clearPinnedSubscriptionIdentity() {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "TLS identity pin clear failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "TLS identity pin clear failed.",
                    error = "Save a subscription source before clearing its TLS identity pin.",
                ),
            )
            return
        }
        runCatching { subscriptionRepository.clearPinnedIdentity(config) }
            .onSuccess { updatedConfig ->
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = updatedConfig,
                        status = "Cleared the explicit TLS identity pin for this subscription source.",
                        urlDraft = "",
                        pinDraft = "",
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "TLS identity pin clear failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun selectPreviousSubscriptionCandidate() {
        shiftPendingSubscriptionSelection(-1)
    }

    fun selectNextSubscriptionCandidate() {
        shiftPendingSubscriptionSelection(1)
    }

    fun refreshSubscriptionNow() {
        val config = runCatching { subscriptionRepository.loadConfig() }.getOrElse { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription update failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return
        }
        if (config == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription update failed.",
                    error = "Save an HTTPS subscription source before updating.",
                ),
            )
            return
        }
        uiState = uiState.copy(
            subscriptionState = uiState.subscriptionState.copy(
                status = "Fetching and validating the subscription update.",
                error = null,
            ),
        )
        val activeProfile = uiState.profile
        backgroundExecutor.execute {
            val result = runCatching {
                subscriptionRepository.fetchUpdate(
                    config = config,
                    activeProfile = activeProfile,
                )
            }
            mainHandler.post {
                result.onSuccess { update ->
                    refreshSubscriptionEventLog()
                    restorePendingSubscriptionUpdate(update.updatedConfig)
                    val status = when (update.status) {
                        SubscriptionUpdateStatus.NOT_MODIFIED -> "Subscription source returned not modified."
                        SubscriptionUpdateStatus.UPDATED -> {
                            if (update.fetchedProfileCount == 1) {
                                "Fetched and validated a pending profile update. ${update.selectionSummary.orEmpty()}".trim()
                            } else {
                                "Fetched ${update.fetchedProfileCount} validated profiles. ${update.selectionSummary.orEmpty()}".trim()
                            }
                        }
                    }
                    uiState = uiState.copy(
                        subscriptionState = subscriptionStateFromConfig(
                            config = update.updatedConfig,
                            status = status,
                        ),
                    )
                }.onFailure { error ->
                    refreshSubscriptionEventLog()
                    val latestConfig = runCatching { subscriptionRepository.loadConfig() }.getOrNull()
                    latestConfig?.let(::restorePendingSubscriptionUpdate) ?: clearPendingSubscriptionSelection()
                    uiState = uiState.copy(
                        subscriptionState = if (latestConfig == null) {
                            uiState.subscriptionState.copy(
                                status = "Subscription update failed.",
                                error = error.message ?: error.javaClass.simpleName,
                            )
                        } else {
                            subscriptionStateFromConfig(
                                config = latestConfig,
                                status = "Subscription update failed.",
                                error = error.message ?: error.javaClass.simpleName,
                            )
                        },
                    )
                }
            }
        }
    }

    fun applyPendingSubscriptionUpdate() {
        val pendingProfile = selectedPendingSubscriptionProfile()
        val pendingConfig = pendingSubscriptionConfig
        if (pendingProfile == null || pendingConfig == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "No pending subscription update is available to apply.",
                    error = null,
                ),
            )
            return
        }
        runCatching {
            val storedProfile = profileRepository.reseal(pendingProfile)
            val appliedConfig = subscriptionRepository.markApplied(
                config = pendingConfig,
                appliedProfile = pendingProfile,
            )
            storedProfile to appliedConfig
        }.onSuccess { (storedProfile, appliedConfig) ->
            val applySummary = if (pendingSubscriptionProfiles.size <= 1) {
                "Applied the staged subscription update into encrypted profile storage."
            } else {
                "Applied staged subscription candidate ${pendingSubscriptionSelectedIndex + 1} of ${pendingSubscriptionProfiles.size} into encrypted profile storage."
            }
            subscriptionRepository.recordEvent(
                origin = SubscriptionEventOrigin.USER,
                severity = SubscriptionEventSeverity.INFO,
                summary = applySummary,
            )
            refreshSubscriptionEventLog()
            applyStoredProfile(
                storedProfile = storedProfile,
                status = if (pendingSubscriptionProfiles.size <= 1) {
                    "Applied the validated subscription update into encrypted profile storage."
                } else {
                    "Applied selected subscription candidate ${pendingSubscriptionSelectedIndex + 1} of ${pendingSubscriptionProfiles.size} into encrypted profile storage."
                },
            )
            clearPendingSubscriptionSelection()
            uiState = uiState.copy(
                subscriptionState = subscriptionStateFromConfig(
                    config = appliedConfig,
                    status = "Applied the validated subscription update.",
                    urlDraft = "",
                    pinDraft = "",
                    publisherPinDraft = "",
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "Subscription apply failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun stageRuntime() {
        runCatching {
            runtimeClient.stageRuntime(
                plan = uiState.tunnelPlan,
                compiledConfig = uiState.compiledConfig,
                profile = uiState.profile,
                runtimeStrategy = uiState.automationState.runtimeStrategy,
            )
        }.onFailure { error ->
            uiState = uiState.copy(controlError = error.message ?: error.javaClass.simpleName)
        }
    }

    fun connectRuntime() {
        if (!uiState.vpnPermissionGranted) {
            uiState = uiState.copy(
                controlError = "Grant VpnService permission before connecting.",
                connectDecisionPhase = null,
                connectDecisionMessage = null,
            )
            return
        }
        val connectSelection = uiState.configuredSelection
        val connectProfileId = uiState.profile.id
        val startingDecision = connectDecisionState(
            selection = connectSelection,
            phase = ConnectDecisionPhase.STARTING,
        )
        uiState = uiState.copy(
            controlError = null,
            connectDecisionPhase = startingDecision?.phase,
            connectDecisionMessage = startingDecision?.message,
        )
        backgroundExecutor.execute {
            val result = runCatching {
                runtimeAutomationOrchestrator.prepareProfile(
                    profile = uiState.profile,
                    runtimeStrategy = uiState.automationState.runtimeStrategy,
                )
            }.fold(
                onSuccess = { request ->
                    runtimeAutomationOrchestrator.startPreparedRuntime(request)
                },
                onFailure = { error ->
                    RuntimeAutomationResult(
                        status = AutomationCommandStatus.PROFILE_INVALID,
                        summary = "The current profile could not be prepared for runtime startup.",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                },
            )
            mainHandler.post {
                val startedDecision = connectDecisionState(
                    selection = connectSelection,
                    phase = ConnectDecisionPhase.STARTED,
                ).takeIf {
                    result.status == AutomationCommandStatus.SUCCESS &&
                        uiState.profile.id == connectProfileId &&
                        uiState.automationState.runtimeStrategy == connectSelection.selectedStrategyId
                }
                val failedDecision = connectDecisionState(
                    selection = connectSelection,
                    phase = ConnectDecisionPhase.FAILED,
                ).takeIf {
                    result.status != AutomationCommandStatus.SUCCESS &&
                        uiState.profile.id == connectProfileId &&
                        uiState.automationState.runtimeStrategy == connectSelection.selectedStrategyId
                }
                uiState = uiState.copy(
                    controlError = result.error,
                    connectDecisionPhase = startedDecision?.phase ?: failedDecision?.phase,
                    connectDecisionMessage = startedDecision?.message ?: failedDecision?.message,
                )
                refreshRuntimeStatus()
            }
        }
    }

    fun startRuntime() {
        if (!uiState.vpnPermissionGranted) {
            uiState = uiState.copy(controlError = "Grant VpnService permission before requesting runtime start.")
            return
        }
        connectRuntime()
    }

    fun stopRuntime() {
        backgroundExecutor.execute {
            val result = runtimeAutomationOrchestrator.stopRuntime()
            mainHandler.post {
                uiState = uiState.copy(
                    controlError = result.error,
                    connectDecisionPhase = null,
                    connectDecisionMessage = null,
                )
                refreshRuntimeStatus()
            }
        }
    }

    fun refreshRuntimeStatus() {
        runtimeClient.requestStatus()
        maybeRefreshEgressObservation()
    }

    private fun startRuntimeStatusRefreshLoop() {
        mainHandler.removeCallbacks(runtimeStatusRefreshLoop)
        mainHandler.postDelayed(runtimeStatusRefreshLoop, RUNTIME_STATUS_REFRESH_INTERVAL_MS)
    }

    private fun synchronizeEgressObservation(snapshot: VpnRuntimeSnapshot) {
        val phaseChanged = snapshot.phase != lastObservedRuntimePhase
        lastObservedRuntimePhase = snapshot.phase
        maybeRefreshEgressObservation(force = phaseChanged)
    }

    private fun maybeRefreshEgressObservation(force: Boolean = false) {
        if (egressProbeInFlight) {
            if (force) {
                pendingForcedEgressRefresh = true
            }
            return
        }
        val now = System.currentTimeMillis()
        val lastObservedAt = uiState.egressObservation.observedAtEpochMs
        if (!force && lastObservedAt != null && now - lastObservedAt < EGRESS_IP_REFRESH_INTERVAL_MS) {
            return
        }
        val probePhase = uiState.runtimeSnapshot.phase
        egressProbeInFlight = true
        activeEgressProbePhase = probePhase
        pendingForcedEgressRefresh = false
        uiState = uiState.copy(
            egressObservation = EgressObservationState(
                phase = EgressObservationPhase.PROBING,
            ),
        )
        if (probePhase == VpnRuntimePhase.RUNNING) {
            runtimeClient.requestEgressIpObservation()
            mainHandler.removeCallbacks(egressProbeTimeoutRunnable)
            mainHandler.postDelayed(egressProbeTimeoutRunnable, EGRESS_IP_PROBE_TIMEOUT_MS)
            return
        }
        observationExecutor.execute {
            val result = runCatching { egressIpObservationRepository.observe() }
            mainHandler.post {
                mainHandler.removeCallbacks(egressProbeTimeoutRunnable)
                egressProbeInFlight = false
                activeEgressProbePhase = null
                if (uiState.runtimeSnapshot.phase != probePhase) {
                    maybeRefreshEgressObservation(force = true)
                    return@post
                }
                result.onSuccess { observation ->
                    uiState = uiState.copy(
                        egressObservation = EgressObservationState(
                            phase = EgressObservationPhase.OBSERVED,
                            publicIp = observation.publicIp,
                            observedAtEpochMs = observation.observedAtEpochMs,
                            observedAt = formatObservedAt(observation.observedAtEpochMs),
                        ),
                    )
                }.onFailure { error ->
                    uiState = uiState.copy(
                        egressObservation = EgressObservationState(
                            phase = EgressObservationPhase.ERROR,
                            error = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
                drainPendingEgressRefresh()
            }
        }
    }

    private fun handleRuntimeEgressIpObservation(observation: RuntimeEgressIpObservation) {
        mainHandler.removeCallbacks(egressProbeTimeoutRunnable)
        val probePhase = activeEgressProbePhase
        egressProbeInFlight = false
        activeEgressProbePhase = null
        if (probePhase != VpnRuntimePhase.RUNNING || uiState.runtimeSnapshot.phase != VpnRuntimePhase.RUNNING) {
            maybeRefreshEgressObservation(force = true)
            return
        }
        uiState = when (observation.status) {
            RuntimeEgressIpObservationStatus.OBSERVED -> uiState.copy(
                egressObservation = EgressObservationState(
                    phase = EgressObservationPhase.OBSERVED,
                    publicIp = observation.publicIp,
                    observedAtEpochMs = observation.observedAtEpochMs,
                    observedAt = formatObservedAt(observation.observedAtEpochMs),
                ),
            )

            RuntimeEgressIpObservationStatus.UNAVAILABLE,
            RuntimeEgressIpObservationStatus.FAILED -> uiState.copy(
                egressObservation = EgressObservationState(
                    phase = EgressObservationPhase.ERROR,
                    error = observation.summary ?: "Engine-routed exit IP detection failed.",
                ),
            )
        }
        drainPendingEgressRefresh()
    }

    private fun drainPendingEgressRefresh() {
        if (pendingForcedEgressRefresh) {
            pendingForcedEgressRefresh = false
            maybeRefreshEgressObservation(force = true)
        }
    }

    private fun formatObservedAt(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(
        Instant.ofEpochMilli(epochMs),
    )

    fun reloadProfile() {
        runCatching { profileRepository.reload() }
            .onSuccess { storedProfile ->
                applyStoredProfile(
                    storedProfile = storedProfile,
                    status = "Reloaded the encrypted profile from app-private storage.",
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    profileStorage = uiState.profileStorage.copy(
                        status = "Encrypted profile reload failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun resealProfile() {
        runCatching { profileRepository.reseal(uiState.profile) }
            .onSuccess { storedProfile ->
                applyStoredProfile(
                    storedProfile = storedProfile,
                    status = "Re-encrypted the current profile with a fresh AES-GCM envelope.",
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    profileStorage = uiState.profileStorage.copy(
                        status = "Profile reseal failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    fun exportEncryptedBackup() {
        runCatching {
            exportRepository.exportEncryptedProfileBackup(
                profile = uiState.profile,
                compiledConfig = uiState.compiledConfig,
            )
        }.onSuccess { artifact ->
            val exportedState = artifact.toExportArtifactState(
                current = uiState.backupExportState,
                exportRootPath = exportRepository.exportRootPath,
                keyReference = exportRepository.keyReference,
            )
            val shareRequest = runCatching { artifact.toShareRequest("Share encrypted backup") }
            uiState = uiState.copy(
                backupExportState = shareRequest.fold(
                    onSuccess = { exportedState },
                    onFailure = { error ->
                        exportedState.copy(
                            status = "Encrypted backup was saved, but the share sheet could not be prepared.",
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    },
                ),
                pendingShareRequest = shareRequest.getOrNull(),
            )
            Log.i(
                TAG,
                "Encrypted backup exported type=${artifact.artifactType} sharePrepared=${shareRequest.isSuccess}.",
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                backupExportState = uiState.backupExportState.copy(
                    exportRootPath = exportRepository.exportRootPath,
                    keyReference = exportRepository.keyReference,
                    status = "Encrypted backup export failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun buildDiagnosticBundle() {
        runCatching {
            exportRepository.exportRedactedDiagnosticBundle(
                profile = uiState.profile,
                compiledConfig = uiState.compiledConfig,
                tunnelPlanSummary = uiState.tunnelPlan.toSummary(),
                runtimeSnapshot = uiState.runtimeSnapshot,
                profileStorage = uiState.profileStorage,
                automationState = uiState.automationState,
                routePreview = uiState.routePreviewLastTested ?: uiState.routePreview,
                previewOutcome = uiState.previewOutcome,
            )
        }.onSuccess { artifact ->
            val exportedState = artifact.toExportArtifactState(
                current = uiState.auditExportState,
                exportRootPath = exportRepository.exportRootPath,
                keyReference = exportRepository.keyReference,
            )
            val shareRequest = runCatching { artifact.toShareRequest("Share redacted audit") }
            uiState = uiState.copy(
                auditExportState = shareRequest.fold(
                    onSuccess = { exportedState },
                    onFailure = { error ->
                        exportedState.copy(
                            status = "Redacted audit was saved, but the share sheet could not be prepared.",
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    },
                ),
                pendingShareRequest = shareRequest.getOrNull(),
            )
            Log.i(
                TAG,
                "Redacted audit exported type=${artifact.artifactType} sharePrepared=${shareRequest.isSuccess}.",
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                auditExportState = uiState.auditExportState.copy(
                    exportRootPath = exportRepository.exportRootPath,
                    keyReference = exportRepository.keyReference,
                    status = "Diagnostic bundle export failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun consumeShareRequest(requestId: Long) {
        if (uiState.pendingShareRequest?.id == requestId) {
            uiState = uiState.copy(pendingShareRequest = null)
        }
    }

    fun reportShareLaunchFailure(requestId: Long, message: String) {
        val request = uiState.pendingShareRequest?.takeIf { it.id == requestId } ?: return
        uiState = when (request.artifactType) {
            "profile_backup" -> uiState.copy(
                backupExportState = uiState.backupExportState.copy(
                    status = "Encrypted backup was saved, but the share sheet could not open.",
                    error = message,
                ),
                pendingShareRequest = null,
            )
            "diagnostic_bundle" -> uiState.copy(
                auditExportState = uiState.auditExportState.copy(
                    status = "Redacted audit was saved, but the share sheet could not open.",
                    error = message,
                ),
                pendingShareRequest = null,
            )
            else -> uiState.copy(pendingShareRequest = null)
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacks(runtimeStatusRefreshLoop)
        mainHandler.removeCallbacks(egressProbeTimeoutRunnable)
        runtimeClient.unbind()
        backgroundExecutor.shutdownNow()
        observationExecutor.shutdownNow()
        super.onCleared()
    }

    private fun initialState(): MainUiState {
        val compiled = plugin.compile(bootstrapProfile)
        return MainUiState(
            profile = bootstrapProfile,
            compiledConfig = compiled,
            tunnelPlan = TunnelSessionPlanner.plan(compiled),
            routePreview = defaultPreview,
            previewOutcome = evaluatePreview(defaultPreview, bootstrapProfile),
            routePreviewLastTested = null,
            routePreviewLastTestedAtEpochMs = null,
            routePreviewStale = true,
            runtimeSnapshot = VpnRuntimeSnapshot(),
            egressObservation = EgressObservationState(),
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = profileRepository.keyReference,
                storagePath = profileRepository.storagePath,
                status = "Pending encrypted profile load.",
            ),
            backupExportState = ExportArtifactState(
                exportRootPath = exportRepository.exportRootPath,
                keyReference = exportRepository.keyReference,
                status = "No encrypted backup has been generated yet.",
            ),
            auditExportState = ExportArtifactState(
                exportRootPath = exportRepository.exportRootPath,
                keyReference = exportRepository.keyReference,
                status = "No redacted audit bundle has been generated yet.",
            ),
            automationState = AutomationState(
                storagePath = automationRepository.storagePath,
                keyReference = automationRepository.keyReference,
                runtimeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                status = "Anubis automation is disabled by default.",
                vpnPermissionReady = false,
            ),
            subscriptionState = SubscriptionState(
                storagePath = subscriptionRepository.storagePath,
                pendingStoragePath = subscriptionRepository.pendingUpdateStoragePath,
                eventLogStoragePath = subscriptionRepository.eventLogStoragePath,
                notificationLedgerStoragePath = subscriptionRepository.notificationLedgerStoragePath,
                keyReference = subscriptionRepository.keyReference,
                status = "No encrypted subscription source has been configured yet.",
                trustStatus = SubscriptionTrustStatus.NONE.name,
                publisherStatus = SubscriptionPublisherStatus.NONE.name,
                scheduleMode = SubscriptionScheduleMode.MANUAL.summary(),
                scheduledStatus = SubscriptionScheduledStatus.NONE.name,
                notificationsEnabled = subscriptionNotificationPublisher.areNotificationsEnabled(),
                scheduledUpdateAlertsEnabled = true,
                scheduledFailureAlertThreshold = 1,
                scheduledFailureStreak = 0,
                lastAlertStatus = subscriptionNotificationLedger.lastEvaluationStatus.name,
            ),
            regionalBypassStatus = null,
            regionalBypassError = null,
            showRegionalBypassPrompt = false,
        )
    }

    private fun loadAutomationIntegration() {
        runCatching { automationRepository.load() }
            .onSuccess { settings ->
                applyAutomationSettings(
                    settings = settings,
                    status = if (settings.enabled) {
                        "Loaded encrypted Anubis automation settings."
                    } else {
                        "Anubis automation is disabled by default."
                    },
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    automationState = uiState.automationState.copy(
                        status = null,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    private fun loadEncryptedProfile() {
        runCatching { profileRepository.loadOrSeed(bootstrapProfile) }
            .onSuccess { result ->
                val status = if (result.seeded) {
                    "Seeded the bootstrap profile into encrypted storage."
                } else {
                    result.status
                }
                applyStoredProfile(
                    storedProfile = result.storedProfile,
                    status = status,
                    showRegionalBypassPrompt = regionalBypassPromptStore.shouldPrompt(
                        profile = result.storedProfile.profile,
                        seeded = result.seeded,
                    ),
                )
                if (result.seeded) {
                    regionalBypassPromptStore.markDecisionRecorded(result.storedProfile.profile.id)
                }
            }
            .onFailure { error ->
                applyProfile(
                    profile = bootstrapProfile,
                    profileStorage = uiState.profileStorage.copy(
                        status = "Encrypted profile load failed; keeping bootstrap state in memory only.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                    showRegionalBypassPrompt = false,
                )
            }
    }

    private fun loadSubscriptionConfig() {
        runCatching { subscriptionRepository.loadConfig() }
            .onSuccess { config ->
                if (config == null) return@onSuccess
                subscriptionUpdateScheduler.reconcile(config)
                refreshSubscriptionEventLog()
                refreshSubscriptionNotificationLedger()
                val restoredPendingStatus = restorePendingSubscriptionUpdate(config)
                uiState = uiState.copy(
                    subscriptionState = subscriptionStateFromConfig(
                        config = config,
                        status = buildString {
                            append("Loaded encrypted subscription source metadata.")
                            restoredPendingStatus?.let {
                                append(' ')
                                append(it)
                            }
                        },
                    ),
                )
            }
            .onFailure { error ->
                uiState = uiState.copy(
                    subscriptionState = uiState.subscriptionState.copy(
                        status = "Encrypted subscription source load failed.",
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
    }

    private fun subscriptionStateFromConfig(
        config: SubscriptionConfig,
        status: String,
        error: String? = null,
        urlDraft: String = uiState.subscriptionState.urlDraft,
        pinDraft: String = uiState.subscriptionState.pinDraft,
        publisherPinDraft: String = uiState.subscriptionState.publisherPinDraft,
        pendingProfiles: List<ProfileIr> = pendingSubscriptionProfiles,
        pendingSelectedIndex: Int = pendingSubscriptionSelectedIndex,
        eventLog: SubscriptionEventLog = subscriptionEventLog,
        notificationLedger: SubscriptionNotificationLedger = subscriptionNotificationLedger,
    ): SubscriptionState {
        val selectedProfile = pendingProfiles.getOrNull(pendingSelectedIndex)
        val publisherRolloverReady = config.lastObservedPublisherPin != null &&
            config.lastObservedPublisherPin !in config.expectedPublisherPins
        val latestEvent = eventLog.entries.firstOrNull()
        return uiState.subscriptionState.copy(
            pendingStoragePath = subscriptionRepository.pendingUpdateStoragePath,
            eventLogStoragePath = subscriptionRepository.eventLogStoragePath,
            notificationLedgerStoragePath = subscriptionRepository.notificationLedgerStoragePath,
            notificationsEnabled = subscriptionNotificationPublisher.areNotificationsEnabled(),
            urlDraft = urlDraft,
            pinDraft = pinDraft,
            publisherPinDraft = publisherPinDraft,
            storedSourceHash = config.sourceUrlHash,
            status = status,
            lastCheckedAt = config.lastCheckedAtEpochMs?.formatTimestamp(),
            lastAppliedAt = config.lastAppliedAtEpochMs?.formatTimestamp(),
            lastAppliedProfileName = config.lastAppliedProfileName,
            lastFetchedProfileCount = config.lastFetchedProfileCount,
            lastFetchedProfileHash = config.lastFetchedProfileHash,
            lastResolvedUrlHash = config.lastResolvedUrlHash,
            expectedPinCount = config.expectedSpkiPins.size,
            expectedPinSummary = SubscriptionPinPolicy.summarize(config.expectedSpkiPins),
            observedPinCount = config.lastObservedSpkiPins.size,
            observedPinSummary = SubscriptionPinPolicy.summarize(config.lastObservedSpkiPins),
            trustStatus = config.lastTrustStatus.name,
            trustSummary = config.lastTrustSummary,
            expectedPublisherPinCount = config.expectedPublisherPins.size,
            expectedPublisherPinSummary = SubscriptionPinPolicy.summarize(config.expectedPublisherPins),
            observedPublisherPin = config.lastObservedPublisherPin,
            publisherStatus = config.lastPublisherStatus.name,
            publisherSummary = config.lastPublisherSummary,
            publisherRolloverReady = publisherRolloverReady,
            scheduleMode = config.scheduleMode.summary(),
            scheduledStatus = config.lastScheduledStatus.name,
            scheduledSummary = config.lastScheduledSummary,
            scheduledUpdateAlertsEnabled = config.scheduledUpdateAlertsEnabled,
            scheduledFailureAlertThreshold = config.scheduledFailureAlertThreshold,
            scheduledFailureStreak = config.scheduledFailureStreak,
            lastScheduledRunAt = config.lastScheduledRunAtEpochMs?.formatTimestamp(),
            eventCount = eventLog.entries.size,
            latestEventAt = latestEvent?.occurredAtEpochMs?.formatTimestamp(),
            latestEventSummary = latestEvent?.summary,
            inboxEntries = eventLog.entries.mapIndexed { index, event ->
                "Inbox ${index + 1}: ${event.occurredAtEpochMs.formatTimestamp()} ${event.severity.name} ${event.summary}"
            },
            lastAlertStatus = notificationLedger.lastEvaluationStatus.name,
            lastAlertStatusSummary = notificationLedger.lastEvaluationSummary,
            lastAlertEvaluatedAt = notificationLedger.lastEvaluatedAtEpochMs?.formatTimestamp(),
            lastAlertDeliveredAt = notificationLedger.lastDeliveredAtEpochMs?.formatTimestamp(),
            lastAlertDeliveredTarget = notificationLedger.lastDeliveredDestination?.alertLabel(),
            lastAlertDeliveredSummary = notificationLedger.lastDeliveredSummary,
            lastAlertOpenedAt = notificationLedger.lastOpenedAtEpochMs?.formatTimestamp(),
            lastAlertOpenedTarget = notificationLedger.lastOpenedDestination?.alertLabel(),
            lastAlertOpenedSummary = notificationLedger.lastOpenedSummary,
            pendingUpdateProfileCount = pendingProfiles.size,
            pendingSelectedProfileIndex = if (selectedProfile == null) null else pendingSelectedIndex,
            pendingSelectedProfileName = selectedProfile?.name,
            pendingUpdateProfileHash = selectedProfile?.canonicalHash(),
            pendingDiffSummary = selectedProfile?.let {
                SubscriptionDiff.between(
                    activeProfile = uiState.profile,
                    fetchedProfile = it,
                    fetchedProfileCount = pendingProfiles.size,
                ).summary()
            },
            error = error,
        )
    }

    private fun clearPendingSubscriptionSelection() {
        pendingSubscriptionProfiles = emptyList()
        pendingSubscriptionSelectedIndex = 0
        pendingSubscriptionConfig = null
    }

    private fun refreshSubscriptionEventLog() {
        subscriptionEventLog = runCatching { subscriptionRepository.loadEventLog() }
            .getOrElse { SubscriptionEventLog() }
    }

    private fun refreshSubscriptionNotificationLedger() {
        subscriptionNotificationLedger = runCatching { subscriptionRepository.loadNotificationLedger() }
            .getOrElse { SubscriptionNotificationLedger() }
    }

    private fun restorePendingSubscriptionUpdate(config: SubscriptionConfig): String? {
        val pending = runCatching { subscriptionRepository.loadPendingUpdate() }.getOrNull()
        if (pending == null) {
            clearPendingSubscriptionSelection()
            return null
        }
        if (pending.sourceUrlHash != config.sourceUrlHash || pending.profiles.isEmpty()) {
            subscriptionRepository.clearPendingUpdate()
            clearPendingSubscriptionSelection()
            return null
        }
        pendingSubscriptionConfig = config
        pendingSubscriptionProfiles = pending.profiles
        pendingSubscriptionSelectedIndex = pending.selectedProfileIndex.coerceIn(0, pending.profiles.lastIndex)
        return if (pending.profiles.size == 1) {
            "Restored a staged pending subscription update from encrypted storage."
        } else {
            "Restored ${pending.profiles.size} staged subscription candidates from encrypted storage."
        }
    }

    private fun selectedPendingSubscriptionProfile(): ProfileIr? =
        pendingSubscriptionProfiles.getOrNull(pendingSubscriptionSelectedIndex)

    private fun shiftPendingSubscriptionSelection(step: Int) {
        if (pendingSubscriptionProfiles.isEmpty() || pendingSubscriptionConfig == null) {
            uiState = uiState.copy(
                subscriptionState = uiState.subscriptionState.copy(
                    status = "No pending subscription candidates are available.",
                    error = null,
                ),
            )
            return
        }
        if (pendingSubscriptionProfiles.size == 1) {
            uiState = uiState.copy(
                subscriptionState = subscriptionStateFromConfig(
                    config = pendingSubscriptionConfig!!,
                    status = "Only one pending subscription candidate is available.",
                ),
            )
            return
        }
        pendingSubscriptionSelectedIndex = Math.floorMod(
            pendingSubscriptionSelectedIndex + step,
            pendingSubscriptionProfiles.size,
        )
        uiState = uiState.copy(
            subscriptionState = subscriptionStateFromConfig(
                config = pendingSubscriptionConfig!!,
                status = "Selected subscription candidate ${pendingSubscriptionSelectedIndex + 1} of ${pendingSubscriptionProfiles.size}.",
            ),
        )
    }

    private fun stageImportPayload(
        payload: String,
        source: ImportCaptureSource,
    ) {
        runCatching { ProfileImportParser.parse(payload) }
            .onSuccess { imported ->
                pendingImportedProfile = imported
                uiState = uiState.copy(
                    importPreview = imported.toImportPreviewState(
                        source = source,
                        selectedStrategyId = uiState.automationState.runtimeStrategy,
                    ),
                    importStatus = "Validated a staged ${source.summary()} import. Confirm to seal it into encrypted storage.",
                    importError = null,
                )
            }
            .onFailure { error ->
                clearStagedImport()
                uiState = uiState.copy(
                    importPreview = null,
                    importStatus = null,
                    importError = error.message ?: error.javaClass.simpleName,
                )
            }
    }

    private fun clearStagedImport() {
        pendingImportedProfile = null
    }

    private fun refreshStagedImportPreview(runtimeStrategy: EmbeddedRuntimeStrategyId) {
        val imported = pendingImportedProfile ?: return
        val preview = uiState.importPreview ?: return
        uiState = uiState.copy(
            importPreview = imported.toImportPreviewState(
                source = preview.source,
                selectedStrategyId = runtimeStrategy,
            ),
        )
    }

    private fun applyAutomationSettings(
        settings: AutomationIntegrationSettings,
        status: String?,
    ) {
        uiState = uiState.copy(
            automationState = uiState.automationState.copy(
                enabled = settings.enabled,
                rawToken = settings.token,
                tokenPreview = settings.token?.let(::maskAutomationToken),
                runtimeStrategy = settings.runtimeStrategy,
                lastAutomationStatus = settings.lastAutomationStatus.name,
                lastAutomationError = settings.lastAutomationError,
                lastAutomationErrorSection = settings.lastAutomationErrorSection,
                lastAutomationErrorFieldPath = settings.lastAutomationErrorFieldPath,
                lastAutomationRuntimeMetadata = settings.lastAutomationRuntimeMetadata,
                lastAutomationAt = settings.lastAutomationAtEpochMs?.formatTimestamp(),
                lastCallerHint = settings.lastCallerHint,
                vpnPermissionReady = VpnService.prepare(getApplication()) == null,
                status = status,
                error = null,
            ),
        )
        synchronizeConfiguredRuntimeStrategy()
    }

    private fun stageAndStartRuntime() {
        runCatching {
            Log.i(
                TAG,
                "Staging runtime with profile='${uiState.profile.name}' config='${uiState.compiledConfig.configHash.take(12)}'.",
            )
            runtimeClient.stageRuntime(
                plan = uiState.tunnelPlan,
                compiledConfig = uiState.compiledConfig,
                profile = uiState.profile,
                runtimeStrategy = uiState.automationState.runtimeStrategy,
            )
            runtimeClient.startRuntime()
            runtimeClient.requestStatus()
        }.onSuccess {
            uiState = uiState.copy(controlError = null)
        }.onFailure { error ->
            uiState = uiState.copy(controlError = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun mutateProfile(
        status: String,
        transform: (ProfileIr) -> ProfileIr,
        onSuccess: (String) -> Unit = { appliedStatus ->
            uiState = uiState.copy(
                regionalBypassStatus = appliedStatus,
                regionalBypassError = null,
                showRegionalBypassPrompt = false,
            )
        },
        onFailure: (Throwable) -> Unit = { error ->
            uiState = uiState.copy(
                regionalBypassStatus = null,
                regionalBypassError = error.message ?: error.javaClass.simpleName,
            )
        },
    ) {
        runCatching {
            val updatedProfile = transform(uiState.profile)
            val storedProfile = profileRepository.reseal(updatedProfile)
            regionalBypassPromptStore.markDecisionRecorded(updatedProfile.id)
            storedProfile
        }.onSuccess { storedProfile ->
            applyStoredProfile(
                storedProfile = storedProfile,
                status = status,
                showRegionalBypassPrompt = false,
            )
            onSuccess(status)
        }.onFailure { error ->
            onFailure(error)
        }
    }

    private fun applyStoredProfile(
        storedProfile: StoredProfile,
        status: String,
        showRegionalBypassPrompt: Boolean? = null,
    ) {
        applyProfile(
            profile = storedProfile.profile,
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = storedProfile.keyReference ?: profileRepository.keyReference,
                storagePath = storedProfile.path.toString(),
                status = status,
                persistedProfileHash = storedProfile.profileHash,
                lastPersistedAt = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli(storedProfile.encryptedAtEpochMs),
                ),
            ),
            showRegionalBypassPrompt = showRegionalBypassPrompt,
        )
    }

    private fun applyProfile(
        profile: ProfileIr,
        profileStorage: ProfileStorageState,
        showRegionalBypassPrompt: Boolean? = null,
    ) {
        clearStagedImport()
        val compiled = plugin.compile(profile)
        uiState = uiState.copy(
            profile = profile,
            compiledConfig = compiled,
            tunnelPlan = TunnelSessionPlanner.plan(compiled),
            routePreviewStale = true,
            profileStorage = profileStorage,
            importPreview = null,
            connectDecisionPhase = null,
            connectDecisionMessage = null,
            showRegionalBypassPrompt = showRegionalBypassPrompt ?: uiState.showRegionalBypassPrompt,
            profileEditorStatus = null,
            profileEditorError = null,
        )
        synchronizeConfiguredRuntimeStrategy()
    }

    private fun evaluatePreview(
        preview: PreviewInputs,
        profile: ProfileIr,
    ): RoutePreviewOutcome = previewEngine.evaluate(
        profile = profile,
        request = RoutePreviewRequest(
            packageName = preview.packageName.takeIf { it.isNotBlank() },
            destinationHost = preview.destinationHost.takeIf { it.isNotBlank() },
            destinationIp = preview.destinationIp.takeIf { it.isNotBlank() },
            destinationPort = preview.destinationPort.toIntOrNull(),
            protocol = preview.protocol,
        ),
    )

    private fun ExportArtifactRecord.toExportArtifactState(
        current: ExportArtifactState,
        exportRootPath: String,
        keyReference: String,
    ): ExportArtifactState = current.copy(
        exportRootPath = exportRootPath,
        keyReference = keyReference,
        lastArtifactType = artifactType,
        lastArtifactPath = path,
        lastArtifactHash = payloadHash,
        lastCreatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(createdAtEpochMs)),
        lastRedacted = redacted,
        status = summary,
        error = null,
    )

    private fun ExportArtifactRecord.toShareRequest(chooserTitle: String): ArtifactShareRequest {
        val uri = FileProvider.getUriForFile(
            getApplication(),
            EXPORT_FILE_PROVIDER_AUTHORITY,
            File(path),
        )
        return ArtifactShareRequest(
            id = nextShareRequestId++,
            artifactType = artifactType,
            contentUri = uri.toString(),
            mimeType = "application/octet-stream",
            chooserTitle = chooserTitle,
        )
    }
}

private const val TAG: String = "MainViewModel"
private const val AUTOMATION_TOKEN_CLIPBOARD_CLEAR_DELAY_MS: Long = 10_000L
private const val RUNTIME_STATUS_REFRESH_INTERVAL_MS: Long = 5_000L
private const val EGRESS_IP_REFRESH_INTERVAL_MS: Long = 15_000L
private const val EGRESS_IP_PROBE_TIMEOUT_MS: Long = 12_000L
private const val EXPORT_FILE_PROVIDER_AUTHORITY: String = "io.acionyx.tunguska.fileprovider"

private fun maskAutomationToken(token: String): String = when {
    token.length <= 10 -> token
    else -> "${token.take(6)}...${token.takeLast(4)}"
}

data class MainUiState(
    val profile: ProfileIr,
    val compiledConfig: CompiledEngineConfig,
    val tunnelPlan: TunnelSessionPlan,
    val routePreview: PreviewInputs,
    val previewOutcome: RoutePreviewOutcome,
    val routePreviewLastTested: PreviewInputs?,
    val routePreviewLastTestedAtEpochMs: Long?,
    val routePreviewStale: Boolean,
    val runtimeSnapshot: VpnRuntimeSnapshot,
    val egressObservation: EgressObservationState,
    val profileStorage: ProfileStorageState,
    val backupExportState: ExportArtifactState,
    val auditExportState: ExportArtifactState,
    val automationState: AutomationState,
    val subscriptionState: SubscriptionState,
    val pendingShareRequest: ArtifactShareRequest? = null,
    val importDraft: String = "",
    val importPreview: ImportPreviewState? = null,
    val importStatus: String? = null,
    val importError: String? = null,
    val connectDecisionPhase: ConnectDecisionPhase? = null,
    val connectDecisionMessage: String? = null,
    val vpnPermissionGranted: Boolean = false,
    val controlChannelConnected: Boolean = false,
    val controlError: String? = null,
    val regionalBypassStatus: String? = null,
    val regionalBypassError: String? = null,
    val profileEditorStatus: String? = null,
    val profileEditorError: String? = null,
    val showRegionalBypassPrompt: Boolean = false,
) {
    internal val connectDecision: ConnectDecisionState?
        get() = connectDecisionPhase?.let { phase ->
            connectDecisionMessage?.let { message ->
                ConnectDecisionState(
                    phase = phase,
                    message = message,
                )
            }
        }

    internal val runtimeSelection: RuntimeSelectionState
        get() = runtimeSelectionState(
            profile = profile,
            activeStrategyId = runtimeSnapshot.activeStrategy,
            configuredStrategyId = runtimeSnapshot.configuredStrategy ?: automationState.runtimeStrategy,
            stagedMetadata = runtimeSnapshot.laneCompatibility,
            configuredMetadata = runtimeSnapshot.configuredLaneCompatibility,
        )

    internal val configuredSelection: ConfiguredSelectionState
        get() = configuredSelectionState(
            profile = profile,
            selectedStrategyId = automationState.runtimeStrategy,
        )
}

data class EgressObservationState(
    val phase: EgressObservationPhase = EgressObservationPhase.IDLE,
    val publicIp: String? = null,
    val observedAtEpochMs: Long? = null,
    val observedAt: String? = null,
    val error: String? = null,
)

enum class EgressObservationPhase {
    IDLE,
    PROBING,
    OBSERVED,
    ERROR,
}

data class ProfileStorageState(
    val backend: String,
    val keyReference: String,
    val storagePath: String,
    val status: String,
    val persistedProfileHash: String? = null,
    val lastPersistedAt: String? = null,
    val error: String? = null,
)

data class SubscriptionState(
    val storagePath: String,
    val pendingStoragePath: String,
    val eventLogStoragePath: String,
    val notificationLedgerStoragePath: String,
    val notificationsEnabled: Boolean,
    val scheduledUpdateAlertsEnabled: Boolean,
    val scheduledFailureAlertThreshold: Int,
    val scheduledFailureStreak: Int,
    val keyReference: String,
    val status: String,
    val trustStatus: String,
    val publisherStatus: String,
    val scheduleMode: String,
    val scheduledStatus: String,
    val urlDraft: String = "",
    val pinDraft: String = "",
    val publisherPinDraft: String = "",
    val storedSourceHash: String? = null,
    val lastCheckedAt: String? = null,
    val lastAppliedAt: String? = null,
    val lastScheduledRunAt: String? = null,
    val lastAppliedProfileName: String? = null,
    val lastFetchedProfileCount: Int = 0,
    val lastFetchedProfileHash: String? = null,
    val lastResolvedUrlHash: String? = null,
    val expectedPinCount: Int = 0,
    val expectedPinSummary: String? = null,
    val observedPinCount: Int = 0,
    val observedPinSummary: String? = null,
    val trustSummary: String? = null,
    val expectedPublisherPinCount: Int = 0,
    val expectedPublisherPinSummary: String? = null,
    val observedPublisherPin: String? = null,
    val publisherSummary: String? = null,
    val publisherRolloverReady: Boolean = false,
    val scheduledSummary: String? = null,
    val eventCount: Int = 0,
    val latestEventAt: String? = null,
    val latestEventSummary: String? = null,
    val inboxEntries: List<String> = emptyList(),
    val lastAlertStatus: String,
    val lastAlertStatusSummary: String? = null,
    val lastAlertEvaluatedAt: String? = null,
    val lastAlertDeliveredAt: String? = null,
    val lastAlertDeliveredTarget: String? = null,
    val lastAlertDeliveredSummary: String? = null,
    val lastAlertOpenedAt: String? = null,
    val lastAlertOpenedTarget: String? = null,
    val lastAlertOpenedSummary: String? = null,
    val notificationAttention: SubscriptionNotificationRoute? = null,
    val notificationAttentionToken: Long? = null,
    val pendingUpdateProfileCount: Int = 0,
    val pendingSelectedProfileIndex: Int? = null,
    val pendingSelectedProfileName: String? = null,
    val pendingUpdateProfileHash: String? = null,
    val pendingDiffSummary: String? = null,
    val error: String? = null,
)

data class ExportArtifactState(
    val exportRootPath: String,
    val keyReference: String,
    val status: String,
    val lastArtifactType: String? = null,
    val lastArtifactPath: String? = null,
    val lastArtifactHash: String? = null,
    val lastCreatedAt: String? = null,
    val lastRedacted: Boolean? = null,
    val error: String? = null,
)

data class ArtifactShareRequest(
    val id: Long,
    val artifactType: String,
    val contentUri: String,
    val mimeType: String,
    val chooserTitle: String,
)

data class AutomationState(
    val enabled: Boolean = false,
    val rawToken: String? = null,
    val tokenPreview: String? = null,
    val storagePath: String,
    val keyReference: String,
    val runtimeStrategy: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
    val vpnPermissionReady: Boolean,
    val lastAutomationStatus: String = AutomationCommandStatus.NEVER_RUN.name,
    val lastAutomationError: String? = null,
    val lastAutomationErrorSection: String? = null,
    val lastAutomationErrorFieldPath: String? = null,
    val lastAutomationRuntimeMetadata: AutomationRuntimeMetadata? = null,
    val lastAutomationAt: String? = null,
    val lastCallerHint: String? = null,
    val status: String? = null,
    val error: String? = null,
)

data class PreviewInputs(
    val packageName: String = "",
    val destinationHost: String = "api.ipify.org",
    val destinationIp: String = "",
    val destinationPort: String = "443",
    val protocol: NetworkProtocol = NetworkProtocol.TCP,
)

private fun TunnelSessionPlan.toSummary(): TunnelPlanSummary = TunnelPlanSummary(
    processNameSuffix = processNameSuffix,
    preserveLoopback = preserveLoopback,
    splitTunnelMode = splitTunnelMode::class.simpleName.orEmpty(),
    allowedPackageCount = allowedPackages.size,
    disallowedPackageCount = disallowedPackages.size,
    runtimeMode = runtimeMode.name,
)

private fun Long.formatTimestamp(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(this))

private fun SubscriptionNotificationDestination.alertLabel(): String = when (this) {
    SubscriptionNotificationDestination.REVIEW_UPDATE -> "Review pending update"
    SubscriptionNotificationDestination.REVIEW_INBOX -> "Review inbox failure"
}
