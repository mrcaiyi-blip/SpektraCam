import ImageIO
import Photos
import SwiftUI
import UIKit

struct GalleryPhoto: Codable, Identifiable, Sendable {
    let id: UUID
    let date: Date
    let primary: String
    let secondary: String
    var photosIdentifier: String?
    var systemOnly: Bool?

    var folder: URL { CaptureArchive.root.appendingPathComponent(id.uuidString, isDirectory: true) }
    var imageURL: URL { folder.appendingPathComponent("photo.jpg") }
}

enum CaptureArchive {
    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CaptureGallery", isDirectory: true)
    }

    static func save(_ jpeg: Data, primary: String, secondary: String) throws -> GalleryPhoto {
        let photo = GalleryPhoto(id: UUID(), date: Date(), primary: primary, secondary: secondary)
        let manager = FileManager.default
        try manager.createDirectory(at: root, withIntermediateDirectories: true)
        let staging = root.appendingPathComponent(".\(photo.id.uuidString)", isDirectory: true)
        try manager.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? manager.removeItem(at: staging) }
        try jpeg.write(to: staging.appendingPathComponent("photo.jpg"))
        try JSONEncoder().encode(photo).write(to: staging.appendingPathComponent("capture.json"))
        try manager.moveItem(at: staging, to: photo.folder)
        return photo
    }

    static func associate(_ photo: GalleryPhoto, with identifier: String) throws {
        var updated = photo
        updated.photosIdentifier = identifier
        try JSONEncoder().encode(updated).write(to: photo.folder.appendingPathComponent("capture.json"), options: .atomic)
    }

    static func photos(legacyAlbum: String? = nil) -> [GalleryPhoto] {
        let folders = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil,
                                                                     options: .skipsHiddenFiles)) ?? []
        var captures: [GalleryPhoto] = folders.compactMap { folder in
            guard let data = try? Data(contentsOf: folder.appendingPathComponent("capture.json")),
                  let photo = try? JSONDecoder().decode(GalleryPhoto.self, from: data),
                  photo.id.uuidString == folder.lastPathComponent,
                  FileManager.default.fileExists(atPath: photo.imageURL.path) else { return nil }
            return photo
        }
        if let legacyAlbum {
            let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if access == .authorized || access == .limited {
                let options = PHFetchOptions()
                options.predicate = NSPredicate(format: "localizedTitle = %@", legacyAlbum)
                if let album = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject {
                    var seen = Set(captures.compactMap(\.photosIdentifier))
                    seen.formUnion(UserDefaults.standard.stringArray(forKey: "gallery_hidden_system_photos") ?? [])
                    PHAsset.fetchAssets(in: album, options: nil).enumerateObjects { asset, _, _ in
                        guard asset.mediaType == .image, seen.insert(asset.localIdentifier).inserted else { return }
                        captures.append(GalleryPhoto(id: UUID(), date: asset.creationDate ?? .distantPast,
                            primary: "", secondary: "", photosIdentifier: asset.localIdentifier, systemOnly: true))
                    }
                }
            }
        }
        return captures.sorted { $0.date > $1.date }
    }

    private static func systemAsset(_ photo: GalleryPhoto) -> PHAsset? {
        guard let identifier = photo.photosIdentifier else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    static func thumbnail(_ photo: GalleryPhoto, pixels: Int, square: Bool = false) -> UIImage? {
        if photo.systemOnly == true {
            guard let asset = systemAsset(photo) else { return nil }
            let options = PHImageRequestOptions()
            options.isSynchronous = true
            options.deliveryMode = .highQualityFormat
            options.resizeMode = .exact
            var result: UIImage?
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels),
                                                 contentMode: square ? .aspectFill : .aspectFit, options: options) { image, _ in result = image }
            return square ? result.flatMap(centerSquare) : result
        }
        guard let source = CGImageSourceCreateWithURL(photo.imageURL as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels
              ] as CFDictionary) else { return nil }
        let thumbnail = UIImage(cgImage: image)
        return square ? centerSquare(thumbnail) : thumbnail
    }

    static func centerSquare(_ image: UIImage) -> UIImage? {
        if image.imageOrientation == .up, let raster = image.cgImage {
            let side = min(raster.width, raster.height)
            if raster.width == raster.height { return image }
            return raster.cropping(to: CGRect(x: (raster.width - side) / 2,
                                               y: (raster.height - side) / 2,
                                               width: side, height: side))
                .map { UIImage(cgImage: $0, scale: image.scale, orientation: .up) }
        }
        let side = min(image.size.width, image.size.height)
        guard side > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            image.draw(at: CGPoint(x: (side - image.size.width) / 2, y: (side - image.size.height) / 2))
        }
    }

    static func shareURL(_ photo: GalleryPhoto) async throws -> URL {
        guard photo.systemOnly == true else { return photo.imageURL }
        guard let asset = systemAsset(photo),
              let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .photo }) else {
            throw NSError(domain: "CaptureGallery", code: 3, userInfo: [NSLocalizedDescriptionKey: L10n.text("read_original_photo")])
        }
        let fileExtension = URL(fileURLWithPath: resource.originalFilename).pathExtension
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("gallery-share-" + UUID().uuidString)
            .appendingPathExtension(fileExtension.isEmpty ? "jpg" : fileExtension)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        do {
            try await PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options)
            return url
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }

    static func delete(_ photo: GalleryPhoto, includingSystemCopy: Bool) async throws {
        if includingSystemCopy, let id = photo.photosIdentifier {
            let access = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            guard access == .authorized || access == .limited else {
                throw NSError(domain: "CaptureGallery", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: L10n.text("photo_delete_permission")
                ])
            }
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
            if assets.count > 0 {
                try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
            } else if access == .limited {
                throw NSError(domain: "CaptureGallery", code: 2, userInfo: [
                    NSLocalizedDescriptionKey: L10n.text("photo_access_limited")
                ])
            }
        }
        guard photo.systemOnly != true else { return }
        try FileManager.default.removeItem(at: photo.folder)
        if !includingSystemCopy, let identifier = photo.photosIdentifier {
            var hidden = UserDefaults.standard.stringArray(forKey: "gallery_hidden_system_photos") ?? []
            hidden.append(identifier)
            UserDefaults.standard.set(hidden, forKey: "gallery_hidden_system_photos")
        }
    }
}

