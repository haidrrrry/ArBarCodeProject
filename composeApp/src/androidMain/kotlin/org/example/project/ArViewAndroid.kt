package org.example.project

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
actual fun ARCameraView(
    isScanning: Boolean,
    onBarcodeDetected: (BarcodeData) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Create PreviewView once and reuse it
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            setupCamera(context, lifecycleOwner, previewView, onBarcodeDetected, cameraExecutor) { provider, cam ->
                cameraProvider = provider
                camera = cam
            }
        } else {
            // Properly cleanup when not scanning
            try {
                cameraProvider?.unbindAll()
                delay(100) // Give time for cleanup
            } catch (e: Exception) {
                Log.w("ARCamera", "Error during camera cleanup", e)
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProvider?.unbindAll()
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                Log.w("ARCamera", "Error during dispose", e)
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun setupCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onBarcodeDetected: (BarcodeData) -> Unit,
    cameraExecutor: ExecutorService,
    onCameraReady: (ProcessCameraProvider, Camera) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Build preview use case
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Build image analysis use case
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy, onBarcodeDetected)
                    }
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind use cases to lifecycle
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            onCameraReady(cameraProvider, camera)
            Log.d("ARCamera", "Camera setup successful")

        } catch (exc: Exception) {
            Log.e("ARCamera", "Use case binding failed", exc)
        }
    }, ContextCompat.getMainExecutor(context))
}

