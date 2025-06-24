package org.example.project

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun IOSARBarcodeScreen(
    viewModel: ARBarcodeViewModel = remember { ARBarcodeViewModel() }
) {
    var showingSettings by remember { mutableStateOf(false) }
    var showSuccessAnimation by remember { mutableStateOf(false) }
    var lastDetectedBarcode by remember { mutableStateOf<BarcodeData?>(null) }

    // Collect the state from the ViewModel
    val scannedBarcodes by viewModel.scannedBarcodes.collectAsState()

    // Debug state changes
    LaunchedEffect(viewModel.isScanning) {
        println("IOSARBarcodeScreen: ViewModel isScanning changed to: ${viewModel.isScanning}")
    }

    LaunchedEffect(scannedBarcodes.size) {
        println("IOSARBarcodeScreen: Scanned barcodes count: ${scannedBarcodes.size}")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Camera View
        ARCameraView(
            isScanning = viewModel.isScanning,
            onBarcodeDetected = { barcode ->
                println("IOSARBarcodeScreen: Barcode detected in UI: ${barcode.value}")
                lastDetectedBarcode = barcode
                showSuccessAnimation = true
                viewModel.onBarcodeDetected(barcode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // AR Scanner Overlay - The main attraction!
        ARScannerOverlay(
            isScanning = viewModel.isScanning,
            showSuccess = showSuccessAnimation,
            onSuccessAnimationComplete = { showSuccessAnimation = false },
            modifier = Modifier.fillMaxSize()
        )

        // iOS-style UI overlay
        IOSAROverlayUI(
            isScanning = viewModel.isScanning,
            barcodeCount = scannedBarcodes.size,
            lastDetectedBarcode = lastDetectedBarcode,
            onToggleScanning = {
                if (viewModel.isScanning) {
                    println("IOSARBarcodeScreen: Stop scanning button pressed")
                    viewModel.stopScanning()
                } else {
                    println("IOSARBarcodeScreen: Start scanning button pressed")
                    viewModel.startScanning()
                }
                println("IOSARBarcodeScreen: Scanning state after toggle: ${viewModel.isScanning}")
            },
            onShowSettings = {
                showingSettings = true
                println("IOSARBarcodeScreen: Show settings pressed")
            },
            modifier = Modifier.fillMaxSize()
        )

        // Settings sheet
        if (showingSettings) {
            IOSSettingsSheet(
                onDismiss = {
                    showingSettings = false
                    println("IOSARBarcodeScreen: Settings dismissed")
                },
                onClearHistory = {
                    println("IOSARBarcodeScreen: Clear history pressed")
                    viewModel.clearHistory()
                },
                barcodeCount = scannedBarcodes.size
            )
        }
    }
}

@Composable
fun ARScannerOverlay(
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
                val size = Size(frameSize, frameSize * 0.8f)
                val topLeft = Offset(
                    x = center.x - size.width / 2,
                    y = center.y - size.height / 2
                )
                val frameRect = Rect(topLeft, size)

               

                // AR Grid background
                drawARGrid(frameRect, Color.Cyan.copy(alpha = 0.2f))

                // Radar sweep
                drawRadarSweep(center, frameSize * 0.4f, radarRotation, Color.Cyan.copy(alpha = 0.3f))

                // Pulsing circles
                drawPulsingCircles(center, frameSize * 0.3f, pulseScale, Color.Cyan.copy(alpha = 0.4f))

                // Main scanning frame with glowing corners
                drawScanningFrame(frameRect, cornerGlow)

                // Moving scan line
                drawScanLine(frameRect, scanLineY)

                // Corner brackets
                drawCornerBrackets(frameRect, cornerGlow)

                // Crosshair in center
                drawCrosshair(center, Color.Cyan.copy(alpha = 0.8f))
            }

            // AR HUD Elements
            ARHUDElements(
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

// Custom drawing functions for AR effects
fun DrawScope.drawARGrid(rect: androidx.compose.ui.geometry.Rect, color: Color) {
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

fun DrawScope.drawRadarSweep(center: Offset, radius: Float, rotation: Float, color: Color) {
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

fun DrawScope.drawPulsingCircles(center: Offset, baseRadius: Float, scale: Float, color: Color) {
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

fun DrawScope.drawScanningFrame(rect: androidx.compose.ui.geometry.Rect, glowIntensity: Float) {
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

fun DrawScope.drawScanLine(rect: androidx.compose.ui.geometry.Rect, progress: Float) {
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

fun DrawScope.drawCornerBrackets(rect: androidx.compose.ui.geometry.Rect, glowIntensity: Float) {
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

fun DrawScope.drawCrosshair(center: Offset, color: Color) {
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
fun ARHUDElements(
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
fun IOSAROverlayUI(
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

        // Bottom control panel with futuristic design
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings button
                Card(
                    onClick = onShowSettings,
                    modifier = Modifier.size(52.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Main scan button with AR styling
                Card(
                    onClick = {
                        println("IOSAROverlayUI: AR Scan button clicked, current state: $isScanning")
                        onToggleScanning()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .border(
                            2.dp,
                            if (isScanning) Color.Red.copy(alpha = 0.8f) else Color.Cyan.copy(alpha = 0.8f),
                            CircleShape
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isScanning)
                            Color.Red.copy(alpha = 0.2f) else
                            Color.Cyan.copy(alpha = 0.2f)
                    ),
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isScanning) "Stop AR Scan" else "Start AR Scan",
                            tint = if (isScanning) Color.Red else Color.Cyan,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // History button with badge
                Card(
                    onClick = {
                        println("IOSAROverlayUI: History button pressed")
                    },
                    modifier = Modifier.size(52.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        BadgedBox(
                            badge = {
                                if (barcodeCount > 0) {
                                    Badge(
                                        containerColor = Color.Cyan,
                                        contentColor = Color.Black
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
                            Icon(
                                Icons.Default.History,
                                contentDescription = "History",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IOSSettingsSheet(
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    barcodeCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
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
                    fontWeight = FontWeight.Bold
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
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "$barcodeCount barcodes detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        onClearHistory()
                        onDismiss()
                        println("IOSSettingsSheet: Clear history executed")
                    },
                    enabled = barcodeCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
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
                FilledTonalButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}