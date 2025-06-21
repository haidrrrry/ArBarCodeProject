package org.example.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BarcodeRepository {
    private val _scannedBarcodes = MutableStateFlow<List<BarcodeData>>(emptyList())
    val scannedBarcodes: StateFlow<List<BarcodeData>> = _scannedBarcodes.asStateFlow()

    fun addBarcode(barcode: BarcodeData) {
        val currentList = _scannedBarcodes.value.toMutableList()
        // Check if barcode already exists (avoid duplicates)
        if (currentList.none { it.value == barcode.value &&
                    (it.timestamp - it.timestamp) < 2000 }) {
            currentList.add(0, barcode)
            _scannedBarcodes.value = currentList.take(50)
        }
    }

    fun clearBarcodes() {
        _scannedBarcodes.value = emptyList()
    }
}
