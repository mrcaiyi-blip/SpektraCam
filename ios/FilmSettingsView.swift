import SwiftUI
import UIKit

struct FilmSettingsView: View {
    let canMuteShutter: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @AppStorage("film_exposure_method") private var meter = FilmMeter.histogramMiddleGray
    @AppStorage(CameraCapture.shutterSoundPreference) private var shutterSoundEnabled = false
    @State private var page: Page?
    @State private var failedSupportLink: SupportLink?
    @AppStorage(L10n.preferenceKey) private var language = "system"
    private var supportLinks: [SupportLink] { SupportLink.load() }
    private enum Page { case metering, license(String, String) }

    private let background = Color(red: 11/255, green: 13/255, blue: 17/255)
    private let card = Color(red: 23/255, green: 27/255, blue: 33/255)
    private let accent = Color(red: 230/255, green: 188/255, blue: 112/255)
    private let muted = Color(red: 154/255, green: 163/255, blue: 173/255)

    var body: some View {
        VStack(spacing: 0) {
            bar
            ScrollView {
                VStack(spacing: 18) {
                    switch page {
                    case .metering:
                        section(L10n.text("raw_metering")) {
                            ForEach(FilmMeter.allCases) { method in
                                row(method.title, value: meter == method ? "✓" : "") {
                                    meter = method
                                    page = nil
                                }.accessibilityAddTraits(meter == method ? .isSelected : [])
                            }
                        }
                    case .license(_, let file):
                        section(L10n.text("license_text")) {
                            Text(license(file)).font(.system(.footnote, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    case nil:
                        section(L10n.text("capture_settings")) {
                            if canMuteShutter {
                                Toggle(L10n.text("shutter_sound"), isOn: $shutterSoundEnabled)
                            } else {
                                Text(L10n.text("shutter_sound"))
                                Text(L10n.text("shutter_sound_unavailable"))
                                    .font(.caption).foregroundColor(muted)
                            }
                        }
                        section(L10n.text("film_settings")) {
                            row(L10n.text("auto_exposure"), value: meter.title + "  ›") { page = .metering }
                            Text(L10n.text("meter_description"))
                                .font(.caption).foregroundColor(muted)
                        }
                        section(L10n.text("language")) {
                            Picker(L10n.text("language"), selection: $language) {
                                Text(L10n.text("follow_system")).tag("system")
                                Text("简体中文").tag("zh")
                                Text("English").tag("en")
                            }
                            .pickerStyle(.menu)
                        }
                        section(L10n.text("privacy")) {
                            row(L10n.text("privacy_policy"), value: "↗") {
                                openURL(URL(string: "https://github.com/mrcaiyi-blip/SpektraCam/blob/main/docs/privacy-policy.md")!)
                            }
                        }
                        if !supportLinks.isEmpty {
                            section(L10n.text("support_community")) {
                                ForEach(supportLinks) { link in
                                    row(link.title, value: link.destination + " ↗") {
                                        openSupport(link)
                                    }
                                }
                            }
                        }
                        section(L10n.text("licenses")) {
                            Link("Spektrafilm for Android · Akshay Sharma",
                                 destination: URL(string: "https://github.com/thetechgeekko/Spektrafilm-android")!)
                            Link("spektrafilm · Andrea Volpato",
                                 destination: URL(string: "https://github.com/andreavolpato/spektrafilm")!)
                            row("GPLv3", value: "›") { page = .license("GPLv3", "LICENSE") }
                            row(L10n.text("profile_license"), value: "›") { page = .license(L10n.text("profile_license"), "PROFILE_LICENSE.txt") }
                            row(L10n.text("third_party_credits"), value: "›") { page = .license(L10n.text("third_party_credits"), "NOTICE.md") }
                        }
                        section(L10n.text("sample_photo")) {
                            Link("Google HDR+ Burst Photography Dataset",
                                 destination: URL(string: "https://www.hdrplusdata.org/dataset.html")!)
                            Text(L10n.text("sample_photo_credit"))
                                .font(.caption).foregroundColor(muted)
                            Link("CC BY-SA 4.0", destination: URL(string: "https://creativecommons.org/licenses/by-sa/4.0/")!)
                        }
                    }
                }.padding(16)
            }
        }.background(background.ignoresSafeArea()).foregroundColor(.white)
            .preferredColorScheme(.dark).tint(accent)
            .environment(\.locale, L10n.locale(for: language))
            .alert(item: $failedSupportLink) { link in
                Alert(title: Text(L10n.text("cannot_open", link.destination)), message: Text(link.fallback),
                      primaryButton: .default(Text(link.copyLabel)) { UIPasteboard.general.string = link.value },
                      secondaryButton: .cancel(Text(L10n.text("cancel"))))
            }
    }

    private var title: String {
        switch page {
        case .metering: return L10n.text("film_auto_exposure")
        case .license(let title, _): return title
        case nil: return L10n.text("settings_title")
        }
    }

    private var bar: some View {
        HStack(spacing: 10) {
            Button {
                if page == nil { dismiss() }
                else { page = nil }
            } label: {
                Image(systemName: "chevron.left").frame(width: 32, height: 44)
            }.accessibilityLabel(L10n.text("back")).foregroundColor(accent)
            Text(title).font(.title3.weight(.semibold))
            Spacer()
            Text("SPEKTRACAM").font(.caption2).tracking(1).foregroundColor(muted)
        }.padding(.horizontal, 12).frame(height: 58)
            .background(background).overlay(Divider(), alignment: .bottom)
    }

    private func section<Content: View>(_ name: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(name).font(.caption2).tracking(2).foregroundColor(muted).padding(.leading, 5)
            VStack(alignment: .leading, spacing: 12, content: content)
                .padding(15).frame(maxWidth: .infinity, alignment: .leading)
                .background(card).clipShape(RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.06)))
        }
    }

