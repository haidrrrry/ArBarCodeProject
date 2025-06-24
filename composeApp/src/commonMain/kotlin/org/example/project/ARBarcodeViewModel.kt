package org.example.project

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.StateFlow

class ARBarcodeViewModel(private val repository: BarcodeRepository = BarcodeRepository()) {
    val scannedBarcodes: StateFlow<List<BarcodeData>> = repository.scannedBarcodes

    var isScanning by mutableStateOf(true)
        private set

    var selectedBarcode by mutableStateOf<BarcodeData?>(null)
        private set

    fun startScanning() {
        isScanning = true
    }

    fun stopScanning() {
        isScanning = false
    }

    fun onBarcodeDetected(barcode: BarcodeData) {
        repository.addBarcode(barcode)
    }

    fun selectBarcode(barcode: BarcodeData?) {
        selectedBarcode = barcode
    }

    fun clearHistory() {
        repository.clearBarcodes()
    }
}