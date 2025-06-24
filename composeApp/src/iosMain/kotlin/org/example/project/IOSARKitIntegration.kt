@file:Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")

package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.*
import platform.ARKit.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.*
import platform.SceneKit.SCNAntialiasingMode
import platform.UIKit.*
import platform.Vision.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ARCameraView(
    isScanning: Boolean,
    onBarcodeDetected: (BarcodeData) -> Unit,
    modifier: Modifier
) {
    var permissionGranted by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<IOSARCameraViewController?>(null) }

    // Debug the state changes
    LaunchedEffect(isScanning) {
        println("ARCameraView: isScanning state changed to: $isScanning")
        controller?.updateScanning(isScanning)
    }

    LaunchedEffect(Unit) {
        println("ARCameraView: Checking camera permission...")
        checkCameraPermission { granted ->
            permissionGranted = granted
            println("ARCameraView: Camera permission granted: $granted")
        }
    }

    if (!permissionGranted) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Camera permission required",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        println("ARCameraView: Requesting camera permission...")
                        requestCameraPermission { permissionGranted = it }
                    }
                ) {
                    Text("Grant Permission")
                }
            }
        }
        return
    }

    UIKitView(
        factory = {
            println("ARCameraView: Creating new IOSARCameraViewController")
            val newController = IOSARCameraViewController(
                onBarcodeDetected = onBarcodeDetected
            )
            controller = newController
            // Set initial scanning state
            newController.updateScanning(isScanning)
            newController.view
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            // The controller is already stored in the remember state
            // Just update its scanning state
            println("ARCameraView: UIKitView update called with isScanning: $isScanning")
            controller?.updateScanning(isScanning)
        }
    )
}

