package org.example.project

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARBarcodeScreen(
    viewModel: ARBarcodeViewModel = remember { ARBarcodeViewModel() }
) {
    val scannedBarcodes by viewModel.scannedBarcodes.collectAsState()
    var showHistory by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Camera View
        ARCameraView(
            isScanning = viewModel.isScanning,
            onBarcodeDetected = viewModel::onBarcodeDetected,
            modifier = Modifier.fillMaxSize()
        )

        // AR Overlay UI
        AROverlayUI(
            isScanning = viewModel.isScanning,
            modifier = Modifier.fillMaxSize()
        )

        // Control Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Color.Black.copy(alpha = 0.8f),
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .padding(20.dp)
        ) {
            // Scan Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = {
                        if (viewModel.isScanning) {
                            viewModel.stopScanning()
                        } else {
                            viewModel.startScanning()
                        }
                    },
                    containerColor = if (viewModel.isScanning)
                        MaterialTheme.colorScheme.error else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = if (viewModel.isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.isScanning) "Stop Scanning" else "Start Scanning",
                        modifier = Modifier.size(28.dp)
                    )
                }

                OutlinedButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("History (${scannedBarcodes.size})")
                }

                OutlinedButton(
                    onClick = viewModel::clearHistory,
                    enabled = scannedBarcodes.isNotEmpty(),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear")
                }
            }

            // History Panel
            AnimatedVisibility(visible = showHistory) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    if (scannedBarcodes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No barcodes scanned yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(scannedBarcodes) { barcode ->
                                BarcodeHistoryItem(
                                    barcode = barcode,
                                    onClick = { viewModel.selectBarcode(barcode) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Barcode Detail Dialog
        viewModel.selectedBarcode?.let { barcode ->
            BarcodeDetailDialog(
                barcode = barcode,
                onDismiss = { viewModel.selectBarcode(null) }
            )
        }
    }
}

@Composable
fun MainAROverlayUI(
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Scanning crosshair
        if (isScanning) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(200.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val cornerLength = 40.dp.toPx()
                    val color = Color.Green

                    // Top-left corner
                    drawLine(
                        color = color,
                        start = Offset(0f, 0f),
                        end = Offset(cornerLength, 0f),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, 0f),
                        end = Offset(0f, cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // Top-right corner
                    drawLine(
                        color = color,
                        start = Offset(size.width - cornerLength, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // Bottom-left corner
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height - cornerLength),
                        end = Offset(0f, size.height),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height),
                        end = Offset(cornerLength, size.height),
                        strokeWidth = strokeWidth
                    )

                    // Bottom-right corner
                    drawLine(
                        color = color,
                        start = Offset(size.width, size.height - cornerLength),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width - cornerLength, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }

        // Status indicator
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.7f)
            )
        ) {
            Text(
                text = if (isScanning) "Scanning for barcodes..." else "AR Scanner Ready",
                color = if (isScanning) Color.Green else Color.White,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}







@Composable
fun AROverlayUI(
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Scanning crosshair
        if (isScanning) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(200.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val cornerLength = 40.dp.toPx()
                    val color = Color.Green

                    // Top-left corner
                    drawLine(
                        color = color,
                        start = Offset(0f, 0f),
                        end = Offset(cornerLength, 0f),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, 0f),
                        end = Offset(0f, cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // Top-right corner
                    drawLine(
                        color = color,
                        start = Offset(size.width - cornerLength, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // Bottom-left corner
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height - cornerLength),
                        end = Offset(0f, size.height),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height),
                        end = Offset(cornerLength, size.height),
                        strokeWidth = strokeWidth
                    )

                    // Bottom-right corner
                    drawLine(
                        color = color,
                        start = Offset(size.width, size.height - cornerLength),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width - cornerLength, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }

        // Status indicator
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.7f)
            )
        ) {
            Text(
                text = if (isScanning) "Scanning for barcodes..." else "AR Scanner Ready",
                color = if (isScanning) Color.Green else Color.White,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun BarcodeHistoryItem(
    barcode: BarcodeData,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = barcode.format,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )


            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = barcode.value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
        }
    }
}

@Composable
fun BarcodeDetailDialog(
    barcode: BarcodeData,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Barcode Details")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Format", barcode.format)
                DetailRow("Value", barcode.value)
             //   DetailRow("Position", "x: %.1f, y: %.1f, z: %.1f".format(barcode.x, barcode.y, barcode.z))
//                DetailRow("Scanned", remember(barcode.timestamp) {
//
//                })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}