@androidx.camera.core.ExperimentalGetImage
private fun processImageProxy(
    imageProxy: ImageProxy,
    onBarcodeDetected: (BarcodeData) -> Unit
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    try {
                        barcodes.forEach { barcode ->
                            barcode.rawValue?.let { value ->
                                val boundingBox = barcode.boundingBox
                                onBarcodeDetected(
                                    BarcodeData(
                                        value = value,
                                        format = getBarcodeFormat(barcode.format),
                                        x = boundingBox?.centerX()?.toFloat() ?: 0f,
                                        y = boundingBox?.centerY()?.toFloat() ?: 0f,
                                        z = 0f
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BarcodeScanning", "Error processing barcodes", e)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e("BarcodeScanning", "Barcode scanning failed", exception)
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (e: Exception) {
                        Log.w("BarcodeScanning", "Error closing image proxy", e)
                    }
                }
        } else {
            imageProxy.close()
        }
    } catch (e: Exception) {
        Log.e("BarcodeScanning", "Error in processImageProxy", e)
        try {
            imageProxy.close()
        } catch (closeException: Exception) {
            Log.w("BarcodeScanning", "Error closing image proxy in catch block", closeException)
        }
    }
}

private fun getBarcodeFormat(format: Int): String {
    return when (format) {
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "Unknown"
    }
}

@Composable
fun AndroidARBarcodeScreen(
    viewModel: ARBarcodeViewModel = remember { ARBarcodeViewModel() }
) {
    var showingSettings by remember { mutableStateOf(false) }
    var showSuccessAnimation by remember { mutableStateOf(false) }
    var lastDetectedBarcode by remember { mutableStateOf<BarcodeData?>(null) }

    // Collect the state from the ViewModel
    val scannedBarcodes by viewModel.scannedBarcodes.collectAsState()

    // Debug state changes
    LaunchedEffect(viewModel.isScanning) {
        println("AndroidARBarcodeScreen: ViewModel isScanning changed to: ${viewModel.isScanning}")
    }

    LaunchedEffect(scannedBarcodes.size) {
        println("AndroidARBarcodeScreen: Scanned barcodes count: ${scannedBarcodes.size}")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Camera View
        ARCameraView(
            isScanning = viewModel.isScanning,
            onBarcodeDetected = { barcode ->
                println("AndroidARBarcodeScreen: Barcode detected in UI: ${barcode.value}")
                lastDetectedBarcode = barcode
                showSuccessAnimation = true
                viewModel.onBarcodeDetected(barcode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // AR Scanner Overlay - The main attraction!
        AndroidARScannerOverlay(
            isScanning = viewModel.isScanning,
            showSuccess = showSuccessAnimation,
            onSuccessAnimationComplete = { showSuccessAnimation = false },
            modifier = Modifier.fillMaxSize()
        )

        // Android-style UI overlay
        AndroidAROverlayUI(
            isScanning = viewModel.isScanning,
            barcodeCount = scannedBarcodes.size,
            lastDetectedBarcode = lastDetectedBarcode,
            onToggleScanning = {
                if (viewModel.isScanning) {
                    println("AndroidARBarcodeScreen: Stop scanning button pressed")
                    viewModel.stopScanning()
                } else {
                    println("AndroidARBarcodeScreen: Start scanning button pressed")
                    viewModel.startScanning()
                }
                println("AndroidARBarcodeScreen: Scanning state after toggle: ${viewModel.isScanning}")
            },
            onShowSettings = {
                showingSettings = true
                println("AndroidARBarcodeScreen: Show settings pressed")
            },
            modifier = Modifier.fillMaxSize()
        )

        // Settings sheet
        if (showingSettings) {
            AndroidSettingsSheet(
                onDismiss = {
                    showingSettings = false
                    println("AndroidARBarcodeScreen: Settings dismissed")
                },
                onClearHistory = {
                    println("AndroidARBarcodeScreen: Clear history pressed")
                    viewModel.clearHistory()
                },
                barcodeCount = scannedBarcodes.size
            )
        }
    }
}

@Composable
fun AndroidARScannerOverlay(
    isScanning: Boolean,
    showSuccess: Boolean,
    onSuccessAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation states
    val scanLineAnimation = rememberInfiniteTransition(label = "scanLine")
    val radarAnimation = rememberInfiniteTransition(label = "radar")
    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val cornerAnimation = rememberInfiniteTransition(label = "corners")

    // Scan line moving up and down
    val scanLineY by scanLineAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLineY"
    )

    // Rotating radar sweep
    val radarRotation by radarAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing)
        ),
        label = "radarRotation"
    )

    // Pulsing circles
    val pulseScale by pulseAnimation.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Corner brackets animation
    val cornerGlow by cornerAnimation.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cornerGlow"
    )

    // Success animation
    val successScale by animateFloatAsState(
        targetValue = if (showSuccess) 1.2f else 1f,
        animationSpec = tween(300),
        finishedListener = { if (showSuccess) onSuccessAnimationComplete() },
        label = "successScale"
    )

    val successAlpha by animateFloatAsState(
        targetValue = if (showSuccess) 1f else 0f,
        animationSpec = tween(500),
        label = "successAlpha"
    )

    Box(modifier = modifier) {
        if (isScanning) {
            // Main scanning frame with AR effects
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(60.dp)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val frameSize = minOf(size.width, size.height) * 0.7f
                val frameRect = Size(frameSize, frameSize * 0.8f)
                val topLeft = Offset(
                    x = center.x - frameRect.width / 2,
                    y = center.y - frameRect.height / 2
                )
                val scanRect = Rect(topLeft, frameRect)

                // AR Grid background
                drawAndroidARGrid(scanRect, Color.Cyan.copy(alpha = 0.2f))

                // Radar sweep
                drawAndroidRadarSweep(center, frameSize * 0.4f, radarRotation, Color.Cyan.copy(alpha = 0.3f))

                // Pulsing circles
                drawAndroidPulsingCircles(center, frameSize * 0.3f, pulseScale, Color.Cyan.copy(alpha = 0.4f))

                // Main scanning frame with glowing corners
                drawAndroidScanningFrame(scanRect, cornerGlow)

                // Moving scan line
                drawAndroidScanLine(scanRect, scanLineY)

                // Corner brackets
                drawAndroidCornerBrackets(scanRect, cornerGlow)

                // Crosshair in center
                drawAndroidCrosshair(center, Color.Cyan.copy(alpha = 0.8f))
            }

            // AR HUD Elements
            AndroidARHUDElements(
                modifier = Modifier.fillMaxSize(),
                scanProgress = scanLineY
            )
        }

        // Success animation overlay
        if (showSuccess) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(successAlpha),
                contentAlignment = Alignment.Center
            ) {
                // Success pulse effect
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(successScale)
                        .background(
                            Color.Green.copy(alpha = 0.3f),
                            CircleShape
                        )
                        .blur(20.dp)
                )

                // Success icon
                Card(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(successScale),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Green
                    ),
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
        }
    }
}