@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
@OptIn(ExperimentalForeignApi::class)
class IOSARCameraViewController(
    private val onBarcodeDetected: (BarcodeData) -> Unit
) : UIViewController(nibName = null, bundle = null), ARSCNViewDelegateProtocol, ARSessionDelegateProtocol {

    private lateinit var arView: ARSCNView
    private lateinit var session: ARSession
    private val visionQueue = dispatch_queue_create("vision_queue", null)
    private var isCurrentlyScanning = false
    private var lastProcessTime = 0L
    private val processInterval = 200L // Reduced for better responsiveness
    private var isProcessingFrame = false
    private val detectedBarcodes = mutableSetOf<String>()
    private var lastClearTime = 0L
    private val clearInterval = 5000L
    private var frameCount = 0L // Add frame counter for debugging

    override fun debugDescription(): String? {
        return "IOSARCameraViewController (ARSCNViewDelegate)"
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        println("IOSARCameraViewController: viewDidLoad")
        setupARView()
        setupSession()
    }

    private fun setupARView() {
        arView = ARSCNView()
        arView.delegate = this
        arView.automaticallyUpdatesLighting = true
        arView.antialiasingMode = SCNAntialiasingMode.SCNAntialiasingModeMultisampling4X
        arView.preferredFramesPerSecond = 30
        arView.contentScaleFactor = 1.0

        view.addSubview(arView)
        arView.setTranslatesAutoresizingMaskIntoConstraints(false)

        arView.topAnchor.constraintEqualToAnchor(view.safeAreaLayoutGuide.topAnchor).active = true
        arView.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor).active = true
        arView.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor).active = true
        arView.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor).active = true
        println("IOSARCameraViewController: setupARView completed")
    }

    private fun setupSession() {
        session = arView.session!!
        session.delegate = this

        val configuration = ARWorldTrackingConfiguration()
        if (ARWorldTrackingConfiguration.isSupported()) {
            configuration.planeDetection = ARPlaneDetectionNone
            configuration.lightEstimationEnabled = false
            configuration.providesAudioData = false

            val options = ARSessionRunOptionResetTracking or ARSessionRunOptionRemoveExistingAnchors
            session.runWithConfiguration(configuration, options)
            println("IOSARCameraViewController: ARWorldTrackingConfiguration started")
        } else {
            println("IOSARCameraViewController: ARWorldTrackingConfiguration not supported")
        }
    }

    fun updateScanning(scanning: Boolean) {
        println("IOSARCameraViewController: updateScanning called with: $scanning")
        println("IOSARCameraViewController: Previous state was: $isCurrentlyScanning")
        isCurrentlyScanning = scanning
        println("IOSARCameraViewController: New state is: $isCurrentlyScanning")

        // Clear detected barcodes when starting new scan
        if (scanning) {
            detectedBarcodes.clear()
            println("IOSARCameraViewController: Cleared detected barcodes cache for new scan")
        }
    }

    override fun session(session: ARSession, didUpdateFrame: ARFrame) {
        frameCount++

        // Log every 60 frames to avoid spam (about once per 2 seconds at 30fps)
        if (frameCount % 60 == 0L) {
            println("IOSARCameraViewController: Frame #$frameCount received, scanning: $isCurrentlyScanning")
        }

        if (!isCurrentlyScanning) {
            return
        }

        if (isProcessingFrame) {
            return
        }

        val currentTime = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000

        // Clear detected barcodes periodically
        if (currentTime - lastClearTime > clearInterval) {
            detectedBarcodes.clear()
            lastClearTime = currentTime
            println("IOSARCameraViewController: Cleared detected barcodes cache (periodic)")
        }

        if (currentTime - lastProcessTime < processInterval) {
            return
        }

        println("IOSARCameraViewController: Processing frame for barcode detection")
        lastProcessTime = currentTime
        isProcessingFrame = true

        val pixelBuffer = didUpdateFrame.capturedImage

        if (pixelBuffer != null) {
            dispatch_async(visionQueue) {
                processFrameImmediate(pixelBuffer)
                dispatch_async(dispatch_get_main_queue()) {
                    isProcessingFrame = false
                }
            }
        } else {
            println("IOSARCameraViewController: ERROR - Pixel buffer is null!")
            isProcessingFrame = false
        }
    }

    private fun processFrameImmediate(pixelBuffer: CVPixelBufferRef) {
        println("IOSARCameraViewController: === STARTING VISION PROCESSING ===")

        val requestHandler = VNImageRequestHandler(pixelBuffer, mapOf<Any?, Any?>())

        val barcodeRequest = VNDetectBarcodesRequest { request, error ->
            println("IOSARCameraViewController: === VISION COMPLETION HANDLER CALLED ===")

            if (error != null) {
                println("IOSARCameraViewController: ❌ VISION ERROR: ${error.localizedDescription}")
                return@VNDetectBarcodesRequest
            }

            val barcodeResults = request?.results as? NSArray
            val resultCount = barcodeResults?.count?.toInt() ?: 0
            println("IOSARCameraViewController: Found $resultCount barcode results")

            if (resultCount == 0) {
                return@VNDetectBarcodesRequest
            }

            barcodeResults?.let { results ->
                for (i in 0 until results.count.toInt()) {
                    val barcode = results.objectAtIndex(i.toULong()) as? VNBarcodeObservation

                    barcode?.let {
                        val value = it.payloadStringValue
                        val symbology = it.symbology
                        val confidence = it.confidence

                        println("IOSARCameraViewController: Barcode found - Value: '$value', Confidence: $confidence")

                        if (value != null && value.isNotEmpty() && confidence > 0.5) {
                            // Prevent duplicate detections
                            if (detectedBarcodes.contains(value)) {
                                println("IOSARCameraViewController: Skipping duplicate barcode: '$value'")
                                return@let
                            }

                            detectedBarcodes.add(value)
                            val format = getBarcodeFormat(symbology)

                            println("IOSARCameraViewController: 🎉 NEW BARCODE DETECTED: '$value'")

                            dispatch_async(dispatch_get_main_queue()) {
                                println("IOSARCameraViewController: Calling onBarcodeDetected callback")
                                onBarcodeDetected(
                                    BarcodeData(
                                        value = value,
                                        format = format,
                                        x = 0f,
                                        y = 0f,
                                        z = 0f
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Configure supported symbologies
        val symbologiesList = mutableListOf<VNBarcodeSymbology>()
        symbologiesList.add(VNBarcodeSymbologyQR)
        symbologiesList.add(VNBarcodeSymbologyCode128)
        symbologiesList.add(VNBarcodeSymbologyCode39)
        symbologiesList.add(VNBarcodeSymbologyEAN13)
        symbologiesList.add(VNBarcodeSymbologyEAN8)
        symbologiesList.add(VNBarcodeSymbologyUPCE)
        symbologiesList.add(VNBarcodeSymbologyDataMatrix)
        symbologiesList.add(VNBarcodeSymbologyPDF417)
        symbologiesList.add(VNBarcodeSymbologyAztec)

        barcodeRequest.symbologies = symbologiesList as List<VNBarcodeSymbology>

        // Perform the request
        memScoped {
            val requestsList = mutableListOf<VNRequest>()
            requestsList.add(barcodeRequest)

            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val success = requestHandler.performRequests(requestsList as List<VNRequest>, errorPtr.ptr)

            if (!success) {
                val error = errorPtr.value
                println("IOSARCameraViewController: ❌ PERFORM REQUESTS FAILED: ${error?.localizedDescription}")
            }
        }
    }

    private fun getBarcodeFormat(symbology: VNBarcodeSymbology): String {
        return when (symbology) {
            VNBarcodeSymbologyQR -> "QR Code"
            VNBarcodeSymbologyCode128 -> "Code 128"
            VNBarcodeSymbologyCode39 -> "Code 39"
            VNBarcodeSymbologyCode93 -> "Code 93"
            VNBarcodeSymbologyEAN13 -> "EAN-13"
            VNBarcodeSymbologyEAN8 -> "EAN-8"
            VNBarcodeSymbologyUPCE -> "UPC-E"
            VNBarcodeSymbologyPDF417 -> "PDF417"
            VNBarcodeSymbologyDataMatrix -> "Data Matrix"
            VNBarcodeSymbologyAztec -> "Aztec"
            else -> "Unknown"
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        println("IOSARCameraViewController: viewWillDisappear - Pausing session")
        isCurrentlyScanning = false
        session.pause()
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        println("IOSARCameraViewController: viewWillAppear - Starting session")

        if (ARWorldTrackingConfiguration.isSupported()) {
            val configuration = ARWorldTrackingConfiguration()
            configuration.planeDetection = ARPlaneDetectionNone
            configuration.lightEstimationEnabled = false
            configuration.providesAudioData = false

            val options = ARSessionRunOptionResetTracking or ARSessionRunOptionRemoveExistingAnchors
            session.runWithConfiguration(configuration, options)
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        println("IOSARCameraViewController: viewDidDisappear")
        isCurrentlyScanning = false
        isProcessingFrame = false
        session.pause()
    }

    // ARSessionDelegate methods
    override fun session(session: ARSession, didFailWithError: NSError) {
        println("IOSARCameraViewController: AR Session failed: ${didFailWithError.localizedDescription}")
    }

    override fun sessionWasInterrupted(session: ARSession) {
        println("IOSARCameraViewController: AR Session interrupted")
        isCurrentlyScanning = false
    }

    override fun sessionInterruptionEnded(session: ARSession) {
        println("IOSARCameraViewController: AR Session interruption ended")
    }
}

// Permission handling functions
private fun checkCameraPermission(callback: (Boolean) -> Unit) {
    val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    println("Permission: Current camera permission status: $status")
    when (status) {
        AVAuthorizationStatusAuthorized -> callback(true)
        AVAuthorizationStatusNotDetermined -> {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    println("Permission: Camera access granted: $granted")
                    callback(granted)
                }
            }
        }
        else -> callback(false)
    }
}

private fun requestCameraPermission(callback: (Boolean) -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        dispatch_async(dispatch_get_main_queue()) {
            println("Permission: Camera access request result: $granted")
            callback(granted)
        }
    }
}