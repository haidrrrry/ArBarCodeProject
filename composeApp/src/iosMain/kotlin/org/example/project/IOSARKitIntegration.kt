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
    private val processInterval = 100L // Increased frequency
    private var isProcessingFrame = false
    private val detectedBarcodes = mutableSetOf<String>()
    private var lastClearTime = 0L
    private val clearInterval = 3000L // Reduced clear interval
    private var frameCount = 0L
    private var sessionStarted = false

    override fun debugDescription(): String? {
        return "IOSARCameraViewController (ARSCNViewDelegate)"
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        println("IOSARCameraViewController: viewDidLoad")
        setupARView()
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        println("IOSARCameraViewController: viewWillAppear - Starting session")
        setupAndStartSession()
    }

    private fun setupARView() {
        arView = ARSCNView()

        // Set up the view constraints first
        view.addSubview(arView)
        arView.setTranslatesAutoresizingMaskIntoConstraints(false)

        arView.topAnchor.constraintEqualToAnchor(view.safeAreaLayoutGuide.topAnchor).active = true
        arView.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor).active = true
        arView.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor).active = true
        arView.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor).active = true

        // Configure ARSCNView properties
        arView.automaticallyUpdatesLighting = true
        arView.antialiasingMode = SCNAntialiasingMode.SCNAntialiasingModeMultisampling4X
        arView.preferredFramesPerSecond = 30
        arView.contentScaleFactor = 1.0

        // CRITICAL: Set delegates AFTER adding to view hierarchy
        arView.delegate = this

        println("IOSARCameraViewController: setupARView completed")
    }

    private fun setupAndStartSession() {
        if (sessionStarted) {
            println("IOSARCameraViewController: Session already started, skipping setup")
            return
        }

        // Get session from ARSCNView
        session = arView.session!!

        // CRITICAL: Set session delegate AFTER getting session
        session.delegate = this

        if (ARWorldTrackingConfiguration.isSupported()) {
            val configuration = ARWorldTrackingConfiguration()
            configuration.planeDetection = ARPlaneDetectionNone
            configuration.lightEstimationEnabled = false
            configuration.providesAudioData = false

            // IMPORTANT: Don't use reset options on initial start
            val options = if (sessionStarted) {
                ARSessionRunOptionResetTracking or ARSessionRunOptionRemoveExistingAnchors
            } else {
                0U // No options for first run
            }

            session.runWithConfiguration(configuration, options)
            sessionStarted = true

            println("IOSARCameraViewController: ARWorldTrackingConfiguration started successfully")
            println("IOSARCameraViewController: Session delegate set to: ${session.delegate}")
        } else {
            println("IOSARCameraViewController: ERROR - ARWorldTrackingConfiguration not supported!")
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
            lastProcessTime = 0L // Reset timing to process immediately
            println("IOSARCameraViewController: Cleared detected barcodes cache for new scan")
        }
    }

    // CRITICAL: This method name must match exactly what ARKit expects
    override fun session(session: ARSession, didUpdateFrame: ARFrame) {
        frameCount++

        // More frequent logging for debugging
        if (frameCount % 30 == 0L) {
            println("IOSARCameraViewController: Frame #$frameCount received, scanning: $isCurrentlyScanning")
        }

        if (!isCurrentlyScanning) {
            if (frameCount % 60 == 0L) {
                println("IOSARCameraViewController: Skipping frame processing - not scanning")
            }
            return
        }

        if (isProcessingFrame) {
            if (frameCount % 60 == 0L) {
                println("IOSARCameraViewController: Skipping frame - already processing")
            }
            return
        }

        val currentTime = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000

        // Clear detected barcodes periodically
        if (currentTime - lastClearTime > clearInterval) {
            val previousSize = detectedBarcodes.size
            detectedBarcodes.clear()
            lastClearTime = currentTime
            println("IOSARCameraViewController: Cleared $previousSize detected barcodes (periodic)")
        }

        if (currentTime - lastProcessTime < processInterval) {
            return
        }

        println("IOSARCameraViewController: Processing frame #$frameCount for barcode detection")
        lastProcessTime = currentTime
        isProcessingFrame = true

        val pixelBuffer = didUpdateFrame.capturedImage

        if (pixelBuffer != null) {
            dispatch_async(visionQueue) {
                processFrameImmediate(pixelBuffer, frameCount)
                dispatch_async(dispatch_get_main_queue()) {
                    isProcessingFrame = false
                }
            }
        } else {
            println("IOSARCameraViewController: ERROR - Pixel buffer is null!")
            isProcessingFrame = false
        }
    }

    private fun processFrameImmediate(pixelBuffer: CVPixelBufferRef, currentFrameCount: Long) {
        println("IOSARCameraViewController: === STARTING VISION PROCESSING FOR FRAME $currentFrameCount ===")

        // Create request handler with improved options
        val options = mapOf<Any?, Any?>(
            VNImageOptionCameraIntrinsics to NSNull() // Use default camera intrinsics
        )
        val requestHandler = VNImageRequestHandler(pixelBuffer, options)

        val barcodeRequest = VNDetectBarcodesRequest { request, error ->
            println("IOSARCameraViewController: === VISION COMPLETION HANDLER CALLED FOR FRAME $currentFrameCount ===")

            if (error != null) {
                println("IOSARCameraViewController: ❌ VISION ERROR: ${error.localizedDescription}")
                return@VNDetectBarcodesRequest
            }

            val barcodeResults = request?.results as? NSArray
            val resultCount = barcodeResults?.count?.toInt() ?: 0
            println("IOSARCameraViewController: Found $resultCount barcode results in frame $currentFrameCount")

            if (resultCount == 0) {
                println("IOSARCameraViewController: No barcodes detected in frame $currentFrameCount")
                return@VNDetectBarcodesRequest
            }

            barcodeResults?.let { results ->
                for (i in 0 until results.count.toInt()) {
                    val barcode = results.objectAtIndex(i.toULong()) as? VNBarcodeObservation

                    barcode?.let {
                        val value = it.payloadStringValue
                        val symbology = it.symbology
                        val confidence = it.confidence

                        println("IOSARCameraViewController: Barcode candidate - Value: '$value', Confidence: $confidence, Symbology: $symbology")

                        if (value != null && value.isNotEmpty() && confidence > 0.3) { // Lowered confidence threshold
                            // Prevent duplicate detections
                            if (detectedBarcodes.contains(value)) {
                                println("IOSARCameraViewController: Skipping duplicate barcode: '$value'")
                                return@let
                            }

                            detectedBarcodes.add(value)
                            val format = getBarcodeFormat(symbology)

                            println("IOSARCameraViewController: 🎉 NEW BARCODE DETECTED: '$value' (Format: $format)")

                            dispatch_async(dispatch_get_main_queue()) {
                                println("IOSARCameraViewController: Calling onBarcodeDetected callback for '$value'")
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
                        } else {
                            println("IOSARCameraViewController: Rejected barcode - Value: '$value', Confidence: $confidence (too low)")
                        }
                    }
                }
            }
        }

        // Configure supported symbologies - Add more types
        val symbologiesList = mutableListOf<VNBarcodeSymbology>()
        symbologiesList.add(VNBarcodeSymbologyQR)
        symbologiesList.add(VNBarcodeSymbologyCode128)
        symbologiesList.add(VNBarcodeSymbologyCode39)
        symbologiesList.add(VNBarcodeSymbologyCode39Checksum)
        symbologiesList.add(VNBarcodeSymbologyCode39FullASCII)
        symbologiesList.add(VNBarcodeSymbologyCode39FullASCIIChecksum)
        symbologiesList.add(VNBarcodeSymbologyCode93)
        symbologiesList.add(VNBarcodeSymbologyCode93i)
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

            println("IOSARCameraViewController: Performing Vision request for frame $currentFrameCount...")
            val success = requestHandler.performRequests(requestsList as List<VNRequest>, errorPtr.ptr)

            if (!success) {
                val error = errorPtr.value
                println("IOSARCameraViewController: ❌ PERFORM REQUESTS FAILED FOR FRAME $currentFrameCount: ${error?.localizedDescription}")
            } else {
                println("IOSARCameraViewController: ✅ Vision request completed successfully for frame $currentFrameCount")
            }
        }
    }

    private fun getBarcodeFormat(symbology: VNBarcodeSymbology): String {
        return when (symbology) {
            VNBarcodeSymbologyQR -> "QR Code"
            VNBarcodeSymbologyCode128 -> "Code 128"
            VNBarcodeSymbologyCode39 -> "Code 39"
            VNBarcodeSymbologyCode39Checksum -> "Code 39 Checksum"
            VNBarcodeSymbologyCode39FullASCII -> "Code 39 Full ASCII"
            VNBarcodeSymbologyCode39FullASCIIChecksum -> "Code 39 Full ASCII Checksum"
            VNBarcodeSymbologyCode93 -> "Code 93"
            VNBarcodeSymbologyCode93i -> "Code 93i"
            VNBarcodeSymbologyEAN13 -> "EAN-13"
            VNBarcodeSymbologyEAN8 -> "EAN-8"
            VNBarcodeSymbologyUPCE -> "UPC-E"
            VNBarcodeSymbologyPDF417 -> "PDF417"
            VNBarcodeSymbologyDataMatrix -> "Data Matrix"
            VNBarcodeSymbologyAztec -> "Aztec"
            else -> "Unknown ($symbology)"
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        println("IOSARCameraViewController: viewWillDisappear - Pausing session")
        isCurrentlyScanning = false
        session.pause()
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
        println("IOSARCameraViewController: ❌ AR Session failed: ${didFailWithError.localizedDescription}")
        println("IOSARCameraViewController: Error code: ${didFailWithError.code}")
        println("IOSARCameraViewController: Error domain: ${didFailWithError.domain}")
    }

    override fun sessionWasInterrupted(session: ARSession) {
        println("IOSARCameraViewController: ⚠️ AR Session interrupted")
        isCurrentlyScanning = false
    }

    override fun sessionInterruptionEnded(session: ARSession) {
        println("IOSARCameraViewController: ✅ AR Session interruption ended")
        // Don't automatically restart scanning, let user control it
    }

    // Additional delegate methods for better debugging
    override fun session(session: ARSession, cameraDidChangeTrackingState: ARCamera) {
        val state = cameraDidChangeTrackingState.trackingState
        println("IOSARCameraViewController: Camera tracking state changed to: $state")

        when (state) {
            ARTrackingState.ARTrackingStateNotAvailable -> {
                println("IOSARCameraViewController: ❌ Tracking not available")
            }
            ARTrackingState.ARTrackingStateLimited -> {
                val reason = cameraDidChangeTrackingState.trackingStateReason
                println("IOSARCameraViewController: ⚠️ Tracking limited, reason: $reason")
            }
            ARTrackingState.ARTrackingStateNormal -> {
                println("IOSARCameraViewController: ✅ Tracking normal")
            }
            else -> {
                println("IOSARCameraViewController: Unknown tracking state: $state")
            }
        }
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
        else -> {
            println("Permission: Camera access denied or restricted")
            callback(false)
        }
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