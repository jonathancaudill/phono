package com.lightphone.spotify.ui

import android.util.Size
import android.view.SurfaceView
import android.view.ViewTreeObserver
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device QR scanner using CameraX + bundled ML Kit models (Passes pattern).
 *
 * Does not use [androidx.camera.mlkit.vision.MlKitAnalyzer] or Play Services —
 * frames are decoded locally via [BarcodeScanning.getClient] on each [ImageProxy].
 */
@Composable
fun PhonoQrScanner(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Scan QR Code",
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LightThemeTokens.colors
    val (checkCameraPermission, launchCameraPermissionRequest) = rememberCameraPermissionHandlers()
    var launchedPermissionRequest by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf(QrScannerUiState.Loading) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val permissionCheck = checkCameraPermission()
            when {
                permissionCheck.isFailure -> uiState = QrScannerUiState.PermissionError
                permissionCheck.getOrNull() == true -> uiState = QrScannerUiState.Active
                !launchedPermissionRequest -> {
                    launchCameraPermissionRequest()
                    launchedPermissionRequest = true
                    uiState = if (checkCameraPermission().getOrNull() == true) {
                        QrScannerUiState.Active
                    } else {
                        QrScannerUiState.PermissionDenied
                    }
                }
                else -> uiState = QrScannerUiState.PermissionDenied
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (uiState == QrScannerUiState.Active) {
            PassesStyleCameraPreview(
                lifecycleOwner = lifecycleOwner,
                onQrCode = onScanned,
                modifier = Modifier.fillMaxSize(),
            )
            QrViewfinderOverlay(
                frameColor = colors.content,
                scrimColor = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = onBack,
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
            }

            if (uiState != QrScannerUiState.Active) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (uiState) {
                        QrScannerUiState.Loading -> CircularProgressIndicator()
                        QrScannerUiState.PermissionDenied -> LightText(
                            text = "Camera permission is required to scan QR codes.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = legacyNToGridDp(22)),
                        )
                        QrScannerUiState.PermissionError -> LightText(
                            text = "Error: unable to request camera permission!",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = legacyNToGridDp(22)),
                        )
                        QrScannerUiState.Active -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PassesStyleCameraPreview(
    lifecycleOwner: LifecycleOwner,
    onQrCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scannedOnce = remember { AtomicBoolean(false) }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        if (view == null) {
            return@DisposableEffect onDispose {}
        }

        var cameraProvider: ProcessCameraProvider? = null

        fun bindWhenReady() {
            if (view.width <= 0 || view.height <= 0) return

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                runCatching {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                processQrFrame(
                                    imageProxy = imageProxy,
                                    barcodeScanner = barcodeScanner,
                                    scannedOnce = scannedOnce,
                                    onQrCode = onQrCode,
                                )
                            }
                        }

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )

                    view.post {
                        for (i in 0 until view.childCount) {
                            val child = view.getChildAt(i)
                            if (child is SurfaceView) {
                                child.setZOrderMediaOverlay(true)
                            }
                        }
                    }
                }
            }, mainExecutor)
        }

        var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

        if (view.width > 0 && view.height > 0) {
            bindWhenReady()
        } else {
            layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (view.width > 0 && view.height > 0) {
                        view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        layoutListener = null
                        bindWhenReady()
                    }
                }
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        onDispose {
            layoutListener?.let { view.viewTreeObserver.removeOnGlobalLayoutListener(it) }
            cameraProvider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { view ->
            if (previewView !== view) {
                previewView = view
            }
        },
    )
}

private fun processQrFrame(
    imageProxy: ImageProxy,
    barcodeScanner: BarcodeScanner,
    scannedOnce: AtomicBoolean,
    onQrCode: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(
        mediaImage,
        imageProxy.imageInfo.rotationDegrees,
    )

    barcodeScanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (barcodes.isEmpty() || scannedOnce.get()) return@addOnSuccessListener
            val value = barcodes
                .asSequence()
                .mapNotNull { it.rawValue ?: it.displayValue }
                .firstOrNull { it.isNotBlank() }
                ?: return@addOnSuccessListener
            if (scannedOnce.compareAndSet(false, true)) {
                onQrCode(value)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun QrViewfinderOverlay(
    frameColor: Color,
    scrimColor: Color,
    modifier: Modifier = Modifier,
    frameSizeFraction: Float = 0.62f,
) {
    Canvas(modifier = modifier) {
        val frameSize = size.minDimension * frameSizeFraction
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2f
        val right = left + frameSize
        val bottom = top + frameSize

        drawRect(scrimColor, topLeft = Offset.Zero, size = ComposeSize(size.width, top))
        drawRect(scrimColor, topLeft = Offset(0f, bottom), size = ComposeSize(size.width, size.height - bottom))
        drawRect(scrimColor, topLeft = Offset(0f, top), size = ComposeSize(left, frameSize))
        drawRect(scrimColor, topLeft = Offset(right, top), size = ComposeSize(size.width - right, frameSize))

        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = ComposeSize(frameSize, frameSize),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 3f),
        )
    }
}

private enum class QrScannerUiState {
    Loading, PermissionError, PermissionDenied, Active
}
