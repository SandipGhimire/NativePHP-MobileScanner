import AVFoundation
import PhotosUI
import UIKit
import Vision

enum ScannerFunctions {

    static let codeScannedEvent = "Sandip\\Scanner\\Native\\Events\\Scanner\\CodeScanned"
    static let cancelledEvent = "Sandip\\Scanner\\Native\\Events\\Scanner\\Cancelled"

    static weak var activeController: ScannerViewController?

    static let validFormats: Set<String> = ["qr", "ean13", "ean8", "code128", "code39", "upca", "upce"]

    private static func metadataObjectTypes(for names: [String]) -> [AVMetadataObject.ObjectType] {
        if names.contains("all") {
            return [.qr, .ean13, .ean8, .code128, .code39, .upce, .code93, .pdf417, .aztec, .dataMatrix, .interleaved2of5, .itf14, .codabar]
        }

        var types = Set<AVMetadataObject.ObjectType>()
        for name in names {
            switch name {
            case "qr": types.insert(.qr)
            case "ean13": types.insert(.ean13)
            case "ean8": types.insert(.ean8)
            case "code128": types.insert(.code128)
            case "code39": types.insert(.code39)
            case "upce": types.insert(.upce)
            case "upca": types.insert(.ean13)
            default: break
            }
        }
        return Array(types)
    }

    static func barcodeSymbologies(for names: [String]) -> [VNBarcodeSymbology] {
        if names.contains("all") {
            return [.qr, .ean13, .ean8, .code128, .code39, .upce, .code93, .pdf417, .aztec, .dataMatrix, .itf14, .codabar]
        }

        var symbologies = Set<VNBarcodeSymbology>()
        for name in names {
            switch name {
            case "qr": symbologies.insert(.qr)
            case "ean13": symbologies.insert(.ean13)
            case "ean8": symbologies.insert(.ean8)
            case "code128": symbologies.insert(.code128)
            case "code39": symbologies.insert(.code39)
            case "upce": symbologies.insert(.upce)
            case "upca": symbologies.insert(.ean13)
            default: break
            }
        }
        return symbologies.isEmpty ? [.qr] : Array(symbologies)
    }

    class Scan: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let prompt = parameters["prompt"] as? String ?? "Scan Code"
            let continuous = parameters["continuous"] as? Bool ?? false
            let allowGallery = parameters["allowGallery"] as? Bool ?? true
            let id = parameters["id"] as? String
            let requestedFormats = (parameters["formats"] as? [String])?.filter { !$0.isEmpty } ?? ["qr"]

