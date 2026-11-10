import AVFoundation
import SwiftUI

struct CameraScreen: View {
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.locale) private var locale
  @Environment(\.colorScheme) private var scheme
  @StateObject private var model = CameraViewModel()
  @State private var selection = false
  @State private var settings = false
  @State private var gallery: GalleryRoute?
  @State private var exposure = false
  @State private var zoomStart: Double?
  @State private var focusMarker: CGPoint?
  @State private var focusMarkerTask: Task<Void, Never>?
  @State private var deviceOrientation: UIDeviceOrientation = .unknown
  @AppStorage("capture_grid") private var grid = false
  @AppStorage("capture_timer") private var timer = 0
  private var p: CameraPalette { CameraPalette(dark: scheme == .dark) }
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      cameraPanel(landscape: landscape)
    }
    .onAppear {
      UIDevice.current.beginGeneratingDeviceOrientationNotifications()
      deviceOrientation = UIDevice.current.orientation
    }
    .onDisappear {
      UIDevice.current.endGeneratingDeviceOrientationNotifications()
      clearFocusMarker()
    }
    .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
      let orientation = UIDevice.current.orientation
      if orientation.isValidInterfaceOrientation { deviceOrientation = orientation }
    }
    .foregroundColor(p.ink).background(p.background.ignoresSafeArea()).buttonStyle(.plain)
    .fullScreenCover(isPresented: $selection) {
      if let film = model.film {
        FilmSelectionScreen(films: model.films, selected: film, apply: model.select)
          .environment(\.locale, locale)
      }
    }
    .fullScreenCover(item: $gallery, onDismiss: {
      model.refreshGalleryThumbnail()
      model.setActive(scenePhase == .active)
    }) { route in
      CaptureGalleryScreen(route: route, primaryLabel: L10n.text("film_stock"), secondaryLabel: L10n.text("print_paper"),
                           index: model.galleryIndex)
        .environment(\.locale, locale)
    }
    .fullScreenCover(isPresented: $settings) { FilmSettingsView().environment(\.locale, locale) }
    .sheet(isPresented: $exposure) {
      VStack(spacing: 24) {
        Text(L10n.text("exposure_compensation")).font(.headline)
        Text(String(format: "%+.1f EV", model.exposureEv)).font(.title)
        Slider(
          value: Binding(get: { Double(model.exposureEv) }, set: { model.setExposure(Float($0)) }),
          in: Double(model.minEv)...Double(model.maxEv), step: 0.1)
        Button(L10n.text("reset")) { model.setExposure(0) }
        Button(L10n.text("done")) { exposure = false }
        Spacer()
      }.padding(24).background(p.panel).foregroundColor(p.ink)
    }
    .task { model.prepare(); model.refreshGalleryThumbnail() }
    .onChange(of: scenePhase) { phase in model.setActive(phase == .active && gallery == nil) }
  }
  // The live camera supplies a portrait-oriented image. Rotate that image to match the device.
  private func previewAngle(landscape: Bool) -> Double {
    let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first { $0.windows.contains { $0.isKeyWindow } }
    let orientation = scene?.interfaceOrientation
    if landscape {
      if deviceOrientation.isLandscape,
         let deviceInterface = UIInterfaceOrientation(rawValue: deviceOrientation.rawValue) {
        return Double(CameraPanelRotation.angle(for: deviceInterface))
      }
      let landscapeOrientation = orientation.flatMap { $0.isLandscape ? $0 : nil } ?? .landscapeRight
      return Double(CameraPanelRotation.angle(for: landscapeOrientation))
    }
    return orientation == .portraitUpsideDown ? Double(CameraPanelRotation.angle(for: .portraitUpsideDown)) : 0
  }

  private func cameraPanel(landscape: Bool) -> some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        quick("arrow.triangle.2.circlepath.camera", L10n.text("switch_camera")) { model.switchFacing() }
          .disabled(!model.canSwitchFacing)
          .accessibilityHint(model.canSwitchFacing ? "" : L10n.text("opposite_no_raw"))
        Spacer()
        quick(model.torch ? "bolt.fill" : "bolt.slash", model.torch ? L10n.text("torch_on") : L10n.text("torch_off")) {
          model.setTorch(!model.torch)
        }.disabled(!model.hasTorch)
        quick(timer == 0 ? "timer" : "timer.circle.fill", timer == 0 ? L10n.text("timer_off") : L10n.text("timer_seconds", String(timer))) {
          timer = timer == 0 ? 3 : timer == 3 ? 10 : 0
        }
        quick(grid ? "square.grid.3x3.fill" : "square.grid.3x3", grid ? L10n.text("grid_on") : L10n.text("grid_off")) {
          grid.toggle()
        }
        quick("slider.horizontal.3", L10n.text("settings")) { settings = true }
      }.padding(.horizontal, 10).frame(height: 54).disabled(model.busy)
      GeometryReader { geometry in
        let size = fittedPreview(geometry.size, aspect: landscape ? 4.0 / 3.0 : 3.0 / 4.0)
        ZStack {
          Color.black
          viewfinder(size: size, landscape: landscape)
          if grid {
            ThirdsGrid().stroke(.white.opacity(0.35), lineWidth: 0.6).allowsHitTesting(false)
          }
          if model.countdown > 0 {
            Text("\(model.countdown)").font(.system(size: 72, weight: .light)).foregroundColor(
              .white)
          }
          VStack {
            if model.status != "single_raw" && model.countdown == 0 {
              Text(L10n.text(model.status)).font(.system(size: 12)).foregroundColor(.white).padding(8)
                .background(.black.opacity(0.45)).clipShape(RoundedRectangle(cornerRadius: 6))
                .padding(8)
            }
            Spacer()
          }.allowsHitTesting(false)
        }.frame(width: size.width, height: size.height).clipShape(RoundedRectangle(cornerRadius: 5))
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }.padding(.horizontal, 12)
        .gesture(MagnificationGesture().onChanged { scale in
          guard !model.busy else { return }
          if zoomStart == nil {
            clearFocusMarker()
            zoomStart = model.zoom
          }
          model.setZoom((zoomStart ?? 1) * Double(scale))
        }.onEnded { _ in zoomStart = nil })
      HStack {
        Spacer()
        ForEach(model.zoomStops, id: \.self) { value in
          Button {
            model.setZoom(value)
          } label: {
            Text(String(format: "%g×", value)).font(.system(size: 14)).frame(width: 42, height: 34)
              .background(abs(model.zoom - value) < 0.05 ? p.ink : .clear)
              .foregroundColor(abs(model.zoom - value) < 0.05 ? p.panel : p.muted).clipShape(
                Capsule())
          }.accessibilityLabel(model.zoomAccessibilityLabel(value))
        }
        Spacer()
        Button {
          exposure = true
        } label: {
          Text(String(format: "%+.1f EV", model.exposureEv)).font(.system(size: 12)).frame(
            width: 76, height: 44)
        }
        .accessibilityLabel(L10n.text("exposure_compensation")).disabled(!model.exposureSupported)
      }.padding(.horizontal, 12).frame(height: 44).disabled(model.busy)
      GeometryReader { geometry in
        let side = (geometry.size.width - 76) / 2
        HStack(spacing: 0) {
          ZStack {
            Image(systemName: "photo").foregroundColor(p.muted)
            CaptureResultButton(image: model.resultImage, openGallery: { openGallery(.grid) },
                                openLatest: { openGallery(.latest) })
          }.frame(width: 48, height: 48).clipped().clipShape(RoundedRectangle(cornerRadius: 7))
            .padding(3).overlay(RoundedRectangle(cornerRadius: 9).stroke(p.line, lineWidth: 1))
            .frame(width: side)
          Button {
            model.requestCapture(timer: timer)
          } label: {
            Circle().fill(p.ink).padding(6).overlay(Circle().stroke(p.ink, lineWidth: 1)).frame(
              width: 76, height: 76)
          }.disabled(!model.canCapture && model.countdown == 0)
            .accessibilityLabel(model.countdown > 0 ? L10n.text("cancel_timer") : L10n.text("take_raw"))
          Button {
            selection = true
          } label: {
            if let film = model.film {
              CameraEntry(film: film).frame(width: min(132, side - 10))
            }
          }.frame(width: side).disabled(model.busy || model.film == nil).accessibilityLabel(
            L10n.text("choose_current_film", model.film?.title ?? ""))
        }.frame(height: 96)
      }.padding(.horizontal, 8).frame(height: 96).padding(.bottom, 12)
    }
  }

  @ViewBuilder
  private func viewfinder(size: CGSize, landscape: Bool) -> some View {
#if targetEnvironment(simulator)
    if model.usesSampleViewfinder, let film = model.film, let root = Bundle.main.resourceURL,
       let image = UIImage(contentsOfFile: root.appendingPathComponent(film.sampleAssetPath).path) {
      Image(uiImage: image).resizable().scaledToFill()
        .frame(width: size.width, height: size.height).clipped()
        .accessibilityLabel(L10n.text("sample_description", film.title))
    } else {
      rotatedLiveViewfinder(size: size, landscape: landscape)
    }
#else
    rotatedLiveViewfinder(size: size, landscape: landscape)
#endif
  }

  private func rotatedLiveViewfinder(size: CGSize, landscape: Bool) -> some View {
    ZStack {
      liveViewfinder
      if let focusMarker {
        RoundedRectangle(cornerRadius: 4)
          .strokeBorder(Color.yellow, lineWidth: 1.5)
          .frame(width: 52, height: 52)
          .position(focusMarker)
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
    }
    .frame(width: landscape ? size.height : size.width,
           height: landscape ? size.width : size.height)
    .rotationEffect(.degrees(previewAngle(landscape: landscape)))
    .frame(width: size.width, height: size.height)
  }

  @ViewBuilder
  private var liveViewfinder: some View {
    if model.previewRenderer.available {
      RawIspLutPreview(renderer: model.previewRenderer,
                       active: scenePhase == .active && !settings && gallery == nil,
                       onFocus: focus)
    } else {
      CameraPreview(session: model.session, onFocus: focus)
    }
  }

  private func focus(_ point: CGPoint, _ target: CGPoint) {
    guard scenePhase == .active, zoomStart == nil, model.focus(at: target) else { return }
    focusMarkerTask?.cancel()
    focusMarker = point
    focusMarkerTask = Task {
      do { try await Task.sleep(nanoseconds: 1_250_000_000) } catch { return }
      withAnimation(.easeOut(duration: 0.2)) { focusMarker = nil }
      focusMarkerTask = nil
    }
  }

  private func clearFocusMarker() {
    focusMarkerTask?.cancel()
    focusMarkerTask = nil
    focusMarker = nil
  }

  private func openGallery(_ route: GalleryRoute) {
    guard !model.busy else { return }
    model.setActive(false)
    gallery = route
  }
  private func quick(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Image(systemName: icon).font(.system(size: 18)).frame(width: 44, height: 44)
    }.accessibilityLabel(label)
  }
  private func fittedPreview(_ available: CGSize, aspect: CGFloat) -> CGSize {
    let width = min(available.width, available.height * aspect)
    return CGSize(width: max(1, width), height: max(1, width / aspect))
  }
}
enum CameraPanelRotation {
  static func angle(for orientation: UIInterfaceOrientation) -> Int32 {
    switch orientation {
    case .landscapeLeft: return 90
    case .landscapeRight: return -90
    case .portraitUpsideDown: return 180
    default: return 0
    }
  }

  static func jpegDegrees(for orientation: UIInterfaceOrientation) -> Int32 {
    // The sensor image needs 90° in portrait, plus the same turn as the live preview.
    (90 + angle(for: orientation)) % 360
  }
}

private struct ThirdsGrid: Shape {
  func path(in rect: CGRect) -> Path {
    var p = Path()
    for i in 1...2 {
      let x = rect.width * CGFloat(i) / 3
      let y = rect.height * CGFloat(i) / 3
      p.move(to: CGPoint(x: x, y: 0))
      p.addLine(to: CGPoint(x: x, y: rect.height))
      p.move(to: CGPoint(x: 0, y: y))
      p.addLine(to: CGPoint(x: rect.width, y: y))
    }
    return p
  }
}
