import Foundation

struct RawIspLutCube {
    let size: Int
    let rgba: [Float]

    static func load(path: String, root: URL) throws -> RawIspLutCube {
        let bytes = try Data(contentsOf: root.appendingPathComponent(path))
        let data = [UInt8](bytes)
        guard data.count >= 16, Array(data.prefix(4)) == Array("PLUT".utf8) else { throw InvalidLut.data }
        func word(_ offset: Int) -> Int {
            (0..<4).reduce(0) { $0 | (Int(data[offset + $1]) << ($1 * 8)) }
        }
        let size = word(8)
        guard word(4) == 1, word(12) == 1, (2...128).contains(size),
              data.count == 16 + size * size * size * 6 else { throw InvalidLut.data }
        var rgba = [Float](); rgba.reserveCapacity(size * size * size * 4)
        for pixel in 0..<(size * size * size) {
            for channel in 0..<3 {
                let offset = 16 + (pixel * 3 + channel) * 2
                rgba.append(Float(Int(data[offset]) | (Int(data[offset + 1]) << 8)) / 65535)
            }
            rgba.append(1)
        }
        return RawIspLutCube(size: size, rgba: rgba)
    }

    enum InvalidLut: Error { case data }
}