enum GalleryRoute: Identifiable {
    case grid
    case latest
    var id: Int { self == .grid ? 0 : 1 }
}

struct CaptureResultButton: UIViewRepresentable {
    @Environment(\.locale) private var locale
    let image: UIImage?
    let openGallery: () -> Void
    let openLatest: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> ResultImageView {
        let view = ResultImageView()
        view.activate = openGallery
        view.clipsToBounds = true
        view.isUserInteractionEnabled = true
        view.isAccessibilityElement = true
        view.accessibilityTraits = .button
        view.accessibilityLabel = L10n.text("open_gallery")
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap))
        let hold = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.hold(_:)))
        tap.require(toFail: hold)
        view.addGestureRecognizer(tap)
        view.addGestureRecognizer(hold)
        view.accessibilityCustomActions = [
            UIAccessibilityCustomAction(name: L10n.text("open_latest"), actionHandler: { [weak coordinator = context.coordinator] _ in
                coordinator?.tapLatest()
                return coordinator != nil
            })
        ]
        return view
    }

    func updateUIView(_ view: ResultImageView, context: Context) {
        context.coordinator.parent = self
        view.activate = openGallery
        view.accessibilityLanguage = locale.identifier
        view.accessibilityLabel = L10n.text("open_gallery")
        view.accessibilityCustomActions?.first?.name = L10n.text("open_latest")
        view.picture.image = image
    }

    final class ResultImageView: UIView {
        let picture = UIImageView()

        override init(frame: CGRect) {
            super.init(frame: frame)
            picture.contentMode = .scaleAspectFill
            picture.clipsToBounds = true
            addSubview(picture)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func layoutSubviews() {
            super.layoutSubviews()
            picture.frame = bounds
        }

        var activate: (() -> Void)?
        override func accessibilityActivate() -> Bool {
            activate?()
            return activate != nil
        }
    }

    final class Coordinator: NSObject {
        var parent: CaptureResultButton
        init(_ parent: CaptureResultButton) { self.parent = parent }
        @objc func tap() { parent.openGallery() }
        @objc func hold(_ gesture: UILongPressGestureRecognizer) {
            if gesture.state == .began { tapLatest() }
        }
        func tapLatest() { parent.openLatest() }
    }
}

