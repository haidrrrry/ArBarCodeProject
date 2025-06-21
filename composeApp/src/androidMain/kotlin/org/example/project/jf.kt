package org.example.project

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
