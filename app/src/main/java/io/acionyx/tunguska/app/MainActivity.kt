package io.acionyx.tunguska.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.acionyx.tunguska.domain.RegionalBypassPresetId
import io.acionyx.tunguska.domain.normalizeDomainForRouting
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var notificationRoute by mutableStateOf<SubscriptionNotificationRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRoute = SubscriptionNotificationIntents.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            TunguskaApp(
                notificationRoute = notificationRoute,
                onNotificationRouteConsumed = { notificationRoute = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute = SubscriptionNotificationIntents.fromIntent(intent)
    }
}

@Composable
private fun TunguskaApp(
    viewModel: MainViewModel = viewModel(),
    notificationRoute: SubscriptionNotificationRoute? = null,
    onNotificationRouteConsumed: () -> Unit = {},
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val compactLayout = LocalConfiguration.current.screenWidthDp <= 320
    val subscriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var showAdvancedDiagnostics by remember { mutableStateOf(false) }
    var showRegionalBypassAdvanced by remember { mutableStateOf(false) }
    var showFrozenSecondarySurface by remember { mutableStateOf(false) }
    var regionalDirectDomainDraft by remember { mutableStateOf("") }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.markVpnPermissionGranted()
            viewModel.connectRuntime()
        }
    }
    LaunchedEffect(notificationRoute) {
        notificationRoute?.let {
            viewModel.openSubscriptionNotificationRoute(it)
            onNotificationRouteConsumed()
        }
    }
    LaunchedEffect(state.subscriptionState.notificationAttentionToken) {
        if (state.subscriptionState.notificationAttentionToken != null) {
            showFrozenSecondarySurface = true
            subscriptionBringIntoViewRequester.bringIntoView()
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshRuntimeStatus()
        viewModel.refreshAutomationIntegrationStatus()
        onPauseOrDispose { }
    }

    MaterialTheme(
        colorScheme = TunguskaChrome.colorScheme,
        typography = TunguskaChrome.typography,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF6F1E7),
                                Color(0xFFE4ECE7),
                            ),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag(UiTags.MAIN_SCROLL_COLUMN)
                        .padding(
                            horizontal = if (compactLayout) 12.dp else 20.dp,
                            vertical = if (compactLayout) 20.dp else 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 16.dp),
                ) {
                    HeroCard(
                        title = "Tunguska",
                        subtitle = "${state.profile.name} - ${state.profile.outbound.address}:${state.profile.outbound.port}",
                        chips = listOf(
                            phaseChip(state.runtimeSnapshot.phase),
                            splitTunnelLabel(state.tunnelPlan.splitTunnelMode),
                            if (state.tunnelPlan.preserveLoopback) "Loopback local" else "Loopback tunneled",
                        ),
                    )

                    ConnectionOverviewCard(
                        state = state,
                        onConnect = {
                            val intent = VpnService.prepare(context)
                            if (intent == null) {
                                viewModel.markVpnPermissionGranted()
                                viewModel.connectRuntime()
                            } else {
                                requestPermission.launch(intent)
                            }
                        },
                        onStop = viewModel::stopRuntime,
                        onRefresh = viewModel::refreshRuntimeStatus,
                    )

                    RegionalBypassCard(
                        state = state,
                        expanded = showRegionalBypassAdvanced,
                        customDirectDomainDraft = regionalDirectDomainDraft,
                        onToggleRussia = viewModel::setRussiaDirectEnabled,
                        onToggleExpanded = { showRegionalBypassAdvanced = !showRegionalBypassAdvanced },
                        onCustomDirectDomainDraftChange = { regionalDirectDomainDraft = it },
                        onAddCustomDirectDomain = {
                            if (runCatching { normalizeDomainForRouting(regionalDirectDomainDraft) }.isSuccess) {
                                viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                                regionalDirectDomainDraft = ""
                            } else {
                                viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                            }
                        },
                        onRemoveCustomDirectDomain = viewModel::removeRegionalDirectDomain,
                        onPackageChange = { viewModel.updatePreview(packageName = it) },
                        onHostChange = { viewModel.updatePreview(destinationHost = it) },
                        onIpChange = { viewModel.updatePreview(destinationIp = it) },
                        onPortChange = { viewModel.updatePreview(destinationPort = it) },
                        onPromptDecision = viewModel::decideRegionalBypassPrompt,
                    )

                    ImportSection(
                        state = state,
                        onDraftChange = viewModel::updateImportDraft,
                        onValidateDraft = viewModel::stageImportDraft,
                        onConfirmImport = viewModel::confirmStagedImport,
                        onDiscardImport = viewModel::discardStagedImport,
                        onQrPayloadDetected = viewModel::stageQrImportPayload,
                        onImportError = viewModel::reportImportError,
                    )

                    DetailCard(
                        title = "Protection Summary",
                        body = listOf(
                            "Runtime lane: xray+tun2socks is the active MVP path.",
                            "Local bridge: loopback-only, authenticated, per-session random port and credentials, and no management API surface.",
                            "Split routing: ${splitTunnelDetail(state.tunnelPlan.splitTunnelMode)}.",
                            "Listener audit: ${state.runtimeSnapshot.auditStatus} ${state.runtimeSnapshot.lastAuditSummary ?: ""}".trim(),
                            "Routed traffic observed: ${state.runtimeSnapshot.routedTrafficObserved}. DNS failure observed: ${state.runtimeSnapshot.dnsFailureObserved}.",
                        ),
                        actions = {
                            ActionGroup {
                                ActionButton(
                                    text = if (showAdvancedDiagnostics) "Hide Advanced" else "Show Advanced",
                                    onClick = { showAdvancedDiagnostics = !showAdvancedDiagnostics },
                                )
                                ActionButton(
                                    text = if (showFrozenSecondarySurface) "Hide Secondary" else "Secondary Surface",
                                    onClick = { showFrozenSecondarySurface = !showFrozenSecondarySurface },
                                    primary = false,
                                )
                            }
                        },
                    )

                    DetailCard(
                        title = "Redacted Diagnostics",
                        body = listOf(
                            "Status: ${state.exportState.status}",
                            "Last artifact: ${state.exportState.lastArtifactType ?: "none"}",
                            "Payload hash: ${state.exportState.lastArtifactHash ?: "n/a"}",
                            "Created at: ${state.exportState.lastCreatedAt ?: "n/a"}",
                        ),
                        actions = {
                            ActionGroup {
                                ActionButton(text = "Encrypted Backup", onClick = { viewModel.exportEncryptedBackup() })
                                ActionButton(text = "Redacted Audit", onClick = { viewModel.buildDiagnosticBundle() })
                            }
                        },
                    )

                    AutomationIntegrationCard(
                        state = state.automationState,
                        onToggleEnabled = viewModel::setAutomationEnabled,
                        onRotateToken = viewModel::rotateAutomationToken,
                        onCopyToken = viewModel::copyAutomationToken,
                        onRefresh = viewModel::refreshAutomationIntegrationStatus,
                    )

                    DetailCard(
                        title = "Advanced Diagnostics",
                        body = buildList {
                            add("Hidden by default so the first screen stays on import, connect, status, and diagnostics.")
                            add("Current mode: ${if (showAdvancedDiagnostics) "expanded" else "collapsed"}")
                            add("Primary runtime: xray+tun2socks.")
                        },
                        actions = {
                            ActionGroup {
                                ActionButton(
                                    text = if (showAdvancedDiagnostics) "Hide Diagnostics" else "Show Diagnostics",
                                    onClick = { showAdvancedDiagnostics = !showAdvancedDiagnostics },
                                    modifier = Modifier.testTag(UiTags.SHOW_DIAGNOSTICS_BUTTON),
                                )
                                ActionButton(
                                    text = "Refresh Runtime",
                                    onClick = { viewModel.refreshRuntimeStatus() },
                                    primary = false,
                                )
                                ActionButton(
                                    text = "Restage",
                                    onClick = { viewModel.stageRuntime() },
                                    modifier = Modifier.testTag(UiTags.RESTAGE_RUNTIME_BUTTON),
                                    primary = false,
                                )
                            }
                        },
                    )

                    if (showAdvancedDiagnostics) {
                        DetailCard(
                            title = "Secure Profile Storage",
                            body = listOf(
                                "Backend: ${state.profileStorage.backend}",
                                "Key reference: ${state.profileStorage.keyReference}",
                                "File: ${state.profileStorage.storagePath}",
                                "Status: ${state.profileStorage.status}",
                                "Persisted hash: ${state.profileStorage.persistedProfileHash ?: "pending"}",
                                "Last sealed: ${state.profileStorage.lastPersistedAt ?: "n/a"}",
                                "Last storage error: ${state.profileStorage.error ?: "none"}",
                            ),
                            actions = {
                                ActionGroup {
                                    ActionButton(text = "Reload", onClick = { viewModel.reloadProfile() }, primary = false)
                                    ActionButton(text = "Reseal", onClick = { viewModel.resealProfile() }, primary = false)
                                }
                            },
                        )

                        DetailCard(
                            title = "Deterministic Build Surface",
                            body = listOf(
                                "Canonical profile hash: ${state.profile.canonicalHash()}",
                                "Compiled config hash: ${state.compiledConfig.configHash}",
                                "Compiled payload bytes: ${state.compiledConfig.payload.length}",
                                "Tun loopback preservation: ${state.compiledConfig.vpnDirectives.preserveLoopback}",
                                "Safe mode enforced: ${state.compiledConfig.vpnDirectives.safeMode}",
                            ),
                        )

                        DetailCard(
                            title = "Tunnel Plan",
                            body = listOf(
                                "Process: ${state.tunnelPlan.processNameSuffix}",
                                "Preserve loopback: ${state.tunnelPlan.preserveLoopback}",
                                "Split mode: ${state.tunnelPlan.splitTunnelMode::class.simpleName}",
                                "Allowed apps: ${state.tunnelPlan.allowedPackages.ifEmpty { listOf("all") }.joinToString()}",
                                "Excluded apps: ${state.tunnelPlan.disallowedPackages.ifEmpty { listOf("none") }.joinToString()}",
                                "Runtime policy: ${state.tunnelPlan.runtimeMode}",
                            ),
                        )

                        DetailCard(
                            title = "Runtime Internals",
                            body = listOf(
                                "Payload bytes: ${state.runtimeSnapshot.compiledPayloadBytes}",
                                "Strategy: ${state.runtimeSnapshot.activeStrategy ?: "n/a"}",
                                "Bridge port: ${state.runtimeSnapshot.bridgePort ?: 0}",
                                "xray pid: ${state.runtimeSnapshot.xrayPid ?: 0}",
                                "tun2socks pid: ${state.runtimeSnapshot.tun2socksPid ?: 0}",
                                "Own package bypasses VPN: ${state.runtimeSnapshot.ownPackageBypassesVpn}",
                                "Routed traffic observed: ${state.runtimeSnapshot.routedTrafficObserved}",
                                "Last routed traffic: ${formatTimestamp(state.runtimeSnapshot.lastRoutedTrafficAtEpochMs)}",
                                "DNS failure observed: ${state.runtimeSnapshot.dnsFailureObserved}",
                                "DNS failure summary: ${state.runtimeSnapshot.lastDnsFailureSummary ?: "none"}",
                                "MTU: ${state.runtimeSnapshot.mtu ?: 0}",
                                "Routes: ${state.runtimeSnapshot.routeCount} / excluded ${state.runtimeSnapshot.excludedRouteCount}",
                                "Audit findings: ${state.runtimeSnapshot.auditFindingCount}",
                                "Last audit: ${formatTimestamp(state.runtimeSnapshot.lastAuditAtEpochMs)}",
                                "Audit summary: ${state.runtimeSnapshot.lastAuditSummary ?: "none"}",
                                "Last bootstrap: ${formatTimestamp(state.runtimeSnapshot.lastBootstrapAtEpochMs)}",
                                "Bootstrap summary: ${state.runtimeSnapshot.lastBootstrapSummary ?: "none"}",
                                "Last host prep: ${formatTimestamp(state.runtimeSnapshot.lastEngineHostAtEpochMs)}",
                                "Host summary: ${state.runtimeSnapshot.lastEngineHostSummary ?: "none"}",
                                "Last session event: ${formatTimestamp(state.runtimeSnapshot.lastEngineSessionAtEpochMs)}",
                                "Session summary: ${state.runtimeSnapshot.lastEngineSessionSummary ?: "none"}",
                                "Last health check: ${formatTimestamp(state.runtimeSnapshot.lastEngineHealthAtEpochMs)}",
                                "Health summary: ${state.runtimeSnapshot.lastEngineHealthSummary ?: "none"}",
                                "Recent xray logs: ${state.runtimeSnapshot.recentXrayLogLines.ifEmpty { listOf("none") }.joinToString(" | ")}",
                                "Recent native events: ${state.runtimeSnapshot.recentNativeEvents.ifEmpty { listOf("none") }.joinToString(" | ")}",
                            ),
                        )
                    }

                    if (showFrozenSecondarySurface) {
                        DetailCard(
                            title = "Frozen Secondary Surface",
                            modifier = Modifier.bringIntoViewRequester(subscriptionBringIntoViewRequester),
                            body = buildList {
                                add("Subscription, inbox, scheduler, and notification controls remain available but are intentionally outside the MVP acceptance path.")
                                add("Status: ${state.subscriptionState.status}")
                                add("Stored source: ${state.subscriptionState.storedSourceHash ?: "not saved"}")
                                add("Pending update count: ${state.subscriptionState.pendingUpdateProfileCount}")
                                add("TLS trust status: ${state.subscriptionState.trustStatus}")
                                add("Publisher trust status: ${state.subscriptionState.publisherStatus}")
                                add("Latest inbox event: ${state.subscriptionState.latestEventSummary ?: "none"}")
                                add("Subscription error: ${state.subscriptionState.error ?: "none"}")
                            },
                            actions = {
                                ActionGroup {
                                    ActionButton(text = "Hide Surface", onClick = { showFrozenSecondarySurface = false }, primary = false)
                                }
                                OutlinedTextField(
                                    value = state.subscriptionState.urlDraft,
                                    onValueChange = { viewModel.updateSubscriptionUrlDraft(it) },
                                    label = { Text("HTTPS subscription URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = state.subscriptionState.pinDraft,
                                    onValueChange = { viewModel.updateSubscriptionPinDraft(it) },
                                    label = { Text("Optional TLS SPKI pin (sha256/...)") },
                                    supportingText = {
                                        Text("Leave blank to keep the current pin set on the same URL, or save a new URL without pinning.")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                                OutlinedTextField(
                                    value = state.subscriptionState.publisherPinDraft,
                                    onValueChange = { viewModel.updateSubscriptionPublisherPinDraft(it) },
                                    label = { Text("Optional publisher pin (sha256/...)") },
                                    supportingText = {
                                        Text("Pins the signing public key for signed feed verification when present.")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                                ActionGroup {
                                    ActionButton(text = "Save Source", onClick = { viewModel.saveSubscriptionSource() })
                                    ActionButton(text = "Update Now", onClick = { viewModel.refreshSubscriptionNow() })
                                }
                                ActionGroup {
                                    ActionButton(text = "Manual", onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.MANUAL) }, primary = false)
                                    ActionButton(text = "Every 6h", onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.EVERY_6_HOURS) }, primary = false)
                                    ActionButton(text = "Every 24h", onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.EVERY_24_HOURS) }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(text = "Updates On", onClick = { viewModel.setSubscriptionUpdateAlertsEnabled(true) }, primary = false)
                                    ActionButton(text = "Updates Off", onClick = { viewModel.setSubscriptionUpdateAlertsEnabled(false) }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(text = "Fail x1", onClick = { viewModel.setSubscriptionFailureAlertThreshold(1) }, primary = false)
                                    ActionButton(text = "Fail x2", onClick = { viewModel.setSubscriptionFailureAlertThreshold(2) }, primary = false)
                                    ActionButton(text = "Fail x3", onClick = { viewModel.setSubscriptionFailureAlertThreshold(3) }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(
                                        text = "Alert Settings",
                                        onClick = {
                                            context.startActivity(
                                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                },
                                            )
                                        },
                                        primary = false,
                                    )
                                    ActionButton(text = "Refresh Alerts", onClick = { viewModel.refreshSubscriptionNotificationStatus() }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(text = "Trust Observed", onClick = { viewModel.trustObservedSubscriptionIdentity() }, primary = false)
                                    ActionButton(text = "Clear Pin", onClick = { viewModel.clearPinnedSubscriptionIdentity() }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(
                                        text = if (state.subscriptionState.publisherRolloverReady) "Add Signer" else "Trust Signer",
                                        onClick = { viewModel.trustObservedSubscriptionPublisherIdentity() },
                                        primary = false,
                                    )
                                    ActionButton(text = "Clear Signer", onClick = { viewModel.clearPinnedSubscriptionPublisherIdentity() }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(
                                        text = "Prev Candidate",
                                        onClick = { viewModel.selectPreviousSubscriptionCandidate() },
                                        enabled = state.subscriptionState.pendingUpdateProfileCount > 1,
                                        primary = false,
                                    )
                                    ActionButton(
                                        text = "Next Candidate",
                                        onClick = { viewModel.selectNextSubscriptionCandidate() },
                                        enabled = state.subscriptionState.pendingUpdateProfileCount > 1,
                                        primary = false,
                                    )
                                }
                                ActionGroup {
                                    ActionButton(text = "Apply Update", onClick = { viewModel.applyPendingSubscriptionUpdate() }, primary = false)
                                    ActionButton(text = "Clear Inbox", onClick = { viewModel.clearSubscriptionInbox() }, primary = false)
                                }
                                ActionGroup {
                                    ActionButton(text = "Clear Alert Ledger", onClick = { viewModel.clearSubscriptionAlertLedger() }, primary = false)
                                }
                                if (state.subscriptionState.notificationAttention != null) {
                                    ActionGroup {
                                        ActionButton(text = "Dismiss Alert", onClick = { viewModel.dismissSubscriptionNotificationAttention() }, primary = false)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long?): String {
    if (epochMs == null) return "n/a"
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

@Composable
private fun ConnectionOverviewCard(
    state: MainUiState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    val compactLayout = rememberCompactLayout()
    val containerColor = when (state.runtimeSnapshot.phase) {
        VpnRuntimePhase.RUNNING -> Color(0xFFDDEBDD)
        VpnRuntimePhase.FAIL_CLOSED -> Color(0xFFF4D8D2)
        VpnRuntimePhase.START_REQUESTED -> Color(0xFFF6E5CD)
        else -> Color(0xFFF3E9D8)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compactLayout) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = runtimeHeadline(state.runtimeSnapshot.phase),
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFF183233),
            )
            Text(
                text = runtimeSummary(state),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF22332C),
            )
            ActionGroup {
                ActionButton(
                    text = "Connect",
                    onClick = onConnect,
                    modifier = Modifier.testTag(UiTags.CONNECT_BUTTON),
                )
                ActionButton(
                    text = "Stop",
                    onClick = onStop,
                    modifier = Modifier.testTag(UiTags.STOP_BUTTON),
                    primary = false,
                )
                ActionButton(
                    text = "Refresh",
                    onClick = onRefresh,
                    modifier = Modifier.testTag(UiTags.REFRESH_RUNTIME_BUTTON),
                    primary = false,
                )
            }
            TwoColumnMetricRow(
                leftTitle = "Profile",
                leftValue = state.profile.name,
                rightTitle = "Routing",
                rightValue = splitTunnelLabel(state.tunnelPlan.splitTunnelMode),
            )
            DetailCard(
                title = "Runtime Snapshot",
                body = listOf(
                    "Phase: ${state.runtimeSnapshot.phase}",
                    "Permission: ${if (state.vpnPermissionGranted) "granted" else "required on connect"}",
                    "Listener audit: ${state.runtimeSnapshot.auditStatus}",
                    "Engine health: ${state.runtimeSnapshot.engineSessionHealthStatus}",
                    "Control channel: ${if (state.controlChannelConnected) "connected" else "disconnected"}",
                    "Last error: ${state.controlError ?: "none"}",
                ),
            )
        }
    }
}

@Composable
private fun RegionalBypassCard(
    state: MainUiState,
    expanded: Boolean,
    customDirectDomainDraft: String,
    onToggleRussia: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onCustomDirectDomainDraftChange: (String) -> Unit,
    onAddCustomDirectDomain: () -> Unit,
    onRemoveCustomDirectDomain: (String) -> Unit,
    onPackageChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPromptDecision: (Boolean) -> Unit,
) {
    val regionalBypass = state.profile.routing.regionalBypass
    val russiaDirectEnabled = regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)
    val customDirectDomains = regionalBypass.customDirectDomains.distinct()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F0EB)),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D9CF)),
    ) {
        Column(
            modifier = Modifier.padding(if (rememberCompactLayout()) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Regional Bypass", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (russiaDirectEnabled) {
                    "Russia direct is on. Russian .ru, .su, and .рф destinations, plus Russian IP ranges, bypass the VPN."
                } else {
                    "Russia direct is off. Russian destinations follow the normal VPN routing policy."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            if (rememberCompactLayout()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Russia direct", style = MaterialTheme.typography.labelMedium, color = Color(0xFF5D6C63))
                    Switch(
                        checked = russiaDirectEnabled,
                        onCheckedChange = onToggleRussia,
                        modifier = Modifier.testTag(UiTags.RUSSIA_DIRECT_SWITCH),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Russia direct", style = MaterialTheme.typography.titleMedium)
                        Text(
                            ".ru, .su, .рф, and Russian IP destinations stay outside the tunnel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4E5E56),
                        )
                    }
                    Switch(
                        checked = russiaDirectEnabled,
                        onCheckedChange = onToggleRussia,
                        modifier = Modifier.testTag(UiTags.RUSSIA_DIRECT_SWITCH),
                    )
                }
            }

            if (state.showRegionalBypassPrompt) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFF7E7D5),
                    contentColor = Color(0xFF332214),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Enable Russia direct for this existing profile?",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "This keeps .ru, .su, .рф, and Russian IP destinations outside the VPN by default.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ActionGroup {
                            ActionButton(
                                text = "Enable",
                                onClick = { onPromptDecision(true) },
                                modifier = Modifier.testTag(UiTags.ACCEPT_RUSSIA_DIRECT_PROMPT_BUTTON),
                            )
                            ActionButton(
                                text = "Keep Current",
                                onClick = { onPromptDecision(false) },
                                primary = false,
                                modifier = Modifier.testTag(UiTags.DECLINE_RUSSIA_DIRECT_PROMPT_BUTTON),
                            )
                        }
                    }
                }
            }

            state.regionalBypassStatus?.let { status ->
                Text(status, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF214345))
            }
            state.regionalBypassError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9C3B27))
            }

            ActionGroup {
                ActionButton(
                    text = if (expanded) "Hide Configure" else "Configure",
                    onClick = onToggleExpanded,
                    primary = false,
                    modifier = Modifier.testTag(UiTags.CONFIGURE_REGIONAL_BYPASS_BUTTON),
                )
            }

            if (expanded) {
                Text(
                    "Precedence: app split tunnel -> explicit block rules -> regional bypass direct rules -> explicit direct/proxy rules -> routing default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4E5E56),
                )
                OutlinedTextField(
                    value = customDirectDomainDraft,
                    onValueChange = onCustomDirectDomainDraftChange,
                    label = { Text("Always direct domain") },
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.CUSTOM_DIRECT_DOMAIN_FIELD),
                    singleLine = true,
                )
                ActionGroup {
                    ActionButton(
                        text = "Add Direct Domain",
                        onClick = onAddCustomDirectDomain,
                        modifier = Modifier.testTag(UiTags.ADD_DIRECT_DOMAIN_BUTTON),
                    )
                }
                if (customDirectDomains.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        customDirectDomains.forEach { domain ->
                            RemovableChip(
                                text = domain,
                                onRemove = { onRemoveCustomDirectDomain(domain) },
                            )
                        }
                    }
                }
                PreviewCard(
                    state = state,
                    onPackageChange = onPackageChange,
                    onHostChange = onHostChange,
                    onIpChange = onIpChange,
                    onPortChange = onPortChange,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    chips: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF163233)),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (rememberCompactLayout()) 18.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Secure-first Android VPN",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFE8C78F),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFF7EED6),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFDCE8E4),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF214345),
                contentColor = Color(0xFFF7EED6),
            ) {
                Text(
                    text = "Import -> preview -> connect -> verify traffic -> stop",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    Chip(text = chip)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFF28595B),
        contentColor = Color(0xFFF6F1E7),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun phaseChip(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "Connected"
    VpnRuntimePhase.FAIL_CLOSED -> "Fail closed"
}

private fun runtimeHeadline(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready to connect"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "VPN active"
    VpnRuntimePhase.FAIL_CLOSED -> "Attention required"
}

private fun runtimeSummary(state: MainUiState): String = when (state.runtimeSnapshot.phase) {
    VpnRuntimePhase.RUNNING ->
        "Connected through ${state.profile.outbound.address}:${state.profile.outbound.port} with ${splitTunnelLabel(state.tunnelPlan.splitTunnelMode).lowercase()} and ${if (state.tunnelPlan.preserveLoopback) "local loopback preserved" else "loopback routed through the tunnel"}."

    VpnRuntimePhase.START_REQUESTED ->
        "VPN permission and runtime start were requested. The session will fail closed if bootstrap, audit, or engine health checks fail."

    VpnRuntimePhase.FAIL_CLOSED ->
        "The runtime stopped in fail-closed mode. Review the latest runtime error before reconnecting."

    VpnRuntimePhase.STAGED ->
        "The validated profile is staged. Connect to request VPN permission and start the runtime."

    VpnRuntimePhase.IDLE ->
        "Import or scan a share link, confirm the normalized profile, then connect."
}

private fun splitTunnelLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "Full tunnel"
    is SplitTunnelMode.Allowlist -> "Allowlist"
    is SplitTunnelMode.Denylist -> "Denylist"
}