struct CaptureGalleryScreen: View {
    let route: GalleryRoute
    let primaryLabel: String
    let secondaryLabel: String
    var legacyAlbum: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var photos: [GalleryPhoto] = []
    @State private var selected: GalleryPhoto?
    @State private var ready = false
    @State private var showTools = true
    @State private var sharedPhoto: SharedGalleryPhoto?
    @State private var temporaryShareURL: URL?
    @State private var sharing = false
    @State private var confirmDelete = false
    @State private var deleting = false
    @State private var error: String?
    @State private var errorTitle = L10n.text("gallery_error")

    var body: some View {
        ZStack {
            if route == .grid || selected == nil {
                grid.opacity(selected == nil ? 1 : 0)
                    .allowsHitTesting(selected == nil)
                    .accessibilityHidden(selected != nil)
            }
            if let photo = selected { detail(photo) }
        }
        .background(Color.black.ignoresSafeArea())
        .foregroundColor(.white)
        .preferredColorScheme(.dark)
        .statusBarHidden(selected != nil && !showTools)
        .task {
            let entries = await Task.detached(priority: .userInitiated) { CaptureArchive.photos(legacyAlbum: legacyAlbum) }.value
            photos = entries
            if route == .latest { selected = entries.first }
            ready = true
        }
        .sheet(item: $sharedPhoto, onDismiss: clearTemporaryShare) { photo in
            GalleryShare(url: photo.url)
        }
        .confirmationDialog(L10n.text("delete_photo_question"), isPresented: $confirmDelete, titleVisibility: .visible) {
            if let photo = selected {
                if photo.photosIdentifier != nil {
                    Button(photo.systemOnly == true ? L10n.text("delete_system_photo") : L10n.text("delete_both"),
                           role: .destructive) { remove(photo, system: true) }
                }
                if photo.systemOnly != true {
                    Button(L10n.text("delete_app_photo"), role: .destructive) { remove(photo, system: false) }
                }
            }
            Button(L10n.text("cancel"), role: .cancel) {}
        }
        .alert(errorTitle, isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button(L10n.text("ok")) { error = nil }
        } message: { Text(error ?? "") }
    }

    private var grid: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                VStack(spacing: 8) {
                    HStack {
                        tool("chevron.left", L10n.text("back_to_camera")) { dismiss() }
                        Spacer()
                        Text(L10n.text("gallery")).font(.headline)
                        Spacer()
                        Color.clear.frame(width: 44, height: 44)
                    }.padding(.horizontal, 12)
                    if !ready { Spacer(); ProgressView(); Spacer() }
                    else if photos.isEmpty { Spacer(); Text(L10n.text("no_photos")).foregroundColor(.secondary); Spacer() }
                    else {
                        ScrollView {
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 3), spacing: 3) {
                                ForEach(photos) { photo in
                                    Button {
                                        showTools = true
                                        selected = photo
                                    } label: {
                                        GalleryTile(photo: photo, side: max(1, (geometry.size.width - 6) / 3))
                                    }.buttonStyle(.plain)
                                    .accessibilityLabel(photo.primary.isEmpty ? L10n.text("view_photo") : photo.primary + ", " + L10n.photoLabel(photo.secondary))
                                }
                            }.padding(.bottom, 100)
                        }
                    }
                }
                Button { dismiss() } label: {
                    Circle().fill(.white).padding(6)
                        .overlay(Circle().strokeBorder(.white, lineWidth: 1))
                        .frame(width: 64, height: 64).background(.black.opacity(0.65), in: Circle())
                }.buttonStyle(.plain).accessibilityLabel(L10n.text("back_to_camera"))
                    .position(x: geometry.size.width / 2, y: geometry.size.height * 5 / 6)
            }
        }
    }

    private func detail(_ photo: GalleryPhoto) -> some View {
        ZStack {
            GalleryZoomImage(photo: photo, previous: neighbor(of: photo, offset: -1),
                             next: neighbor(of: photo, offset: 1),
                             toggleTools: { showTools.toggle() }, swipeBack: {
                if !deleting && !sharing { closeDetail() }
            }, stepPhoto: stepPhoto).ignoresSafeArea()
            if showTools {
                VStack(spacing: 0) {
                    HStack {
                        tool("chevron.left", route == .latest ? L10n.text("back_to_camera") : L10n.text("back_to_gallery")) { closeDetail() }
                        Spacer()
                        tool("square.and.arrow.up", L10n.text("share_photo")) { share(photo) }
                        tool("trash", L10n.text("delete_photo")) { confirmDelete = true }
                    }.padding(.horizontal, 12).background(.black.opacity(0.7))
                    Spacer()
                    VStack(alignment: .leading, spacing: 5) {
                        Text(primaryLabel + " · " + L10n.photoLabel(photo.primary))
                        Text(secondaryLabel + " · " + L10n.photoLabel(photo.secondary))
                    }.font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16).background(.black.opacity(0.7))
                }.disabled(deleting || sharing)
            }
            if deleting || sharing { ProgressView().padding(20).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12)) }
        }
    }

    private func tool(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(label)
    }

    private func closeDetail() {
        if route == .latest { dismiss() } else { selected = nil }
    }

    private func neighbor(of photo: GalleryPhoto, offset: Int) -> GalleryPhoto? {
        guard let current = photos.firstIndex(where: { $0.id == photo.id }),
              photos.indices.contains(current + offset) else { return nil }
        return photos[current + offset]
    }

    private func stepPhoto(_ offset: Int) -> Bool {
        guard !deleting, !sharing, let selected,
              let photo = neighbor(of: selected, offset: offset) else { return false }
        self.selected = photo
        return true
    }

    private func share(_ photo: GalleryPhoto) {
        sharing = true
        Task {
            defer { sharing = false }
            do {
                let url = try await CaptureArchive.shareURL(photo)
                if photo.systemOnly == true { temporaryShareURL = url }
                sharedPhoto = SharedGalleryPhoto(url: url)
            } catch {
                errorTitle = L10n.text("share_failed")
                self.error = error.localizedDescription
            }
        }
    }

    private func clearTemporaryShare() {
        if let url = temporaryShareURL { try? FileManager.default.removeItem(at: url) }
        temporaryShareURL = nil
    }

    private func remove(_ photo: GalleryPhoto, system: Bool) {
        deleting = true
        Task {
            defer { deleting = false }
            do {
                try await CaptureArchive.delete(photo, includingSystemCopy: system)
                photos.removeAll { $0.id == photo.id }
                closeDetail()
            } catch {
                errorTitle = L10n.text("delete_failed")
                self.error = error.localizedDescription
            }
        }
    }
}

