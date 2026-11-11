import AVFoundation
import OSLog
import SwiftUI
import UIKit

@main
struct SpektraCamApp: App {
    @AppStorage(L10n.preferenceKey) private var language = "system"
    @State private var systemLocale = L10n.locale(for: "system")

    var body: some Scene {
        WindowGroup {
            CameraScreen()
                .environment(\.locale, language == "system" ? systemLocale : L10n.locale(for: language))
                .onReceive(NotificationCenter.default.publisher(for: NSLocale.currentLocaleDidChangeNotification)) { _ in
                    systemLocale = L10n.locale(for: "system")
                }
        }
    }
}

enum FilmMeter: String, CaseIterable, Identifiable {
    case histogramMiddleGray = "histogram_middle_gray"
    case centerWeighted = "center_weighted"
    case matrix
    case multiZone = "multi_zone"
    case partial
    case highlightWeighted = "highlight_weighted"
    case median
    case average

    var id: String { rawValue }
    var title: String {
        switch self {
        case .histogramMiddleGray: return L10n.text("histogram_gray")
        case .centerWeighted: return L10n.text("center_weighted")
        case .matrix: return L10n.text("matrix_meter")
        case .multiZone: return L10n.text("multi_zone")
        case .partial: return L10n.text("partial_meter")
        case .highlightWeighted: return L10n.text("highlight_weighted")
        case .median: return L10n.text("median_meter")
        case .average: return L10n.text("average_meter")
        }
    }
}

@MainActor
final class CameraViewModel: ObservableObject {
    private static let captureLog = Logger(subsystem: "org.spektracam.app", category: "CaptureTiming")
    let previewRenderer = RawIspLutPreviewRenderer()
    let galleryIndex = CaptureGalleryIndex()
    private let capture = CameraCapture()
    private let development = DispatchQueue(label: "spektracam.develop", qos: .userInitiated)
    private var prepared = false
    private var preparedRawPipelines = false
    @Published private var active = true
    @Published private var ready = false
    @Published private var sessionRunning = false
    private var runningRequest = 0
    private var position = AVCaptureDevice.Position.back
    private var selectedLens: CameraLens?
    private var lenses: [CameraLens] = []
    private var countdownTask: Task<Void, Never>?

    @Published private(set) var films: [RawIspFilmLook] = []
    @Published private(set) var film: RawIspFilmLook?
    @Published private(set) var status = "starting_camera"
    @Published private(set) var busy = false
    @Published private(set) var countdown = 0
    @Published private(set) var zoom = 1.0
    @Published private(set) var exposureEv: Float = 0
    @Published private(set) var minEv: Float = 0
    @Published private(set) var maxEv: Float = 0
    @Published private(set) var hasTorch = false
    @Published private(set) var torch = false
    @Published private(set) var resultImage: UIImage?
    private var currentMeter: FilmMeter {
        FilmMeter(rawValue: UserDefaults.standard.string(forKey: "film_exposure_method") ?? "") ?? .histogramMiddleGray
    }
#if targetEnvironment(simulator)
    var usesSampleViewfinder: Bool {
        ProcessInfo.processInfo.arguments.contains("--sample-viewfinder")
    }
#endif
    var session: AVCaptureSession { capture.session }
    private var facingLenses: [CameraLens] {
        lenses.filter { $0.device.position == position }.sorted { $0.zoom < $1.zoom }
    }
    var canSwitchFacing: Bool { ready && !busy && lenses.contains { $0.device.position != position } }
    var exposureSupported: Bool { minEv < maxEv }
    var canCapture: Bool { active && ready && sessionRunning && film != nil && !busy }
    var zoomStops: [Double] {
        var stops = Set(facingLenses.map(\.zoom))
        if facingLenses.contains(where: { $0.zoom == 1 }) { stops.insert(2) }
        return stops.sorted()
    }

    func prepare() {
        guard !prepared else { return }
        prepared = true
        do {
            guard let root = Bundle.main.resourceURL else { throw CameraFailure("film_catalog_unavailable") }
            films = try RawIspFilmLook.load(root: root)
            let selection = UserDefaults.standard.string(forKey: "selected_film") ?? "kodak_portra_400"
            guard let selected = films.first(where: { $0.id == selection }) ?? films.first else {
                throw CameraFailure("film_catalog_empty")
            }
            select(selected.selectingPaper(UserDefaults.standard.string(forKey: "selected_print")))
        } catch { status = Self.errorKey(error, fallback: "film_catalog_read_error"); return }
#if targetEnvironment(simulator)
        if usesSampleViewfinder {
            status = "sample_viewfinder"
            return
        }
#endif
        previewRenderer.onError = { [weak self] message in
            NSLog("[SpektraCam] Live preview failed: %@", message)
            Task { @MainActor in self?.status = "preview_failed" }
        }
        AVCaptureDevice.requestAccess(for: .video) { allowed in
            Task { @MainActor in
                if allowed { self.discoverCameras() }
                else { self.status = "camera_permission" }
            }
        }
    }