// Custom drawing functions for Android AR effects
fun DrawScope.drawAndroidARGrid(rect: androidx.compose.ui.geometry.Rect, color: Color) {
    val gridSpacing = 30f
    val strokeWidth = 1f

    // Vertical lines
    var x = rect.left
    while (x <= rect.right) {
        drawLine(
            color = color,
            start = Offset(x, rect.top),
            end = Offset(x, rect.bottom),
            strokeWidth = strokeWidth
        )
        x += gridSpacing
    }

    // Horizontal lines
    var y = rect.top
    while (y <= rect.bottom) {
        drawLine(
            color = color,
            start = Offset(rect.left, y),
            end = Offset(rect.right, y),
            strokeWidth = strokeWidth
        )
        y += gridSpacing
    }
}

fun DrawScope.drawAndroidRadarSweep(center: Offset, radius: Float, rotation: Float, color: Color) {
    val sweepGradient = Brush.sweepGradient(
        colors = listOf(
            Color.Transparent,
            color.copy(alpha = 0.1f),
            color.copy(alpha = 0.3f),
            color.copy(alpha = 0.1f),
            Color.Transparent
        ),
        center = center
    )

    rotate(rotation, center) {
        drawCircle(
            brush = sweepGradient,
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

fun DrawScope.drawAndroidPulsingCircles(center: Offset, baseRadius: Float, scale: Float, color: Color) {
    for (i in 1..3) {
        val radius = baseRadius * i * 0.3f * scale
        val alpha = (1f - (i * 0.2f)) * 0.5f
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = (4f - i).dp.toPx())
        )
    }
}

fun DrawScope.drawAndroidScanningFrame(rect: androidx.compose.ui.geometry.Rect, glowIntensity: Float) {
    val glowColor = Color.Cyan.copy(alpha = 0.6f * glowIntensity)
    val strokeWidth = 3.dp.toPx()

    // Main frame
    drawRoundRect(
        color = glowColor,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
        style = Stroke(width = strokeWidth)
    )

    // Glow effect
    drawRoundRect(
        color = glowColor.copy(alpha = 0.3f),
        topLeft = Offset(rect.left - 4, rect.top - 4),
        size = Size(rect.width + 8, rect.height + 8),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
        style = Stroke(width = strokeWidth * 2)
    )
}

fun DrawScope.drawAndroidScanLine(rect: androidx.compose.ui.geometry.Rect, progress: Float) {
    val y = rect.top + (rect.height * progress)
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.Cyan.copy(alpha = 0.3f),
            Color.Cyan.copy(alpha = 0.8f),
            Color.Cyan.copy(alpha = 0.3f),
            Color.Transparent
        ),
        startX = rect.left,
        endX = rect.right
    )

    drawRect(
        brush = gradient,
        topLeft = Offset(rect.left, y - 1),
        size = Size(rect.width, 3f)
    )

    // Bright center line
    drawLine(
        color = Color.Cyan,
        start = Offset(rect.left + rect.width * 0.2f, y),
        end = Offset(rect.right - rect.width * 0.2f, y),
        strokeWidth = 1.5f
    )
}