private struct GalleryTile: View {
    let photo: GalleryPhoto
    let side: CGFloat
    @State private var thumbnail: UIImage?
    var body: some View {
        Color.white.opacity(0.12)
            .overlay {
                if let thumbnail { Image(uiImage: thumbnail).resizable().scaledToFill() }
            }
            .frame(width: side, height: side).clipped()
            .task(id: photo.id) {
                let image = await Task.detached(priority: .utility) { CaptureArchive.thumbnail(photo, pixels: 480) }.value
                if !Task.isCancelled { thumbnail = image }
            }
    }
}

private struct SharedGalleryPhoto: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct GalleryShare: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ view: UIActivityViewController, context: Context) {}
}

private struct GalleryZoomImage: UIViewRepresentable {
    let photo: GalleryPhoto
    let previous: GalleryPhoto?
    let next: GalleryPhoto?
    let toggleTools: () -> Void
    let swipeBack: () -> Void
    let stepPhoto: (Int) -> Bool

    func makeUIView(context: Context) -> ZoomSurface { ZoomSurface() }

    func updateUIView(_ view: ZoomSurface, context: Context) {
        view.toggleTools = toggleTools
        view.swipeBack = swipeBack
        view.stepPhoto = stepPhoto
        view.setPages(current: photo, previous: previous, next: next)
    }

    static func dismantleUIView(_ view: ZoomSurface, coordinator: ()) {
        view.cancelLoads()
    }

    final class ZoomSurface: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        private let picture = UIImageView()
        private let previousPicture = UIImageView()
        private let nextPicture = UIImageView()
        private let loading = UILabel()
        private var current: GalleryPhoto?
        private var previous: GalleryPhoto?
        private var nextPage: GalleryPhoto?
        private var previews: [UUID: UIImage] = [:]
        private var unavailable: Set<UUID> = []
        private var loads: [UUID: Task<Void, Never>] = [:]
        private var fullSizeLoad: Task<Void, Never>?
        private var settling = false
        var toggleTools: (() -> Void)?
        var swipeBack: (() -> Void)?
        var stepPhoto: ((Int) -> Bool)?
        private enum SwipeAxis { case horizontal, down }
        private var swipeAxis: SwipeAxis?
        private var swipeOffset = CGPoint.zero
        private var fittedBounds: CGSize = .zero