private fun splitTunnelDetail(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "all applications use the tunnel by default"
    is SplitTunnelMode.Allowlist -> "${mode.packageNames.size} allowed app(s) use the tunnel"
    is SplitTunnelMode.Denylist -> "${mode.packageNames.size} excluded app(s) bypass the tunnel"
}

@Composable
private fun TwoColumnMetricRow(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
) {
    val compactLayout = rememberCompactLayout()
    if (compactLayout) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = leftTitle,
                value = leftValue,
                modifier = Modifier.fillMaxWidth(),
            )
            MetricCard(
                title = rightTitle,
                value = rightValue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = leftTitle,
                value = leftValue,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = rightTitle,
                value = rightValue,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RemovableChip(
    text: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFFFAF7F1),
        contentColor = Color(0xFF183233),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF7F1)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF5D6C63))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    body: List<String>,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF9F5)),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5DED1)),
    ) {
        Column(
            modifier = Modifier.padding(if (rememberCompactLayout()) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            body.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF22332C))
            }
            actions?.invoke()
        }
    }
}

@Composable
private fun AutomationIntegrationCard(
    state: AutomationState,
    onToggleEnabled: (Boolean) -> Unit,
    onRotateToken: () -> Unit,
    onCopyToken: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4EEE5)),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0D5C6)),
    ) {
        Column(
            modifier = Modifier.padding(if (rememberCompactLayout()) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Anubis Integration", style = MaterialTheme.typography.titleLarge)
            if (rememberCompactLayout()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enable automation", style = MaterialTheme.typography.labelMedium, color = Color(0xFF5D6C63))
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.testTag(UiTags.AUTOMATION_ENABLE_SWITCH),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Enable Anubis automation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Opt-in explicit activity API with a rotatable token. Grant VPN permission once manually before using Anubis.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4E5E56),
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.testTag(UiTags.AUTOMATION_ENABLE_SWITCH),
                    )
                }
            }
            Text(
                "VPN permission: ${if (state.vpnPermissionReady) "granted" else "required"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            Text(
                "Token: ${state.tokenPreview ?: "not generated"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            Text(
                "Last automation result: ${state.lastAutomationStatus}${state.lastAutomationAt?.let { " at $it" } ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            state.lastAutomationError?.let { error ->
                Text(
                    "Last automation error: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9C3B27),
                )
            }
            state.lastCallerHint?.let { callerHint ->
                Text(
                    "Last caller hint: $callerHint",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5B4A32),
                )
            }
            state.status?.let { status ->
                Text(status, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF214345))
            }
            state.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9C3B27))
            }
            ActionGroup {
                ActionButton(
                    text = "Rotate Token",
                    onClick = onRotateToken,
                    enabled = state.enabled,
                    modifier = Modifier.testTag(UiTags.AUTOMATION_ROTATE_TOKEN_BUTTON),
                )
                ActionButton(
                    text = "Copy Token",
                    onClick = onCopyToken,
                    enabled = state.enabled && state.rawToken != null,
                    primary = false,
                    modifier = Modifier.testTag(UiTags.AUTOMATION_COPY_TOKEN_BUTTON),
                )
                ActionButton(
                    text = "Refresh",
                    onClick = onRefresh,
                    primary = false,
                    modifier = Modifier.testTag(UiTags.AUTOMATION_REFRESH_BUTTON),
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    state: MainUiState,
    onPackageChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7E7D5)),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE6C7AC)),
    ) {
        Column(
            modifier = Modifier.padding(if (rememberCompactLayout()) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Routing Preview", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.routePreview.packageName,
                onValueChange = onPackageChange,
                label = { Text("Package name") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_PACKAGE_FIELD),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationHost,
                onValueChange = onHostChange,
                label = { Text("Destination host") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_HOST_FIELD),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationIp,
                onValueChange = onIpChange,
                label = { Text("Destination IP") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_IP_FIELD),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationPort,
                onValueChange = onPortChange,
                label = { Text("Destination port") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_PORT_FIELD),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                text = "Decision: ${state.previewOutcome.action} via ${state.previewOutcome.matchedRuleId ?: "default"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.previewOutcome.reason,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.previewOutcome.runtimeDatasetHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5B4A32),
                )
            }
        }
    }
}

@Composable
private fun ActionGroup(
    content: @Composable () -> Unit,
) {
    if (rememberCompactLayout()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val compactLayout = rememberCompactLayout()
    val buttonModifier = if (compactLayout) modifier.fillMaxWidth() else modifier
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Text(text)
        }
    } else {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Text(text)
        }
    }
}

@Composable
private fun rememberCompactLayout(): Boolean = LocalConfiguration.current.screenWidthDp <= 320

private object TunguskaChrome {
    val colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF193B3C),
        onPrimary = Color(0xFFF7EED6),
        secondary = Color(0xFFB55A32),
        onSecondary = Color.White,
        surface = Color(0xFFFAF7F1),
        onSurface = Color(0xFF1E2B25),
        background = Color(0xFFF6F1E7),
        onBackground = Color(0xFF18221E),
    )

    val typography = androidx.compose.material3.Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.Serif),
        titleLarge = TextStyle(fontFamily = FontFamily.Serif),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        labelLarge = TextStyle(fontFamily = FontFamily.Monospace),
        labelMedium = TextStyle(fontFamily = FontFamily.Monospace),
    )
}
