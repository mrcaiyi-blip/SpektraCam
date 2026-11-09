import Foundation
import CoreFoundation

/// The CVPixelBuffer extent may include non-image columns (4224 vs 4032 on
/// iPhone 16 Pro Max). DNG ActiveArea declares sensor image pixels; row stride
/// and DefaultCropSize are not interchangeable with that declaration.
struct RawIspRawRegion {
    let bufferWidth: Int
    let bufferHeight: Int
    let bytesPerRow: Int
    let x: Int
    let y: Int
    let width: Int
    let height: Int
    let source: String

    enum RegionError: LocalizedError {
        case invalidGeometry
        case invalidActiveArea
        case truncatedBuffer

        var errorDescription: String? {
            switch self {
            case .invalidGeometry: return L10n.text("raw_buffer_geometry")
            case .invalidActiveArea: return L10n.text("bayer_active_area")
            case .truncatedBuffer: return L10n.text("raw_buffer_short")
            }
        }
    }

    init(bufferWidth: Int, bufferHeight: Int, bytesPerRow: Int, activeArea: Any?) throws {
        guard bufferWidth >= 2, bufferHeight >= 2,
              bufferWidth <= Int(Int32.max) / 2, bufferHeight <= Int(Int32.max),
              bytesPerRow >= bufferWidth * 2, bytesPerRow <= Int(Int32.max) else {
            throw RegionError.invalidGeometry
        }
        self.bufferWidth = bufferWidth
        self.bufferHeight = bufferHeight
        self.bytesPerRow = bytesPerRow
        let area: [Int]
        if let activeArea {
            guard let values = activeArea as? [Any], values.count == 4 else {
                throw RegionError.invalidActiveArea
            }
            area = try values.map { value in
                guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
                    throw RegionError.invalidActiveArea
                }
                let v = n.doubleValue
                guard v.isFinite, v >= 0, v <= Double(Int32.max), v == v.rounded() else {
                    throw RegionError.invalidActiveArea
                }
                return Int(v)
            }
            source = "dng_active_area"
        } else {
            area = [0, 0, bufferHeight, bufferWidth]
            source = "cv_pixel_buffer_extent"
        }
        // DNG order: top, left, bottom, right. Even origins retain both CFA
        // and black-level phase ordering; reject unsupported odd origins.
        guard area[0] < area[2], area[1] < area[3],
              area[2] <= bufferHeight, area[3] <= bufferWidth,
              area.allSatisfy({ $0 % 2 == 0 }) else {
            throw RegionError.invalidActiveArea
        }
        y = area[0]; x = area[1]
        height = area[2] - area[0]; width = area[3] - area[1]
    }

    /// A compact copy avoids passing trailing zero columns or an offset view
    /// with an incomplete final stride to the shared C frame contract.
    func packedBayerData(from buffer: UnsafeRawBufferPointer) throws -> Data {
        guard let base = buffer.baseAddress,
              buffer.count >= bytesPerRow * bufferHeight else { throw RegionError.truncatedBuffer }
        let rowBytes = width * 2
        var data = Data(count: rowBytes * height)
        data.withUnsafeMutableBytes { (destination: UnsafeMutableRawBufferPointer) in
            for row in 0..<height {
                destination.baseAddress!.advanced(by: row * rowBytes).copyMemory(
                    from: base.advanced(by: (y + row) * bytesPerRow + x * 2), byteCount: rowBytes)
            }
        }
        return data
    }

    var diagnostics: [String: Any] {
        ["source": source, "bufferWidth": bufferWidth, "bufferHeight": bufferHeight,
         "bufferRowStride": bytesPerRow, "activeArea": [y, x, y + height, x + width],
         "width": width, "height": height, "rowStride": width * 2,
         "excludedPixelCount": bufferWidth * bufferHeight - width * height]
    }
}
