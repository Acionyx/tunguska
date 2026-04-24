package io.acionyx.tunguska.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.acionyx.tunguska.R
import io.acionyx.tunguska.domain.REQUIRED_VLESS_FLOW
import io.acionyx.tunguska.domain.EffectiveRoutingPolicyResolver
import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RegionalBypassPresetId
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.displayLabel
import io.acionyx.tunguska.domain.endpointSummary
import io.acionyx.tunguska.engine.api.EngineCapabilitySupport
import io.acionyx.tunguska.engine.api.EngineCapabilitySupportState
import io.acionyx.tunguska.domain.normalizeDomainForRouting
import io.acionyx.tunguska.domain.outboundProtocolLabel
import io.acionyx.tunguska.domain.outboundShapeLabel
import io.acionyx.tunguska.domain.outboundSecurityLabel
import io.acionyx.tunguska.domain.outboundTransportLabel
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.profileShapeLabel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

@Composable
fun TunguskaAppShell(
    viewModel: MainViewModel = viewModel(),
    notificationRoute: SubscriptionNotificationRoute? = null,
    onNotificationRouteConsumed: () -> Unit = {},
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    var currentScreen by rememberSaveable { mutableStateOf(defaultScreenFor(TunguskaSection.HOME)) }
    var returnToProfilesAfterImport by rememberSaveable { mutableStateOf(false) }
    var regionalDirectDomainDraft by rememberSaveable { mutableStateOf("") }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.markVpnPermissionGranted()
            viewModel.connectRuntime()
        }
    }

    LaunchedEffect(notificationRoute) {
        notificationRoute?.let {
            viewModel.openSubscriptionNotificationRoute(it)
            currentScreen = TunguskaScreen.SECURITY
            onNotificationRouteConsumed()
        }
    }

    LaunchedEffect(state.subscriptionState.notificationAttentionToken) {
        if (state.subscriptionState.notificationAttentionToken != null) {
            currentScreen = TunguskaScreen.SECURITY
        }
    }
    LaunchedEffect(returnToProfilesAfterImport, state.importStatus, state.importError, state.importPreview) {
        if (!returnToProfilesAfterImport) return@LaunchedEffect
        when {
            state.importError != null -> returnToProfilesAfterImport = false
            state.importPreview == null && state.importStatus?.startsWith("Imported ") == true -> {
                currentScreen = TunguskaScreen.PROFILES
                returnToProfilesAfterImport = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshRuntimeStatus()
        viewModel.refreshAutomationIntegrationStatus()
        onPauseOrDispose { }
    }
    BackHandler(enabled = currentScreen.detail) {
        currentScreen = defaultScreenFor(currentScreen.section)
    }
    LaunchedEffect(state.pendingShareRequest?.id) {
        val request = state.pendingShareRequest ?: return@LaunchedEffect
        val uri = Uri.parse(request.contentUri)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = request.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, request.chooserTitle)
            clipData = ClipData.newUri(context.contentResolver, request.chooserTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, request.chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { error ->
                viewModel.reportShareLaunchFailure(
                    requestId = request.id,
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
            .onSuccess {
                viewModel.consumeShareRequest(request.id)
            }
    }

    MaterialTheme(
        colorScheme = TunguskaTheme.colorScheme,
        typography = TunguskaTheme.typography,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(TunguskaTheme.backgroundBrush),
        ) {
            val metrics = remember(maxWidth, maxHeight) { uiMetricsFor(maxWidth, maxHeight) }
            val selectedSection = currentScreen.section

            if (metrics.useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ShellNavigationRail(
                        selectedSection = selectedSection,
                        onNavigate = { currentScreen = defaultScreenFor(it) },
                        modifier = Modifier.fillMaxHeight(),
                    )
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = Color.Transparent,
                    ) {
                        ScreenHost(
                            metrics = metrics,
                            screen = currentScreen,
                            state = state,
                            regionalDirectDomainDraft = regionalDirectDomainDraft,
                            onRegionalDirectDomainDraftChange = { regionalDirectDomainDraft = it },
                            onNavigate = { currentScreen = it },
                            onBack = { currentScreen = defaultScreenFor(currentScreen.section) },
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
                            onRefreshRuntime = viewModel::refreshRuntimeStatus,
                            onStageRuntime = viewModel::stageRuntime,
                            onRuntimeStrategyChange = viewModel::setRuntimeStrategy,
                            onImportDraftChange = viewModel::updateImportDraft,
                            onValidateImport = viewModel::stageImportDraft,
                            onConfirmImport = {
                                returnToProfilesAfterImport = true
                                viewModel.confirmStagedImport()
                            },
                            onDiscardImport = viewModel::discardStagedImport,
                            onQrPayloadDetected = viewModel::stageQrImportPayload,
                            onImportError = viewModel::reportImportError,
                            onUseRecommendedStrategy = viewModel::setRuntimeStrategy,
                            onSaveProfileEditor = viewModel::saveProfileEditorDraft,
                            onToggleRussia = viewModel::setRussiaDirectEnabled,
                            onPromptDecision = viewModel::decideRegionalBypassPrompt,
                            onAddRegionalDomain = {
                                if (runCatching { normalizeDomainForRouting(regionalDirectDomainDraft) }.isSuccess) {
                                    viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                                    regionalDirectDomainDraft = ""
                                } else {
                                    viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                                }
                            },
                            onRemoveRegionalDomain = viewModel::removeRegionalDirectDomain,
                            onPreviewPackageChange = { viewModel.updatePreview(packageName = it) },
                            onPreviewHostChange = { viewModel.updatePreview(destinationHost = it) },
                            onPreviewIpChange = { viewModel.updatePreview(destinationIp = it) },
                            onPreviewPortChange = { viewModel.updatePreview(destinationPort = it) },
                            onPreviewProtocolChange = { viewModel.updatePreview(protocol = it) },
                            onTestRoutePreview = viewModel::testRoutePreview,
                            onExportEncryptedBackup = viewModel::exportEncryptedBackup,
                            onBuildDiagnosticBundle = viewModel::buildDiagnosticBundle,
                            onReloadProfile = viewModel::reloadProfile,
                            onResealProfile = viewModel::resealProfile,
                            onToggleAutomation = viewModel::setAutomationEnabled,
                            onRotateAutomationToken = viewModel::rotateAutomationToken,
                            onCopyAutomationToken = viewModel::copyAutomationToken,
                            onRefreshAutomation = viewModel::refreshAutomationIntegrationStatus,
                        )
                    }
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (!currentScreen.detail) {
                            ShellBottomBar(
                                selectedSection = selectedSection,
                                onNavigate = { currentScreen = defaultScreenFor(it) },
                            )
                        }
                    },
                ) { innerPadding ->
                    ScreenHost(
                        metrics = metrics,
                        screen = currentScreen,
                        state = state,
                        regionalDirectDomainDraft = regionalDirectDomainDraft,
                        onRegionalDirectDomainDraftChange = { regionalDirectDomainDraft = it },
                        onNavigate = { currentScreen = it },
                        onBack = { currentScreen = defaultScreenFor(currentScreen.section) },
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
                        onRefreshRuntime = viewModel::refreshRuntimeStatus,
                        onStageRuntime = viewModel::stageRuntime,
                        onRuntimeStrategyChange = viewModel::setRuntimeStrategy,
                        onImportDraftChange = viewModel::updateImportDraft,
                        onValidateImport = viewModel::stageImportDraft,
                        onConfirmImport = {
                            returnToProfilesAfterImport = true
                            viewModel.confirmStagedImport()
                        },
                        onDiscardImport = viewModel::discardStagedImport,
                        onQrPayloadDetected = viewModel::stageQrImportPayload,
                        onImportError = viewModel::reportImportError,
                        onUseRecommendedStrategy = viewModel::setRuntimeStrategy,
                        onSaveProfileEditor = viewModel::saveProfileEditorDraft,
                        onToggleRussia = viewModel::setRussiaDirectEnabled,
                        onPromptDecision = viewModel::decideRegionalBypassPrompt,
                        onAddRegionalDomain = {
                            if (runCatching { normalizeDomainForRouting(regionalDirectDomainDraft) }.isSuccess) {
                                viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                                regionalDirectDomainDraft = ""
                            } else {
                                viewModel.addRegionalDirectDomain(regionalDirectDomainDraft)
                            }
                        },
                        onRemoveRegionalDomain = viewModel::removeRegionalDirectDomain,
                        onPreviewPackageChange = { viewModel.updatePreview(packageName = it) },
                        onPreviewHostChange = { viewModel.updatePreview(destinationHost = it) },
                        onPreviewIpChange = { viewModel.updatePreview(destinationIp = it) },
                        onPreviewPortChange = { viewModel.updatePreview(destinationPort = it) },
                        onPreviewProtocolChange = { viewModel.updatePreview(protocol = it) },
                        onTestRoutePreview = viewModel::testRoutePreview,
                        onExportEncryptedBackup = viewModel::exportEncryptedBackup,
                        onBuildDiagnosticBundle = viewModel::buildDiagnosticBundle,
                        onReloadProfile = viewModel::reloadProfile,
                        onResealProfile = viewModel::resealProfile,
                        onToggleAutomation = viewModel::setAutomationEnabled,
                        onRotateAutomationToken = viewModel::rotateAutomationToken,
                        onCopyAutomationToken = viewModel::copyAutomationToken,
                        onRefreshAutomation = viewModel::refreshAutomationIntegrationStatus,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

private enum class TunguskaSection(
    val title: String,
    val shortLabel: String,
    val marker: String,
    val tag: String,
) {
    HOME("Home", "Home", "H", UiTags.TAB_HOME),
    PROFILES("Profiles", "Profiles", "P", UiTags.TAB_PROFILES),
    ROUTING("Routing", "Routing", "R", UiTags.TAB_ROUTING),
    SECURITY("Security", "Security", "S", UiTags.TAB_SECURITY),
}

private enum class TunguskaScreen(
    val section: TunguskaSection,
    val title: String,
    val subtitle: String,
    val detail: Boolean,
) {
    HOME(
        section = TunguskaSection.HOME,
        title = "Protection center",
        subtitle = "Connect, verify posture, and understand what the next session will do.",
        detail = false,
    ),
    PROFILES(
        section = TunguskaSection.PROFILES,
        title = "Profiles",
        subtitle = "Review the active sealed profile or replace it through validated intake.",
        detail = false,
    ),
    PROFILE_EDITOR(
        section = TunguskaSection.PROFILES,
        title = "Advanced profile editor",
        subtitle = "Adjust the current profile fields with typed controls before resealing the active profile.",
        detail = true,
    ),
    PROFILE_IMPORT(
        section = TunguskaSection.PROFILES,
        title = "Import profile",
        subtitle = "Validate a share link or QR payload before committing it as the active profile.",
        detail = true,
    ),
    ROUTING(
        section = TunguskaSection.ROUTING,
        title = "Routing",
        subtitle = "See how traffic is handled, where exceptions apply, and how a route will resolve.",
        detail = false,
    ),
    REGIONAL_BYPASS(
        section = TunguskaSection.ROUTING,
        title = "Regional bypass",
        subtitle = "Control the Russia-direct policy and maintain custom direct-domain exceptions.",
        detail = true,
    ),
    ROUTE_PREVIEW(
        section = TunguskaSection.ROUTING,
        title = "Route preview",
        subtitle = "Simulate how a package and destination will be treated before reconnecting.",
        detail = true,
    ),
    SECURITY(
        section = TunguskaSection.SECURITY,
        title = "Security",
        subtitle = "Check trust posture, exports, automation, and controlled operational surfaces.",
        detail = false,
    ),
    AUTOMATION(
        section = TunguskaSection.SECURITY,
        title = "Automation",
        subtitle = "Configure the token-gated local integration used by Anubis.",
        detail = true,
    ),
    ADVANCED_DIAGNOSTICS(
        section = TunguskaSection.SECURITY,
        title = "Advanced diagnostics",
        subtitle = "Use runtime-lane controls and internals only when operational work requires them.",
        detail = true,
    ),
}

private fun defaultScreenFor(section: TunguskaSection): TunguskaScreen = when (section) {
    TunguskaSection.HOME -> TunguskaScreen.HOME
    TunguskaSection.PROFILES -> TunguskaScreen.PROFILES
    TunguskaSection.ROUTING -> TunguskaScreen.ROUTING
    TunguskaSection.SECURITY -> TunguskaScreen.SECURITY
}

private data class UiMetrics(
    val compact: Boolean,
    val medium: Boolean,
    val expanded: Boolean,
    val useRail: Boolean,
    val contentMaxWidth: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val cardPadding: Dp,
    val sectionSpacing: Dp,
    val heroRingSize: Dp,
)

private fun uiMetricsFor(width: Dp, height: Dp): UiMetrics {
    val compact = width < 520.dp
    val expanded = width >= 960.dp
    val medium = !compact && !expanded
    return UiMetrics(
        compact = compact,
        medium = medium,
        expanded = expanded,
        useRail = width >= 840.dp,
        contentMaxWidth = when {
            expanded -> 880.dp
            medium -> 760.dp
            else -> 640.dp
        },
        horizontalPadding = when {
            expanded -> 32.dp
            medium -> 24.dp
            else -> 16.dp
        },
        verticalPadding = when {
            compact -> 16.dp
            height < 680.dp -> 14.dp
            else -> 20.dp
        },
        cardPadding = when {
            expanded -> 24.dp
            medium -> 22.dp
            else -> 18.dp
        },
        sectionSpacing = when {
            expanded -> 20.dp
            medium -> 18.dp
            else -> 14.dp
        },
        heroRingSize = when {
            expanded -> 214.dp
            medium -> 190.dp
            else -> 176.dp
        },
    )
}

@Composable
private fun ShellBottomBar(
    selectedSection: TunguskaSection,
    onNavigate: (TunguskaSection) -> Unit,
) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 8.dp),
        color = TunguskaTheme.chrome.copy(alpha = 0.98f),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TunguskaSection.entries.forEach { section ->
                NavigationItemButton(
                    section = section,
                    selected = selectedSection == section,
                    onClick = { onNavigate(section) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(section.tag),
                )
            }
        }
    }
}

@Composable
private fun ShellNavigationRail(
    selectedSection: TunguskaSection,
    onNavigate: (TunguskaSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(start = 18.dp, top = 18.dp, bottom = 18.dp),
        color = TunguskaTheme.chrome,
        shape = RoundedCornerShape(34.dp),
        border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.85f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(112.dp)
                .padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BrandLockup(compact = true, centered = true)
            Spacer(modifier = Modifier.height(8.dp))
            TunguskaSection.entries.forEach { section ->
                NavigationItemButton(
                    section = section,
                    selected = selectedSection == section,
                    onClick = { onNavigate(section) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(section.tag),
                    vertical = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavigationItemButton(
    section: TunguskaSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val inactiveAccent = if (vertical) {
        TunguskaTheme.mutedText
    } else {
        TunguskaTheme.bodyText.copy(alpha = 0.68f)
    }
    val accent by animateColorAsState(
        targetValue = if (selected) TunguskaTheme.accent else inactiveAccent,
        animationSpec = tween(durationMillis = 260),
        label = "nav-accent",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "nav-indicator",
    )
    val arrangement = if (vertical) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(2.dp)
    val iconFrameSize = if (vertical) {
        42.dp to 28.dp
    } else {
        46.dp to 30.dp
    }
    val iconSize = if (vertical) {
        22.dp
    } else {
        when (section) {
            TunguskaSection.HOME -> 27.dp
            TunguskaSection.PROFILES -> 28.dp
            TunguskaSection.ROUTING -> 28.dp
            TunguskaSection.SECURITY -> 26.dp
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (vertical) 10.dp else 4.dp, vertical = if (vertical) 5.dp else 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = arrangement,
    ) {
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(if (vertical) 22.dp else 28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accent.copy(alpha = indicatorAlpha)),
        )
        Box(
            modifier = Modifier
                .size(width = iconFrameSize.first, height = iconFrameSize.second),
            contentAlignment = Alignment.Center,
        ) {
            AppGlyphIcon(
                glyph = glyphForSection(section),
                tint = accent,
                modifier = Modifier.size(iconSize),
            )
        }
        Text(
            text = section.shortLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = accent,
        )
    }
}

@Composable
private fun ScreenHost(
    metrics: UiMetrics,
    screen: TunguskaScreen,
    state: MainUiState,
    regionalDirectDomainDraft: String,
    onRegionalDirectDomainDraftChange: (String) -> Unit,
    onNavigate: (TunguskaScreen) -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onRefreshRuntime: () -> Unit,
    onStageRuntime: () -> Unit,
    onRuntimeStrategyChange: (EmbeddedRuntimeStrategyId) -> Unit,
    onImportDraftChange: (String) -> Unit,
    onValidateImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onDiscardImport: () -> Unit,
    onQrPayloadDetected: (String, ImportCaptureSource) -> Unit,
    onImportError: (String) -> Unit,
    onUseRecommendedStrategy: (EmbeddedRuntimeStrategyId) -> Unit,
    onSaveProfileEditor: (ProfileEditorDraft) -> Unit,
    onToggleRussia: (Boolean) -> Unit,
    onPromptDecision: (Boolean) -> Unit,
    onAddRegionalDomain: () -> Unit,
    onRemoveRegionalDomain: (String) -> Unit,
    onPreviewPackageChange: (String) -> Unit,
    onPreviewHostChange: (String) -> Unit,
    onPreviewIpChange: (String) -> Unit,
    onPreviewPortChange: (String) -> Unit,
    onPreviewProtocolChange: (NetworkProtocol) -> Unit,
    onTestRoutePreview: () -> Unit,
    onExportEncryptedBackup: () -> Unit,
    onBuildDiagnosticBundle: () -> Unit,
    onReloadProfile: () -> Unit,
    onResealProfile: () -> Unit,
    onToggleAutomation: (Boolean) -> Unit,
    onRotateAutomationToken: () -> Unit,
    onCopyAutomationToken: () -> Unit,
    onRefreshAutomation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                slideInHorizontally(animationSpec = tween(300)) { it / 10 }) togetherWith
                (fadeOut(animationSpec = tween(180)) +
                    slideOutHorizontally(animationSpec = tween(240)) { -it / 12 }) using
                SizeTransform(clip = false)
        },
        label = "screen-host",
    ) { currentScreen ->
        ScreenColumn(
            metrics = metrics,
            detail = currentScreen.detail,
            modifier = modifier,
        ) {
            if (currentScreen != TunguskaScreen.HOME) {
                SectionHeader(
                    metrics = metrics,
                    screen = currentScreen,
                    onBack = if (currentScreen.detail) onBack else null,
                )
            }
            when (currentScreen) {
                TunguskaScreen.HOME -> HomeScreen(
                    metrics = metrics,
                    state = state,
                    onConnect = onConnect,
                    onStop = onStop,
                    onOpenProfiles = { onNavigate(TunguskaScreen.PROFILES) },
                    onOpenRouting = { onNavigate(TunguskaScreen.ROUTING) },
                )

                TunguskaScreen.PROFILES -> ProfilesScreen(
                    metrics = metrics,
                    state = state,
                    onOpenEditor = { onNavigate(TunguskaScreen.PROFILE_EDITOR) },
                    onOpenImport = { onNavigate(TunguskaScreen.PROFILE_IMPORT) },
                    onOpenAdvancedDiagnostics = { onNavigate(TunguskaScreen.ADVANCED_DIAGNOSTICS) },
                    onUseRecommendedStrategy = onRuntimeStrategyChange,
                )

                TunguskaScreen.PROFILE_EDITOR -> ProfileEditorScreen(
                    metrics = metrics,
                    state = state,
                    onOpenAdvancedDiagnostics = { onNavigate(TunguskaScreen.ADVANCED_DIAGNOSTICS) },
                    onSaveDraft = onSaveProfileEditor,
                )

                TunguskaScreen.PROFILE_IMPORT -> ProfileImportScreen(
                    state = state,
                    onDraftChange = onImportDraftChange,
                    onValidateDraft = onValidateImport,
                    onConfirmImport = onConfirmImport,
                    onDiscardImport = onDiscardImport,
                    onQrPayloadDetected = onQrPayloadDetected,
                    onImportError = onImportError,
                    onUseRecommendedStrategy = onUseRecommendedStrategy,
                    onOpenAdvancedDiagnostics = { onNavigate(TunguskaScreen.ADVANCED_DIAGNOSTICS) },
                )

                TunguskaScreen.ROUTING -> RoutingScreen(
                    metrics = metrics,
                    state = state,
                    onOpenRegionalBypass = { onNavigate(TunguskaScreen.REGIONAL_BYPASS) },
                    onOpenRoutePreview = { onNavigate(TunguskaScreen.ROUTE_PREVIEW) },
                )

                TunguskaScreen.REGIONAL_BYPASS -> RegionalBypassScreen(
                    metrics = metrics,
                    state = state,
                    customDirectDomainDraft = regionalDirectDomainDraft,
                    onCustomDirectDomainDraftChange = onRegionalDirectDomainDraftChange,
                    onToggleRussia = onToggleRussia,
                    onPromptDecision = onPromptDecision,
                    onAddCustomDirectDomain = onAddRegionalDomain,
                    onRemoveCustomDirectDomain = onRemoveRegionalDomain,
                )

                TunguskaScreen.ROUTE_PREVIEW -> RoutePreviewScreen(
                    metrics = metrics,
                    state = state,
                    onPackageChange = onPreviewPackageChange,
                    onHostChange = onPreviewHostChange,
                    onIpChange = onPreviewIpChange,
                    onPortChange = onPreviewPortChange,
                    onProtocolChange = onPreviewProtocolChange,
                    onTestRoute = onTestRoutePreview,
                )

                TunguskaScreen.SECURITY -> SecurityScreen(
                    metrics = metrics,
                    state = state,
                    onExportEncryptedBackup = onExportEncryptedBackup,
                    onBuildDiagnosticBundle = onBuildDiagnosticBundle,
                    onOpenAutomation = { onNavigate(TunguskaScreen.AUTOMATION) },
                    onOpenAdvancedDiagnostics = { onNavigate(TunguskaScreen.ADVANCED_DIAGNOSTICS) },
                )

                TunguskaScreen.AUTOMATION -> AutomationScreen(
                    metrics = metrics,
                    state = state.automationState,
                    onToggleEnabled = onToggleAutomation,
                    onRotateToken = onRotateAutomationToken,
                    onCopyToken = onCopyAutomationToken,
                    onRefresh = onRefreshAutomation,
                )

                TunguskaScreen.ADVANCED_DIAGNOSTICS -> AdvancedDiagnosticsScreen(
                    metrics = metrics,
                    state = state,
                    onRefreshRuntime = onRefreshRuntime,
                    onStageRuntime = onStageRuntime,
                    onRuntimeStrategyChange = onRuntimeStrategyChange,
                    onReloadProfile = onReloadProfile,
                    onResealProfile = onResealProfile,
                )
            }
        }
    }
}

@Composable
private fun ScreenColumn(
    metrics: UiMetrics,
    detail: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.MAIN_SCROLL_COLUMN),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = metrics.contentMaxWidth)
                .padding(
                    horizontal = metrics.horizontalPadding,
                    vertical = metrics.verticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(metrics.sectionSpacing),
            // Detail screens do not have the compact bottom bar; keep final controls above gesture nav.
            content = {
                content()
                if (detail && !metrics.useRail) {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    metrics: UiMetrics,
    screen: TunguskaScreen,
    onBack: (() -> Unit)?,
) {
    if (screen.detail) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = { onBack?.invoke() },
                    modifier = Modifier.testTag(UiTags.BACK_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.bodyText),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppGlyphIcon(
                            glyph = AppGlyph.BACK,
                            tint = TunguskaTheme.bodyText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Back")
                    }
                }
                StatusChip(
                    text = screen.section.title,
                    accent = TunguskaTheme.accentDim,
                )
            }
            Text(
                text = screen.title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = screen.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
                maxLines = if (metrics.compact) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                text = screen.section.title,
                accent = TunguskaTheme.accent,
            )
            Text(
                text = screen.title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = screen.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
                maxLines = if (metrics.compact) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BrandLockup(compact: Boolean, centered: Boolean = false) {
    val iconSize = if (compact) 24.dp else 34.dp
    val spacing = if (compact) 10.dp else 12.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Surface(
            shape = RoundedCornerShape(if (compact) 14.dp else 18.dp),
            color = TunguskaTheme.surfaceStrong.copy(alpha = 0.82f),
            border = BorderStroke(1.dp, TunguskaTheme.accent.copy(alpha = 0.24f)),
        ) {
            Box(
                modifier = Modifier.padding(if (compact) 6.dp else 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppGlyphIcon(
                    glyph = AppGlyph.SECURITY,
                    tint = TunguskaTheme.accent,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "TUNGUSKA",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.6.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!compact) {
                Text(
                    text = "Secure-first Android VPN",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                    color = TunguskaTheme.mutedText,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    val connected = state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING
    val connectDecision = state.connectDecision
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 18.dp else 22.dp),
    ) {
        HomeBrandHeader()
        ProtectionHeroCard(
            metrics = metrics,
            state = state,
        )
        HomeProfileCard(
            profileName = state.profile.name,
            onClick = onOpenProfiles,
        )
        HomeRoutingCard(
            state = state,
            onClick = onOpenRouting,
        )
        AnimatedContent(
            targetState = connected,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
            },
            label = "home-connect-button",
        ) { connected ->
            if (connected) {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.STOP_BUTTON),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TunguskaTheme.danger,
                        contentColor = Color(0xFF160A0B),
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppGlyphIcon(
                            glyph = AppGlyph.POWER,
                            tint = Color(0xFF160A0B),
                            modifier = Modifier.size(22.dp),
                        )
                        Text("Disconnect")
                    }
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.CONNECT_BUTTON),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TunguskaTheme.accent,
                        contentColor = Color(0xFF061214),
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppGlyphIcon(
                            glyph = AppGlyph.POWER,
                            tint = Color(0xFF061214),
                            modifier = Modifier.size(22.dp),
                        )
                        Text("Connect")
                    }
                }
            }
        }
        if (!connected && connectDecision == null) {
            state.runtimeSelection.connectDecisionHint?.let { hint ->
                SupportingNote(
                    text = hint,
                    color = TunguskaTheme.warning,
                )
            }
        }
        connectDecision?.let { decision ->
            SupportingNote(
                text = decision.message,
                color = when (decision.phase) {
                    ConnectDecisionPhase.STARTING -> TunguskaTheme.warning
                    ConnectDecisionPhase.STARTED -> TunguskaTheme.accent
                    ConnectDecisionPhase.FAILED -> TunguskaTheme.danger
                },
            )
        }
        HomeStatusLine(
            state = state,
            showLaneHint = connected && connectDecision?.phase != ConnectDecisionPhase.STARTED,
        )
    }
}

@Composable
private fun ProtectionHeroCard(
    metrics: UiMetrics,
    state: MainUiState,
) {
    val phase = state.runtimeSnapshot.phase
    val visual = heroVisual(phase)
    val heroStageSize = metrics.heroRingSize + if (metrics.compact) 52.dp else 64.dp
    var energyTrigger by remember { mutableStateOf(0) }
    var previousPhase by remember { mutableStateOf<VpnRuntimePhase?>(null) }

    LaunchedEffect(phase) {
        val lastPhase = previousPhase
        if (lastPhase == null) {
            previousPhase = phase
        } else if (lastPhase != phase) {
            previousPhase = phase
            energyTrigger += 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(heroStageSize),
                contentAlignment = Alignment.Center,
            ) {
                if (visual.burstEnabled) {
                    HeroEnergyBurst(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = visual.burstScale, scaleY = visual.burstScale),
                        accentColor = visual.accent,
                        trigger = energyTrigger,
                        intensity = visual.burstIntensity,
                        durationMillis = visual.burstDurationMillis,
                    )
                }
                HeroPlasmaBackdrop(
                    phase = phase,
                    visual = visual,
                    diameter = metrics.heroRingSize,
                    modifier = Modifier.graphicsLayer(
                        scaleX = visual.backdropScale,
                        scaleY = visual.backdropScale,
                        alpha = visual.backdropAlpha,
                    ),
                )
                ProtectionSignal(
                    diameter = metrics.heroRingSize,
                    phase = phase,
                    visual = visual,
                )
            }
            AnimatedContent(
                targetState = runtimeHeadline(phase),
                transitionSpec = {
                    fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(animationSpec = tween(160))
                },
                label = "home-headline",
            ) { headline ->
                Text(
                    text = headline,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HeroPlasmaBackdrop(
    phase: VpnRuntimePhase,
    visual: HeroVisual,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val accent by animateColorAsState(
        targetValue = visual.accent,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "hero-plasma-backdrop-accent",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "hero-plasma-backdrop")
    val sweepStart by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = visual.orbitDurationMillis, easing = LinearEasing)),
        label = "hero-plasma-backdrop-sweep",
    )
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = visual.pulseDurationMillis, easing = LinearEasing),
        ),
        label = "hero-plasma-backdrop-pulse",
    )
    val segmentSweepStart by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = visual.segmentOrbitDurationMillis, easing = LinearEasing),
        ),
        label = "hero-plasma-backdrop-segment-sweep",
    )
    val plasmaEnergy = visual.backdropEnergy
    val plasmaPulse = periodicRange(
        progress = pulseProgress,
        min = visual.backdropPulseMin,
        max = visual.backdropPulseMax,
        phaseOffset = 0.22f,
    )
    val plasmaInstability = visual.backdropInstability

    Canvas(modifier = modifier.size(diameter)) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val strokeWidth = canvasSize.minDimension * 0.048f * visual.ringStrokeScale
        val outerShellStroke = strokeWidth * 1.24f
        val outerShellRadius = canvasSize.minDimension / 2f - outerShellStroke * 0.72f
        val orbitRadius = outerShellRadius * 0.72f

        if (visual.backdropMode == HeroBackdropMode.PARTICLE_FLOW) {
            drawHeroParticleFlow(
                accentColor = accent,
                center = center,
                fieldRadius = orbitRadius * 0.98f,
                rotationDegrees = sweepStart + segmentSweepStart,
                energy = plasmaEnergy,
                pulse = plasmaPulse,
                instability = plasmaInstability,
            )
        } else {
            drawHeroPlasmaField(
                accentColor = accent,
                center = center,
                fieldRadius = orbitRadius * 1.04f,
                rotationDegrees = sweepStart * 0.24f + segmentSweepStart * 0.06f,
                energy = plasmaEnergy,
                pulse = plasmaPulse,
                instability = plasmaInstability,
            )
        }
    }
}

