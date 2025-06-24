package org.example.project

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun IOSARBarcodeScreen(
    viewModel: ARBarcodeViewModel = remember { ARBarcodeViewModel() }
) {
    var showingSettings by remember { mutableStateOf(false) }

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
            isScanning = viewModel.isScanning, // This is now a direct boolean from mutableStateOf
            onBarcodeDetected = { barcode ->
                println("IOSARBarcodeScreen: Barcode detected in UI: ${barcode.value}")
                viewModel.onBarcodeDetected(barcode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // iOS-style overlay
        IOSAROverlayUI(
            isScanning = viewModel.isScanning,
            barcodeCount = scannedBarcodes.size,
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
fun IOSAROverlayUI(
    isScanning: Boolean,
    barcodeCount: Int,
    onToggleScanning: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Top status bar
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.QrCodeScanner else Icons.Default.QrCode,
                    contentDescription = null,
                    tint = if (isScanning) Color.Green else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (isScanning) "Scanning..." else "Ready",
                    color = if (isScanning) Color.Green else Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (barcodeCount > 0) {
                    Text(
                        text = "• $barcodeCount found",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Bottom control panel
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings button
                FilledIconButton(
                    onClick = onShowSettings,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }

                // Scan button
                FloatingActionButton(
                    onClick = {
                        println("IOSAROverlayUI: Scan button clicked, current state: $isScanning")
                        onToggleScanning()
                    },
                    modifier = Modifier.size(64.dp),
                    containerColor = if (isScanning)
                        MaterialTheme.colorScheme.error else
                        MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isScanning) "Stop" else "Start",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // History button
                FilledIconButton(
                    onClick = {
                        println("IOSAROverlayUI: History button pressed")
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    BadgedBox(
                        badge = {
                            if (barcodeCount > 0) {
                                Badge {
                                    Text(barcodeCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.History, contentDescription = "History")
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
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            // Clear history option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Clear History",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "$barcodeCount barcodes stored",
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
                    enabled = barcodeCount > 0
                ) {
                    Text("Clear")
                }
            }

            HorizontalDivider()

            // Done button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}