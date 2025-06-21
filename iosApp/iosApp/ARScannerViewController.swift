import UIKit
import SceneKit
import ARKit
import Vision

// This is a new UIViewController dedicated to handling the AR experience.
class ARScannerViewController: UIViewController, ARSCNViewDelegate {

    // MARK: - Properties

    // The main view for our AR content
    private var sceneView: ARSCNView!

    // A label to show status updates
    private var statusLabel: UILabel!

    // A queue to handle Vision requests to avoid blocking the main UI thread
    private let visionQueue = DispatchQueue(label: "com.example.visionQueue")

    // The Vision request for detecting QR codes
    private var barcodeRequest: VNDetectBarcodesRequest?

    // Dictionaries to keep track of detected QR codes and their corresponding AR anchors/text
    private var detectedDataAnchorMap: [UUID: ARAnchor] = [:]
    private var detectedDataPayloadMap: [UUID: String] = [:]

    // MARK: - View Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()

        // 1. Setup the ARSCNView
        sceneView = ARSCNView(frame: self.view.bounds)
        self.view.addSubview(sceneView)
        sceneView.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        // 2. Set the view's delegate
        sceneView.delegate = self

        // 3. For debugging: show statistics and world origin
        sceneView.showsStatistics = true
        sceneView.debugOptions = [ARSCNDebugOptions.showWorldOrigin]

        // 4. Setup the Vision request
        setupVision()

        // 5. Setup the UI
        setupUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        // 6. Start the AR Session
        let configuration = ARWorldTrackingConfiguration()
        sceneView.session.run(configuration)

        // Start the barcode detection loop
        updateBarcodeDetection()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        // Pause the view's session to save battery
        sceneView.session.pause()
    }

    // MARK: - UI Setup

    private func setupUI() {
        statusLabel = UILabel()
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.backgroundColor = UIColor(white: 0.0, alpha: 0.5)
        statusLabel.textColor = .white
        statusLabel.font = UIFont.systemFont(ofSize: 16)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.text = "Point camera at a QR code"
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true

        view.addSubview(statusLabel)

        // Position the label at the top
        NSLayoutConstraint.activate([
            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            statusLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)
        ])
    }

    // MARK: - Vision Setup and Processing

    private func setupVision() {
        self.barcodeRequest = VNDetectBarcodesRequest(completionHandler: self.handleDetectedBarcodes)
        self.barcodeRequest?.symbologies = [.qr]
    }

    private func updateBarcodeDetection() {
        guard let request = self.barcodeRequest else { return }

        visionQueue.async {
            guard let currentFrame = self.sceneView.session.currentFrame else { return }
            let imageRequestHandler = VNImageRequestHandler(cvPixelBuffer: currentFrame.capturedImage, options: [:])

            do {
                try imageRequestHandler.perform([request])
            } catch {
                print("Error: Could not perform Vision request. \(error)")
            }
        }
    }

    private func handleDetectedBarcodes(request: VNRequest, error: Error?) {
        guard let observations = request.results as? [VNBarcodeObservation] else { return }

        DispatchQueue.main.async {
            self.updateScene(with: observations)
        }
    }

    // MARK: - AR Scene Management

    private func updateScene(with observations: [VNBarcodeObservation]) {
        var observedBarcodes = [UUID]()

        for observation in observations {
            observedBarcodes.append(observation.uuid)

            if detectedDataAnchorMap.keys.contains(observation.uuid) { continue }

            guard let payload = observation.payloadStringValue,
                  let hitTestResult = self.sceneView.session.currentFrame?.hitTest(
                      self.normalizedCenter(for: observation.boundingBox),
                      types: [.featurePoint]
                  ).first else { continue }

            let anchor = ARAnchor(transform: hitTestResult.worldTransform)
            self.sceneView.session.add(anchor: anchor)

            self.detectedDataAnchorMap[observation.uuid] = anchor
            self.detectedDataPayloadMap[observation.uuid] = payload
            self.statusLabel.text = "Detected: \(payload)"
        }

        for uuid in detectedDataAnchorMap.keys {
            if !observedBarcodes.contains(uuid) {
                if let anchor = detectedDataAnchorMap[uuid] {
                    self.sceneView.session.remove(anchor: anchor)
                }
                detectedDataAnchorMap.removeValue(forKey: uuid)
                detectedDataPayloadMap.removeValue(forKey: uuid)
            }
        }

        if observations.isEmpty {
            self.statusLabel.text = "Point camera at a QR code"
        }
    }

    private func normalizedCenter(for boundingBox: CGRect) -> CGPoint {
        return CGPoint(x: boundingBox.origin.x + boundingBox.size.width / 2,
                       y: 1 - (boundingBox.origin.y + boundingBox.size.height / 2))
    }

    // MARK: - ARSCNViewDelegate

    func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
        guard let uuid = detectedDataAnchorMap.first(where: { $0.value == anchor })?.key,
              let payload = detectedDataPayloadMap[uuid] else { return }

        let textNode = createTextNode(with: payload)
        node.addChildNode(textNode)
    }

    func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
        DispatchQueue.main.async { self.updateBarcodeDetection() }
    }

    // MARK: - 3D Content Creation

    private func createTextNode(with string: String) -> SCNNode {
        let text = SCNText(string: string, extrusionDepth: 1.0)
        text.font = UIFont.systemFont(ofSize: 10)
        text.firstMaterial?.diffuse.contents = UIColor.systemBlue

        let textNode = SCNNode(geometry: text)
        let scale: Float = 0.002
        textNode.scale = SCNVector3(scale, scale, scale)

        let (min, max) = text.boundingBox
        let dx = min.x + 0.5 * (max.x - min.x)
        let dy = min.y + 0.5 * (max.y - min.y)
        textNode.pivot = SCNMatrix4MakeTranslation(dx, dy, 0)

        return textNode
    }
}