fun DrawScope.drawAndroidCornerBrackets(rect: androidx.compose.ui.geometry.Rect, glowIntensity: Float) {
    val bracketLength = 30.dp.toPx()
    val strokeWidth = 4.dp.toPx()
    val color = Color.Cyan.copy(alpha = 0.8f * glowIntensity)
    val cornerRadius = 16.dp.toPx()

    // Top-left brackets
    drawLine(
        color = color,
        start = Offset(rect.left, rect.top + cornerRadius + bracketLength),
        end = Offset(rect.left, rect.top + cornerRadius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(rect.left + cornerRadius, rect.top),
        end = Offset(rect.left + cornerRadius + bracketLength, rect.top),
        strokeWidth = strokeWidth
    )

    // Top-right brackets
    drawLine(
        color = color,
        start = Offset(rect.right, rect.top + cornerRadius + bracketLength),
        end = Offset(rect.right, rect.top + cornerRadius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(rect.right - cornerRadius, rect.top),
        end = Offset(rect.right - cornerRadius - bracketLength, rect.top),
        strokeWidth = strokeWidth
    )

    // Bottom-left brackets
    drawLine(
        color = color,
        start = Offset(rect.left, rect.bottom - cornerRadius - bracketLength),
        end = Offset(rect.left, rect.bottom - cornerRadius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(rect.left + cornerRadius, rect.bottom),
        end = Offset(rect.left + cornerRadius + bracketLength, rect.bottom),
        strokeWidth = strokeWidth
    )

    // Bottom-right brackets
    drawLine(
        color = color,
        start = Offset(rect.right, rect.bottom - cornerRadius - bracketLength),
        end = Offset(rect.right, rect.bottom - cornerRadius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(rect.right - cornerRadius, rect.bottom),
        end = Offset(rect.right - cornerRadius - bracketLength, rect.bottom),
        strokeWidth = strokeWidth
    )
}

fun DrawScope.drawAndroidCrosshair(center: Offset, color: Color) {
    val size = 20.dp.toPx()
    val strokeWidth = 2.dp.toPx()

    // Horizontal line
    drawLine(
        color = color,
        start = Offset(center.x - size, center.y),
        end = Offset(center.x + size, center.y),
        strokeWidth = strokeWidth
    )

    // Vertical line
    drawLine(
        color = color,
        start = Offset(center.x, center.y - size),
        end = Offset(center.x, center.y + size),
        strokeWidth = strokeWidth
    )

    // Center dot
    drawCircle(
        color = color,
        radius = 3.dp.toPx(),
        center = center
    )
}

@Composable
fun AndroidARHUDElements(
    modifier: Modifier = Modifier,
    scanProgress: Float
) {
    Box(modifier = modifier) {
        // Scanning progress indicator
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.7f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.Cyan, CircleShape)
                        .alpha(scanProgress)
                )
                Text(
                    text = "SCANNING",
                    color = Color.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // AR coordinates display
        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.7f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "AR MODE",
                    color = Color.Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "READY",
                    color = Color.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom instruction
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .padding(horizontal = 32.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = "Align barcode within the scanning frame",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}


@Composable
fun AndroidAROverlayUI(
    isScanning: Boolean,
    barcodeCount: Int,
    lastDetectedBarcode: BarcodeData?,
    onToggleScanning: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Last detected barcode info
        lastDetectedBarcode?.let { barcode ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .animateContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Green.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Barcode Detected",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = barcode.value,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = barcode.format,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Bottom control panel with Material Design 3 styling
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
            ),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings button
                FilledIconButton(
                    onClick = onShowSettings,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Main scan button with AR styling
                FilledIconButton(
                    onClick = {
                        println("AndroidAROverlayUI: AR Scan button clicked, current state: $isScanning")
                        onToggleScanning()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .border(
                            3.dp,
                            if (isScanning) Color.Red.copy(alpha = 0.8f) else Color.Cyan.copy(alpha = 0.8f),
                            CircleShape
                        ),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isScanning)
                            Color.Red.copy(alpha = 0.2f) else
                            Color.Cyan.copy(alpha = 0.2f)
                    )
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isScanning) "Stop AR Scan" else "Start AR Scan",
                        tint = if (isScanning) Color.Red else Color.Cyan,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // History button with badge
                BadgedBox(
                    badge = {
                        if (barcodeCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(
                                    barcodeCount.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                ) {
                    FilledIconButton(
                        onClick = {
                            println("AndroidAROverlayUI: History button pressed")
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "History",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AndroidSettingsSheet(
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    barcodeCount: Int
) {
    // Background overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Handle bar
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp)
                        )
                        .align(Alignment.CenterHorizontally)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "AR Scanner Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Clear history option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Clear Scan History",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$barcodeCount barcodes detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            onClearHistory()
                            onDismiss()
                            println("AndroidSettingsSheet: Clear history executed")
                        },
                        enabled = barcodeCount > 0,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Clear All")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Done button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Done")
                    }

                }
            }
        }
    }
}