    private func discoverCameras() {
        busy = true
        capture.discoverLenses { lenses in
            Task { @MainActor in
                self.lenses = lenses
                let preferred = lenses.first { $0.device.position == .back && $0.zoom == 1 }
                    ?? lenses.first { $0.device.position == .back } ?? lenses.first
                if let preferred {
                    self.configure(preferred, zoom: preferred.zoom)
                } else {
                    self.busy = false
                    self.status = "no_raw_lens"
                }
            }
        }
    }

    private func configure(_ next: CameraLens, zoom: Double) {
        busy = true
        status = "switching_lens"
        capture.configure(next, renderer: previewRenderer) { result in
            Task { @MainActor in
                self.busy = false
                switch result {
                case .success(let device):
                    self.ready = true
                    self.position = device.position
                    self.selectedLens = next
                    self.minEv = device.minExposureTargetBias
                    self.maxEv = device.maxExposureTargetBias
                    self.exposureEv = device.exposureTargetBias
                    self.hasTorch = device.hasTorch
                    self.torch = false
                    self.applyZoom(zoom, on: next)
                    self.updateSessionRunning()
                    self.status = "single_raw"
                case .failure(let error):
                    self.status = Self.errorKey(error, fallback: "raw_camera_failed")
                }
            }
        }
    }

    func switchFacing() {
        guard canSwitchFacing else { return }
        let opposite = lenses.filter { $0.device.position != position }
        if let target = opposite.first(where: { $0.zoom == 1 }) ?? opposite.first {
            configure(target, zoom: target.zoom)
        }
    }

    func setActive(_ value: Bool) {
        active = value
        if !value { cancelCountdown(); torch = false }
        updateSessionRunning()
    }

    private func updateSessionRunning() {
        runningRequest += 1
        let request = runningRequest
        if !active { sessionRunning = false }
        capture.setRunning(active) { running in
            Task { @MainActor in
                guard self.runningRequest == request else { return }
                // Compile the Bayer shaders before the first shot. A speculative
                // film render would compete with the live preview for the GPU.
                if running && self.active && !self.preparedRawPipelines {
                    self.preparedRawPipelines = true
                    self.development.async { _ = sc_prepare_raw() }
                }
                self.sessionRunning = running
            }
        }
    }

    func select(_ value: RawIspFilmLook) {
        film = value
        UserDefaults.standard.set(value.id, forKey: "selected_film")
        UserDefaults.standard.set(value.paper, forKey: "selected_print")
        previewRenderer.setFilm(value)
    }

    func setZoom(_ value: Double) {
        let options = facingLenses
        guard !busy, value.isFinite, let first = options.first, let last = options.last else { return }
        let clamped = min(last.zoom * 4, max(first.zoom, value))
        let lens = options.last(where: { $0.zoom <= clamped }) ?? first
        if lens.device.uniqueID == selectedLens?.device.uniqueID {
            applyZoom(clamped, on: lens)
        } else {
            configure(lens, zoom: clamped)
        }
    }

    private func applyZoom(_ value: Double, on lens: CameraLens) {
        zoom = value
        previewRenderer.setZoom(max(1, value / lens.zoom))
    }

    func zoomAccessibilityLabel(_ value: Double) -> String {
        let factor = String(format: "%g×", value)
        if let lens = facingLenses.first(where: { abs($0.zoom - value) < 0.01 }) {
            return "\(lens.title) \(factor)"
        }
        return L10n.text("digital_crop", factor)
    }

    func focus(at point: CGPoint) -> Bool {
        guard active, ready, !busy, let device = selectedLens?.device,
              device.isFocusPointOfInterestSupported else { return false }
        let mode: AVCaptureDevice.FocusMode = device.isFocusModeSupported(.continuousAutoFocus)
            ? .continuousAutoFocus : .autoFocus
        guard device.isFocusModeSupported(mode) else { return false }
        capture.adjust({ current in
            guard current === device else { return }
            current.focusPointOfInterest = point
            current.focusMode = mode
        }, completion: reportAdjustment)
        return true
    }

    func setExposure(_ value: Float) {
        guard exposureSupported, value.isFinite else { return }
        let ev = min(maxEv, max(minEv, value))
        exposureEv = ev
        capture.adjust({ $0.setExposureTargetBias(ev, completionHandler: nil) }, completion: reportAdjustment)
    }