@Composable
private fun ProtectionSignal(
    diameter: Dp,
    phase: VpnRuntimePhase,
    visual: HeroVisual,
) {
    val accent by animateColorAsState(
        targetValue = visual.accent,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "hero-accent",
    )
    val coreFill by animateColorAsState(
        targetValue = visual.coreFill,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "hero-core-fill",
    )
    val coreScale by animateFloatAsState(
        targetValue = visual.coreScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 80f),
        label = "hero-core-scale",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "hero-signal")
    val sweepStart by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = visual.orbitDurationMillis, easing = LinearEasing)),
        label = "hero-sweep",
    )
    val breathProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = visual.breathDurationMillis + 800, easing = LinearEasing),
        ),
        label = "hero-breath-progress",
    )
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = visual.pulseDurationMillis, easing = LinearEasing),
        ),
        label = "hero-inner-pulse-progress",
    )
    val segmentSweepStart by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = visual.segmentOrbitDurationMillis, easing = LinearEasing),
        ),
        label = "hero-segment-sweep",
    )
    val haloScale = periodicRange(
        progress = breathProgress,
        min = 0.96f,
        max = 1.08f,
        phaseOffset = 0.12f,
    )
    val breathScale = periodicRange(
        progress = breathProgress,
        min = 0.988f,
        max = visual.outerScale,
    )
    val innerPulse = periodicRange(
        progress = pulseProgress,
        min = 0.82f,
        max = 1f,
        phaseOffset = 0.18f,
    )
    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer(scaleX = breathScale, scaleY = breathScale),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size
            val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
            val strokeWidth = canvasSize.minDimension * 0.048f * visual.ringStrokeScale
            val outerShellStroke = strokeWidth * 1.24f
            val outerShellRadius = canvasSize.minDimension / 2f - outerShellStroke * 0.72f
            val orbitRadius = outerShellRadius * 0.72f
            val innerOrbitRadius = orbitRadius * 0.82f
            val shellAlpha = if (phase == VpnRuntimePhase.IDLE) 0.42f else 1f
            val shellEdgeAlpha = if (phase == VpnRuntimePhase.IDLE) 0.08f else 0.16f
            val outerShellRect = Rect(
                offset = Offset(center.x - outerShellRadius, center.y - outerShellRadius),
                size = Size(outerShellRadius * 2f, outerShellRadius * 2f),
            )
            val orbitRect = Rect(
                offset = Offset(center.x - orbitRadius, center.y - orbitRadius),
                size = Size(orbitRadius * 2f, orbitRadius * 2f),
            )
            val innerOrbitRect = Rect(
                offset = Offset(center.x - innerOrbitRadius, center.y - innerOrbitRadius),
                size = Size(innerOrbitRadius * 2f, innerOrbitRadius * 2f),
            )
            val shellColor = Color(0xFF111A22)
            val shellEdge = Color(0xFF283640)
            val segmentStart = -84f
            val segmentSweep = 48f
            val segmentDrift = segmentSweepStart

            repeat(6) { segmentIndex ->
                val startAngle = segmentStart + segmentIndex * 60f
                drawArc(
                    color = shellColor.copy(alpha = shellAlpha),
                    startAngle = startAngle,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = outerShellRect.topLeft,
                    size = outerShellRect.size,
                    style = Stroke(width = outerShellStroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = shellEdge.copy(alpha = shellEdgeAlpha),
                    startAngle = startAngle + 2f,
                    sweepAngle = segmentSweep * 0.72f,
                    useCenter = false,
                    topLeft = outerShellRect.topLeft,
                    size = outerShellRect.size,
                    style = Stroke(width = outerShellStroke * 0.14f, cap = StrokeCap.Round),
                )
            }

            heroHighlightedSegments(phase).forEachIndexed { index, segmentIndex ->
                val shimmer = periodicRange(
                    progress = breathProgress,
                    min = 0.76f,
                    max = 1f,
                    phaseOffset = 0.1f * index,
                )
                val startAngle = segmentStart + segmentIndex * 60f + segmentDrift
                drawArc(
                    color = accent.copy(alpha = visual.glowAlpha * 0.22f * shimmer),
                    startAngle = startAngle,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = outerShellRect.topLeft,
                    size = outerShellRect.size,
                    style = Stroke(width = outerShellStroke * 1.34f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent.copy(alpha = 0.34f + 0.24f * shimmer),
                    startAngle = startAngle,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = outerShellRect.topLeft,
                    size = outerShellRect.size,
                    style = Stroke(width = outerShellStroke * 0.74f, cap = StrokeCap.Round),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = visual.glowAlpha * innerPulse), Color.Transparent),
                    center = center,
                    radius = orbitRadius * 1.42f * haloScale,
                ),
                radius = orbitRadius * 1.2f * haloScale,
                center = center,
            )
            drawCircle(
                color = accent.copy(alpha = 0.045f * innerPulse),
                radius = orbitRadius * 0.74f,
                center = center,
                style = Stroke(width = strokeWidth * 0.58f),
            )
            drawCircle(
                color = accent.copy(alpha = visual.baseRingAlpha),
                radius = orbitRadius,
                center = center,
                style = Stroke(width = strokeWidth * 0.94f),
            )
            drawCircle(
                color = accent.copy(alpha = visual.baseRingAlpha * 0.55f),
                radius = innerOrbitRadius,
                center = center,
                style = Stroke(width = strokeWidth * 0.44f),
            )
            drawArc(
                color = accent.copy(alpha = visual.trailingArcAlpha),
                startAngle = sweepStart - 18f,
                sweepAngle = visual.trailingSweep,
                useCenter = false,
                topLeft = orbitRect.topLeft,
                size = orbitRect.size,
                style = Stroke(width = strokeWidth * 0.94f, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent.copy(alpha = 0.9f),
                startAngle = sweepStart + visual.primaryArcOffset,
                sweepAngle = visual.primarySweep,
                useCenter = false,
                topLeft = orbitRect.topLeft,
                size = orbitRect.size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (visual.secondarySweep > 0f) {
                drawArc(
                    color = accent.copy(alpha = 0.74f),
                    startAngle = sweepStart + 176f,
                    sweepAngle = visual.secondarySweep,
                    useCenter = false,
                    topLeft = innerOrbitRect.topLeft,
                    size = innerOrbitRect.size,
                    style = Stroke(width = strokeWidth * 0.64f, cap = StrokeCap.Round),
                )
            }
        }
        Surface(
            modifier = Modifier.graphicsLayer(scaleX = coreScale, scaleY = coreScale),
            shape = RoundedCornerShape(999.dp),
            color = coreFill.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.34f + 0.18f * innerPulse)),
        ) {
            Box(
                modifier = Modifier
                    .size((diameter.value * visual.coreDiameterScale).dp)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppGlyphIcon(
                    glyph = heroGlyph(phase),
                    tint = accent,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun HomeBrandHeader() {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppGlyphIcon(
            glyph = AppGlyph.SECURITY,
            tint = TunguskaTheme.accent,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = "TUNGUSKA",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.2.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun HomeProfileCard(
    profileName: String,
    onClick: () -> Unit,
) {
    HomeSelectorCard(
        title = "Active profile",
        value = profileName,
        glyph = AppGlyph.PROFILE,
        modifier = Modifier.testTag(UiTags.HOME_PROFILE_CARD),
        onClick = onClick,
    )
}

@Composable
private fun HomeRoutingCard(
    state: MainUiState,
    onClick: () -> Unit,
) {
    val russiaDirect = state.profile.routing.regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)
    HomeSelectorCard(
        title = "Routing",
        value = buildString {
            append(splitTunnelLabel(state.tunnelPlan.splitTunnelMode))
            append(" · ")
            append(if (russiaDirect) "Russia direct on" else "Russia direct off")
        },
        glyph = AppGlyph.ROUTING,
        modifier = Modifier.testTag(UiTags.HOME_ROUTING_CARD),
        onClick = onClick,
    )
}

@Composable
private fun HomeSelectorCard(
    title: String,
    value: String,
    glyph: AppGlyph,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.72f)),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TunguskaTheme.surfaceStrong.copy(alpha = 0.94f),
                            TunguskaTheme.surface.copy(alpha = 0.98f),
                        ),
                    ),
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = TunguskaTheme.surface.copy(alpha = 0.74f),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.78f)),
                ) {
                    Box(
                        modifier = Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppGlyphIcon(
                            glyph = glyph,
                            tint = TunguskaTheme.accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.mutedText,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = TunguskaTheme.surface.copy(alpha = 0.52f),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.86f)),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppGlyphIcon(
                            glyph = AppGlyph.CHEVRON_RIGHT,
                            tint = TunguskaTheme.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeStatusLine(
    state: MainUiState,
    showLaneHint: Boolean,
) {
    val homeLaneHint = state.runtimeSelection.homeStatusHint.takeIf { showLaneHint }
    val statusColor = when {
        state.runtimeSnapshot.phase == VpnRuntimePhase.FAIL_CLOSED ||
            state.egressObservation.phase == EgressObservationPhase.ERROR ->
            TunguskaTheme.warning

        state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING ->
            TunguskaTheme.accent

        else -> TunguskaTheme.mutedText
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_STATUS_LINE)
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(modifier = Modifier.width(10.dp))
            AnimatedContent(
                targetState = homeEgressStatusLine(state),
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(140))
                },
                label = "home-status-line",
            ) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
        homeLaneHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING) {
                    TunguskaTheme.mutedText
                } else {
                    statusColor
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProfilesScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onOpenEditor: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenAdvancedDiagnostics: () -> Unit,
    onUseRecommendedStrategy: (EmbeddedRuntimeStrategyId) -> Unit,
) {
    val configuredSelection = state.configuredSelection
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppGlyphIcon(
                    glyph = AppGlyph.PROFILE,
                    tint = TunguskaTheme.accent,
                    modifier = Modifier.size(28.dp),
                )
                Text("Active profile", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                state.profile.name,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(text = profileTypeLabel(state.profile), accent = TunguskaTheme.accent)
                StatusChip(text = profileSealLabel(state), accent = profileSealAccent(state))
            }
            Text(
                "Replace the active sealed profile through validation and review. Existing sessions keep their current runtime until reconnect.",
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            PrimaryActionButton(
                text = if (state.importPreview != null) "Continue review" else "Replace profile",
                onClick = onOpenImport,
                modifier = Modifier.testTag(UiTags.OPEN_PROFILE_IMPORT_BUTTON),
            )
            OutlinedButton(
                onClick = onOpenEditor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.OPEN_PROFILE_EDITOR_BUTTON),
                border = BorderStroke(1.dp, TunguskaTheme.stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.accent),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
            ) {
                Text("Open advanced editor")
            }
        }
    }
    SurfaceCard(
        subtle = true,
        accent = if (configuredSelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
            TunguskaTheme.accentDim
        } else {
            TunguskaTheme.warning
        },
        modifier = Modifier.testTag(UiTags.PROFILE_COMPATIBILITY_CARD),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Compatibility summary", style = MaterialTheme.typography.titleLarge)
            CompatibilityLimitSummary(
                title = configuredSelection.selectedSummaryTitle,
                severity = configuredSelection.selectedSeverity,
                details = configuredSelection.selectedSummaryDetails,
                onOpenAdvancedDiagnostics = onOpenAdvancedDiagnostics,
                recommendation = configuredSelection.recommendation,
                textStyle = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = configuredSelection.selectedLaneLabel,
                    accent = if (state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                        TunguskaTheme.accentDim
                    } else {
                        TunguskaTheme.warning
                    },
                )
                StatusChip(
                    text = configuredSelection.statusLabel,
                    accent = if (configuredSelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
                        TunguskaTheme.accent
                    } else {
                        TunguskaTheme.warning
                    },
                )
                configuredSelection.recommendedLaneLabel?.let { label ->
                    StatusChip(
                        text = label,
                        accent = TunguskaTheme.warning,
                    )
                }
            }
            configuredSelection.recommendation?.let { recommendation ->
                Text(
                    text = recommendation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.bodyText,
                )
            }
            configuredSelection.recommendedStrategyId?.let { recommendedStrategyId ->
                OutlinedButton(
                    onClick = { onUseRecommendedStrategy(recommendedStrategyId) },
                    modifier = Modifier.testTag(UiTags.USE_RECOMMENDED_STRATEGY_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.accent),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    Text(
                        if (recommendedStrategyId == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                            "Use sing-box embedded"
                        } else {
                            "Use xray+tun2socks"
                        },
                    )
                }
            }
        }
    }
    SurfaceCard(subtle = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val outboundSummary = state.profile.outboundSummary
            Text("Technical details", style = MaterialTheme.typography.titleLarge)
            DetailRow("Shape", outboundSummary.shapeLabel)
            DetailRow("Protocol", outboundSummary.protocolLabel)
            DetailRow("Transport", outboundSummary.transportLabel)
            DetailRow("Security", outboundSummary.securityLabel)
            DetailRow("Endpoint", "${outboundSummary.endpoint.address}:${outboundSummary.endpoint.port}")
            DetailRow("Server name", outboundSummary.endpoint.serverName)
            DetailRow("Storage", state.profileStorage.backend)
            DetailRow("Canonical hash", abbreviateHash(state.profile.canonicalHash()))
            DetailRow("Sealed hash", abbreviateHash(state.profileStorage.persistedProfileHash ?: "pending"))
            DetailRow("Last sealed", state.profileStorage.lastPersistedAt ?: "Not sealed yet")
        }
    }
}

