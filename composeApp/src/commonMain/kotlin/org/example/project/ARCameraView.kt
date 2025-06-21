package org.example.project

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun ARCameraView(
    isScanning: Boolean,
    onBarcodeDetected: (BarcodeData) -> Unit,
    modifier: Modifier = Modifier
)