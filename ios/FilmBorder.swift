import UIKit

/// Finishing geometry shared by the picker sample and the saved JPEG.
enum FilmBorder: String, CaseIterable, Identifiable, Sendable {
    case none, print, strip, instant

    static let preferenceKey = "selected_film_border"
    static let textModeKey = "film_border_text_mode"
    static let customTextKey = "film_border_custom_text"
    private static let paper = UIColor(red: 249/255, green: 246/255, blue: 238/255, alpha: 1)
    private static let negative = UIColor(red: 37/255, green: 30/255, blue: 27/255, alpha: 1)
    private static let perforation = UIColor(red: 238/255, green: 235/255, blue: 227/255, alpha: 1)
    private static let edgeInk = UIColor(red: 230/255, green: 172/255, blue: 116/255, alpha: 1)
    private static let paperInk = UIColor(red: 48/255, green: 42/255, blue: 36/255, alpha: 1)
    private static let swatchGreen = UIColor(red: 104/255, green: 123/255, blue: 98/255, alpha: 1)
    private static let swatchPaperOutline = UIColor(red: 210/255, green: 207/255, blue: 196/255, alpha: 1)

    var id: String { rawValue }
    var title: String { L10n.text("film_border_\(rawValue)") }

    enum TextMode: String, CaseIterable, Identifiable, Sendable {
        case `default`, hidden, film, custom
        var id: String { rawValue }
        var title: String { L10n.text("film_border_text_\(rawValue)") }
    }

    struct Caption: Sendable {
        static let empty = Caption(label: "", frameNumber: false)
        let label: String
        let frameNumber: Bool
    }

    struct TextSettings: Equatable {
        let mode: TextMode
        let custom: String

        static var current: Self {
            let preferences = UserDefaults.standard
            return Self(mode: TextMode(rawValue: preferences.string(forKey: FilmBorder.textModeKey) ?? "") ?? .default,
                        custom: preferences.string(forKey: FilmBorder.customTextKey) ?? "")
        }

        func caption(for border: FilmBorder, film: String) -> Caption {
            let label: String
            switch mode {
            case .default: label = border == .strip ? "135" : ""
            case .hidden: label = ""
            case .film: label = film
            case .custom: label = custom
            }
            let line = label.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return line.isEmpty || border == .none ? .empty : Caption(label: line, frameNumber: border == .strip)
        }
    }