@Composable
private fun ProfileEditorScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onOpenAdvancedDiagnostics: () -> Unit,
    onSaveDraft: (ProfileEditorDraft) -> Unit,
) {
    var draft by remember(state.profile) { mutableStateOf(ProfileEditorDraft.fromProfile(state.profile)) }
    val validationIssues = draft.validationIssues(state.profile)
    val previewProfile = remember(draft, state.profile) { draft.previewProfile(state.profile) }
    val editorSelection = remember(previewProfile, state.automationState.runtimeStrategy) {
        configuredSelectionState(
            profile = previewProfile,
            selectedStrategyId = state.automationState.runtimeStrategy,
        )
    }
    val editorGuidance = remember(previewProfile, state.automationState.runtimeStrategy) {
        StrategyCapabilityRegistry.evaluateProfileGuidance(
            profile = previewProfile,
            strategyId = state.automationState.runtimeStrategy,
        )
    }
    val dnsGuidance = editorGuidance.guidanceFor(ProfileGuidanceSection.DNS)
    val vpnGuidance = editorGuidance.guidanceFor(ProfileGuidanceSection.VPN_POLICY)
    val protocolSpecificEditorIntro = remember(state.profile) { state.profile.protocolSpecificEditorIntroSummary() }
    val validationBody = if (validationIssues.size > 3) {
        validationIssues.take(3).map { it.message } + "${validationIssues.size - 3} more issues remain."
    } else {
        validationIssues.map { it.message }
    }

    SurfaceCard(modifier = Modifier.testTag(UiTags.PROFILE_EDITOR_COMPATIBILITY_CARD)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Typed editor", style = MaterialTheme.typography.titleLarge)
            Text(
                protocolSpecificEditorIntro.supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(text = protocolSpecificEditorIntro.shapeChipLabel, accent = TunguskaTheme.accent)
                StatusChip(
                    text = if (state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                        "Editing for sing-box lane"
                    } else {
                        "Editing for xray+tun2socks lane"
                    },
                    accent = if (state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                        TunguskaTheme.accentDim
                    } else {
                        TunguskaTheme.warning
                    },
                )
                StatusChip(
                    text = editorSelection.statusLabel,
                    accent = if (editorSelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
                        TunguskaTheme.accent
                    } else {
                        TunguskaTheme.warning
                    },
                )
                editorSelection.recommendedLaneLabel?.let { label ->
                    StatusChip(
                        text = label,
                        accent = TunguskaTheme.warning,
                    )
                }
            }
            CompatibilityLimitSummary(
                title = editorSelection.selectedSummaryTitle,
                severity = editorSelection.selectedSeverity,
                details = editorSelection.selectedSummaryDetails,
                onOpenAdvancedDiagnostics = onOpenAdvancedDiagnostics,
                recommendation = editorSelection.recommendation,
                color = TunguskaTheme.bodyText,
            )
            editorSelection.recommendation?.let { recommendation ->
                SupportingNote(text = recommendation, color = TunguskaTheme.bodyText)
            }
            state.profileEditorStatus?.let { status ->
                SupportingNote(text = status, color = TunguskaTheme.accent)
            }
            state.profileEditorError?.let { error ->
                SupportingNote(text = error, color = TunguskaTheme.danger)
            }
        }
    }

    ProtocolSpecificOutboundShapeCard(profile = state.profile)

    if (validationBody.isNotEmpty()) {
        AttentionCard(
            title = "Fix the draft before saving",
            body = validationBody,
            accent = TunguskaTheme.warning,
        )
    }

    ResponsivePair(
        metrics = metrics,
        first = {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Identity and endpoint", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Profile name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    ProtocolSpecificEndpointEditorFields(
                        profile = state.profile,
                        draft = draft,
                        onDraftChange = { updatedDraft -> draft = updatedDraft },
                    )
                }
            }
        },
        second = {
            ProtocolSpecificSecurityEditorCard(
                profile = state.profile,
                draft = draft,
                onDraftChange = { updatedDraft -> draft = updatedDraft },
            )
        },
    )

    ProtocolSpecificTransportEditorCard(
        profile = state.profile,
        draft = draft,
        metrics = metrics,
        onDraftChange = { updatedDraft -> draft = updatedDraft },
    )

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DNS handling", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "Choose whether the tunnel uses system resolution, VPN-resolved DNS, or explicit encrypted upstreams.",
            )
            dnsGuidance?.let { guidance ->
                ProfileEditorGuidanceCard(
                    guidance = guidance,
                    modifier = Modifier.testTag(UiTags.PROFILE_EDITOR_DNS_GUIDANCE_CARD),
                )
            }
            Text("DNS mode", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = { draft = draft.copy(dnsMode = ProfileEditorDnsMode.SYSTEM) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.dnsMode == ProfileEditorDnsMode.SYSTEM),
                ) {
                    Text("System DNS")
                }
                Button(
                    onClick = {
                        draft = draft.copy(
                            dnsMode = ProfileEditorDnsMode.VPN,
                            dnsEndpoints = draft.dnsEndpoints.ifBlank { defaultProfileEditorVpnDnsEndpoints() },
                        )
                    },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.dnsMode == ProfileEditorDnsMode.VPN),
                ) {
                    Text("VPN DNS")
                }
                Button(
                    onClick = {
                        draft = draft.copy(
                            dnsMode = ProfileEditorDnsMode.CUSTOM,
                            dnsEndpoints = draft.dnsEndpoints.ifBlank { defaultProfileEditorCustomDnsEndpoints(draft.dnsEncryptedKind) },
                        )
                    },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.dnsMode == ProfileEditorDnsMode.CUSTOM),
                ) {
                    Text("Custom encrypted")
                }
            }
            if (draft.dnsMode == ProfileEditorDnsMode.CUSTOM) {
                Text("Encrypted DNS kind", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
                ActionCluster(compact = metrics.compact) {
                    Button(
                        onClick = {
                            draft = draft.copy(
                                dnsEncryptedKind = ProfileEditorEncryptedDnsKind.DOH,
                                dnsEndpoints = draft.dnsEndpoints.ifBlank { defaultProfileEditorCustomDnsEndpoints(ProfileEditorEncryptedDnsKind.DOH) },
                            )
                        },
                        modifier = actionButtonModifier(metrics),
                        colors = buttonColorsForSelection(active = draft.dnsEncryptedKind == ProfileEditorEncryptedDnsKind.DOH),
                    ) {
                        Text("DoH")
                    }
                    Button(
                        onClick = {
                            draft = draft.copy(
                                dnsEncryptedKind = ProfileEditorEncryptedDnsKind.DOT,
                                dnsEndpoints = draft.dnsEndpoints.ifBlank { defaultProfileEditorCustomDnsEndpoints(ProfileEditorEncryptedDnsKind.DOT) },
                            )
                        },
                        modifier = actionButtonModifier(metrics),
                        colors = buttonColorsForSelection(active = draft.dnsEncryptedKind == ProfileEditorEncryptedDnsKind.DOT),
                    ) {
                        Text("DoT")
                    }
                }
            }
            if (draft.dnsMode != ProfileEditorDnsMode.SYSTEM) {
                OutlinedTextField(
                    value = draft.dnsEndpoints,
                    onValueChange = { draft = draft.copy(dnsEndpoints = it) },
                    label = {
                        Text(
                            if (draft.dnsMode == ProfileEditorDnsMode.VPN) {
                                "Resolver endpoints"
                            } else {
                                "Encrypted DNS endpoints"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    supportingText = {
                        Text("Enter one endpoint per line. Comma-separated values also work.")
                    },
                )
            }
        }
    }

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("VPN policy", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "Choose whether all apps use the tunnel, only selected apps use it, or selected apps stay direct.",
            )
            vpnGuidance?.let { guidance ->
                ProfileEditorGuidanceCard(
                    guidance = guidance,
                    modifier = Modifier.testTag(UiTags.PROFILE_EDITOR_VPN_POLICY_GUIDANCE_CARD),
                )
            }
            Text("Split-tunnel mode", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = { draft = draft.copy(splitTunnelMode = ProfileEditorSplitTunnelMode.FULL) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.splitTunnelMode == ProfileEditorSplitTunnelMode.FULL),
                ) {
                    Text("Full tunnel")
                }
                Button(
                    onClick = { draft = draft.copy(splitTunnelMode = ProfileEditorSplitTunnelMode.ALLOWLIST) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.splitTunnelMode == ProfileEditorSplitTunnelMode.ALLOWLIST),
                ) {
                    Text("Allowlist")
                }
                Button(
                    onClick = { draft = draft.copy(splitTunnelMode = ProfileEditorSplitTunnelMode.DENYLIST) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.splitTunnelMode == ProfileEditorSplitTunnelMode.DENYLIST),
                ) {
                    Text("Denylist")
                }
            }
            if (draft.splitTunnelMode != ProfileEditorSplitTunnelMode.FULL) {
                OutlinedTextField(
                    value = draft.splitTunnelPackages,
                    onValueChange = { draft = draft.copy(splitTunnelPackages = it) },
                    label = {
                        Text(
                            if (draft.splitTunnelMode == ProfileEditorSplitTunnelMode.ALLOWLIST) {
                                "Allowed Android packages"
                            } else {
                                "Excluded Android packages"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    supportingText = {
                        Text("Enter one package per line. Comma-separated values also work.")
                    },
                )
            }
        }
    }

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Routing defaults", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "This sets the fallback action for traffic not matched by explicit rules. Detailed rule editing stays in the Routing section for now.",
            )
            Text("Fallback traffic action", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = { draft = draft.copy(defaultRouteAction = RouteAction.PROXY) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.defaultRouteAction == RouteAction.PROXY),
                ) {
                    Text("Proxy")
                }
                Button(
                    onClick = { draft = draft.copy(defaultRouteAction = RouteAction.DIRECT) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.defaultRouteAction == RouteAction.DIRECT),
                ) {
                    Text("Direct")
                }
                Button(
                    onClick = { draft = draft.copy(defaultRouteAction = RouteAction.BLOCK) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.defaultRouteAction == RouteAction.BLOCK),
                ) {
                    Text("Block")
                }
            }
            DetailRow("Existing explicit rules", state.profile.routing.rules.size.toString())
        }
    }

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Safety guards", style = MaterialTheme.typography.titleLarge)
            ProfileEditorToggleRow(
                title = "Safe mode",
                detail = "Keep the runtime on the fail-closed path and suppress unsafe local helper surfaces.",
                checked = draft.safeMode,
                onCheckedChange = { enabled ->
                    draft = draft.copy(
                        safeMode = enabled,
                        compatibilityLocalProxy = if (enabled) false else draft.compatibilityLocalProxy,
                        debugEndpointsEnabled = if (enabled) false else draft.debugEndpointsEnabled,
                    )
                },
            )
            ProfileEditorToggleRow(
                title = "Compatibility local proxy",
                detail = "Expose the compatibility listener only when you explicitly need a local bridge for debugging or legacy workflows.",
                checked = draft.compatibilityLocalProxy,
                enabled = !draft.safeMode,
                onCheckedChange = { enabled -> draft = draft.copy(compatibilityLocalProxy = enabled) },
            )
            ProfileEditorToggleRow(
                title = "Debug endpoints",
                detail = "Keep runtime diagnostic endpoints off unless active operational work requires them.",
                checked = draft.debugEndpointsEnabled,
                enabled = !draft.safeMode,
                onCheckedChange = { enabled -> draft = draft.copy(debugEndpointsEnabled = enabled) },
            )
        }
    }

    PrimaryActionButton(
        text = "Save advanced changes",
        onClick = { onSaveDraft(draft) },
        modifier = Modifier.testTag(UiTags.PROFILE_EDITOR_SAVE_BUTTON),
        enabled = validationIssues.isEmpty(),
    )
}