    func setTorch(_ value: Bool) {
        capture.adjust({ device in
            if value { try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel) }
            else { device.torchMode = .off }
        }) { result in
            Task { @MainActor in
                switch result {
                case .success: self.torch = value
                case .failure(let error): self.status = Self.errorKey(error, fallback: "raw_camera_failed")
                }
            }
        }
    }

    private func reportAdjustment(_ result: Result<Void, Error>) {
        if case .failure(let error) = result {
            Task { @MainActor in self.status = Self.errorKey(error, fallback: "raw_camera_failed") }
        }
    }

    func requestCapture(timer: Int) {
        if countdown > 0 { cancelCountdown(); return }
        guard canCapture else { return }
        if timer <= 0 { takePhoto(); return }
        countdown = timer
        busy = true
        countdownTask = Task {
            while countdown > 0 {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
                countdown -= 1
            }
            countdownTask = nil
            busy = false
            takePhoto()
        }
    }

    private func cancelCountdown() {
        guard countdown > 0 else { return }
        countdownTask?.cancel()
        countdownTask = nil
        countdown = 0
        busy = false
    }

    private func takePhoto() {
        guard canCapture, let film else { return }
        let meter = currentMeter.rawValue
        let crop = max(1, zoom / (selectedLens?.zoom ?? 1))
        let orientation = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first
        let rotation = CameraPanelRotation.jpegDegrees(for: orientation ?? .portrait)
        busy = true
        status = "developing_raw"
        let shutter = ProcessInfo.processInfo.systemUptime
        capture.capture(rotation: rotation) { result in
            let captured = ProcessInfo.processInfo.systemUptime
            self.development.async {
                do {
                    let raw = try result.get()
                    let (image, nativeTime) = try FilmProcessor.render(raw, film: film, meter: meter, zoom: crop)
                    let rendered = ProcessInfo.processInfo.systemUptime
                    guard let jpeg = image.jpegData(compressionQuality: 0.96) else {
                        throw CameraFailure("encode_jpeg")
                    }
                    let photo = try CaptureArchive.save(jpeg, primary: film.title, secondary: film.paper.isEmpty ? "direct_scan" : film.finishTitle)
                    let preview = CaptureArchive.downsample(image, pixels: 1280)
                    let thumbnail = CaptureArchive.centerSquare(preview).map { CaptureArchive.downsample($0, pixels: 320) }
                    let dngTime = raw.dngEncodingTime
                    Task { @MainActor in
                        self.galleryIndex.recordCapture(photo, detailPreview: preview)
                        if let thumbnail { self.resultImage = thumbnail }
                        self.busy = false
                        self.status = "single_raw"
                        let visible = ProcessInfo.processInfo.systemUptime
                        func ms(_ time: TimeInterval) -> Int { Int((time * 1_000).rounded()) }
                        Self.captureLog.notice("Capture ms: camera=\(ms(captured - shutter)), DNG=\(ms(dngTime)) (included in camera), development=\(ms(rendered - captured)), native=\(ms(nativeTime)) (included in development), JPEG/archive/previews=\(ms(visible - rendered)), ready=\(ms(visible - shutter))")
                        self.exportToSystemPhotos(photo)
                    }
                } catch {
                    Task { @MainActor in
                        self.busy = false
                        self.status = Self.errorKey(error, fallback: "develop_failed")
                    }
                }
            }
        }
    }

    private func exportToSystemPhotos(_ photo: GalleryPhoto) {
        Task.detached(priority: .utility) { [weak self] in
            let (entry, message) = await CaptureArchive.copyToSystemPhotos(photo)
            await self?.completeSystemCopy(entry, message: message)
        }
    }

    private static func errorKey(_ error: Error, fallback: String) -> String {
        if let failure = error as? CameraFailure { return failure.key }
        NSLog("[SpektraCam] %@", error.localizedDescription)
        return fallback
    }

    private func completeSystemCopy(_ photo: GalleryPhoto, message: String) {
        galleryIndex.completeSystemCopy(photo)
        guard !busy, galleryIndex.photos.first?.id == photo.id, status == "single_raw" else { return }
        status = message
        if message == "photo_saved" {
            Task {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if status == message, galleryIndex.photos.first?.id == photo.id { status = "single_raw" }
            }
        }
    }

    func refreshGalleryThumbnail() {
        Task {
            if !galleryIndex.loaded { await galleryIndex.refresh() }
            guard let photo = galleryIndex.photos.first else {
                if !busy && galleryIndex.loaded { resultImage = nil }
                return
            }
            let image = await Task.detached(priority: .utility) {
                CaptureArchive.thumbnail(photo, pixels: 320, square: true)
            }.value
            if !busy && galleryIndex.photos.first?.id == photo.id { resultImage = image }
            await galleryIndex.warmLatestDetail()
        }
    }
}

final class PreviewFocusTap: NSObject {
    var action: ((CGPoint, CGRect) -> Void)?

    func install(on view: UIView) {
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
    }

    @objc private func tapped(_ tap: UITapGestureRecognizer) {
        guard let view = tap.view else { return }
        action?(tap.location(in: view), view.bounds)
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let onFocus: (CGPoint, CGPoint) -> Void

    func makeCoordinator() -> PreviewFocusTap { PreviewFocusTap() }

    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface()
        view.preview.session = session
        view.preview.videoGravity = .resizeAspect
        context.coordinator.install(on: view)
        return view
    }
    func updateUIView(_ view: PreviewSurface, context: Context) {
        if let connection = view.preview.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        context.coordinator.action = { [weak view] point, _ in
            guard let view, view.preview.connection?.isActive == true else { return }
            let target = view.preview.captureDevicePointConverted(fromLayerPoint: point)
            guard (0...1).contains(target.x), (0...1).contains(target.y) else { return }
            onFocus(point, target)
        }
    }
}

final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