            let unknown = requestedFormats.filter { $0 != "all" && !ScannerFunctions.validFormats.contains($0) }
            if !unknown.isEmpty {
                return BridgeResponse.error(
                    code: "INVALID_FORMAT",
                    message: "Unknown barcode format(s): \(unknown.joined(separator: ", ")). Valid formats are: \(ScannerFunctions.validFormats.sorted().joined(separator: ", ")), all."
                )
            }

            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                DispatchQueue.main.async {
                    ScannerFunctions.present(prompt: prompt, continuous: continuous, allowGallery: allowGallery, formats: requestedFormats, id: id)
                }
                return BridgeResponse.success(data: ["started": true])

            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        LaravelBridge.shared.send?(ScannerFunctions.cancelledEvent, [
                            "reason": granted ? "permission_required" : "permission_denied",
                            "id": id,
                        ])
                    }
                }

                return BridgeResponse.success(data: ["permissionRequested": true])

            case .denied, .restricted:
                return BridgeResponse.error(
                    code: "PERMISSION_DENIED",
                    message: "Camera access is denied. Enable it in Settings to use the scanner."
                )

            @unknown default:
                return BridgeResponse.error(code: "PERMISSION_DENIED", message: "Camera access is unavailable.")
            }
        }
    }

    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let id = parameters["id"] as? String

            guard let controller = ScannerFunctions.activeController,
                  id == nil || controller.sessionId == id else {
                return BridgeResponse.success(data: ["stopped": false])
            }

            DispatchQueue.main.async {
                controller.finish(cancelled: true, reason: "stopped_by_app")
            }

            return BridgeResponse.success(data: ["stopped": true])
        }
    }

    private static func present(prompt: String, continuous: Bool, allowGallery: Bool, formats: [String], id: String?) {
        let rootViewController = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow })?.rootViewController

        guard let presenter = rootViewController else {
            LaravelBridge.shared.send?(cancelledEvent, [
                "reason": "no_root_view_controller",
                "id": id,
            ])
            return
        }

        activeController?.finish(cancelled: true, reason: nil)

        let types = metadataObjectTypes(for: formats)
        let controller = ScannerViewController(prompt: prompt, continuous: continuous, allowGallery: allowGallery, formats: formats, types: types, id: id)
        controller.modalPresentationStyle = .fullScreen
        activeController = controller
        presenter.present(controller, animated: true)
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    private let prompt: String
    private let continuous: Bool
    private let allowGallery: Bool
    private let requestedFormats: [String]
    private let types: [AVMetadataObject.ObjectType]
    let sessionId: String?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var captureDevice: AVCaptureDevice?
    private var finished = false
    private var torchOn = false
    private let torchButton = UIButton(type: .system)
    private let galleryButton = UIButton(type: .system)

    private var lastValue: String?
    private var lastFiredAt: TimeInterval = 0
    private let repeatDebounceSeconds: TimeInterval = 2.0

    init(prompt: String, continuous: Bool, allowGallery: Bool, formats: [String], types: [AVMetadataObject.ObjectType], id: String?) {
        self.prompt = prompt
        self.continuous = continuous
        self.allowGallery = allowGallery
        self.requestedFormats = formats
        self.types = types
        self.sessionId = id
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setUpCamera()
        setUpOverlay()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard !session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { [session] in
            session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        guard session.isRunning else { return }
        session.stopRunning()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func setUpCamera() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            finish(cancelled: true, reason: "camera_error")
            return
        }
        session.addInput(input)
        captureDevice = device

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            finish(cancelled: true, reason: "camera_error")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = types.filter { output.availableMetadataObjectTypes.contains($0) }

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
    }

    private func setUpOverlay() {
        let label = UILabel()
        label.text = prompt
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        let closeButton = UIButton(type: .system)
        closeButton.setTitle("✕", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        closeButton.layer.cornerRadius = 18
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        view.addSubview(closeButton)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            label.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -48),

            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            closeButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            closeButton.widthAnchor.constraint(equalToConstant: 36),
            closeButton.heightAnchor.constraint(equalToConstant: 36),
        ])

        var torchTrailingAnchor: NSLayoutXAxisAnchor = closeButton.leadingAnchor

        if allowGallery {
            galleryButton.setTitle("🖼", for: .normal)
            galleryButton.setTitleColor(.white, for: .normal)
            galleryButton.backgroundColor = UIColor.black.withAlphaComponent(0.4)
            galleryButton.layer.cornerRadius = 18
            galleryButton.translatesAutoresizingMaskIntoConstraints = false
            galleryButton.addTarget(self, action: #selector(galleryTapped), for: .touchUpInside)
            view.addSubview(galleryButton)

            NSLayoutConstraint.activate([
                galleryButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
                galleryButton.trailingAnchor.constraint(equalTo: closeButton.leadingAnchor, constant: -12),
                galleryButton.widthAnchor.constraint(equalToConstant: 36),
                galleryButton.heightAnchor.constraint(equalToConstant: 36),
            ])

            torchTrailingAnchor = galleryButton.leadingAnchor
        }

        guard let device = captureDevice, device.hasTorch else { return }

        torchButton.setTitle("⚡", for: .normal)
        torchButton.setTitleColor(.white, for: .normal)
        torchButton.alpha = 0.6
        torchButton.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        torchButton.layer.cornerRadius = 18
        torchButton.translatesAutoresizingMaskIntoConstraints = false
        torchButton.addTarget(self, action: #selector(torchTapped), for: .touchUpInside)
        view.addSubview(torchButton)

        NSLayoutConstraint.activate([
            torchButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            torchButton.trailingAnchor.constraint(equalTo: torchTrailingAnchor, constant: -12),
            torchButton.widthAnchor.constraint(equalToConstant: 36),
            torchButton.heightAnchor.constraint(equalToConstant: 36),
        ])
    }

    @objc private func closeTapped() {
        finish(cancelled: true, reason: "user_cancelled")
    }

    @objc private func galleryTapped() {
        guard allowGallery else { return }

        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1

        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        present(picker, animated: true)
    }

    @objc private func torchTapped() {
        guard let device = captureDevice, device.hasTorch else { return }

        do {
            try device.lockForConfiguration()
            torchOn.toggle()
            device.torchMode = torchOn ? .on : .off
            device.unlockForConfiguration()
            torchButton.alpha = torchOn ? 1.0 : 0.6
        } catch {
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else {
            return
        }

        let format = ScannerViewController.formatName(for: object.type, value: value, requested: requestedFormats)

        if !continuous {
            finish(cancelled: false, data: value, format: format)
            return
        }

        let now = Date().timeIntervalSince1970
        if value == lastValue, now - lastFiredAt < repeatDebounceSeconds {
            return
        }
        lastValue = value
        lastFiredAt = now

        LaravelBridge.shared.send?(ScannerFunctions.codeScannedEvent, [
            "data": value,
            "format": format,
            "id": sessionId,
        ])
    }

    private static func formatName(for type: AVMetadataObject.ObjectType, value: String, requested: [String]) -> String {
        if type == .ean13, value.count == 13, value.hasPrefix("0"),
           requested.contains("upca") || requested.contains("all"), !requested.contains("ean13") {
            return "upca"
        }

        switch type {
        case .qr: return "qr"
        case .ean13: return "ean13"
        case .ean8: return "ean8"
        case .code128: return "code128"
        case .code39: return "code39"
        case .upce: return "upce"
        case .code93: return "code93"
        case .pdf417: return "pdf417"
        case .aztec: return "aztec"
        case .dataMatrix: return "data_matrix"
        case .interleaved2of5: return "itf"
        case .itf14: return "itf14"
        case .codabar: return "codabar"
        default: return "unknown"
        }
    }

    private static func formatName(forSymbology symbology: VNBarcodeSymbology, value: String, requested: [String]) -> String {
        if symbology == .ean13, value.count == 13, value.hasPrefix("0"),
           requested.contains("upca") || requested.contains("all"), !requested.contains("ean13") {
            return "upca"
        }

        switch symbology {
        case .qr: return "qr"
        case .ean13: return "ean13"
        case .ean8: return "ean8"
        case .code128: return "code128"
        case .code39: return "code39"
        case .upce: return "upce"
        case .code93: return "code93"
        case .pdf417: return "pdf417"
        case .aztec: return "aztec"
        case .dataMatrix: return "data_matrix"
        case .itf14: return "itf14"
        case .codabar: return "codabar"
        default: return "unknown"
        }
    }

    fileprivate func loadAndDecode(provider: NSItemProvider) {
        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            guard let self = self else { return }
            guard let image = object as? UIImage, let cgImage = image.cgImage else {
                DispatchQueue.main.async {
                    self.showGalleryAlert(message: "Couldn't read that image.")
                }
                return
            }
            self.decodeGalleryImage(cgImage)
        }
    }

    private func decodeGalleryImage(_ cgImage: CGImage) {
        let request = VNDetectBarcodesRequest()
        request.symbologies = ScannerFunctions.barcodeSymbologies(for: requestedFormats)

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])

        do {
            try handler.perform([request])
        } catch {
            DispatchQueue.main.async { [weak self] in
                self?.showGalleryAlert(message: "Couldn't read that image.")
            }
            return
        }

        guard let match = request.results?.first(where: { $0.payloadStringValue != nil }),
              let value = match.payloadStringValue else {
            DispatchQueue.main.async { [weak self] in
                self?.showGalleryAlert(message: "No code found in that image.")
            }
            return
        }

        let format = ScannerViewController.formatName(forSymbology: match.symbology, value: value, requested: requestedFormats)

        DispatchQueue.main.async { [weak self] in
            self?.finish(cancelled: false, data: value, format: format)
        }
    }

    private func showGalleryAlert(message: String) {
        guard !finished else { return }
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    func finish(cancelled: Bool, data: String? = nil, format: String? = nil, reason: String? = nil) {
        guard !finished else { return }
        finished = true

        if ScannerFunctions.activeController === self {
            ScannerFunctions.activeController = nil
        }

        if session.isRunning {
            session.stopRunning()
        }

        dismiss(animated: true)

        if cancelled {
            guard reason != nil else { return }

            LaravelBridge.shared.send?(ScannerFunctions.cancelledEvent, [
                "reason": reason,
                "id": sessionId,
            ])
        } else {
            LaravelBridge.shared.send?(ScannerFunctions.codeScannedEvent, [
                "data": data,
                "format": format ?? "unknown",
                "id": sessionId,
            ])
        }
    }
}

extension ScannerViewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            picker.dismiss(animated: true)
            return
        }

        picker.dismiss(animated: true) { [weak self] in
            self?.loadAndDecode(provider: provider)
        }
    }
}