        init() {
            super.init(frame: .zero)
            backgroundColor = .black
            delegate = self
            contentInsetAdjustmentBehavior = .never
            showsVerticalScrollIndicator = false
            showsHorizontalScrollIndicator = false
            minimumZoomScale = 1
            maximumZoomScale = 5
            clipsToBounds = true
            for neighbor in [previousPicture, nextPicture] {
                neighbor.contentMode = .scaleAspectFit
                addSubview(neighbor)
            }
            picture.contentMode = .scaleToFill
            addSubview(picture)
            loading.textColor = .white
            loading.textAlignment = .center
            addSubview(loading)
            let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
            let doubleTap = UITapGestureRecognizer(target: self, action: #selector(zoomTapped(_:)))
            doubleTap.numberOfTapsRequired = 2
            tap.require(toFail: doubleTap)
            addGestureRecognizer(tap)
            addGestureRecognizer(doubleTap)
            let swipe = UIPanGestureRecognizer(target: self, action: #selector(swiped(_:)))
            swipe.maximumNumberOfTouches = 1
            swipe.delegate = self
            panGestureRecognizer.require(toFail: swipe)
            addGestureRecognizer(swipe)
        }

        required init?(coder: NSCoder) { nil }

        func setPages(current: GalleryPhoto, previous: GalleryPhoto?, next: GalleryPhoto?) {
            let changedPage = self.current?.id != current.id
            self.current = current
            self.previous = previous
            self.nextPage = next
            let pages = [current, previous, next].compactMap { $0 }
            let ids = Set(pages.map(\.id))
            previews = previews.filter { ids.contains($0.key) }
            unavailable.formIntersection(ids)
            for id in Array(loads.keys) where !ids.contains(id) {
                loads.removeValue(forKey: id)?.cancel()
            }
            if changedPage {
                fullSizeLoad?.cancel()
                fullSizeLoad = nil
                settling = false
                swipeOffset = .zero
                setZoomScale(minimumZoomScale, animated: false)
                picture.image = previews[current.id]
                fittedBounds = .zero
            }
            refreshPreviews()
            if changedPage { layoutIfNeeded() }
            for photo in pages where previews[photo.id] == nil && !unavailable.contains(photo.id) && loads[photo.id] == nil {
                loads[photo.id] = Task { [weak self] in
                    let image = await Self.decode(photo, pixels: 2048)
                    guard !Task.isCancelled, let self else { return }
                    self.loads[photo.id] = nil
                    if let image { self.previews[photo.id] = image }
                    else { self.unavailable.insert(photo.id) }
                    self.refreshPreviews()
                }
            }
        }

        func cancelLoads() {
            for load in loads.values { load.cancel() }
            loads.removeAll()
            fullSizeLoad?.cancel()
            fullSizeLoad = nil
        }

        private static func decode(_ photo: GalleryPhoto, pixels: Int) async -> UIImage? {
            await Task.detached(priority: .userInitiated) {
                guard let image = CaptureArchive.thumbnail(photo, pixels: pixels) else { return nil }
                return image.preparingForDisplay() ?? image
            }.value
        }

        private func refreshPreviews() {
            previousPicture.image = previous.flatMap { previews[$0.id] }
            nextPicture.image = nextPage.flatMap { previews[$0.id] }
            if let current, picture.image == nil {
                picture.image = previews[current.id]
                if picture.image != nil { fittedBounds = .zero }
            }
            loading.isHidden = picture.image != nil
            loading.text = current.map { unavailable.contains($0.id) } == true ? L10n.text("photo_unreadable") : L10n.text("loading_photos")
            setNeedsLayout()
        }

        private func requestFullSize() {
            guard fullSizeLoad == nil, let current, picture.image != nil else { return }
            fullSizeLoad = Task { [weak self] in
                let image = await Self.decode(current, pixels: 4096)
                guard !Task.isCancelled, let self, let image, self.current?.id == current.id else { return }
                self.picture.image = image
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard bounds.width > 0, bounds.height > 0 else { return }
            if bounds.size != fittedBounds {
                if fittedBounds.width > 0 {
                    swipeOffset.x *= bounds.width / fittedBounds.width
                }
                fittedBounds = bounds.size
                setZoomScale(minimumZoomScale, animated: false)
                setContentOffset(.zero, animated: false)
                if let image = picture.image, image.size.width > 0, image.size.height > 0 {
                    let scale = min(bounds.width / image.size.width, bounds.height / image.size.height)
                    picture.frame = CGRect(origin: .zero, size: CGSize(width: image.size.width * scale,
                                                                       height: image.size.height * scale))
                    contentSize = picture.frame.size
                } else {
                    picture.frame = .zero
                    contentSize = .zero
                }
            }
            positionPages()
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { picture }
        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            positionPages()
            if zoomScale > 1.05 { requestFullSize() }
        }
        func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
            guard !settling else { return }
            swipeAxis = nil
            swipeOffset = .zero
            positionPages()
        }

        private func positionPages() {
            let horizontal = max(0, (bounds.width - picture.frame.width) / 2)
            let vertical = max(0, (bounds.height - picture.frame.height) / 2)
            picture.frame.origin = CGPoint(x: horizontal + swipeOffset.x, y: vertical + swipeOffset.y)
            previousPicture.frame = CGRect(origin: CGPoint(x: swipeOffset.x - bounds.width, y: 0), size: bounds.size)
            nextPicture.frame = CGRect(origin: CGPoint(x: swipeOffset.x + bounds.width, y: 0), size: bounds.size)
            loading.frame = CGRect(origin: CGPoint(x: swipeOffset.x + contentOffset.x, y: contentOffset.y + swipeOffset.y),
                                   size: bounds.size)
        }

        private func canShowPage(_ offset: Int) -> Bool {
            guard let photo = offset < 0 ? previous : nextPage else { return false }
            return previews[photo.id] != nil || unavailable.contains(photo.id)
        }

        override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard !settling else { return false }
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer, pan !== panGestureRecognizer else {
                return super.gestureRecognizerShouldBegin(gestureRecognizer)
            }
            let velocity = pan.velocity(in: self)
            return zoomScale <= minimumZoomScale + 0.01 &&
                (abs(velocity.x) > abs(velocity.y) || velocity.y > 0)
        }

        @objc private func tapped() { toggleTools?() }

        @objc private func zoomTapped(_ gesture: UITapGestureRecognizer) {
            if zoomScale > minimumZoomScale + 0.01 {
                setZoomScale(minimumZoomScale, animated: true)
            } else {
                let point = gesture.location(in: picture)
                let size = CGSize(width: bounds.width / 2.5, height: bounds.height / 2.5)
                zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                                width: size.width, height: size.height), animated: true)
            }
        }

        @objc private func swiped(_ gesture: UIPanGestureRecognizer) {
            let translation = gesture.translation(in: self)
            let velocity = gesture.velocity(in: self)
            switch gesture.state {
            case .began:
                swipeAxis = abs(velocity.x) > abs(velocity.y) ? .horizontal : .down
            case .changed:
                guard let axis = swipeAxis else { return }
                if axis == .horizontal {
                    let distance = canShowPage(translation.x < 0 ? 1 : -1) ? translation.x : 0
                    swipeOffset = CGPoint(x: min(bounds.width, max(-bounds.width, distance)), y: 0)
                } else {
                    swipeOffset = CGPoint(x: 0, y: max(0, translation.y))
                }
                positionPages()
            case .ended:
                let axis = swipeAxis
                swipeAxis = nil
                if axis == .horizontal {
                    let distance = abs(translation.x)
                    let flick = distance > 24 && abs(velocity.x) > 650 && translation.x * velocity.x > 0
                    let page = translation.x < 0 ? 1 : -1
                    if canShowPage(page) && (distance > min(72, bounds.width * 0.2) || flick) {
                        settle(on: page)
                        return
                    }
                } else if axis == .down,
                          translation.y > 96 || (translation.y > 36 && velocity.y > 650) {
                    swipeBack?()
                }
                resetSwipe()
            case .cancelled, .failed:
                swipeAxis = nil
                resetSwipe()
            default: break
            }
        }

        private func settle(on page: Int) {
            settling = true
            UIView.animate(withDuration: 0.22, delay: 0, options: .curveEaseOut) {
                self.swipeOffset = CGPoint(x: -CGFloat(page) * self.bounds.width, y: 0)
                self.positionPages()
            } completion: { [weak self] finished in
                guard let self, self.settling else { return }
                // Keep the incoming page centered until SwiftUI commits the selection.
                if !finished || self.stepPhoto?(page) != true {
                    self.settling = false
                    self.resetSwipe()
                }
            }
        }

        private func resetSwipe() {
            swipeOffset = .zero
            UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                self.positionPages()
            }
        }
    }
}