@Composable
private fun ProfileImportScreen(
    state: MainUiState,
    onDraftChange: (String) -> Unit,
    onValidateDraft: () -> Unit,
    onConfirmImport: () -> Unit,
    onDiscardImport: () -> Unit,
    onQrPayloadDetected: (String, ImportCaptureSource) -> Unit,
    onImportError: (String) -> Unit,
    onUseRecommendedStrategy: (EmbeddedRuntimeStrategyId) -> Unit,
    onOpenAdvancedDiagnostics: () -> Unit,
) {
    val stageText = if (state.importPreview == null) "Step 1 of 2" else "Step 2 of 2"
    StatusChip(text = stageText, accent = TunguskaTheme.accent)
    ImportSection(
        state = state,
        onDraftChange = onDraftChange,
        onValidateDraft = onValidateDraft,
        onConfirmImport = onConfirmImport,
        onDiscardImport = onDiscardImport,
        onQrPayloadDetected = onQrPayloadDetected,
        onImportError = onImportError,
        onUseRecommendedStrategy = onUseRecommendedStrategy,
        onOpenAdvancedDiagnostics = onOpenAdvancedDiagnostics,
    )
}

@Composable
private fun RoutingScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onOpenRegionalBypass: () -> Unit,
    onOpenRoutePreview: () -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Traffic policy", style = MaterialTheme.typography.titleLarge)
            Text(
                splitTunnelLabel(state.tunnelPlan.splitTunnelMode),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                splitTunnelDetail(state.tunnelPlan.splitTunnelMode),
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(text = splitTunnelInventory(state.tunnelPlan.splitTunnelMode), accent = TunguskaTheme.accentDim)
                StatusChip(
                    text = if (state.profile.routing.regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)) {
                        "Russia direct enabled"
                    } else {
                        "Russia direct disabled"
                    },
                    accent = TunguskaTheme.accent,
                )
            }
            SupportingNote("Profile app rules are read-only in this build.")
        }
    }
    UtilityEntryCard(
        title = "Regional bypass",
        value = if (state.profile.routing.regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)) {
            "Russia direct enabled"
        } else {
            "Russia direct disabled"
        },
        detail = "${state.profile.routing.regionalBypass.customDirectDomains.size} custom direct domain(s)",
        actionText = "Configure",
        glyph = AppGlyph.ROUTING,
        accent = TunguskaTheme.accentDim,
        modifier = Modifier.testTag(UiTags.CONFIGURE_REGIONAL_BYPASS_BUTTON),
        onClick = onOpenRegionalBypass,
    )
    UtilityEntryCard(
        title = "Route test",
        value = if (state.routePreviewLastTestedAtEpochMs == null) {
            "Not tested"
        } else {
            routeOutcomeUserLabel(state.previewOutcome.action)
        },
        detail = if (state.routePreviewLastTestedAtEpochMs == null) {
            "Offline policy simulation; VPN does not need to be connected."
        } else if (state.routePreviewStale) {
            "Inputs changed. Run the test again."
        } else {
            routeOutcomeReasonLine(state)
        },
        actionText = "Test route",
        glyph = AppGlyph.ROUTING,
        accent = TunguskaTheme.accent,
        modifier = Modifier.testTag(UiTags.OPEN_ROUTE_PREVIEW_BUTTON),
        onClick = onOpenRoutePreview,
    )
}

@Composable
private fun RegionalBypassScreen(
    metrics: UiMetrics,
    state: MainUiState,
    customDirectDomainDraft: String,
    onCustomDirectDomainDraftChange: (String) -> Unit,
    onToggleRussia: (Boolean) -> Unit,
    onPromptDecision: (Boolean) -> Unit,
    onAddCustomDirectDomain: () -> Unit,
    onRemoveCustomDirectDomain: (String) -> Unit,
) {
    val regionalBypass = state.profile.routing.regionalBypass
    val russiaDirectEnabled = regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)
    val directDomainRows = effectiveDirectDomainRows(state)
    val directDomainListRows = if (directDomainRows.isEmpty()) {
        listOf("No active direct-domain rules" to "Enable Russia direct or add a custom suffix.")
    } else {
        directDomainRows.map { row -> row.label to row.source }
    }
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Russia direct", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Bypass the tunnel for Russian TLDs and Russian IP ranges while other traffic stays protected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.mutedText,
                    )
                }
                Switch(
                    checked = russiaDirectEnabled,
                    onCheckedChange = onToggleRussia,
                    modifier = Modifier.testTag(UiTags.RUSSIA_DIRECT_SWITCH),
                )
            }

            if (state.showRegionalBypassPrompt) {
                AttentionCard(
                    title = "Enable Russia direct for this profile?",
                    body = listOf(
                        "This keeps Russian TLD and Russian IP destinations outside the VPN by default.",
                    ),
                    accent = TunguskaTheme.warning,
                ) {
                    ActionCluster(compact = metrics.compact) {
                        Button(
                            onClick = { onPromptDecision(true) },
                            modifier = actionButtonModifier(metrics).testTag(UiTags.ACCEPT_RUSSIA_DIRECT_PROMPT_BUTTON),
                        ) {
                            Text("Enable")
                        }
                        OutlinedButton(
                            onClick = { onPromptDecision(false) },
                            modifier = actionButtonModifier(metrics).testTag(UiTags.DECLINE_RUSSIA_DIRECT_PROMPT_BUTTON),
                            border = BorderStroke(1.dp, TunguskaTheme.stroke),
                        ) {
                            Text("Keep current")
                        }
                    }
                }
            }

            state.regionalBypassStatus?.let { status ->
                SupportingNote(text = status)
            }
            state.regionalBypassError?.let { error ->
                SupportingNote(text = error, color = TunguskaTheme.danger)
            }
        }
    }

    DetailListCard(
        title = "Direct-domain list",
        rows = directDomainListRows,
        modifier = Modifier.testTag(UiTags.DIRECT_DOMAIN_LIST),
    )

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add custom direct domain", style = MaterialTheme.typography.titleLarge)
            InfoHint(
                text = "Enter a domain suffix. example.com also matches *.example.com and will bypass the tunnel.",
            )
            OutlinedTextField(
                value = customDirectDomainDraft,
                onValueChange = onCustomDirectDomainDraftChange,
                label = { Text("Domain suffix") },
                placeholder = { Text("example.com") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CUSTOM_DIRECT_DOMAIN_FIELD),
                singleLine = true,
            )
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = onAddCustomDirectDomain,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.ADD_DIRECT_DOMAIN_BUTTON),
                ) {
                    Text("Add direct domain")
                }
            }
            if (regionalBypass.customDirectDomains.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    regionalBypass.customDirectDomains.distinct().forEach { domain ->
                        RemovableDomainChip(
                            domain = domain,
                            onRemove = { onRemoveCustomDirectDomain(domain) },
                        )
                    }
                }
            }
        }
    }

    SupportingNote(
        text = "Precedence: app split tunnel -> explicit block rules -> regional direct rules -> explicit direct/proxy rules -> default routing.",
    )
}