    func render(_ source: UIImage, caption: Caption) -> UIImage {
        if self == .none, source.imageOrientation == .up { return source }
        let size: CGSize
        if let image = source.cgImage {
            switch source.imageOrientation {
            case .left, .right, .leftMirrored, .rightMirrored:
                size = CGSize(width: image.height, height: image.width)
            default:
                size = CGSize(width: image.width, height: image.height)
            }
        } else {
            size = CGSize(width: source.size.width * source.scale, height: source.size.height * source.scale)
        }
        let layout = layout(for: size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        if self != .none { format.preferredRange = .standard }
        return UIGraphicsImageRenderer(size: layout.size, format: format).image { renderer in
            drawBackground(renderer.cgContext, layout: layout, caption: caption)
            source.draw(in: layout.photo)
        }
    }

    var swatch: UIImage {
        let size = CGSize(width: 31, height: 20)
        let layout = layout(for: CGSize(width: self == .instant ? 90 : 135, height: 90))
        let scale = min(size.width / layout.size.width, size.height / layout.size.height)
        return UIGraphicsImageRenderer(size: size).image { renderer in
            let context = renderer.cgContext
            context.translateBy(x: (size.width - layout.size.width * scale) / 2,
                                y: (size.height - layout.size.height * scale) / 2)
            context.scaleBy(x: scale, y: scale)
            drawBackground(context, layout: layout, caption: .empty)
            context.setFillColor(Self.swatchGreen.cgColor)
            context.fill(layout.photo)
            if self == .print || self == .instant {
                let stroke = 0.65 / scale
                context.setStrokeColor(Self.swatchPaperOutline.cgColor)
                context.setLineWidth(stroke)
                context.stroke(CGRect(origin: .zero, size: layout.size).insetBy(dx: stroke / 2, dy: stroke / 2))
            }
        }
    }

    private struct Layout {
        let size: CGSize
        let photo: CGRect
        let portrait: Bool
        let mm: CGFloat
        let gap: CGFloat
    }

    private func layout(for size: CGSize) -> Layout {
        let shortEdge = min(size.width, size.height)
        let portrait = size.width < size.height
        let mm = shortEdge / 24
        let margin = (shortEdge * 0.05).rounded()
        let gap = (2 * mm).rounded()
        let rebate = (5.5 * mm).rounded()
        let (side, top): (CGFloat, CGFloat)
        switch self {
        case .none:
            (side, top) = (0, 0)
        case .print, .instant:
            (side, top) = (margin, margin)
        case .strip:
            (side, top) = portrait ? (rebate, gap) : (gap, rebate)
        }
        let bottom = self == .instant ? (shortEdge * 0.14).rounded() : top
        return Layout(size: CGSize(width: size.width + 2 * side, height: size.height + top + bottom),
                      photo: CGRect(origin: CGPoint(x: side, y: top), size: size),
                      portrait: portrait, mm: mm, gap: gap)
    }

    private func drawBackground(_ context: CGContext, layout: Layout, caption: Caption) {
        guard self != .none else { return }
        context.setFillColor((self == .strip ? Self.negative : Self.paper).cgColor)
        context.fill(CGRect(origin: .zero, size: layout.size))
        guard self == .strip else {
            let bottom = layout.size.height - layout.photo.maxY
            let size = min(bottom * 0.58, 0.9 * layout.mm)
            if size >= 1 && !caption.label.isEmpty {
                let band = CGRect(x: layout.photo.minX, y: layout.photo.maxY, width: layout.photo.width, height: bottom)
                drawCaption(caption.label, in: band, context: context, font: .systemFont(ofSize: size),
                            color: Self.paperInk, alignment: .center)
            }
            return
        }
        let length = layout.portrait ? layout.size.height : layout.size.width
        let breadth = layout.portrait ? layout.size.width : layout.size.height
        let holeWidth = 1.98 * layout.mm
        let holeHeight = 2.79 * layout.mm
        let pitch = 4.75 * layout.mm
        let outer = 1.7 * layout.mm
        let radius = 0.5 * layout.mm
        let count = Int(floor((length - 2 * layout.gap - holeWidth) / pitch)) + 1
        let start = (length - CGFloat(count - 1) * pitch - holeWidth) / 2

        context.saveGState()
        defer { context.restoreGState() }
        if layout.portrait {
            context.translateBy(x: layout.size.width, y: 0)
            context.rotate(by: .pi / 2)
        }
        context.setFillColor(Self.perforation.cgColor)
        for y in [outer, breadth - outer - holeHeight] {
            for index in 0..<count {
                let hole = CGRect(x: start + CGFloat(index) * pitch, y: y, width: holeWidth, height: holeHeight)
                context.addPath(CGPath(roundedRect: hole, cornerWidth: radius, cornerHeight: radius, transform: nil))
            }
        }
        context.fillPath()
        if caption.frameNumber {
            let font = UIFont.monospacedSystemFont(ofSize: max(1, (0.9 * layout.mm).rounded()), weight: .medium)
            let numberWidth = ceil(("24A" as NSString).size(withAttributes: [.font: font]).width)
            let width = length - 3 * layout.gap - numberWidth
            if width > 0 {
                let labelBand = CGRect(x: layout.gap, y: breadth - outer, width: width, height: outer)
                let numberBand = CGRect(x: length - layout.gap - numberWidth, y: breadth - outer,
                                        width: numberWidth + 1, height: outer)
                drawCaption(caption.label, in: labelBand, context: context, font: font, color: Self.edgeInk, alignment: .left)
                drawCaption("24A", in: numberBand, context: context, font: font, color: Self.edgeInk, alignment: .left)
            }
        }
    }

    private func drawCaption(_ text: String, in band: CGRect, context: CGContext, font: UIFont,
                             color: UIColor, alignment: NSTextAlignment) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byTruncatingTail
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: paragraph]
        let height = ceil(font.lineHeight) + 1
        let line = CGRect(x: band.minX, y: band.midY - height / 2, width: band.width, height: height)
        context.saveGState()
        context.clip(to: band)
        (text as NSString).draw(with: line, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                                attributes: attributes, context: nil)
        context.restoreGState()
    }
}
