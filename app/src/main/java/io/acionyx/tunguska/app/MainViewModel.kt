package io.acionyx.tunguska.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
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
import io.acionyx.tunguska.vpnservice.TunnelSessionPlan
import io.acionyx.tunguska.vpnservice.TunnelSessionPlanner
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
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
    private val subscriptionUpdateScheduler = SubscriptionUpdateScheduler(application)
    private val subscriptionNotificationPublisher = SubscriptionNotificationPublisher(application)
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimeAutomationOrchestrator = RuntimeAutomationOrchestrator(
        context = application,
        profileRepository = profileRepository,
    )
    private val runtimeClient = VpnRuntimeClient(
        context = application,
        onConnectionChanged = { connected ->
            uiState = uiState.copy(controlChannelConnected = connected)
        },
        onStatus = { snapshot, error ->
            Log.i(
                TAG,
                "Runtime status phase=${snapshot.phase} " +
                    "engineSession=${snapshot.engineSessionStatus} " +
                    "health=${snapshot.engineSessionHealthStatus} " +
                    "error='${error ?: snapshot.lastError ?: "none"}'",
            )
            uiState = uiState.copy(
                runtimeSnapshot = snapshot,
                controlError = error ?: snapshot.lastError,
            )
        },
    )
    private var pendingSubscriptionProfiles: List<ProfileIr> = emptyList()
    private var pendingSubscriptionSelectedIndex: Int = 0
    private var pendingSubscriptionConfig: SubscriptionConfig? = null
    private var subscriptionEventLog: SubscriptionEventLog = SubscriptionEventLog()
    private var subscriptionNotificationLedger: SubscriptionNotificationLedger = SubscriptionNotificationLedger()
    private var pendingImportedProfile: ImportedProfile? = null

    var uiState: MainUiState by mutableStateOf(initialState())
        private set

    init {
        refreshSubscriptionNotificationStatus()
        loadAutomationIntegration()
        loadEncryptedProfile()
        loadSubscriptionConfig()
        runtimeClient.bind()
    }

    fun updatePreview(
        packageName: String = uiState.routePreview.packageName,
        destinationHost: String = uiState.routePreview.destinationHost,
        destinationIp: String = uiState.routePreview.destinationIp,
        destinationPort: String = uiState.routePreview.destinationPort,
    ) {
        val preview = PreviewInputs(
            packageName = packageName,
            destinationHost = destinationHost,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
        )
        uiState = uiState.copy(
            routePreview = preview,
            previewOutcome = evaluatePreview(preview, uiState.profile),
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
                profileCanonicalJson = uiState.profile.canonicalJson(),
                runtimeStrategy = uiState.automationState.runtimeStrategy,
            )
        }.onFailure { error ->
            uiState = uiState.copy(controlError = error.message ?: error.javaClass.simpleName)
        }
    }

    fun connectRuntime() {
        if (!uiState.vpnPermissionGranted) {
            uiState = uiState.copy(controlError = "Grant VpnService permission before connecting.")
            return
        }
        uiState = uiState.copy(controlError = null)
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
                uiState = uiState.copy(controlError = result.error)
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
                uiState = uiState.copy(controlError = result.error)
                refreshRuntimeStatus()
            }
        }
    }

    fun refreshRuntimeStatus() {
        runtimeClient.requestStatus()
    }

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
            uiState = uiState.copy(
                exportState = uiState.exportState.copy(
                    exportRootPath = exportRepository.exportRootPath,
                    keyReference = exportRepository.keyReference,
                    lastArtifactType = artifact.artifactType,
                    lastArtifactPath = artifact.path,
                    lastArtifactHash = artifact.payloadHash,
                    lastCreatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(artifact.createdAtEpochMs)),
                    lastRedacted = artifact.redacted,
                    status = artifact.summary,
                    error = null,
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                exportState = uiState.exportState.copy(
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
                routePreview = uiState.routePreview,
                previewOutcome = uiState.previewOutcome,
            )
        }.onSuccess { artifact ->
            uiState = uiState.copy(
                exportState = uiState.exportState.copy(
                    exportRootPath = exportRepository.exportRootPath,
                    keyReference = exportRepository.keyReference,
                    lastArtifactType = artifact.artifactType,
                    lastArtifactPath = artifact.path,
                    lastArtifactHash = artifact.payloadHash,
                    lastCreatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(artifact.createdAtEpochMs)),
                    lastRedacted = artifact.redacted,
                    status = artifact.summary,
                    error = null,
                ),
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                exportState = uiState.exportState.copy(
                    exportRootPath = exportRepository.exportRootPath,
                    keyReference = exportRepository.keyReference,
                    status = "Diagnostic bundle export failed.",
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    override fun onCleared() {
        runtimeClient.unbind()
        backgroundExecutor.shutdownNow()
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
            runtimeSnapshot = VpnRuntimeSnapshot(),
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = profileRepository.keyReference,
                storagePath = profileRepository.storagePath,
                status = "Pending encrypted profile load.",
            ),
            exportState = ExportState(
                exportRootPath = exportRepository.exportRootPath,
                keyReference = exportRepository.keyReference,
                status = "No encrypted export has been generated yet.",
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
                    importPreview = ImportPreviewState(
                        source = source,
                        normalizedSourceSummary = imported.source.summary,
                        sourceScheme = imported.source.rawScheme.ifBlank { imported.source.normalizedScheme },
                        profileName = imported.profile.name,
                        endpointSummary = "${imported.profile.outbound.address}:${imported.profile.outbound.port}",
                        profileHash = imported.profile.canonicalHash(),
                        warnings = imported.warnings,
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
                lastAutomationAt = settings.lastAutomationAtEpochMs?.formatTimestamp(),
                lastCallerHint = settings.lastCallerHint,
                vpnPermissionReady = VpnService.prepare(getApplication()) == null,
                status = status,
                error = null,
            ),
        )
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
                profileCanonicalJson = uiState.profile.canonicalJson(),
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
            uiState = uiState.copy(
                regionalBypassStatus = status,
                regionalBypassError = null,
                showRegionalBypassPrompt = false,
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                regionalBypassStatus = null,
                regionalBypassError = error.message ?: error.javaClass.simpleName,
            )
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
            previewOutcome = evaluatePreview(uiState.routePreview, profile),
            profileStorage = profileStorage,
            importPreview = null,
            showRegionalBypassPrompt = showRegionalBypassPrompt ?: uiState.showRegionalBypassPrompt,
        )
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
            protocol = NetworkProtocol.TCP,
        ),
    )
}

private const val TAG: String = "MainViewModel"
private const val AUTOMATION_TOKEN_CLIPBOARD_CLEAR_DELAY_MS: Long = 10_000L

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
    val runtimeSnapshot: VpnRuntimeSnapshot,
    val profileStorage: ProfileStorageState,
    val exportState: ExportState,
    val automationState: AutomationState,
    val subscriptionState: SubscriptionState,
    val importDraft: String = "",
    val importPreview: ImportPreviewState? = null,
    val importStatus: String? = null,
    val importError: String? = null,
    val vpnPermissionGranted: Boolean = false,
    val controlChannelConnected: Boolean = false,
    val controlError: String? = null,
    val regionalBypassStatus: String? = null,
    val regionalBypassError: String? = null,
    val showRegionalBypassPrompt: Boolean = false,
)

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

data class ExportState(
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
    val lastAutomationAt: String? = null,
    val lastCallerHint: String? = null,
    val status: String? = null,
    val error: String? = null,
)

data class PreviewInputs(
    val packageName: String = "io.acionyx.browser",
    val destinationHost: String = "login.corp.example",
    val destinationIp: String = "",
    val destinationPort: String = "443",
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