@Composable
private fun RoutePreviewScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onPackageChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onProtocolChange: (NetworkProtocol) -> Unit,
    onTestRoute: () -> Unit,
) {
    val routeTestStatus = routeTestStatusText(state)
    AttentionCard(
        title = if (state.routePreviewLastTestedAtEpochMs == null) {
            "Route not tested"
        } else if (state.routePreviewStale) {
            "${routeOutcomeUserLabel(state.previewOutcome.action)} - stale"
        } else {
            routeOutcomeUserLabel(state.previewOutcome.action)
        },
        body = listOf(
            "This is an offline policy simulation. It does not send traffic and does not require the VPN to be connected.",
            routeOutcomeExpectationLine(state.previewOutcome.action),
            "Matched: ${routeRuleLabel(state.previewOutcome.matchedRuleId)}",
            state.previewOutcome.reason,
        ) + listOfNotNull(
            state.previewOutcome.runtimeDatasetHint,
            state.routePreviewLastTested?.let { "Tested: ${routePreviewInputSummary(it)}" },
            state.routePreviewLastTestedAtEpochMs?.let { "Last run: ${formatTimestamp(it)}" },
            if (state.routePreviewStale) "Inputs changed after the last test. Press Test route to refresh the decision." else null,
        ),
        accent = routeOutcomeAccent(state.previewOutcome.action),
        modifier = Modifier.testTag(UiTags.ROUTE_PREVIEW_RESULT_CARD),
    )
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Route test inputs", style = MaterialTheme.typography.titleLarge)
            InfoHint(
                text = "Use the host for domain rules, IP for IP/GeoIP rules, and package only when checking split-tunnel app behavior.",
            )
            OutlinedTextField(
                value = state.routePreview.destinationHost,
                onValueChange = onHostChange,
                label = { Text("Destination host") },
                placeholder = { Text("yandex.ru or api.ipify.org") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_HOST_FIELD),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationIp,
                onValueChange = onIpChange,
                label = { Text("Destination IP (optional)") },
                placeholder = { Text("203.0.113.10") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_IP_FIELD),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationPort,
                onValueChange = onPortChange,
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_PORT_FIELD),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Protocol", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                OutlinedButton(
                    onClick = { onProtocolChange(NetworkProtocol.TCP) },
                    modifier = actionButtonModifier(metrics).testTag(UiTags.ROUTE_PREVIEW_PROTOCOL_TCP_BUTTON),
                    border = BorderStroke(1.dp, if (state.routePreview.protocol == NetworkProtocol.TCP) TunguskaTheme.accent else TunguskaTheme.stroke),
                ) {
                    Text("TCP")
                }
                OutlinedButton(
                    onClick = { onProtocolChange(NetworkProtocol.UDP) },
                    modifier = actionButtonModifier(metrics).testTag(UiTags.ROUTE_PREVIEW_PROTOCOL_UDP_BUTTON),
                    border = BorderStroke(1.dp, if (state.routePreview.protocol == NetworkProtocol.UDP) TunguskaTheme.accent else TunguskaTheme.stroke),
                ) {
                    Text("UDP")
                }
            }
            SurfaceCard(subtle = true, accent = TunguskaTheme.accentDim) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Advanced split-tunnel input", style = MaterialTheme.typography.titleMedium)
                    InfoHint("Only set this when you want to know whether a specific Android app package is inside or outside the VPN.")
                    OutlinedTextField(
                        value = state.routePreview.packageName,
                        onValueChange = onPackageChange,
                        label = { Text("App package (optional)") },
                        placeholder = { Text("com.android.chrome") },
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTE_PREVIEW_PACKAGE_FIELD),
                        singleLine = true,
                    )
                }
            }
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = onTestRoute,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.ROUTE_PREVIEW_TEST_BUTTON),
                ) {
                    Text("Test route")
                }
            }
            Text(
                text = routeTestStatus,
                modifier = Modifier.testTag(UiTags.ROUTE_PREVIEW_STALE_LABEL),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.routePreviewStale) TunguskaTheme.warning else TunguskaTheme.accent,
            )
        }
    }
}

@Composable
private fun SecurityScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onExportEncryptedBackup: () -> Unit,
    onBuildDiagnosticBundle: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenAdvancedDiagnostics: () -> Unit,
) {
    val connectDecision = state.connectDecision
    val securitySelection = state.runtimeSelection
    val securityLaneStrategy = securitySelection.activeStrategyId ?: securitySelection.configuredStrategyId
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Security posture", style = MaterialTheme.typography.titleLarge)
            Text(
                trustHeadline(state),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                trustSummary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(text = phaseChip(state.runtimeSnapshot.phase), accent = phaseAccent(state.runtimeSnapshot.phase))
                StatusChip(text = trustAuditChipText(state), accent = trustAuditChipAccent(state))
                StatusChip(text = trustEngineChipText(state), accent = trustEngineChipAccent(state))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = securitySelection.activeLaneLabel,
                    accent = if (securityLaneStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                        TunguskaTheme.accentDim
                    } else {
                        TunguskaTheme.warning
                    },
                )
                StatusChip(
                    text = securitySelection.statusLabel,
                    accent = if (securitySelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
                        TunguskaTheme.accent
                    } else {
                        TunguskaTheme.warning
                    },
                )
                securitySelection.recommendedLaneLabel?.let { label ->
                    StatusChip(
                        text = label,
                        accent = TunguskaTheme.warning,
                    )
                }
            }
            connectDecision?.let { decision ->
                SupportingNote(
                    text = decision.message,
                    color = when (decision.phase) {
                        ConnectDecisionPhase.STARTING -> TunguskaTheme.warning
                        ConnectDecisionPhase.STARTED -> TunguskaTheme.accent
                        ConnectDecisionPhase.FAILED -> TunguskaTheme.danger
                    },
                )
            }
            securitySelection.restageHint?.let { hint ->
                SupportingNote(text = hint)
            }
            state.controlError?.let { error ->
                SupportingNote(
                    text = runtimeFailureDisplayText(
                        rawError = error,
                        section = state.runtimeSnapshot.lastErrorSection,
                        fieldPath = state.runtimeSnapshot.lastErrorFieldPath,
                    ) ?: error,
                    color = TunguskaTheme.danger,
                )
            }
        }
    }
    UtilityEntryCard(
        title = "Encrypted backup",
        value = backupSummary(state),
        detail = state.backupExportState.error ?: state.backupExportState.status,
        actionText = "Export",
        glyph = AppGlyph.SECURITY,
        accent = TunguskaTheme.accent,
        modifier = Modifier.testTag(UiTags.EXPORT_BACKUP_BUTTON),
        valueModifier = Modifier.testTag(UiTags.EXPORT_BACKUP_STATUS),
        onClick = onExportEncryptedBackup,
    )
    UtilityEntryCard(
        title = "Redacted audit",
        value = auditSummary(state),
        detail = state.auditExportState.error ?: state.auditExportState.status,
        actionText = "Export",
        glyph = AppGlyph.SECURITY,
        accent = TunguskaTheme.accentDim,
        modifier = Modifier.testTag(UiTags.EXPORT_AUDIT_BUTTON),
        valueModifier = Modifier.testTag(UiTags.EXPORT_AUDIT_STATUS),
        onClick = onBuildDiagnosticBundle,
    )
    UtilityEntryCard(
        title = "Automation",
        value = if (state.automationState.enabled) "Enabled" else "Disabled",
        detail = "Permission ${if (state.automationState.vpnPermissionReady) "ready" else "required"} · Last result ${state.automationState.lastAutomationStatus}",
        actionText = "Manage",
        glyph = AppGlyph.ROUTING,
        accent = TunguskaTheme.accent,
        modifier = Modifier.testTag(UiTags.OPEN_AUTOMATION_BUTTON),
        onClick = onOpenAutomation,
    )
    UtilityEntryCard(
        title = "Advanced diagnostics",
        value = "Operational controls",
        detail = "Runtime lane, restage, storage, and build internals",
        actionText = "Open",
        glyph = AppGlyph.SECURITY,
        accent = TunguskaTheme.warning,
        modifier = Modifier.testTag(UiTags.SHOW_DIAGNOSTICS_BUTTON),
        onClick = onOpenAdvancedDiagnostics,
    )
}

@Composable
private fun AutomationScreen(
    metrics: UiMetrics,
    state: AutomationState,
    onToggleEnabled: (Boolean) -> Unit,
    onRotateToken: () -> Unit,
    onCopyToken: () -> Unit,
    onRefresh: () -> Unit,
) {
    val lastAutomationError = runtimeFailureDisplayText(
        rawError = state.lastAutomationError,
        section = state.lastAutomationErrorSection,
        fieldPath = state.lastAutomationErrorFieldPath,
    )
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Enable Anubis automation", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Grant VPN permission once manually, then allow the token-gated local control surface to operate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.mutedText,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag(UiTags.AUTOMATION_ENABLE_SWITCH),
                )
            }
            DetailListCard(
                title = "Automation status",
                rows = buildList {
                    add("VPN permission" to if (state.vpnPermissionReady) "granted" else "required")
                    add("Token" to (state.tokenPreview ?: "not generated"))
                    add("Last automation result" to state.lastAutomationStatus)
                    state.lastAutomationRuntimeMetadata?.laneLabel?.let { add("Last runtime lane" to it) }
                    state.lastAutomationRuntimeMetadata?.connectDecisionSummary?.let {
                        add("Last lane decision" to it)
                    }
                    lastAutomationError?.let { add("Last automation error" to it) }
                    state.lastAutomationErrorSection?.let { add("Last error section" to it) }
                    state.lastAutomationErrorFieldPath?.let { add("Last error field path" to it) }
                    add("Last caller hint" to (state.lastCallerHint ?: "none"))
                },
            )
        }
    }
    state.status?.let { SupportingNote(text = it) }
    state.error?.let { SupportingNote(text = it, color = TunguskaTheme.danger) }
    SurfaceCard(subtle = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Token actions", style = MaterialTheme.typography.titleLarge)
            ActionCluster(compact = metrics.compact) {
                OutlinedButton(
                    onClick = onRotateToken,
                    enabled = state.enabled,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.AUTOMATION_ROTATE_TOKEN_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Rotate token")
                }
                OutlinedButton(
                    onClick = onCopyToken,
                    enabled = state.enabled && state.rawToken != null,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.AUTOMATION_COPY_TOKEN_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Copy token")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.AUTOMATION_REFRESH_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun AdvancedDiagnosticsScreen(
    metrics: UiMetrics,
    state: MainUiState,
    onRefreshRuntime: () -> Unit,
    onStageRuntime: () -> Unit,
    onRuntimeStrategyChange: (EmbeddedRuntimeStrategyId) -> Unit,
    onReloadProfile: () -> Unit,
    onResealProfile: () -> Unit,
) {
    val connectDecision = state.connectDecision
    val diagnosticsSelection = state.runtimeSelection
    val diagnosticsStrategy = diagnosticsSelection.activeStrategyId ?: diagnosticsSelection.configuredStrategyId
    val capabilityProfile = remember(state.automationState.runtimeStrategy) {
        StrategyCapabilityRegistry.profileFor(state.automationState.runtimeStrategy)
    }
    val capabilitySummaryBringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val runtimeFailureRows = remember(
        state.controlError,
        state.runtimeSnapshot.lastError,
        state.runtimeSnapshot.lastErrorSection,
        state.runtimeSnapshot.lastErrorFieldPath,
    ) {
        runtimeFailureDiagnosticsRows(
            rawError = state.controlError ?: state.runtimeSnapshot.lastError,
            section = state.runtimeSnapshot.lastErrorSection,
            fieldPath = state.runtimeSnapshot.lastErrorFieldPath,
        )
    }
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Runtime controls", style = MaterialTheme.typography.titleLarge)
            Text(
                "Engine changes apply to the next staged or connected session. Use this only for operational work.",
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = { onRuntimeStrategyChange(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS) },
                    modifier = actionButtonModifier(metrics).testTag(UiTags.RUNTIME_STRATEGY_XRAY_BUTTON),
                    colors = buttonColorsForSelection(
                        active = state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                    ),
                ) {
                    Text("xray+tun2socks")
                }
                Button(
                    onClick = { onRuntimeStrategyChange(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) },
                    modifier = actionButtonModifier(metrics).testTag(UiTags.RUNTIME_STRATEGY_SINGBOX_BUTTON),
                    colors = buttonColorsForSelection(
                        active = state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    ),
                ) {
                    Text("sing-box embedded")
                }
            }
            ActionCluster(compact = metrics.compact) {
                OutlinedButton(
                    onClick = onRefreshRuntime,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.REFRESH_RUNTIME_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                ) {
                    Text("Refresh runtime")
                }
                OutlinedButton(
                    onClick = onStageRuntime,
                    modifier = actionButtonModifier(metrics).testTag(UiTags.RESTAGE_RUNTIME_BUTTON),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke),
                ) {
                    Text("Restage")
                }
            }
        }
    }
    key(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD) {
        StrategyCapabilitySummaryCard(
            capabilityProfile = capabilityProfile,
            bringIntoViewRequester = capabilitySummaryBringIntoViewRequester,
        )
    }
    key(UiTags.RUNTIME_LANE_SUMMARY_CARD) {
        RuntimeLaneSummaryCard(
            diagnosticsSelection = diagnosticsSelection,
            diagnosticsStrategy = diagnosticsStrategy,
            connectDecision = connectDecision,
            onInfoClick = {
                coroutineScope.launch {
                    capabilitySummaryBringIntoViewRequester.bringIntoView()
                }
            },
        )
    }
    DetailListCard(
        title = "Runtime internals",
        rows = buildList {
            add("Payload bytes" to state.runtimeSnapshot.compiledPayloadBytes.toString())
            add("Strategy" to (state.runtimeSnapshot.activeStrategy?.name ?: "n/a"))
            add(
                "Compiler path" to (
                    state.runtimeSnapshot.runtimeConfigSource
                        ?.let(::runtimeConfigSourceLabel)
                        ?: "n/a"
                ),
            )
            state.runtimeSnapshot.configuredRuntimeConfigSource
                ?.takeIf {
                    state.runtimeSnapshot.activeStrategy == null ||
                        state.runtimeSnapshot.configuredStrategy != state.runtimeSnapshot.activeStrategy ||
                        it != state.runtimeSnapshot.runtimeConfigSource
                }
                ?.let { nextStartCompilerPath ->
                    add("Next-start compiler path" to runtimeConfigSourceLabel(nextStartCompilerPath))
                }
            add("Profile shape" to (state.runtimeSnapshot.profileShapeLabel() ?: "n/a"))
            add("Profile protocol" to (state.runtimeSnapshot.profileProtocolId?.displayLabel() ?: "n/a"))
            add("Profile transport" to (state.runtimeSnapshot.profileTransportId?.displayLabel() ?: "n/a"))
            add("Profile security" to (state.runtimeSnapshot.profileSecurityId?.displayLabel() ?: "n/a"))
            add("Bridge port" to (state.runtimeSnapshot.bridgePort?.toString() ?: "0"))
            add("xray pid" to (state.runtimeSnapshot.xrayPid?.toString() ?: "0"))
            add("tun2socks pid" to (state.runtimeSnapshot.tun2socksPid?.toString() ?: "0"))
            add("Routed traffic observed" to state.runtimeSnapshot.routedTrafficObserved.toString())
            add("DNS failure observed" to state.runtimeSnapshot.dnsFailureObserved.toString())
            add("MTU" to (state.runtimeSnapshot.mtu?.toString() ?: "0"))
            add("Last exposure check" to formatTimestamp(state.runtimeSnapshot.lastAuditAtEpochMs))
            add("Bootstrap summary" to (state.runtimeSnapshot.lastBootstrapSummary ?: "none"))
            add("Health summary" to (state.runtimeSnapshot.lastEngineHealthSummary ?: "none"))
            addAll(runtimeFailureRows)
        },
    )
    ResponsivePair(
        metrics = metrics,
        first = {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Secure profile storage", style = MaterialTheme.typography.titleLarge)
                    DetailRow("Backend", state.profileStorage.backend)
                    DetailRow("Key reference", abbreviateHash(state.profileStorage.keyReference))
                    DetailRow("Storage path", state.profileStorage.storagePath)
                    DetailRow("Persisted hash", abbreviateHash(state.profileStorage.persistedProfileHash ?: "pending"))
                    DetailRow("Last sealed", state.profileStorage.lastPersistedAt ?: "n/a")
                    ActionCluster(compact = metrics.compact) {
                        OutlinedButton(
                            onClick = onReloadProfile,
                            modifier = actionButtonModifier(metrics),
                            border = BorderStroke(1.dp, TunguskaTheme.stroke),
                        ) {
                            Text("Reload")
                        }
                        OutlinedButton(
                            onClick = onResealProfile,
                            modifier = actionButtonModifier(metrics),
                            border = BorderStroke(1.dp, TunguskaTheme.stroke),
                        ) {
                            Text("Reseal")
                        }
                    }
                }
            }
        },
        second = {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Deterministic build surface", style = MaterialTheme.typography.titleLarge)
                    DetailRow("Canonical profile hash", abbreviateHash(state.profile.canonicalHash()))
                    DetailRow("Compiled config hash", abbreviateHash(state.compiledConfig.configHash))
                    DetailRow("Compiled payload bytes", state.compiledConfig.payload.length.toString())
                    DetailRow("Preserve loopback", state.compiledConfig.vpnDirectives.preserveLoopback.toString())
                    DetailRow("Safe mode", state.compiledConfig.vpnDirectives.safeMode.toString())
                }
            }
        },
    )
    DetailListCard(
        title = "Tunnel plan",
        rows = listOf(
            "Process" to state.tunnelPlan.processNameSuffix,
            "Preserve loopback" to state.tunnelPlan.preserveLoopback.toString(),
            "Split mode" to state.tunnelPlan.splitTunnelMode::class.simpleName.orEmpty(),
            "Allowed apps" to state.tunnelPlan.allowedPackages.ifEmpty { listOf("all") }.joinToString(),
            "Excluded apps" to state.tunnelPlan.disallowedPackages.ifEmpty { listOf("none") }.joinToString(),
            "Runtime policy" to state.tunnelPlan.runtimeMode.name,
        ),
    )
}

