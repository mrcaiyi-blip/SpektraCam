import AVFoundation
import CoreVideo
import Foundation

// Ownership of the immutable capture payload moves to the development queue.
struct RawIspCapturedPhoto: @unchecked Sendable {
    let pixelBuffer: CVPixelBuffer
    let metadata: [String: Any]
    let dngData: Data?
    let rawPixelFormat: OSType
    let displayRotationDegrees: Int32
}

struct CameraFailure: LocalizedError {
    let key: String
    var errorDescription: String? { L10n.text(key) }
    init(_ key: String) { self.key = key }
}

struct CameraLens {
    let device: AVCaptureDevice
    let zoom: Double

    var title: String {
        switch device.deviceType {
        case .builtInUltraWideCamera: return L10n.text("ultra_wide")
        case .builtInTelephotoCamera: return L10n.text("telephoto")
        default: return device.position == .front ? L10n.text("front_camera") : L10n.text("wide")
        }
    }
}

final class CameraCapture: NSObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let video = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "spektracam.capture")
    private let previewQueue = DispatchQueue(label: "spektracam.preview")
    private var format: OSType?
    private var device: AVCaptureDevice?
    private var request: (id: Int64, rotation: Int32, callback: (Result<RawIspCapturedPhoto, Error>) -> Void)?
    private var processed: Result<RawIspCapturedPhoto, Error>?

    func discoverLenses(completion: @escaping ([CameraLens]) -> Void) {
        queue.async {
            let discovery = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInUltraWideCamera, .builtInWideAngleCamera, .builtInTelephotoCamera],
                mediaType: .video, position: .unspecified)
            let virtualTypes: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera]
            let combined = virtualTypes.lazy.compactMap {
                AVCaptureDevice.default($0, for: .video, position: .back)
            }.first
            let constituents = combined?.constituentDevices ?? []
            let factors = [1.0] + (combined?.virtualDeviceSwitchOverVideoZoomFactors.map(\.doubleValue) ?? [])
            let reference = constituents.firstIndex { $0.deviceType == .builtInWideAngleCamera }
                .map { factors[$0] } ?? 1
            let wideFov = discovery.devices.first {
                $0.position == .back && $0.deviceType == .builtInWideAngleCamera
            }?.activeFormat.videoFieldOfView

            let lenses = discovery.devices.compactMap { device -> CameraLens? in
                let supported = self.supportsBayer(device)
                let factor: Double
                if device.position == .front || device.deviceType == .builtInWideAngleCamera {
                    factor = 1
                } else if let index = constituents.firstIndex(where: { $0.uniqueID == device.uniqueID }) {
                    factor = factors[index] / reference
                } else if let wideFov, wideFov > 0, device.activeFormat.videoFieldOfView > 0 {
                    let target = Double(device.activeFormat.videoFieldOfView)
                    factor = tan(Double(wideFov) * .pi / 360) / tan(target * .pi / 360)
                } else {
                    factor = device.deviceType == .builtInUltraWideCamera ? 0.5 : 2
                }
                let lens = CameraLens(device: device, zoom: (factor * 10).rounded() / 10)
                NSLog("[SpektraCam] %@ %g× Bayer RAW: %@", lens.title, lens.zoom, supported ? "yes" : "no")
                return supported ? lens : nil
            }
            completion(lenses)
        }
    }

    private func supportsBayer(_ device: AVCaptureDevice) -> Bool {
        guard let input = try? AVCaptureDeviceInput(device: device) else { return false }
        let probe = AVCaptureSession()
        let photos = AVCapturePhotoOutput()
        probe.sessionPreset = .photo
        guard probe.canAddInput(input) else { return false }
        probe.addInput(input)
        guard probe.canAddOutput(photos) else { return false }
        probe.addOutput(photos)
        return bayerFormat(photos) != nil
    }

    private func bayerFormat(_ output: AVCapturePhotoOutput) -> OSType? {
        output.availableRawPhotoPixelFormatTypes.first { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }
    }

    func configure(_ lens: CameraLens, renderer: RawIspLutPreviewRenderer,
                   completion: @escaping (Result<AVCaptureDevice, Error>) -> Void) {
        queue.async {
            let result = Result {
                guard self.request == nil else { throw CameraFailure("capture_pending") }
                let device = lens.device
                let input = try AVCaptureDeviceInput(device: device)
                self.session.beginConfiguration()
                defer { self.session.commitConfiguration() }
                let oldInputs = self.session.inputs
                oldInputs.forEach(self.session.removeInput)
                var accepted = false
                defer {
                    if !accepted {
                        if self.session.inputs.contains(input) { self.session.removeInput(input) }
                        for previous in oldInputs where self.session.canAddInput(previous) {
                            self.session.addInput(previous)
                        }
                    }
                }
                self.session.sessionPreset = .photo
                guard self.session.canAddInput(input) else { throw CameraFailure("add_lens_failed") }
                self.session.addInput(input)
                if !self.session.outputs.contains(self.output) {
                    guard self.session.canAddOutput(self.output) else { throw CameraFailure("add_raw_failed") }
                    self.session.addOutput(self.output)
                }
                guard let raw = self.bayerFormat(self.output) else {
                    throw CameraFailure("lens_no_raw")
                }
                if !self.session.outputs.contains(self.video) {
                    self.video.alwaysDiscardsLateVideoFrames = true
                    self.video.videoSettings = [
                        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                    ]
                    guard self.session.canAddOutput(self.video) else { throw CameraFailure("add_preview_failed") }
                    self.video.setSampleBufferDelegate(renderer, queue: self.previewQueue)
                    self.session.addOutput(self.video)
                }
                if let connection = self.video.connection(with: .video) {
                    if connection.isVideoOrientationSupported { connection.videoOrientation = .landscapeRight }
                    if connection.isVideoMirroringSupported {
                        connection.automaticallyAdjustsVideoMirroring = false
                        connection.isVideoMirrored = device.position == .front
                    }
                }
                if let old = self.device, old.hasTorch, (try? old.lockForConfiguration()) != nil {
                    old.torchMode = .off
                    old.unlockForConfiguration()
                }
                renderer.resetFocusGeometry()
                self.format = raw
                self.device = device
                accepted = true
                return device
            }
            if case .success = result {
                NSLog("[SpektraCam] Active camera: %@ %g×", lens.title, lens.zoom)
            }
            completion(result)
        }
    }

    func setRunning(_ running: Bool) {
        queue.async {
            if running && self.format != nil && !self.session.isRunning { self.session.startRunning() }
            if !running && self.session.isRunning {
                if let device = self.device, device.hasTorch, (try? device.lockForConfiguration()) != nil {
                    device.torchMode = .off
                    device.unlockForConfiguration()
                }
                self.session.stopRunning()
            }
        }
    }

    func adjust(_ change: @escaping (AVCaptureDevice) throws -> Void,
                completion: @escaping (Result<Void, Error>) -> Void) {
        queue.async {
            let result = Result {
                guard let device = self.device, self.request == nil else { throw CameraFailure("camera_busy") }
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                try change(device)
            }
            completion(result)
        }
    }

    func capture(rotation: Int32, completion: @escaping (Result<RawIspCapturedPhoto, Error>) -> Void) {
        queue.async {
            guard let format = self.format, self.session.isRunning, self.request == nil else {
                completion(.failure(CameraFailure("raw_not_ready"))); return
            }
            let settings = AVCapturePhotoSettings(rawPixelFormatType: format)
            self.request = (settings.uniqueID, rotation, completion)
            self.processed = nil
            self.output.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        queue.async {
            guard let request = self.request, request.id == photo.resolvedSettings.uniqueID else { return }
            if let error { self.processed = .failure(error); return }
            guard photo.isRawPhoto, let pixels = photo.pixelBuffer else {
                self.processed = .failure(CameraFailure("raw_no_buffer")); return
            }
            self.processed = .success(RawIspCapturedPhoto(
                pixelBuffer: pixels, metadata: photo.metadata, dngData: photo.fileDataRepresentation(),
                rawPixelFormat: CVPixelBufferGetPixelFormatType(pixels), displayRotationDegrees: request.rotation))
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor settings: AVCaptureResolvedPhotoSettings, error: Error?) {
        queue.async {
            guard let request = self.request, request.id == settings.uniqueID else { return }
            let result = error.map { Result<RawIspCapturedPhoto, Error>.failure($0) }
                ?? self.processed ?? .failure(CameraFailure("raw_no_data"))
            self.request = nil
            self.processed = nil
            request.callback(result)
        }
    }
}
