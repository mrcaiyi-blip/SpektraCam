import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import UIKit

enum FilmProcessor {
    static func render(_ photo: RawIspCapturedPhoto, film: RawIspFilmLook,
                       meter: String, zoom: Double) throws -> UIImage {
        guard let root = Bundle.main.resourceURL?.appendingPathComponent("spektra") else {
            throw CameraFailure("resource_directory")
        }
        let region = try nativeRawRegion(from: photo)
        let buffer = photo.pixelBuffer
        guard !CVPixelBufferIsPlanar(buffer),
              CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else {
            throw CameraFailure("read_raw_buffer")
        }
        let packed: Data
        do {
            defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
            packed = try region.packedBayerData(from: UnsafeRawBufferPointer(
                start: CVPixelBufferGetBaseAddress(buffer), count: CVPixelBufferGetDataSize(buffer)))
        }
        let width = region.width, height = region.height
        guard width <= 32 * 1024 * 1024 / height else { throw CameraFailure("raw_size_limit") }
        var frame = try makeRawMetadata(from: photo)
        frame.width = UInt32(width)
        frame.height = UInt32(height)
        frame.row_stride = width * 2
        frame.pixel_stride = 2
        frame.size = packed.count
        frame.bayer = try cfaPattern(for: photo.rawPixelFormat)
        frame.highlight_chroma_blend = 1
        if let dng = photo.dngData,
           let vignette = try RawIspDngStage3Vignette.read(dng, activeWidth: width, activeHeight: height) {
            guard vignette.acceptsRawSource(region.source, x: region.x, y: region.y) else {
                throw CameraFailure("bayer_vignette_source")
            }
            let c = vignette.coefficients
            frame.vignette = (c[0], c[1], c[2], c[3], c[4])
            frame.vignette_center_x = vignette.centerX
            frame.vignette_center_y = vignette.centerY
            frame.has_vignette = 1
        }
        var rgba = Data(count: width * height * 4)
        let capacity = rgba.count
        var error = [CChar](repeating: 0, count: 512)
        let ok = packed.withUnsafeBytes { (source: UnsafeRawBufferPointer) in
            frame.data = source.bindMemory(to: UInt8.self).baseAddress
            return rgba.withUnsafeMutableBytes { (destination: UnsafeMutableRawBufferPointer) in
                sc_render_raw(&frame, root.path, film.id, film.paper, meter,
                              destination.bindMemory(to: UInt8.self).baseAddress, capacity, &error, error.count)
            }
        }
        guard ok == 1 else {
            NSLog("[SpektraCam] RAW development failed: %@", String(cString: error))
            throw CameraFailure("develop_failed")
        }
        guard let provider = CGDataProvider(data: rgba as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: width * 4, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else {
            throw CameraFailure("create_film_photo")
        }
        let cropWidth = max(1, Int(Double(width) / zoom))
        let cropHeight = max(1, Int(Double(height) / zoom))
        let cropped = image.cropping(to: CGRect(x: (width - cropWidth) / 2, y: (height - cropHeight) / 2,
                                               width: cropWidth, height: cropHeight)) ?? image
        let orientation = imageOrientation(for: photo.displayRotationDegrees)
        let swaps = orientation == .right || orientation == .left
        let size = CGSize(width: swaps ? cropped.height : cropped.width, height: swaps ? cropped.width : cropped.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIImage(cgImage: cropped, scale: 1, orientation: orientation).draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private static func nativeRawRegion(from photo: RawIspCapturedPhoto) throws -> RawIspRawRegion {
        let properties = imageProperties(from: photo.dngData)
        let encoded = dictionary(properties?[kCGImagePropertyDNGDictionary as String])
        let captured = dictionary(photo.metadata[kCGImagePropertyDNGDictionary as String])
        return try RawIspRawRegion(
            bufferWidth: CVPixelBufferGetWidth(photo.pixelBuffer),
            bufferHeight: CVPixelBufferGetHeight(photo.pixelBuffer),
            bytesPerRow: CVPixelBufferGetBytesPerRow(photo.pixelBuffer),
            activeArea: encoded?["ActiveArea"] ?? captured?["ActiveArea"])
    }

    private static func imageOrientation(for rotationDegrees: Int32) -> UIImage.Orientation {
        switch ((rotationDegrees % 360) + 360) % 360 {
        case 90:
            return .right
        case 180:
            return .down
        case 270:
            return .left
        default:
            return .up
        }
    }

    private static func cfaPattern(for pixelFormat: OSType) throws -> UInt32 {
        switch pixelFormat {
        case kCVPixelFormatType_14Bayer_RGGB:
            return 0
        case kCVPixelFormatType_14Bayer_GRBG:
            return 1
        case kCVPixelFormatType_14Bayer_GBRG:
            return 2
        case kCVPixelFormatType_14Bayer_BGGR:
            return 3
        default:
            throw CameraFailure("raw_pixel_format")
        }
    }

    private static func makeRawMetadata(
        from photo: RawIspCapturedPhoto
    ) throws -> sc_raw_frame {
        var metadata = sc_raw_frame()
        let encodedProperties = imageProperties(from: photo.dngData)
        let captureDng = dictionary(
            photo.metadata[kCGImagePropertyDNGDictionary as String]
        )
        let encodedDng = dictionary(
            encodedProperties?[kCGImagePropertyDNGDictionary as String]
        )
        var dng = captureDng ?? [:]
        encodedDng?.forEach { dng[$0.key] = $0.value }

        // AVCapturePhoto's RAW CVPixelBuffer is one of Apple's 14-bit Bayer
        // formats. fileDataRepresentation() may quantize the encoded DNG to a
        // lower bit depth, so its BlackLevel/WhiteLevel cannot override levels
        // attached to the live pixel buffer. Prefer capture metadata; if those
        // tags are absent, lift encoded levels back to the 14-bit sample domain.
        let captureBlack = numbers(
            captureDng?[kCGImagePropertyDNGBlackLevel as String]
        )
        let captureWhite = numbers(
            captureDng?[kCGImagePropertyDNGWhiteLevel as String]
        )
        let encodedBlack = numbers(
            encodedDng?[kCGImagePropertyDNGBlackLevel as String]
        )
        let encodedWhite = numbers(
            encodedDng?[kCGImagePropertyDNGWhiteLevel as String]
        )
        let proxyBlack: [Double]
        let proxyWhite: Double
        if let white = captureWhite.first,
           white.isFinite,
           white > (captureBlack.first ?? 0) {
            proxyBlack = captureBlack
            proxyWhite = white
        } else if let white = encodedWhite.first,
                  white.isFinite,
                  white > (encodedBlack.first ?? 0) {
            let encodedBits = max(1, Int(ceil(log2(white + 1))))
            let shift = max(0, 14 - encodedBits)
            let levelScale = Double(1 << shift)
            proxyBlack = encodedBlack.map { $0 * levelScale }
            proxyWhite = white * levelScale
        } else {
            throw CameraFailure(
                "raw_buffer_levels"
            )
        }
        let fallbackBlack = proxyBlack.first ?? 0
        guard proxyWhite <= 65535, fallbackBlack >= 0 else { throw CameraFailure("invalid_raw_levels") }
        metadata.white = Float(proxyWhite.rounded())
        let black = proxyBlack.count >= 4 ? Array(proxyBlack.prefix(4)) : Array(repeating: fallbackBlack, count: 4)
        metadata.black = (Float(black[0].rounded()), Float(black[1].rounded()),
                          Float(black[2].rounded()), Float(black[3].rounded()))

        let neutralValues = fixedCountNumbers(
            dng[kCGImagePropertyDNGAsShotNeutral as String],
            count: 3
        )
        let colorMatrix = firstMatrix(
            dng[kCGImagePropertyDNGColorMatrix2 as String],
            dng[kCGImagePropertyDNGColorMatrix1 as String]
        )
        guard neutralValues.count == 3,
              neutralValues.allSatisfy({ $0.isFinite && $0 > 0 }),
              let colorMatrix,
              colorMatrix.allSatisfy(\.isFinite) else {
            throw CameraFailure(
                "dng_color_metadata"
            )
        }
        let green = neutralValues[1]
        let neutral = neutralValues.map { $0 / green }
        let redGain = Float(1 / neutral[0])
        let blueGain = Float(1 / neutral[2])
        guard let matrix = dngCameraToSrgb(
            colorMatrix: colorMatrix,
            neutral: neutral
        ) else {
            throw CameraFailure(
                "dng_color_matrix"
            )
        }
        metadata.white_balance = (redGain, 1, 1, blueGain)
        metadata.matrix = (
            Float(matrix[0]), Float(matrix[1]), Float(matrix[2]),
            Float(matrix[3]), Float(matrix[4]), Float(matrix[5]),
            Float(matrix[6]), Float(matrix[7]), Float(matrix[8])
        )
        metadata.has_matrix = 1
        return metadata
    }

    private static func dngCameraToSrgb(
        colorMatrix: [Double],
        neutral: [Double]
    ) -> [Double]? {
        guard neutral.count == 3,
              let cameraToXyz = inverse3x3(colorMatrix) else {
            return nil
        }
        var sourceWhite = multiply3x3Vector(cameraToXyz, neutral)
        guard sourceWhite.count == 3,
              sourceWhite[1].isFinite,
              sourceWhite[1] > 0 else {
            return nil
        }
        sourceWhite = sourceWhite.map { $0 / sourceWhite[1] }

        let bradford: [Double] = [
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
        ]
        let d65 = [0.95047, 1.0, 1.08883]
        guard let inverseBradford = inverse3x3(bradford) else { return nil }
        let sourceCone = multiply3x3Vector(bradford, sourceWhite)
        let destinationCone = multiply3x3Vector(bradford, d65)
        guard sourceCone.allSatisfy({ $0.isFinite && abs($0) > 0.000_001 }) else {
            return nil
        }
        let coneScale: [Double] = [
            destinationCone[0] / sourceCone[0], 0, 0,
            0, destinationCone[1] / sourceCone[1], 0,
            0, 0, destinationCone[2] / sourceCone[2]
        ]
        let adaptation = multiply3x3(
            multiply3x3(inverseBradford, coneScale),
            bradford
        )
        let xyzToSrgb: [Double] = [
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252
        ]
        let neutralMatrix: [Double] = [
            neutral[0], 0, 0,
            0, neutral[1], 0,
            0, 0, neutral[2]
        ]
        var matrix = multiply3x3(
            multiply3x3(
                multiply3x3(xyzToSrgb, adaptation),
                cameraToXyz
            ),
            neutralMatrix
        )
        let greenRowSum = matrix[3] + matrix[4] + matrix[5]
        guard greenRowSum.isFinite, abs(greenRowSum) > 0.000_001 else { return nil }
        matrix = matrix.map { $0 / greenRowSum }
        return matrix.allSatisfy(\.isFinite) ? matrix : nil
    }

    private static func imageProperties(from data: Data?) -> [String: Any]? {
        guard let data,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        for index in 0..<CGImageSourceGetCount(source) {
            guard let properties = CGImageSourceCopyPropertiesAtIndex(
                source,
                index,
                nil
            ) as? [String: Any] else {
                continue
            }
            if properties[kCGImagePropertyDNGDictionary as String] != nil {
                return properties
            }
        }
        return nil
    }

    private static func dictionary(_ value: Any?) -> [String: Any]? {
        if let dictionary = value as? [String: Any] {
            return dictionary
        }
        if let dictionary = value as? NSDictionary {
            var result: [String: Any] = [:]
            for (key, value) in dictionary {
                if let key = key as? String {
                    result[key] = value
                }
            }
            return result
        }
        return nil
    }

    private static func firstMatrix(_ values: Any?...) -> [Double]? {
        for value in values {
            let matrix = fixedCountNumbers(value, count: 9)
            if matrix.count >= 9, matrix.prefix(9).allSatisfy(\.isFinite) {
                return Array(matrix.prefix(9))
            }
        }
        return nil
    }

    private static func multiply3x3(_ lhs: [Double], _ rhs: [Double]) -> [Double] {
        var result = Array(repeating: 0.0, count: 9)
        for row in 0..<3 {
            for column in 0..<3 {
                result[row * 3 + column] = (0..<3).reduce(0.0) { partial, index in
                    partial + lhs[row * 3 + index] * rhs[index * 3 + column]
                }
            }
        }
        return result
    }

    private static func inverse3x3(_ matrix: [Double]) -> [Double]? {
        guard matrix.count == 9 else { return nil }
        let determinant =
            matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
            matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
            matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
        guard determinant.isFinite, abs(determinant) > 0.000_001 else { return nil }
        let inverseDeterminant = 1 / determinant
        return [
            (matrix[4] * matrix[8] - matrix[5] * matrix[7]) * inverseDeterminant,
            (matrix[2] * matrix[7] - matrix[1] * matrix[8]) * inverseDeterminant,
            (matrix[1] * matrix[5] - matrix[2] * matrix[4]) * inverseDeterminant,
            (matrix[5] * matrix[6] - matrix[3] * matrix[8]) * inverseDeterminant,
            (matrix[0] * matrix[8] - matrix[2] * matrix[6]) * inverseDeterminant,
            (matrix[2] * matrix[3] - matrix[0] * matrix[5]) * inverseDeterminant,
            (matrix[3] * matrix[7] - matrix[4] * matrix[6]) * inverseDeterminant,
            (matrix[1] * matrix[6] - matrix[0] * matrix[7]) * inverseDeterminant,
            (matrix[0] * matrix[4] - matrix[1] * matrix[3]) * inverseDeterminant
        ]
    }

    private static func multiply3x3Vector(_ matrix: [Double], _ vector: [Double]) -> [Double] {
        guard matrix.count == 9, vector.count == 3 else { return [] }
        return (0..<3).map { row in
            matrix[row * 3] * vector[0] +
            matrix[row * 3 + 1] * vector[1] +
            matrix[row * 3 + 2] * vector[2]
        }
    }

    private static func fixedCountNumbers(_ value: Any?, count: Int) -> [Double] {
        let parsed = numbers(value)
        guard parsed.count == count * 2 else { return parsed }

        var rationals: [Double] = []
        rationals.reserveCapacity(count)
        for index in 0..<count {
            let denominator = parsed[index * 2 + 1]
            guard denominator != 0 else { return parsed }
            rationals.append(parsed[index * 2] / denominator)
        }
        return rationals
    }

    private static func numbers(_ value: Any?) -> [Double] {
        if let number = value as? NSNumber {
            return [number.doubleValue]
        }
        if let string = value as? String {
            return string
                .components(separatedBy: CharacterSet(charactersIn: "0123456789+-.eE/").inverted)
                .compactMap(parseNumber)
        }
        if let array = value as? [Any] {
            // ImageIO can expose a DNG SRATIONAL/URATIONAL as a two-number
            // [numerator, denominator] array instead of an NSNumber.
            if array.count == 2,
               let numerator = array[0] as? NSNumber,
               let denominator = array[1] as? NSNumber,
               denominator.doubleValue != 0 {
                return [numerator.doubleValue / denominator.doubleValue]
            }
            return array.flatMap(numbers)
        }
        return []
    }

    private static func parseNumber(_ token: String) -> Double? {
        guard !token.isEmpty else { return nil }
        let parts = token.split(separator: "/", omittingEmptySubsequences: false)
        if parts.count == 2,
           let numerator = Double(parts[0]),
           let denominator = Double(parts[1]),
           denominator != 0 {
            return numerator / denominator
        }
        return Double(token)
    }
}
