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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF9F5)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5DED1)),
    ) {
        Column(
            modifier = Modifier.padding(if (compactLayout) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Secure Import", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Paste a share link or scan a QR code. Accepted now: strict vless:// or ess:// REALITY links and canonical JSON.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            Text(
                text = "Import status: ${state.importStatus ?: "idle"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
            Text(
                text = "Import error: ${state.importError ?: "none"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF22332C),
            )
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
                ) {
                    Text("Validate Import")
                }
                Button(
                    onClick = {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    modifier = importActionModifier(compactLayout).testTag(UiTags.OPEN_CAMERA_BUTTON),
                ) {
                    Text("Open Camera")
                }
                Button(
                    onClick = {
                        pickQrImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = importActionModifier(compactLayout).testTag(UiTags.SCAN_IMAGE_BUTTON),
                ) {
                    Text("Scan Image")
                }
            }

            state.importPreview?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF2EEE4)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Staged Import", style = MaterialTheme.typography.titleMedium)
                        Text("Source: ${preview.source.summary()}", style = MaterialTheme.typography.bodyMedium)
                        Text("Normalized: ${preview.normalizedSourceSummary}", style = MaterialTheme.typography.bodyMedium)
                        Text("Scheme: ${preview.sourceScheme}", style = MaterialTheme.typography.bodyMedium)
                        Text("Profile: ${preview.profileName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Endpoint: ${preview.endpointSummary}", style = MaterialTheme.typography.bodyMedium)
                        Text("Canonical hash: ${preview.profileHash}", style = MaterialTheme.typography.bodyMedium)
                        if (preview.warnings.isEmpty()) {
                            Text("Warnings: none", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            preview.warnings.forEach { warning ->
                                Text("Warning: $warning", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        ImportActionGroup(compactLayout = compactLayout) {
                            Button(
                                onClick = onConfirmImport,
                                modifier = importActionModifier(compactLayout).testTag(UiTags.CONFIRM_IMPORT_BUTTON),
                            ) {
                                Text("Confirm Import")
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF2EEE4)),
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
