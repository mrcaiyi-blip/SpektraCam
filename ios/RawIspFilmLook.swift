import Foundation

struct RawIspPrint: Equatable, Identifiable, Sendable {
    let id: String
    let title: String
}

struct RawIspFilmLook: Equatable, Identifiable, Sendable {
    let id: String
    let title: String
    let group: String
    let order: Int
    let defaultPaper: String
    let prints: [RawIspPrint]
    private(set) var paper: String

    var finishTitle: String {
        prints.first(where: { $0.id == paper })?.title ?? L10n.text("direct_scan")
    }

    private var variantKey: String { id + "--" + paper }
    var previewAssetPath: String {
        paper == defaultPaper ? "spektra/preview_luts/\(id).plut" : "spektra/print_variants/\(variantKey).plut"
    }
    var sampleAssetPath: String {
        paper == defaultPaper ? "spektra/samples/\(id).jpg" : "spektra/film_samples/\(variantKey).jpg"
    }

    func selectingPaper(_ id: String?) -> Self {
        var look = self
        look.paper = prints.first(where: { $0.id == id })?.id ?? defaultPaper
        return look
    }

    static func load(root: URL) throws -> [Self] {
        struct Catalog: Decodable {
            let stocks: [String: Stock]
            struct Stock: Decodable { let name: String; let group: String; let order: Int }
        }
        struct Manifest: Decodable {
            let schema: Int
            let size: Int?
            let looks: [Entry]
            struct Entry: Decodable, Hashable { let film: String; let print: String; let size: Int? }
        }
        struct Profile: Decodable {
            let info: Info
            struct Info: Decodable { let type: String; let target_print: String? }
        }

        let decoder = JSONDecoder()
        func load<T: Decodable>(_ path: String) throws -> T {
            try decoder.decode(T.self, from: Data(contentsOf: root.appendingPathComponent(path)))
        }

        let catalog: Catalog = try load("spektra/catalog.json")
        let manifest: Manifest = try load("spektra/preview_luts/manifest.json")
        let alternatives: Manifest = try load("spektra/print_variants/manifest.json")
        guard manifest.schema == 1, manifest.size == 65, manifest.looks.count == 20,
              Set(manifest.looks.map(\.film)).count == 20, alternatives.schema == 1,
              alternatives.looks.count == 60 else { throw FilmError.invalidCatalog }

        let groups = ["color_negative": ("color_negative", 0, "print_paper"),
                      "color_reversal": ("color_reversal", 1000, ""),
                      "motion_picture_negative": ("motion_picture_negative", 2000, "print_film")]
        let variantsByFilm = Dictionary(grouping: alternatives.looks, by: \.film)
        guard Set(variantsByFilm.keys).isSubset(of: Set(manifest.looks.map(\.film))) else {
            throw FilmError.invalidCatalog
        }
        return try manifest.looks.map { entry in
            guard let stock = catalog.stocks[entry.film], let group = groups[stock.group] else {
                throw FilmError.invalidCatalog
            }
            let profile: Profile = try load("spektra/profiles/\(entry.film).json")
            let defaultPaper = profile.info.type == "positive" ? "" : profile.info.target_print ?? ""
            let variants = variantsByFilm[entry.film] ?? []
            let paperStocks = catalog.stocks.filter { $0.value.group == group.2 }
            let papers = paperStocks.sorted {
                ($0.value.order, $0.key) < ($1.value.order, $1.key)
            }.map { RawIspPrint(id: $0.key, title: $0.value.name) }
            guard defaultPaper == entry.print,
                  defaultPaper.isEmpty ? group.2.isEmpty : paperStocks[defaultPaper] != nil,
                  Set(variants.map(\.print)) == Set(paperStocks.keys).subtracting([defaultPaper]),
                  Set(variants.map(\.print)).count == variants.count else {
                throw FilmError.invalidCatalog
            }

            let look = Self(id: entry.film, title: stock.name, group: group.0,
                            order: group.1 + stock.order, defaultPaper: defaultPaper,
                            prints: papers, paper: defaultPaper)
            let previews = [look] + variants.map { look.selectingPaper($0.print) }
            for preview in previews {
                for path in [preview.previewAssetPath, preview.sampleAssetPath] {
                    guard FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) else {
                        throw FilmError.invalidCatalog
                    }
                }
            }
            return look
        }.sorted { $0.order < $1.order }
    }

    enum FilmError: LocalizedError {
        case invalidCatalog
        var errorDescription: String? { L10n.text("invalid_film_catalog") }
    }
}