    private func row(_ title: String, value: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundColor(.white)
                Spacer()
                Text(value).foregroundColor(accent)
            }.frame(minHeight: 37).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func license(_ file: String) -> String {
        guard let root = Bundle.main.resourceURL else { return L10n.text("license_unavailable") }
        let url = root.appendingPathComponent("spektrafilm").appendingPathComponent(file)
        return (try? String(contentsOf: url, encoding: .utf8)) ?? L10n.text("license_unavailable")
    }

    private func openSupport(_ link: SupportLink) {
        openURL(link.url) { accepted in
            guard !accepted else { return }
            if let fallback = link.webFallback {
                openURL(fallback) { opened in
                    if !opened { failedSupportLink = link }
                }
            } else {
                failedSupportLink = link
            }
        }
    }
}

private struct SupportLink: Identifiable {
    let title: String
    let destination: String
    let url: URL
    let webFallback: URL?
    let value: String
    let fallback: String
    let copyLabel: String

    var id: String { title }

    static func load() -> [Self] {
        guard let file = Bundle.main.url(forResource: "support-links.local", withExtension: "json"),
              let data = try? Data(contentsOf: file),
              let config = try? JSONDecoder().decode([String: String].self, from: data) else { return [] }

        var links: [Self] = []
        let account = config["alipay_account"].flatMap { value in
            value.range(of: #"\A1[0-9]{10}\z"#, options: .regularExpression) != nil ? value : nil
        }
        if let paymentCode = paymentCode(config["alipay_qr_url"]),
           let url = makeURL(scheme: "alipays", host: "platformapi", path: "/startapp", query: [
                ("saId", "10000007"), ("clientVersion", "3.7.0.0718"), ("qrcode", paymentCode.absoluteString)
           ]) {
            links.append(Self(title: L10n.text("donate"), destination: L10n.text("alipay"), url: url, webFallback: paymentCode,
                              value: account ?? paymentCode.absoluteString,
                              fallback: account == nil ? L10n.text("alipay_link_help") : L10n.text("alipay_transfer_help"),
                              copyLabel: account == nil ? L10n.text("copy_payment_link") : L10n.text("copy_phone")))
        } else if let account,
                  let url = makeURL(scheme: "alipays", host: "platformapi", path: "/startapp", query: [
                ("appId", "09999988"), ("actionType", "toAccount"), ("account", account)
           ]) {
            links.append(Self(title: L10n.text("donate"), destination: L10n.text("alipay"), url: url, webFallback: nil, value: account,
                              fallback: L10n.text("alipay_transfer_help"), copyLabel: L10n.text("copy_phone")))
        }
        if let group = config["qq_group"],
           group.range(of: #"\A[1-9][0-9]{4,11}\z"#, options: .regularExpression) != nil,
           let url = makeURL(scheme: "mqqapi", host: "card", path: "/show_pslcard", query: [
                ("src_type", "internal"), ("version", "1"), ("card_type", "group"),
                ("source", "external"), ("uin", group)
           ]) {
            links.append(Self(title: L10n.text("community"), destination: L10n.text("qq_group"), url: url, webFallback: nil, value: group,
                              fallback: L10n.text("qq_join_help"), copyLabel: L10n.text("copy_qq_group")))
        }
        return links
    }

    private static func paymentCode(_ value: String?) -> URL? {
        guard let value, let components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https", components.host?.lowercased() == "qr.alipay.com",
              components.user == nil, components.password == nil, components.port == nil,
              components.path.count > 1 else { return nil }
        return components.url
    }

    private static func makeURL(scheme: String, host: String, path: String, query: [(String, String)]) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.path = path
        let unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        components.percentEncodedQueryItems = query.map {
            URLQueryItem(name: $0.0, value: $0.1.addingPercentEncoding(withAllowedCharacters: unreserved))
        }
        return components.url
    }
}
