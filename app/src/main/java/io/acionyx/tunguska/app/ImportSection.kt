package io.acionyx.tunguska.app

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ImportSection(
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
    val context = LocalContext.current
    val compactLayout = LocalConfiguration.current.screenWidthDp <= 320
    var cameraScannerVisible by remember { mutableStateOf(false) }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraScannerVisible = true
        } else {
            onImportError("QR import failed: camera permission is required for live scanning.")
        }
    }
    val pickQrImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodeQrImage(
            context = context,
            uri = uri,
            onPayloadDetected = { payload ->
                onQrPayloadDetected(payload, ImportCaptureSource.IMAGE_QR)
            },
            onFailure = onImportError,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, TunguskaTheme.stroke.copy(alpha = 0.82f)),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        TunguskaTheme.surfaceStrong.copy(alpha = 0.94f),
                        TunguskaTheme.surface.copy(alpha = 0.98f),
                    ),
                ),
            ),
        ) {
            Column(
                modifier = Modifier.padding(if (compactLayout) 16.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Profile intake", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Paste a share link or scan a QR code. Tunguska validates it before replacing the active sealed profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunguskaTheme.mutedText,
                )
                state.importStatus?.takeIf { it.isNotBlank() && it != "idle" }?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.accent,
                    )
                }
                state.importError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TunguskaTheme.danger,
                    )
                }
                OutlinedTextField(
                    value = state.importDraft,
                    onValueChange = onDraftChange,
                    label = { Text("Share link or JSON profile") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.IMPORT_DRAFT_FIELD),
                    minLines = 4,
                )
                ImportActionGroup(compactLayout = compactLayout) {
                    Button(
                        onClick = onValidateDraft,
                        modifier = importActionModifier(compactLayout).testTag(UiTags.VALIDATE_IMPORT_BUTTON),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TunguskaTheme.accent,
                            contentColor = Color(0xFF061214),
                        ),
                    ) {
                        Text("Validate")
                    }
                    OutlinedButton(
                        onClick = {
                            requestCameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        modifier = importActionModifier(compactLayout).testTag(UiTags.OPEN_CAMERA_BUTTON),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, TunguskaTheme.stroke),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.bodyText),
                    ) {
                        Text("Open camera")
                    }
                    OutlinedButton(
                        onClick = {
                            pickQrImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = importActionModifier(compactLayout).testTag(UiTags.SCAN_IMAGE_BUTTON),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, TunguskaTheme.stroke),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.bodyText),
                    ) {
                        Text("Scan image")
                    }
                }

                state.importPreview?.let { preview ->
                    val compatibilitySelection = importConfiguredSelectionState(
                        selectedStrategyId = state.automationState.runtimeStrategy,
                        severity = preview.compatibilitySeverity,
                        selectedSummaryTitle = preview.compatibilityTitle,
                        selectedSummaryDetails = preview.compatibilityDetails,
                        recommendedStrategyId = preview.recommendedStrategyId,
                        recommendation = preview.recommendation,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = TunguskaTheme.surface.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, TunguskaTheme.accent.copy(alpha = 0.24f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text("Review before saving", style = MaterialTheme.typography.titleLarge)
                            Text("Source: ${preview.source.summary()}", style = MaterialTheme.typography.bodyMedium, color = TunguskaTheme.mutedText)
                            Text("Profile: ${preview.profileName}", style = MaterialTheme.typography.bodyMedium)
                            ProtocolSpecificImportReviewSummary(preview = preview)
                            Text("Canonical hash: ${abbreviateImportHash(preview.profileHash)}", style = MaterialTheme.typography.bodyMedium)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(UiTags.IMPORT_PREVIEW_COMPATIBILITY_CARD),
                                color = TunguskaTheme.surfaceStrong.copy(alpha = 0.72f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (preview.compatibilitySeverity == StrategyCompatibilitySeverity.READY) {
                                        TunguskaTheme.accent.copy(alpha = 0.24f)
                                    } else {
                                        TunguskaTheme.warning.copy(alpha = 0.32f)
                                    },
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Runtime compatibility", style = MaterialTheme.typography.titleMedium)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ImportStatusChip(
                                            text = compatibilitySelection.selectedLaneLabel,
                                            accent = if (state.automationState.runtimeStrategy == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                                                TunguskaTheme.accentDim
                                            } else {
                                                TunguskaTheme.warning
                                            },
                                        )
                                        ImportStatusChip(
                                            text = compatibilitySelection.statusLabel,
                                            accent = if (compatibilitySelection.selectedSeverity == StrategyCompatibilitySeverity.READY) {
                                                TunguskaTheme.accent
                                            } else {
                                                TunguskaTheme.warning
                                            },
                                        )
                                        compatibilitySelection.recommendedLaneLabel?.let { label ->
                                            ImportStatusChip(
                                                text = label,
                                                accent = TunguskaTheme.warning,
                                            )
                                        }
                                    }
                                    CompatibilityLimitSummary(
                                        title = compatibilitySelection.selectedSummaryTitle,
                                        severity = compatibilitySelection.selectedSeverity,
                                        details = compatibilitySelection.selectedSummaryDetails,
                                        onOpenAdvancedDiagnostics = onOpenAdvancedDiagnostics,
                                        recommendation = compatibilitySelection.recommendation,
                                        color = TunguskaTheme.bodyText,
                                    )
                                    compatibilitySelection.recommendation?.let { recommendation ->
                                        Text(recommendation, style = MaterialTheme.typography.bodyMedium, color = TunguskaTheme.warning)
                                    }
                                    compatibilitySelection.recommendedStrategyId?.let { strategyId ->
                                        OutlinedButton(
                                            onClick = { onUseRecommendedStrategy(strategyId) },
                                            modifier = Modifier.testTag(UiTags.IMPORT_USE_RECOMMENDED_STRATEGY_BUTTON),
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, TunguskaTheme.stroke),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TunguskaTheme.accent),
                                        ) {
                                            Text(
                                                if (strategyId == EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED) {
                                                    "Use sing-box embedded"
                                                } else {
                                                    "Use xray+tun2socks"
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            preview.warnings.forEach { warning ->
                                Text("Warning: $warning", style = MaterialTheme.typography.bodyMedium, color = TunguskaTheme.warning)
                            }
                            ImportActionGroup(compactLayout = compactLayout) {
                                Button(
                                    onClick = onConfirmImport,
                                    modifier = importActionModifier(compactLayout).testTag(UiTags.CONFIRM_IMPORT_BUTTON),
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TunguskaTheme.accent,
                                        contentColor = Color(0xFF061214),
                                    ),
                                ) {
                                    Text("Save profile")
                                }
                                TextButton(
                                    onClick = onDiscardImport,
                                    modifier = importActionModifier(compactLayout).testTag(UiTags.DISCARD_IMPORT_BUTTON),
                                ) {
                                    Text("Discard")
                                }
                            }
                        }
                    }
                }

                if (cameraScannerVisible) {
                    CameraQrScannerCard(
                        onPayloadDetected = { payload ->
                            cameraScannerVisible = false
                            onQrPayloadDetected(payload, ImportCaptureSource.CAMERA_QR)
                        },
                        onFailure = { message ->
                            cameraScannerVisible = false
                            onImportError(message)
                        },
                        onClose = { cameraScannerVisible = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportStatusChip(
    text: String,
    accent: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
private fun CameraQrScannerCard(
    onPayloadDetected: (String) -> Unit,
    onFailure: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = rememberQrScanner()
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(lifecycleOwner, previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || delivered.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            when (val selection = QrImportPayloadSelector.select(barcodes.map(Barcode::getRawValue))) {
                                is QrImportPayloadSelection.Single -> {
                                    if (delivered.compareAndSet(false, true)) {
                                        onPayloadDetected(selection.payload)
                                    }
                                }

                                is QrImportPayloadSelection.Failure -> {
                                    if (barcodes.isNotEmpty() && delivered.compareAndSet(false, true)) {
                                        onFailure(selection.message)
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            if (delivered.compareAndSet(false, true)) {
                                onFailure("QR import failed: ${error.message ?: error.javaClass.simpleName}")
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Live QR Scanner", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
            Text(
                text = "Point the camera at a single QR code. The scan uses the same validation pipeline as manual imports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
        }
    }
}

private fun decodeQrImage(
    context: Context,
    uri: Uri,
    onPayloadDetected: (String) -> Unit,
    onFailure: (String) -> Unit,
) {
    val scanner = newQrScannerClient()
    val image = runCatching { InputImage.fromFilePath(context, uri) }
        .getOrElse { error ->
            onFailure("QR import failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            when (val selection = QrImportPayloadSelector.select(barcodes.map(Barcode::getRawValue))) {
                is QrImportPayloadSelection.Single -> onPayloadDetected(selection.payload)
                is QrImportPayloadSelection.Failure -> onFailure(selection.message)
            }
        }
        .addOnFailureListener { error ->
            onFailure("QR import failed: ${error.message ?: error.javaClass.simpleName}")
        }
        .addOnCompleteListener {
            scanner.close()
        }
}

@Composable
private fun rememberQrScanner() = remember {
    newQrScannerClient()
}

private fun newQrScannerClient() = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build(),
)

@Composable
private fun ProtocolSpecificImportReviewSummary(preview: ImportPreviewState) {
    when (preview.protocolId) {
        OutboundProtocolId.VLESS_REALITY -> VlessRealityImportReviewSummary(preview)
    }
}

@Composable
private fun VlessRealityImportReviewSummary(preview: ImportPreviewState) {
    Text("Shape: ${preview.shapeLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Protocol: ${preview.protocolLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Transport: ${preview.transportLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Security: ${preview.securityLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Endpoint: ${preview.endpointSummary}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ImportActionGroup(
    compactLayout: Boolean,
    content: @Composable () -> Unit,
) {
    if (compactLayout) {
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

private fun importActionModifier(compactLayout: Boolean): Modifier =
    if (compactLayout) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
    }

private fun abbreviateImportHash(value: String): String =
    if (value.length <= 20) value else "${value.take(10)}...${value.takeLast(8)}"