@Composable
private fun UtilityEntryCard(
    title: String,
    value: String,
    detail: String,
    actionText: String,
    glyph: AppGlyph,
    accent: Color,
    modifier: Modifier = Modifier,
    valueModifier: Modifier = Modifier,
    detailModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SurfaceCard(
        modifier = modifier.clickable(onClick = onClick),
        subtle = true,
        accent = accent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = accent.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
            ) {
                Box(
                    modifier = Modifier.padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppGlyphIcon(
                        glyph = glyph,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    value,
                    modifier = valueModifier,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    modifier = detailModifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppGlyphIcon(
                    glyph = AppGlyph.CHEVRON_RIGHT,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    actionText,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TunguskaTheme.accent,
            contentColor = Color(0xFF061214),
            disabledContainerColor = TunguskaTheme.surfaceStrong,
            disabledContentColor = TunguskaTheme.mutedText,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun SummaryEntryCard(
    title: String,
    body: List<String>,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    glyph: AppGlyph = AppGlyph.SECURITY,
    accent: Color = TunguskaTheme.accentDim,
) {
    SurfaceCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                    ) {
                        Box(
                            modifier = Modifier.padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppGlyphIcon(
                                glyph = glyph,
                                tint = accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = TunguskaTheme.surfaceStrong.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.75f)),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppGlyphIcon(
                            glyph = AppGlyph.CHEVRON_RIGHT,
                            tint = accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            body.firstOrNull()?.let { firstLine ->
                Text(
                    text = firstLine,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            body.drop(1).take(2).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.mutedText,
                )
            }
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = actionModifier,
                border = BorderStroke(1.dp, TunguskaTheme.stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.accent),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun StrategyCapabilitySummaryCard(
    capabilityProfile: StrategyCapabilityProfile,
    bringIntoViewRequester: BringIntoViewRequester,
) {
    SurfaceCard(
        subtle = true,
        accent = TunguskaTheme.accentDim,
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .testTag(UiTags.STRATEGY_CAPABILITY_SUMMARY_CARD),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Strategy capability summary", style = MaterialTheme.typography.titleLarge)
            Text(
                capabilityProfile.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                capabilityProfile.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
            capabilityProfile.capabilityMatrix.features.forEach { support ->
                CapabilitySupportRow(support = support)
            }
        }
    }
}

@Composable
private fun RuntimeLaneSummaryCard(
    diagnosticsSelection: RuntimeSelectionState,
    diagnosticsStrategy: EmbeddedRuntimeStrategyId,
    connectDecision: ConnectDecisionState?,
    onInfoClick: () -> Unit,
) {
    SurfaceCard(
        subtle = true,
        accent = if (diagnosticsSelection.selectedSeverity == StrategyCompatibilitySeverity.READY && diagnosticsSelection.nextRestageLaneLabel == null) {
            TunguskaTheme.accentDim
        } else {
            TunguskaTheme.warning
        },
        modifier = Modifier.testTag(UiTags.RUNTIME_LANE_SUMMARY_CARD),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Runtime lane summary", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = diagnosticsSelection.activeLaneLabel,
                    accent = if (diagnosticsStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                        TunguskaTheme.accentDim
                    } else {
                        TunguskaTheme.warning
                    },
                )
                diagnosticsSelection.nextRestageLaneLabel?.let { label ->
                    StatusChip(
                        text = label,
                        accent = TunguskaTheme.accent,
                    )
                }
                StatusChip(
                    text = diagnosticsSelection.statusLabel,
                    accent = if (diagnosticsSelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
                        TunguskaTheme.accent
                    } else {
                        TunguskaTheme.warning
                    },
                )
                diagnosticsSelection.recommendedLaneLabel?.let { label ->
                    StatusChip(
                        text = label,
                        accent = TunguskaTheme.warning,
                    )
                }
            }
            CompatibilityLimitSummary(
                title = diagnosticsSelection.selectedSummaryTitle,
                severity = diagnosticsSelection.selectedSeverity,
                details = diagnosticsSelection.selectedSummaryDetails,
                onInfoClick = onInfoClick,
                infoButtonTag = UiTags.RUNTIME_LANE_SUMMARY_INFO_BUTTON,
                recommendation = diagnosticsSelection.recommendation,
                textStyle = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            diagnosticsSelection.recommendation?.let { recommendation ->
                Text(
                    recommendation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.bodyText,
                )
            }
            connectDecision?.let { decision ->
                Text(
                    decision.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (decision.phase) {
                        ConnectDecisionPhase.STARTING -> TunguskaTheme.warning
                        ConnectDecisionPhase.STARTED -> TunguskaTheme.accent
                        ConnectDecisionPhase.FAILED -> TunguskaTheme.danger
                    },
                )
            }
            diagnosticsSelection.nextStartDetail?.let { nextStartDetail ->
                Text(
                    "Next-start lane",
                    style = MaterialTheme.typography.labelLarge,
                    color = TunguskaTheme.mutedText,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(
                        text = nextStartDetail.statusLabel,
                        accent = if (nextStartDetail.statusLabel == "Clean match") {
                            TunguskaTheme.accent
                        } else {
                            TunguskaTheme.warning
                        },
                    )
                    nextStartDetail.recommendedLaneLabel?.let { label ->
                        StatusChip(
                            text = label,
                            accent = TunguskaTheme.warning,
                        )
                    }
                }
                Text(
                    nextStartDetail.summaryTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                nextStartDetail.recommendation?.let { recommendation ->
                    Text(
                        recommendation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.bodyText,
                    )
                }
            }
            diagnosticsSelection.restageHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.mutedText,
                )
            }
        }
    }
}

@Composable
private fun AttentionCard(
    title: String,
    body: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(28.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = accent,
            )
            body.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            actions?.invoke()
        }
    }
}

@Composable
private fun DetailListCard(
    title: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            rows.forEach { (label, value) ->
                DetailRow(label, value)
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TunguskaTheme.mutedText,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TunguskaTheme.bodyText,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
    accent: Color = TunguskaTheme.accent,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(modifier),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(if (subtle) 24.dp else 28.dp),
        border = BorderStroke(
            1.dp,
            if (subtle) TunguskaTheme.stroke.copy(alpha = 0.62f) else TunguskaTheme.stroke.copy(alpha = 0.9f),
        ),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            if (subtle) TunguskaTheme.surface.copy(alpha = 0.88f) else TunguskaTheme.surfaceStrong.copy(alpha = 0.95f),
                            TunguskaTheme.surface.copy(alpha = if (subtle) 0.92f else 0.98f),
                        ),
                    ),
                ),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = if (subtle) 0.035f else 0.08f),
                                Color.Transparent,
                            ),
                            center = Offset(120f, 0f),
                            radius = 820f,
                        ),
                    ),
            )
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SupportingNote(
    text: String,
    color: Color = TunguskaTheme.mutedText,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
internal fun CompatibilityLimitSummary(
    title: String,
    severity: StrategyCompatibilitySeverity,
    details: List<String>,
    onInfoClick: (() -> Unit)? = null,
    infoButtonTag: String? = null,
    onOpenAdvancedDiagnostics: (() -> Unit)? = null,
    recommendation: String? = null,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    var showLimitDetails by rememberSaveable(title, details, recommendation) { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = textStyle,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (severity == StrategyCompatibilitySeverity.ATTENTION && details.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .then(if (infoButtonTag != null) Modifier.testTag(infoButtonTag) else Modifier)
                    .clip(CircleShape)
                    .border(
                        width = 1.dp,
                        color = TunguskaTheme.warning.copy(alpha = 0.42f),
                        shape = CircleShape,
                    )
                    .background(TunguskaTheme.warning.copy(alpha = 0.12f))
                    .clickable {
                        if (onInfoClick != null) {
                            onInfoClick()
                        } else {
                            showLimitDetails = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "i",
                    style = MaterialTheme.typography.labelLarge,
                    color = TunguskaTheme.warning,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    if (showLimitDetails) {
        AlertDialog(
            onDismissRequest = { showLimitDetails = false },
            title = {
                Text("Engine limits", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Click to see more in Security.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.bodyText,
                    )
                    if (onOpenAdvancedDiagnostics != null) {
                        TextButton(onClick = {
                            showLimitDetails = false
                            onOpenAdvancedDiagnostics()
                        }) {
                            Text("Open Security diagnostics")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLimitDetails = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun StatusChip(
    text: String,
    accent: Color,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

@Composable
private fun RemovableDomainChip(
    domain: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = TunguskaTheme.surfaceStrong,
        border = BorderStroke(1.dp, TunguskaTheme.stroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

@Composable
private fun ResponsivePair(
    metrics: UiMetrics,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (metrics.expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.sectionSpacing)) {
            Box(modifier = Modifier.weight(1f)) { first() }
            Box(modifier = Modifier.weight(1f)) { second() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(metrics.sectionSpacing)) {
            first()
            second()
        }
    }
}

@Composable
private fun ActionCluster(
    compact: Boolean,
    content: @Composable () -> Unit,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

private fun actionButtonModifier(metrics: UiMetrics): Modifier =
    if (metrics.compact) Modifier.fillMaxWidth() else Modifier

@Composable
private fun buttonColorsForSelection(active: Boolean) = ButtonDefaults.buttonColors(
    containerColor = if (active) TunguskaTheme.accent.copy(alpha = 0.18f) else TunguskaTheme.surfaceStrong,
    contentColor = if (active) TunguskaTheme.accent else TunguskaTheme.bodyText,
)

private data class ProtocolSpecificEditorIntroSummary(
    val shapeChipLabel: String,
    val supportingText: String,
)

private fun ProfileIr.protocolSpecificEditorIntroSummary(): ProtocolSpecificEditorIntroSummary = when (outboundProtocolId) {
    OutboundProtocolId.VLESS_REALITY -> ProtocolSpecificEditorIntroSummary(
        shapeChipLabel = outboundSummary.shapeLabel,
        supportingText = "Import and QR stay the fastest path. Use this lane when you need to tune the active ${outboundSummary.shapeLabel} shape without replacing the whole payload.",
    )
}

@Composable
private fun ProtocolSpecificOutboundShapeCard(profile: ProfileIr) {
    when (profile.outboundProtocolId) {
        OutboundProtocolId.VLESS_REALITY -> VlessRealityOutboundShapeCard(profile = profile)
    }
}

@Composable
private fun VlessRealityOutboundShapeCard(profile: ProfileIr) {
    val outboundSummary = profile.outboundSummary
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current outbound shape", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "The editor shell stays protocol-agnostic below, but the transport and security fields in this section are specific to the active ${outboundSummary.shapeLabel} shape.",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(text = outboundSummary.protocolLabel, accent = TunguskaTheme.accent)
                StatusChip(text = outboundSummary.transportLabel, accent = TunguskaTheme.accentDim)
                StatusChip(text = outboundSummary.securityLabel, accent = TunguskaTheme.warning)
            }
        }
    }
}

@Composable
private fun ProtocolSpecificEndpointEditorFields(
    profile: ProfileIr,
    draft: ProfileEditorDraft,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    when (profile.outboundProtocolId) {
        OutboundProtocolId.VLESS_REALITY -> VlessRealityEndpointEditorFields(
            shapeLabel = profile.outboundShapeLabel(),
            draft = draft,
            onDraftChange = onDraftChange,
        )
    }
}

@Composable
private fun VlessRealityEndpointEditorFields(
    shapeLabel: String,
    draft: ProfileEditorDraft,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    SupportingNote(
        text = "These endpoint fields are specific to the active $shapeLabel shape and stay routed here until a second outbound family needs its own inputs.",
    )
    OutlinedTextField(
        value = draft.address,
        onValueChange = { onDraftChange(draft.copy(address = it)) },
        label = { Text("Server address") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.port,
        onValueChange = { onDraftChange(draft.copy(port = it)) },
        label = { Text("Port") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(
        value = draft.serverName,
        onValueChange = { onDraftChange(draft.copy(serverName = it)) },
        label = { Text("Server name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun ProtocolSpecificSecurityEditorCard(
    profile: ProfileIr,
    draft: ProfileEditorDraft,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    when (profile.outboundProtocolId) {
        OutboundProtocolId.VLESS_REALITY -> VlessRealitySecurityEditorCard(
            shapeLabel = profile.outboundShapeLabel(),
            draft = draft,
            onDraftChange = onDraftChange,
        )
    }
}

@Composable
private fun ProtocolSpecificTransportEditorCard(
    profile: ProfileIr,
    draft: ProfileEditorDraft,
    metrics: UiMetrics,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    when (profile.outboundProtocolId) {
        OutboundProtocolId.VLESS_REALITY -> VlessRealityTransportEditorCard(
            shapeLabel = profile.outboundShapeLabel(),
            draft = draft,
            metrics = metrics,
            onDraftChange = onDraftChange,
        )
    }
}

@Composable
private fun VlessRealitySecurityEditorCard(
    shapeLabel: String,
    draft: ProfileEditorDraft,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current shape security", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "These inputs are specific to the current $shapeLabel shape and will be the first fields to move behind a protocol-specific editor when more outbound families land.",
            )
            OutlinedTextField(
                value = draft.uuid,
                onValueChange = { onDraftChange(draft.copy(uuid = it)) },
                label = { Text("UUID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.realityPublicKey,
                onValueChange = { onDraftChange(draft.copy(realityPublicKey = it)) },
                label = { Text("REALITY public key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.realityShortId,
                onValueChange = { onDraftChange(draft.copy(realityShortId = it)) },
                label = { Text("REALITY short id") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.realitySpiderX,
                onValueChange = { onDraftChange(draft.copy(realitySpiderX = it)) },
                label = { Text("REALITY spider path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun VlessRealityTransportEditorCard(
    shapeLabel: String,
    draft: ProfileEditorDraft,
    metrics: UiMetrics,
    onDraftChange: (ProfileEditorDraft) -> Unit,
) {
    val fingerprintChoices = remember(draft.utlsFingerprint) {
        buildList {
            addAll(PROFILE_EDITOR_UTLS_FINGERPRINT_CHOICES)
            if (draft.utlsFingerprint !in this) {
                add(draft.utlsFingerprint)
            }
        }
    }
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current shape transport", style = MaterialTheme.typography.titleLarge)
            SupportingNote(
                text = "These controls are still specific to the active $shapeLabel shape. Flow is constrained to the only accepted v1 value, and the fingerprint picker stays typed unless you imported a custom value.",
            )
            Text("VLESS flow", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                Button(
                    onClick = { onDraftChange(draft.copy(flowEnabled = true)) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = draft.flowEnabled),
                ) {
                    Text(REQUIRED_VLESS_FLOW)
                }
                Button(
                    onClick = { onDraftChange(draft.copy(flowEnabled = false)) },
                    modifier = actionButtonModifier(metrics),
                    colors = buttonColorsForSelection(active = !draft.flowEnabled),
                ) {
                    Text("Unset")
                }
            }
            Text("uTLS fingerprint", style = MaterialTheme.typography.labelLarge, color = TunguskaTheme.mutedText)
            ActionCluster(compact = metrics.compact) {
                fingerprintChoices.forEach { fingerprint ->
                    Button(
                        onClick = { onDraftChange(draft.copy(utlsFingerprint = fingerprint)) },
                        modifier = actionButtonModifier(metrics),
                        colors = buttonColorsForSelection(active = draft.utlsFingerprint == fingerprint),
                    ) {
                        Text(fingerprint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TunguskaTheme.mutedText else TunguskaTheme.mutedText.copy(alpha = 0.68f),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ProfileEditorGuidanceCard(
    guidance: ProfileSectionGuidance,
    modifier: Modifier = Modifier,
) {
    AttentionCard(
        title = guidance.title,
        body = guidance.details,
        accent = if (guidance.severity == StrategyCompatibilitySeverity.READY) {
            TunguskaTheme.accentDim
        } else {
            TunguskaTheme.warning
        },
        modifier = modifier,
    )
}

@Composable
private fun CapabilitySupportRow(
    support: EngineCapabilitySupport,
) {
    val expandable = support.state != EngineCapabilitySupportState.SUPPORTED
    var detailsExpanded by rememberSaveable(support.feature, support.state, support.summary) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = support.feature.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusChip(
                text = capabilityStateLabel(support.state),
                accent = capabilityStateAccent(support.state),
                onClick = if (expandable) {
                    { detailsExpanded = !detailsExpanded }
                } else {
                    null
                },
            )
        }
        if (support.state == EngineCapabilitySupportState.SUPPORTED) {
            Text(
                text = support.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = TunguskaTheme.mutedText,
            )
        } else if (detailsExpanded) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = capabilityStateAccent(support.state).copy(alpha = 0.08f),
                border = BorderStroke(1.dp, capabilityStateAccent(support.state).copy(alpha = 0.22f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (support.state == EngineCapabilitySupportState.UNSUPPORTED) {
                            "Technical block"
                        } else {
                            "Technical limit"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = capabilityStateAccent(support.state),
                    )
                    Text(
                        text = support.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.bodyText,
                    )
                }
            }
        }
    }
}

private fun capabilityStateLabel(state: EngineCapabilitySupportState): String = when (state) {
    EngineCapabilitySupportState.SUPPORTED -> "Supported"
    EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS -> "Supported with limits"
    EngineCapabilitySupportState.DEGRADED_ON_FALLBACK -> "Degraded on fallback"
    EngineCapabilitySupportState.UNSUPPORTED -> "Unsupported"
}

private fun capabilityStateAccent(state: EngineCapabilitySupportState): Color = when (state) {
    EngineCapabilitySupportState.SUPPORTED -> TunguskaTheme.accent
    EngineCapabilitySupportState.SUPPORTED_WITH_LIMITS -> TunguskaTheme.warning
    EngineCapabilitySupportState.DEGRADED_ON_FALLBACK -> TunguskaTheme.warning
    EngineCapabilitySupportState.UNSUPPORTED -> TunguskaTheme.danger
}

private enum class AppGlyph {
    HOME,
    PROFILE,
    ROUTING,
    SECURITY,
    POWER,
    ALERT,
    BACK,
    CHEVRON_RIGHT,
}

private fun glyphForSection(section: TunguskaSection): AppGlyph = when (section) {
    TunguskaSection.HOME -> AppGlyph.HOME
    TunguskaSection.PROFILES -> AppGlyph.PROFILE
    TunguskaSection.ROUTING -> AppGlyph.ROUTING
    TunguskaSection.SECURITY -> AppGlyph.SECURITY
}

@Composable
private fun AppGlyphIcon(
    glyph: AppGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * if (glyph == AppGlyph.SECURITY) 0.088f else 0.1f
        val inset = if (glyph == AppGlyph.SECURITY) {
            size.minDimension * 0.045f
        } else {
            strokeWidth * 1.35f
        }
        val left = inset
        val top = inset
        val iconWidth = size.width - inset * 2f
        val iconHeight = size.height - inset * 2f
        fun x(fraction: Float): Float = left + iconWidth * fraction
        fun y(fraction: Float): Float = top + iconHeight * fraction
        fun point(xFraction: Float, yFraction: Float): Offset = Offset(x(xFraction), y(yFraction))
        fun iconSize(widthFraction: Float, heightFraction: Float): Size =
            Size(iconWidth * widthFraction, iconHeight * heightFraction)
        when (glyph) {
            AppGlyph.HOME -> {
                val roof = Path().apply {
                    moveTo(x(0.18f), y(0.52f))
                    lineTo(x(0.5f), y(0.2f))
                    lineTo(x(0.82f), y(0.52f))
                }
                val body = Path().apply {
                    moveTo(x(0.28f), y(0.5f))
                    lineTo(x(0.28f), y(0.8f))
                    lineTo(x(0.72f), y(0.8f))
                    lineTo(x(0.72f), y(0.5f))
                }
                val door = Path().apply {
                    moveTo(x(0.45f), y(0.8f))
                    lineTo(x(0.45f), y(0.6f))
                    lineTo(x(0.55f), y(0.6f))
                    lineTo(x(0.55f), y(0.8f))
                }
                drawPath(roof, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(body, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(door, tint, style = Stroke(width = strokeWidth * 0.82f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            AppGlyph.PROFILE -> {
                drawCircle(
                    color = tint,
                    radius = iconWidth.coerceAtMost(iconHeight) * 0.13f,
                    center = point(0.5f, 0.28f),
                    style = Stroke(width = strokeWidth),
                )
                val shoulders = Path().apply {
                    moveTo(x(0.23f), y(0.82f))
                    cubicTo(
                        x(0.25f),
                        y(0.6f),
                        x(0.75f),
                        y(0.6f),
                        x(0.77f),
                        y(0.82f),
                    )
                }
                drawPath(shoulders, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            AppGlyph.ROUTING -> {
                val topNode = point(0.5f, 0.2f)
                val leftNode = point(0.28f, 0.72f)
                val rightNode = point(0.72f, 0.72f)
                drawLine(tint, topNode, leftNode, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, topNode, rightNode, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, leftNode, rightNode, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawCircle(tint, radius = iconWidth.coerceAtMost(iconHeight) * 0.09f, center = topNode, style = Stroke(width = strokeWidth * 0.9f))
                drawCircle(tint, radius = iconWidth.coerceAtMost(iconHeight) * 0.09f, center = leftNode, style = Stroke(width = strokeWidth * 0.9f))
                drawCircle(tint, radius = iconWidth.coerceAtMost(iconHeight) * 0.09f, center = rightNode, style = Stroke(width = strokeWidth * 0.9f))
            }

            AppGlyph.SECURITY -> {
                val shield = Path().apply {
                    moveTo(x(0.5f), y(0.1f))
                    cubicTo(x(0.75f), y(0.18f), x(0.88f), y(0.25f), x(0.88f), y(0.4f))
                    lineTo(x(0.88f), y(0.55f))
                    cubicTo(x(0.88f), y(0.75f), x(0.7f), y(0.88f), x(0.5f), y(0.92f))
                    cubicTo(x(0.3f), y(0.88f), x(0.12f), y(0.75f), x(0.12f), y(0.55f))
                    lineTo(x(0.12f), y(0.4f))
                    cubicTo(x(0.12f), y(0.25f), x(0.25f), y(0.18f), x(0.5f), y(0.1f))
                    close()
                }
                val check = Path().apply {
                    moveTo(x(0.34f), y(0.5f))
                    lineTo(x(0.47f), y(0.63f))
                    lineTo(x(0.69f), y(0.39f))
                }
                drawPath(shield, tint.copy(alpha = 0.08f))
                drawPath(shield, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(check, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            AppGlyph.POWER -> {
                drawArc(
                    color = tint,
                    startAngle = 315f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(x(0.18f), y(0.2f)),
                    size = iconSize(0.64f, 0.64f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawLine(
                    color = tint,
                    start = point(0.5f, 0.12f),
                    end = point(0.5f, 0.46f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            AppGlyph.ALERT -> {
                val triangle = Path().apply {
                    moveTo(x(0.5f), y(0.16f))
                    lineTo(x(0.86f), y(0.82f))
                    lineTo(x(0.14f), y(0.82f))
                    close()
                }
                drawPath(triangle, tint.copy(alpha = 0.08f))
                drawPath(triangle, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(
                    color = tint,
                    start = point(0.5f, 0.36f),
                    end = point(0.5f, 0.58f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = tint,
                    radius = strokeWidth * 0.58f,
                    center = point(0.5f, 0.69f),
                )
            }

            AppGlyph.BACK -> {
                val path = Path().apply {
                    moveTo(x(0.72f), y(0.2f))
                    lineTo(x(0.3f), y(0.5f))
                    lineTo(x(0.72f), y(0.8f))
                }
                drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            AppGlyph.CHEVRON_RIGHT -> {
                val path = Path().apply {
                    moveTo(x(0.3f), y(0.2f))
                    lineTo(x(0.7f), y(0.5f))
                    lineTo(x(0.3f), y(0.8f))
                }
                drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

internal object TunguskaTheme {
    val accent = Color(0xFF63E7D4)
    val accentDim = Color(0xFF7BC2B8)
    val warning = Color(0xFFE8BF63)
    val danger = Color(0xFFFF8979)
    val chrome = Color(0xFF081117)
    val surface = Color(0xFF101A20)
    val surfaceStrong = Color(0xFF15242C)
    val stroke = Color(0xFF24404A)
    val mutedText = Color(0xFF8FA2AB)
    val bodyText = Color(0xFFEAF2F3)
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF04090D),
            Color(0xFF081116),
            Color(0xFF0A141A),
        ),
    )

    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF061214),
        secondary = accentDim,
        onSecondary = Color(0xFF091214),
        background = Color(0xFF071015),
        onBackground = Color(0xFFEAF2F4),
        surface = surface,
        onSurface = Color(0xFFEAF2F4),
        surfaceVariant = surfaceStrong,
        onSurfaceVariant = mutedText,
        error = danger,
        onError = Color.White,
    )

    private val brandFont = FontFamily(
        Font(R.font.sora_wght, weight = FontWeight.Normal),
        Font(R.font.sora_wght, weight = FontWeight.Medium),
        Font(R.font.sora_wght, weight = FontWeight.SemiBold),
        Font(R.font.sora_wght, weight = FontWeight.Bold),
    )
    private val bodyFont = FontFamily(
        Font(R.font.manrope_wght, weight = FontWeight.Normal),
        Font(R.font.manrope_wght, weight = FontWeight.Medium),
        Font(R.font.manrope_wght, weight = FontWeight.SemiBold),
        Font(R.font.manrope_wght, weight = FontWeight.Bold),
    )

    val typography = Typography(
        headlineLarge = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.Bold,
            fontSize = 29.sp,
            letterSpacing = (-0.4).sp,
            lineHeight = 33.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            letterSpacing = (-0.3).sp,
            lineHeight = 28.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 25.sp,
        ),
        displaySmall = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.8).sp,
            lineHeight = 36.sp,
        ),
        titleLarge = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 24.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = brandFont,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 0.6.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp,
        ),
    )
}

@Composable
private fun InfoHint(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = TunguskaTheme.accentDim.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, TunguskaTheme.accentDim.copy(alpha = 0.34f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "i",
                    style = MaterialTheme.typography.labelMedium,
                    color = TunguskaTheme.accentDim,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TunguskaTheme.mutedText,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTimestamp(epochMs: Long?): String = epochMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "n/a"

private fun runtimeStrategyShortLabel(strategy: EmbeddedRuntimeStrategyId): String = when (strategy) {
    EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS -> "Engine: Xray"
    EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED -> "Engine: Sing-box"
}

@Immutable
private enum class HeroBackdropMode {
    PARTICLE_FLOW,
    PLASMA_FIELD,
}

@Immutable
private data class HeroVisual(
    val accent: Color,
    val coreFill: Color,
    val orbitDurationMillis: Int,
    val breathDurationMillis: Int,
    val pulseDurationMillis: Int,
    val segmentOrbitDurationMillis: Int,
    val outerScale: Float,
    val coreScale: Float,
    val glowAlpha: Float,
    val baseRingAlpha: Float,
    val trailingArcAlpha: Float,
    val trailingSweep: Float,
    val primarySweep: Float,
    val primaryArcOffset: Float,
    val secondarySweep: Float,
    val ringStrokeScale: Float,
    val coreDiameterScale: Float,
    val backdropMode: HeroBackdropMode,
    val backdropScale: Float,
    val backdropAlpha: Float,
    val backdropEnergy: Float,
    val backdropPulseMin: Float,
    val backdropPulseMax: Float,
    val backdropInstability: Float,
    val burstEnabled: Boolean,
    val burstScale: Float,
    val burstIntensity: Float,
    val burstDurationMillis: Int,
)

private fun heroVisual(phase: VpnRuntimePhase): HeroVisual = when (phase) {
    VpnRuntimePhase.IDLE -> HeroVisual(
        accent = Color(0xFF4DBBFF),
        coreFill = Color(0xFF09131E),
        orbitDurationMillis = 30_000,
        breathDurationMillis = 8_200,
        pulseDurationMillis = 7_200,
        segmentOrbitDurationMillis = 34_000,
        outerScale = 1.008f,
        coreScale = 0.9f,
        glowAlpha = 0.08f,
        baseRingAlpha = 0.05f,
        trailingArcAlpha = 0.08f,
        trailingSweep = 62f,
        primarySweep = 12f,
        primaryArcOffset = 188f,
        secondarySweep = 0f,
        ringStrokeScale = 0.74f,
        coreDiameterScale = 0.42f,
        backdropMode = HeroBackdropMode.PARTICLE_FLOW,
        backdropScale = 1.68f,
        backdropAlpha = 0.28f,
        backdropEnergy = 0.32f,
        backdropPulseMin = 0.84f,
        backdropPulseMax = 1.04f,
        backdropInstability = 0.018f,
        burstEnabled = false,
        burstScale = 1.72f,
        burstIntensity = 0.34f,
        burstDurationMillis = 980,
    )

    VpnRuntimePhase.STAGED -> HeroVisual(
        accent = Color(0xFF80E4DC),
        coreFill = Color(0xFF102B30),
        orbitDurationMillis = 14_000,
        breathDurationMillis = 5_200,
        pulseDurationMillis = 3_800,
        segmentOrbitDurationMillis = 22_000,
        outerScale = 1.02f,
        coreScale = 1.01f,
        glowAlpha = 0.28f,
        baseRingAlpha = 0.14f,
        trailingArcAlpha = 0.18f,
        trailingSweep = 108f,
        primarySweep = 34f,
        primaryArcOffset = 22f,
        secondarySweep = 18f,
        ringStrokeScale = 0.9f,
        coreDiameterScale = 0.46f,
        backdropMode = HeroBackdropMode.PARTICLE_FLOW,
        backdropScale = 1.82f,
        backdropAlpha = 0.42f,
        backdropEnergy = 0.48f,
        backdropPulseMin = 0.8f,
        backdropPulseMax = 1.04f,
        backdropInstability = 0.05f,
        burstEnabled = true,
        burstScale = 1.84f,
        burstIntensity = 0.5f,
        burstDurationMillis = 980,
    )

    VpnRuntimePhase.START_REQUESTED -> HeroVisual(
        accent = Color(0xFFFFC83D),
        coreFill = Color(0xFF2A210A),
        orbitDurationMillis = 5_200,
        breathDurationMillis = 2_300,
        pulseDurationMillis = 1_700,
        segmentOrbitDurationMillis = 8_800,
        outerScale = 1.036f,
        coreScale = 1.07f,
        glowAlpha = 0.48f,
        baseRingAlpha = 0.22f,
        trailingArcAlpha = 0.28f,
        trailingSweep = 124f,
        primarySweep = 58f,
        primaryArcOffset = 8f,
        secondarySweep = 44f,
        ringStrokeScale = 1f,
        coreDiameterScale = 0.52f,
        backdropMode = HeroBackdropMode.PLASMA_FIELD,
        backdropScale = 2.08f,
        backdropAlpha = 0.68f,
        backdropEnergy = 0.92f,
        backdropPulseMin = 0.76f,
        backdropPulseMax = 1.06f,
        backdropInstability = 0.2f,
        burstEnabled = true,
        burstScale = 2.12f,
        burstIntensity = 1.02f,
        burstDurationMillis = 1_180,
    )

    VpnRuntimePhase.RUNNING -> HeroVisual(
        accent = Color(0xFF67FF83),
        coreFill = Color(0xFF072D1C),
        orbitDurationMillis = 12_000,
        breathDurationMillis = 3_800,
        pulseDurationMillis = 2_800,
        segmentOrbitDurationMillis = 17_000,
        outerScale = 1.034f,
        coreScale = 1.14f,
        glowAlpha = 0.66f,
        baseRingAlpha = 0.2f,
        trailingArcAlpha = 0.26f,
        trailingSweep = 82f,
        primarySweep = 36f,
        primaryArcOffset = 12f,
        secondarySweep = 24f,
        ringStrokeScale = 0.96f,
        coreDiameterScale = 0.54f,
        backdropMode = HeroBackdropMode.PLASMA_FIELD,
        backdropScale = 1.94f,
        backdropAlpha = 0.56f,
        backdropEnergy = 0.72f,
        backdropPulseMin = 0.8f,
        backdropPulseMax = 1.02f,
        backdropInstability = 0.072f,
        burstEnabled = true,
        burstScale = 1.94f,
        burstIntensity = 0.88f,
        burstDurationMillis = 1_080,
    )

    VpnRuntimePhase.FAIL_CLOSED -> HeroVisual(
        accent = Color(0xFFFF4F5E),
        coreFill = Color(0xFF300A11),
        orbitDurationMillis = 7_200,
        breathDurationMillis = 2_000,
        pulseDurationMillis = 1_400,
        segmentOrbitDurationMillis = 8_200,
        outerScale = 1.028f,
        coreScale = 1.1f,
        glowAlpha = 0.58f,
        baseRingAlpha = 0.24f,
        trailingArcAlpha = 0.32f,
        trailingSweep = 96f,
        primarySweep = 46f,
        primaryArcOffset = 20f,
        secondarySweep = 32f,
        ringStrokeScale = 1f,
        coreDiameterScale = 0.52f,
        backdropMode = HeroBackdropMode.PLASMA_FIELD,
        backdropScale = 2.02f,
        backdropAlpha = 0.62f,
        backdropEnergy = 0.84f,
        backdropPulseMin = 0.78f,
        backdropPulseMax = 1.04f,
        backdropInstability = 0.16f,
        burstEnabled = true,
        burstScale = 1.98f,
        burstIntensity = 0.9f,
        burstDurationMillis = 960,
    )
}

private fun periodicRange(
    progress: Float,
    min: Float,
    max: Float,
    phaseOffset: Float = 0f,
): Float {
    val normalized = ((progress + phaseOffset) % 1f + 1f) % 1f
    val wave = ((1.0 - cos(normalized * PI * 2.0)) * 0.5).toFloat()
    return min + (max - min) * wave
}

private fun heroHighlightedSegments(phase: VpnRuntimePhase): IntArray = when (phase) {
    VpnRuntimePhase.IDLE -> intArrayOf(4)
    VpnRuntimePhase.STAGED -> intArrayOf(0, 4)
    VpnRuntimePhase.START_REQUESTED -> intArrayOf(0, 1, 3, 5)
    VpnRuntimePhase.RUNNING -> intArrayOf(0, 2, 3, 5)
    VpnRuntimePhase.FAIL_CLOSED -> intArrayOf(0, 2, 4, 5)
}

private fun heroGlyph(phase: VpnRuntimePhase): AppGlyph = when (phase) {
    VpnRuntimePhase.START_REQUESTED -> AppGlyph.POWER
    VpnRuntimePhase.FAIL_CLOSED -> AppGlyph.ALERT
    else -> AppGlyph.SECURITY
}

private fun profileTypeLabel(profile: io.acionyx.tunguska.domain.ProfileIr): String =
    profile.outboundShapeLabel()

private fun profileSealLabel(state: MainUiState): String =
    if (state.profileStorage.persistedProfileHash == state.profile.canonicalHash()) {
        "Sealed and verified"
    } else {
        "Seal pending"
    }

private fun profileSealAccent(state: MainUiState): Color =
    if (state.profileStorage.persistedProfileHash == state.profile.canonicalHash()) {
        TunguskaTheme.accent
    } else {
        TunguskaTheme.warning
    }

private fun backupSummary(state: MainUiState): String =
    exportSummary(
        exportState = state.backupExportState,
        empty = "No backup yet",
        prefix = "Backup saved",
    )

private fun auditSummary(state: MainUiState): String =
    exportSummary(
        exportState = state.auditExportState,
        empty = "No audit bundle yet",
        prefix = "Audit saved",
    )

private fun exportSummary(
    exportState: ExportArtifactState,
    empty: String,
    prefix: String,
): String = when {
    exportState.error != null -> "Export needs attention"
    exportState.lastArtifactHash != null -> "$prefix ${abbreviateHash(exportState.lastArtifactHash)}"
    else -> empty
}

private data class DirectDomainRow(
    val label: String,
    val source: String,
)

private fun effectiveDirectDomainRows(state: MainUiState): List<DirectDomainRow> {
    val effective = EffectiveRoutingPolicyResolver.resolve(state.profile)
    return effective.rules
        .filter { rule -> rule.action == RouteAction.DIRECT && rule.match.domainSuffix.isNotEmpty() }
        .flatMap { rule ->
            val source = when (rule.id) {
                "__regional_bypass_russia__" -> "Russia preset"
                "__regional_bypass_custom_direct__" -> "Custom"
                else -> "Profile rule ${rule.id}"
            }
            rule.match.domainSuffix.distinct().map { suffix ->
                DirectDomainRow(label = ".$suffix", source = source)
            }
        }
        .distinctBy { it.label to it.source }
}

private fun routePreviewInputSummary(inputs: PreviewInputs): String = buildList {
    inputs.destinationHost.takeIf { it.isNotBlank() }?.let { add("host $it") }
    inputs.destinationIp.takeIf { it.isNotBlank() }?.let { add("ip $it") }
    inputs.destinationPort.takeIf { it.isNotBlank() }?.let { add("port $it") }
    add(inputs.protocol.name)
    inputs.packageName.takeIf { it.isNotBlank() }?.let { add("app $it") }
}.joinToString(", ")

private fun routeTestStatusText(state: MainUiState): String = when {
    state.routePreviewLastTestedAtEpochMs == null ->
        "Not tested yet. Press Test route to simulate this policy."

    state.routePreviewStale ->
        "Draft changed after the last run. Press Test route again."

    else ->
        "${routeOutcomeUserLabel(state.previewOutcome.action)}. Test complete at ${formatTimestamp(state.routePreviewLastTestedAtEpochMs)}."
}

private fun routeOutcomeUserLabel(action: RouteAction): String = when (action) {
    RouteAction.PROXY -> "Would use VPN"
    RouteAction.DIRECT -> "Would go direct"
    RouteAction.BLOCK -> "Would be blocked"
}

private fun routeOutcomeExpectationLine(action: RouteAction): String = when (action) {
    RouteAction.PROXY -> "Expected path: VPN tunnel."
    RouteAction.DIRECT -> "Expected path: direct device network, outside the VPN tunnel."
    RouteAction.BLOCK -> "Expected path: blocked before connecting."
}

private fun routeOutcomeReasonLine(state: MainUiState): String =
    "${routeRuleLabel(state.previewOutcome.matchedRuleId)} · ${state.previewOutcome.reason}"

private fun routeRuleLabel(ruleId: String?): String = when (ruleId) {
    null -> "Default VPN route"
    "default" -> "Default VPN route"
    "__loopback__" -> "Loopback safety rule"
    "__split_tunnel_allowlist__" -> "Split-tunnel allowlist"
    "__split_tunnel_denylist__" -> "Split-tunnel denylist"
    "__regional_bypass_russia__" -> "Russia direct preset"
    "__regional_bypass_custom_direct__" -> "Custom direct domain"
    else -> "Rule $ruleId"
}

private fun phaseChip(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "Protected"
    VpnRuntimePhase.FAIL_CLOSED -> "Attention"
}

private fun phaseGlyph(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "R"
    VpnRuntimePhase.STAGED -> "S"
    VpnRuntimePhase.START_REQUESTED -> "C"
    VpnRuntimePhase.RUNNING -> "OK"
    VpnRuntimePhase.FAIL_CLOSED -> "!"
}

private fun phaseAccent(phase: VpnRuntimePhase): Color = when (phase) {
    VpnRuntimePhase.RUNNING -> TunguskaTheme.accent
    VpnRuntimePhase.START_REQUESTED -> TunguskaTheme.warning
    VpnRuntimePhase.FAIL_CLOSED -> TunguskaTheme.danger
    VpnRuntimePhase.STAGED -> TunguskaTheme.accentDim
    VpnRuntimePhase.IDLE -> TunguskaTheme.accentDim
}

private fun runtimeHeadline(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready to connect"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "Protected"
    VpnRuntimePhase.FAIL_CLOSED -> "Attention required"
}

private fun runtimeSummary(state: MainUiState): String = when (state.runtimeSnapshot.phase) {
    VpnRuntimePhase.RUNNING ->
        "Traffic is flowing through ${state.profile.outboundSummary.let { "${it.endpoint.address}:${it.endpoint.port}" }} with ${splitTunnelLabel(state.tunnelPlan.splitTunnelMode).lowercase()} applied."

    VpnRuntimePhase.START_REQUESTED ->
        "Starting the VPN runtime. The session will only stay up if bootstrap, local exposure, and health checks pass."

    VpnRuntimePhase.FAIL_CLOSED ->
        "The runtime stopped to protect traffic. Review Security before reconnecting."

    VpnRuntimePhase.STAGED ->
        "A validated profile is staged and ready for the next connection."

    VpnRuntimePhase.IDLE ->
        "Review the active profile and traffic policy, then connect when ready."
}

private fun homePrimaryStatusLine(state: MainUiState): String = when {
    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.OBSERVED &&
        state.egressObservation.publicIp != null ->
        "Protected. Exit IP ${state.egressObservation.publicIp}"

    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.PROBING ->
        "Protected. Detecting exit IP…"

    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.ERROR ->
        "Protected. Exit IP unavailable right now."

    state.runtimeSnapshot.phase == VpnRuntimePhase.START_REQUESTED ->
        "Connecting…"

    state.runtimeSnapshot.phase == VpnRuntimePhase.STAGED ->
        "Profile staged and ready."

    state.runtimeSnapshot.phase == VpnRuntimePhase.FAIL_CLOSED ->
        runtimeFailureDisplayText(
            rawError = state.controlError ?: state.runtimeSnapshot.lastError,
            section = state.runtimeSnapshot.lastErrorSection,
            fieldPath = state.runtimeSnapshot.lastErrorFieldPath,
        ) ?: "Connection attention required."

    else -> "Ready when you are."
}

private fun homeEgressStatusLine(state: MainUiState): String = when {
    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.OBSERVED &&
        state.egressObservation.publicIp != null ->
        "Exit IP ${state.egressObservation.publicIp}"

    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.PROBING ->
        "Detecting exit IP..."

    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING &&
        state.egressObservation.phase == EgressObservationPhase.ERROR ->
        "Can't detect exit IP."

    state.runtimeSnapshot.phase == VpnRuntimePhase.START_REQUESTED ->
        "Connecting..."

    state.runtimeSnapshot.phase == VpnRuntimePhase.FAIL_CLOSED ->
        runtimeFailureDisplayText(
            rawError = state.controlError ?: state.runtimeSnapshot.lastError,
            section = state.runtimeSnapshot.lastErrorSection,
            fieldPath = state.runtimeSnapshot.lastErrorFieldPath,
        ) ?: "Connection attention required."

    state.egressObservation.phase == EgressObservationPhase.OBSERVED &&
        state.egressObservation.publicIp != null ->
        "Current IP ${state.egressObservation.publicIp}"

    state.egressObservation.phase == EgressObservationPhase.PROBING ->
        "Detecting current IP..."

    state.egressObservation.phase == EgressObservationPhase.ERROR ->
        "Can't detect current IP."

    else -> "Current IP pending."
}

private fun prettyName(raw: String): String =
    raw.lowercase().split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun prettyEnum(value: Enum<*>?): String = value?.name?.let(::prettyName) ?: "n/a"

private fun trustHeadline(state: MainUiState): String = when {
    state.controlError != null || state.runtimeSnapshot.phase == VpnRuntimePhase.FAIL_CLOSED -> "Needs attention"
    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING -> "Healthy runtime posture"
    state.runtimeSnapshot.phase == VpnRuntimePhase.START_REQUESTED -> "Checks in progress"
    else -> "Ready for the next session"
}

private fun trustSummary(state: MainUiState): String = when {
    state.controlError != null -> "The control plane reported an issue. Review diagnostics before reconnecting."
    state.runtimeSnapshot.auditStatus == io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.LIMITED ->
        state.runtimeSnapshot.lastAuditSummary
            ?: "Android restricts OS socket inventory; Tunguska is using its declared runtime topology for this local exposure check."
    state.runtimeSnapshot.auditStatus == io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.UNAVAILABLE &&
        state.runtimeSnapshot.lastAuditAtEpochMs != null ->
        state.runtimeSnapshot.lastAuditSummary ?: "Listener audit could not read socket inventory on this device."
    state.runtimeSnapshot.phase == VpnRuntimePhase.RUNNING ->
        "Fail-closed posture is active while the current engine session is running."
    state.runtimeSnapshot.phase == VpnRuntimePhase.START_REQUESTED ->
        "Bootstrap, local exposure, and engine health checks are being established."
    else ->
        "Security exports and automation are available even while the tunnel is idle."
}

private fun trustAuditChipText(state: MainUiState): String = when (state.runtimeSnapshot.auditStatus) {
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.PASS -> "Exposure check passed"
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.FAIL -> "Exposure check failed"
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.LIMITED -> "Exposure check limited"
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.UNAVAILABLE ->
        if (state.runtimeSnapshot.lastAuditAtEpochMs != null) "Exposure check unavailable" else "Exposure check pending"
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.NOT_RUN ->
        if (state.runtimeSnapshot.phase == VpnRuntimePhase.START_REQUESTED) "Exposure check starting" else "Exposure check pending"
}

private fun trustAuditChipAccent(state: MainUiState): Color = when (state.runtimeSnapshot.auditStatus) {
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.PASS -> TunguskaTheme.accent
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.FAIL -> TunguskaTheme.danger
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.LIMITED -> TunguskaTheme.accentDim
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.UNAVAILABLE -> TunguskaTheme.warning
    io.acionyx.tunguska.vpnservice.RuntimeAuditStatus.NOT_RUN -> TunguskaTheme.accentDim
}

private fun trustEngineChipText(state: MainUiState): String = when (state.runtimeSnapshot.engineSessionHealthStatus) {
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.HEALTHY -> "Engine healthy"
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.DEGRADED -> "Engine degraded"
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.FAILED -> "Engine failed"
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.UNKNOWN -> when (state.runtimeSnapshot.phase) {
        VpnRuntimePhase.IDLE -> "Engine idle"
        VpnRuntimePhase.START_REQUESTED -> "Engine starting"
        else -> "Engine pending"
    }
}

private fun trustEngineChipAccent(state: MainUiState): Color = when (state.runtimeSnapshot.engineSessionHealthStatus) {
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.HEALTHY -> TunguskaTheme.accent
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.DEGRADED -> TunguskaTheme.warning
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.FAILED -> TunguskaTheme.danger
    io.acionyx.tunguska.vpnservice.EmbeddedEngineSessionHealthStatus.UNKNOWN -> TunguskaTheme.accentDim
}

private fun splitTunnelLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "Full tunnel"
    is SplitTunnelMode.Allowlist -> "Allowlist"
    is SplitTunnelMode.Denylist -> "Denylist"
}

private fun splitTunnelDetail(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "All applications use the tunnel by default."
    is SplitTunnelMode.Allowlist -> "${mode.packageNames.size} allowed app(s) use the tunnel."
    is SplitTunnelMode.Denylist -> "${mode.packageNames.size} excluded app(s) bypass the tunnel."
}

private fun splitTunnelInventory(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "App rules: all applications"
    is SplitTunnelMode.Allowlist -> "App rules: ${mode.packageNames.size} included package(s)"
    is SplitTunnelMode.Denylist -> "App rules: ${mode.packageNames.size} excluded package(s)"
}

private fun routeOutcomeAccent(action: RouteAction): Color = when (action) {
    RouteAction.DIRECT -> TunguskaTheme.accent
    RouteAction.BLOCK -> TunguskaTheme.danger
    RouteAction.PROXY -> TunguskaTheme.warning
}

private fun abbreviateHash(value: String): String =
    if (value.length <= 20) value else "${value.take(10)}...${value.takeLast(8)}"
