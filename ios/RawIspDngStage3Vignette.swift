import Foundation

/// Reads this capture's stage-3 instruction, never a fixed device profile.
/// TIFF/RAW coordinates are top-left sensor rows, before display orientation.
struct RawIspDngStage3Vignette {
    let coefficients: [Float]
    let centerX: Float
    let centerY: Float
    let width: Int
    let height: Int
    let usesFullImageDefault: Bool

    func acceptsRawSource(_ source: String, x: Int, y: Int) -> Bool {
        source == "dng_active_area" ||
            (usesFullImageDefault && source == "cv_pixel_buffer_extent" && x == 0 && y == 0)
    }

    enum ParseError: LocalizedError {
        case invalid(String)
        var errorDescription: String? {
            switch self { case .invalid(let reason): return L10n.text("invalid_vignette", reason) }
        }
    }

    private struct Tag {
        let type: Int
        let count: Int
        let payload: [UInt8]
    }

    static func read(_ data: Data, activeWidth: Int, activeHeight: Int) throws -> Self? {
        try data.withUnsafeBytes { bytes in
            try read(bytes, activeWidth: activeWidth, activeHeight: activeHeight)
        }
    }

    private static func read(_ bytes: UnsafeRawBufferPointer, activeWidth: Int, activeHeight: Int) throws -> Self? {
        func fail(_ text: String) -> ParseError { .invalid(text) }
        guard bytes.count >= 8 else { throw fail(L10n.text("tiff_header")) }
        let little: Bool
        if bytes[0] == 73 && bytes[1] == 73 { little = true }
        else if bytes[0] == 77 && bytes[1] == 77 { little = false }
        else { throw fail(L10n.text("not_tiff")) }
        func integer(_ offset: Int, _ size: Int, littleEndian: Bool? = nil) throws -> UInt64 {
            guard offset >= 0, size > 0, size <= 8, offset <= bytes.count - size else {
                throw fail(L10n.text("tiff_bounds"))
            }
            var value: UInt64 = 0
            if littleEndian ?? little {
                for i in 0..<size { value |= UInt64(bytes[offset+i]) << (8*i) }
            } else {
                for i in 0..<size { value = (value << 8) | UInt64(bytes[offset+i]) }
            }
            return value
        }
        guard try integer(2, 2) == 42 else { throw fail(L10n.text("no_bigtiff")) }
        let sizes = [1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4]
        var seen = Set<Int>(), visiting = Set<Int>()
        var rawImages: [[Int: Tag]] = []
        func numbers(_ tag: Tag?) throws -> [Double]? {
            guard let tag else { return nil }
            let unit: Int
            switch tag.type {
            case 1: unit = 1
            case 3: unit = 2
            case 4, 13: unit = 4
            case 5: unit = 8
            default: throw fail(L10n.text("size_field"))
            }
            func at(_ start: Int, _ size: Int) -> UInt64 {
                var v: UInt64 = 0
                if little { for i in 0..<size { v |= UInt64(tag.payload[start+i]) << (8*i) } }
                else { for i in 0..<size { v = (v << 8) | UInt64(tag.payload[start+i]) } }
                return v
            }
            return try (0..<tag.count).map { i in
                if tag.type == 5 {
                    let denominator = at(i*unit+4, 4)
                    guard denominator > 0 else { throw fail(L10n.text("zero_denominator")) }
                    return Double(at(i*unit, 4)) / Double(denominator)
                }
                return Double(at(i*unit, unit))
            }
        }
        func visit(_ offset: Int) throws {
            if offset == 0 { return }
            guard !visiting.contains(offset), seen.count < 128 else { throw fail(L10n.text("ifd_loop")) }
            if seen.contains(offset) { return }
            visiting.insert(offset)
            defer { visiting.remove(offset) }
            seen.insert(offset)
            let count = Int(try integer(offset, 2))
            guard count <= 1024, offset + 2 <= bytes.count - count*12 - 4 else { throw fail(L10n.text("ifd_incomplete")) }
            var tags: [Int: Tag] = [:]
            for i in 0..<count {
                let entry = offset + 2 + i*12
                let id = Int(try integer(entry, 2))
                // Ignore unrelated payloads without copying compressed image data.
                if ![256, 257, 262, 330, 50719, 50720, 50718, 50829, 51022].contains(id) { continue }
                guard tags[id] == nil else { throw fail(L10n.text("ifd_duplicate")) }
                let type = Int(try integer(entry+2, 2)), n = Int(try integer(entry+4, 4))
                guard let unit = sizes[type], n <= 1_048_576 / unit else { throw fail(L10n.text("field_invalid")) }
                let size = n*unit, start = size <= 4 ? entry+8 : Int(try integer(entry+8, 4))
                guard start >= 0, start <= bytes.count - size else { throw fail(L10n.text("field_bounds")) }
                tags[id] = Tag(type: type, count: n, payload: Array(bytes[start..<start+size]))
            }
            if try numbers(tags[262]) == [32803] { rawImages.append(tags) }
            if let children = try numbers(tags[330]) {
                for child in children { try visit(Int(child)) }
            }
            try visit(Int(integer(offset+2+count*12, 4)))
        }
        try visit(Int(integer(4, 4)))
        guard rawImages.count == 1 else { throw fail(L10n.text("bayer_ifd")) }
        let tags = rawImages[0]
        guard let opcode = tags[51022] else { return nil }
        guard opcode.type == 7 else { throw fail(L10n.text("opcode_type")) }
        // Stage 3 addresses the demosaiced active image. Default cropping or
        // scaling needs its own coordinate contract; do not silently guess it.
        let usesFullImageDefault = tags[50829] == nil
        let area: [Double]
        if let declared = try numbers(tags[50829]) {
            area = declared
        } else {
            // DNG ActiveArea defaults to [0, 0, ImageLength, ImageWidth].
            // Use the Bayer IFD's stored extent, never the requested buffer size.
            guard let widthTag = tags[256], let heightTag = tags[257],
                  [3, 4].contains(widthTag.type), [3, 4].contains(heightTag.type),
                  let width = try numbers(widthTag), let height = try numbers(heightTag),
                  width.count == 1, height.count == 1,
                  width[0] > 0, height[0] > 0 else { throw fail(L10n.text("raw_dimensions")) }
            area = [0, 0, height[0], width[0]]
        }
        guard area.count == 4,
              area.allSatisfy({ $0.isFinite && $0 >= 0 && $0 == $0.rounded() }),
              area[2]-area[0] == Double(activeHeight), area[3]-area[1] == Double(activeWidth),
              activeWidth > 0, activeHeight > 0 else { throw fail(L10n.text("active_mismatch")) }
        if let origin = try numbers(tags[50719]), origin != [0, 0] { throw fail(L10n.text("crop_origin")) }
        if let crop = try numbers(tags[50720]), crop != [Double(activeWidth), Double(activeHeight)] {
            throw fail(L10n.text("crop_dimensions"))
        }
        if let scale = try numbers(tags[50718]), scale != [1, 1] { throw fail(L10n.text("non_square_pixels")) }
        let payload = opcode.payload
        func big(_ offset: Int, _ size: Int) throws -> UInt64 {
            guard offset >= 0, offset <= payload.count-size else { throw fail(L10n.text("opcode_incomplete")) }
            return payload[offset..<offset+size].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        }
        let count = Int(try big(0, 4))
        guard count <= 1024 else { throw fail(L10n.text("opcode_count")) }
        var cursor = 4, result: Self?
        for _ in 0..<count {
            let id = try big(cursor, 4), version = try big(cursor+4, 4)
            let flags = try big(cursor+8, 4), size = Int(try big(cursor+12, 4))
            cursor += 16
            guard flags & ~UInt64(3) == 0, size <= payload.count-cursor else { throw fail(L10n.text("opcode_flags")) }
            if id != 3 || version != 0x01030000 {
                guard flags & 1 != 0 else { throw fail(L10n.text("opcode_required")) }
            } else {
                guard size == 56, result == nil else { throw fail(L10n.text("radial_duplicate")) }
                let values = try (0..<7).map { Double(bitPattern: try big(cursor+$0*8, 8)) }
                guard values.allSatisfy(\.isFinite), (0...1).contains(values[5]), (0...1).contains(values[6]),
                      values.map({ Float($0) }).allSatisfy(\.isFinite) else { throw fail(L10n.text("radial_invalid")) }
                result = Self(coefficients: values.prefix(5).map(Float.init), centerX: Float(values[5]),
                              centerY: Float(values[6]), width: activeWidth, height: activeHeight,
                              usesFullImageDefault: usesFullImageDefault)
            }
            cursor += size
        }
        guard cursor == payload.count else { throw fail(L10n.text("opcode_trailing")) }
        return result
    }

    var diagnostics: [String: Any] {
        ["source": "capture_dng_opcode_list3", "coefficients": coefficients,
         "center": [centerX, centerY], "imageWidth": width, "imageHeight": height,
         "activeAreaSource": usesFullImageDefault ? "dng_default_full_raw_ifd" : "dng_active_area",
         "pixelAspectRatio": 1, "origin": "top_left_raw_rows", "sourceBeforeStage3": true]
    }
}
