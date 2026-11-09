import Foundation

enum L10n {
    static let preferenceKey = "app_language"

    static func language(for choice: String) -> String {
        switch choice {
        case "zh", "en": return choice
        default:
            let system = Locale.preferredLanguages.first ?? "en"
            let language = system.split(whereSeparator: { $0 == "-" || $0 == "_" }).first
            switch language?.lowercased() {
            case "zh", "yue", "cmn": return "zh"
            default: return "en"
            }
        }
    }

    static func locale(for choice: String) -> Locale {
        Locale(identifier: language(for: choice) == "zh" ? "zh-Hans" : "en")
    }

    private static let english = Bundle.main.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:))
    private static let chinese = Bundle.main.path(forResource: "zh", ofType: "lproj").flatMap(Bundle.init(path:))

    static func text(_ key: String, _ arguments: CVarArg...) -> String {
        let choice = UserDefaults.standard.string(forKey: preferenceKey) ?? "system"
        let bundle = language(for: choice) == "zh" ? chinese : english
        let localized = bundle?.localizedString(forKey: key, value: key, table: nil) ?? key
        return arguments.isEmpty ? localized : String(format: localized, locale: locale(for: choice), arguments: arguments)
    }

    static func photoLabel(_ value: String) -> String {
        switch value {
        case "": return text("unknown")
        case "direct_scan", "正片直扫", "Direct scan": return text("direct_scan")
        default: return value
        }
    }
}
