import SwiftUI
import UIKit

struct CameraPalette {
  let dark: Bool
  private func color(_ light: UInt32, _ night: UInt32) -> Color {
    let value = dark ? night : light
    return Color(
      red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255,
      blue: Double(value & 255) / 255)
  }
  var background: Color { color(0xf0eee8, 0x1e201f) }
  var panel: Color { color(0xfaf9f5, 0x282b27) }
  var ink: Color { color(0x242822, 0xeceee6) }
  var muted: Color { color(0x75786e, 0xa5aa9e) }
  var soft: Color { color(0xe7e6df, 0x383c34) }
  var line: Color { color(0xdcdcd4, 0x41463c) }
}
struct FilmIcon: View {
  var body: some View {
    Image(systemName: "film").resizable().scaledToFit().padding(6)
  }
}
struct CameraEntry: View {
  let film: RawIspFilmLook
  @Environment(\.colorScheme) private var scheme
  var body: some View {
    let p = CameraPalette(dark: scheme == .dark)
    VStack(alignment: .leading, spacing: 2) {
      HStack(spacing: 7) {
        FilmIcon().frame(width: 32, height: 27)
        Text(film.title).font(.system(size: 13, weight: .medium)).lineLimit(1).minimumScaleFactor(
          0.75)
      }.frame(height: 29)
      Text(film.finishTitle).font(.system(size: 11)).foregroundColor(p.muted)
        .lineLimit(1).minimumScaleFactor(0.75)
    }
    .padding(.horizontal, 8).frame(maxWidth: .infinity, alignment: .leading).frame(height: 64)
    .foregroundColor(p.ink).background(p.soft).clipShape(RoundedRectangle(cornerRadius: 11))
  }
}
struct FilmSelectionScreen: View {
  let films: [RawIspFilmLook]
  let apply: (RawIspFilmLook) -> Void
  @State private var film: RawIspFilmLook
  @State private var filter = "all_films"
  @Environment(\.colorScheme) private var scheme
  @Environment(\.dismiss) private var dismiss
  init(films: [RawIspFilmLook], selected: RawIspFilmLook,
       apply: @escaping (RawIspFilmLook) -> Void) {
    self.films = films
    self.apply = apply
    _film = State(initialValue: selected)
  }
  var body: some View {
    let p = CameraPalette(dark: scheme == .dark)
    VStack(spacing: 0) {
      HStack(spacing: 14) {
        FilmIcon().frame(width: 66, height: 48)
        VStack(alignment: .leading, spacing: 4) {
          Text(film.title).font(.system(size: 23, weight: .medium))
          if film.prints.isEmpty {
            Text(film.finishTitle).font(.system(size: 13)).foregroundColor(p.muted)
          }
        }
        Spacer()
      }.padding(.horizontal, 20).frame(height: 85)
      if !film.prints.isEmpty {
        printRow(p)
      }
      GeometryReader { geometry in
        if let root = Bundle.main.resourceURL,
           let image = UIImage(contentsOfFile: root.appendingPathComponent(film.sampleAssetPath).path) {
          Image(uiImage: image).resizable().scaledToFit()
            .frame(width: geometry.size.width, height: geometry.size.height)
        } else {
          FilmIcon().frame(maxWidth: .infinity, maxHeight: .infinity).foregroundColor(p.muted)
        }
      }.background(p.background).clipShape(RoundedRectangle(cornerRadius: 7))
        .padding(.horizontal, 20).padding(.top, 8).accessibilityLabel(L10n.text("sample_description", film.title))
      Text(L10n.text("sample_caption", film.title, film.finishTitle)).font(.system(size: 11))
        .foregroundColor(p.muted).frame(height: 30)
      HStack(spacing: 0) {
        ForEach(["all_films", "color_negative", "color_reversal", "motion_picture_negative"], id: \.self) { value in
          Button {
            filter = value
          } label: {
            VStack(spacing: 6) {
              Text(L10n.text(filterTitle(value))).font(.system(size: 14, weight: value == filter ? .semibold : .regular))
              Rectangle().fill(value == filter ? p.ink : .clear).frame(height: 2)
            }.frame(maxWidth: .infinity).foregroundColor(value == filter ? p.ink : p.muted)
          }.accessibilityAddTraits(value == filter ? .isSelected : [])
        }
      }.padding(.horizontal, 20).frame(height: 44)
      ScrollViewReader { proxy in
        ScrollView(.horizontal, showsIndicators: false) {
          HStack(spacing: 8) {
            ForEach(films.filter { filter == "all_films" || $0.group == filter }) { option in
              Button {
                if film.id != option.id { film = option }
              } label: {
                VStack(spacing: 4) {
                  FilmIcon().frame(width: 57, height: 38)
                  Text(option.title).font(.system(size: 13)).lineLimit(1).minimumScaleFactor(0.8)
                }.frame(width: 102, height: 84).background(film.id == option.id ? p.soft : p.background)
                  .clipShape(RoundedRectangle(cornerRadius: 9))
                  .overlay(
                    RoundedRectangle(cornerRadius: 9).stroke(
                      film.id == option.id ? p.ink : .clear, lineWidth: 1))
              }.accessibilityLabel(L10n.text("choose_film", option.title)).accessibilityAddTraits(
                film.id == option.id ? .isSelected : [])
            }
          }.padding(.horizontal, 20)
        }.onAppear { proxy.scrollTo(film.id, anchor: .center) }
      }.frame(height: 100)
      HStack {
        Spacer()
        Button {
          apply(film)
          dismiss()
        } label: {
          Label(L10n.text("camera"), systemImage: "arrow.down.right").font(.system(size: 14, weight: .medium))
            .frame(width: 106, height: 44).foregroundColor(p.panel).background(p.ink).clipShape(
              RoundedRectangle(cornerRadius: 10))
        }.accessibilityLabel(L10n.text("return_to_camera"))
      }.padding(.horizontal, 20).frame(height: 56)
    }.background(p.panel.ignoresSafeArea()).foregroundColor(p.ink).buttonStyle(.plain)
  }

  private func filterTitle(_ category: String) -> String {
    switch category {
    case "color_negative": return "negative_short"
    case "color_reversal": return "reversal_short"
    case "motion_picture_negative": return "cinema_short"
    default: return category
    }
  }

  private func printRow(_ palette: CameraPalette) -> some View {
    let label = film.group == "motion_picture_negative" ? L10n.text("print_film") : L10n.text("print_paper")
    return HStack(spacing: 10) {
      Text(label).font(.system(size: 13, weight: .medium)).foregroundColor(palette.muted)
        .fixedSize()
      ScrollViewReader { proxy in
        ScrollView(.horizontal, showsIndicators: false) {
          HStack(spacing: 8) {
            ForEach(film.prints) { paper in
              let selected = paper.id == film.paper
              Button {
                film = film.selectingPaper(paper.id)
              } label: {
                Text(paper.title).font(.system(size: 13, weight: selected ? .semibold : .regular))
                  .lineLimit(1).fixedSize(horizontal: true, vertical: false)
                  .padding(.horizontal, 12).frame(height: 32)
                  .background(selected ? palette.soft : palette.background)
                  .clipShape(RoundedRectangle(cornerRadius: 7))
                  .overlay(RoundedRectangle(cornerRadius: 7)
                    .strokeBorder(selected ? palette.ink : palette.line))
                  .frame(minHeight: 44).contentShape(Rectangle())
              }.accessibilityAddTraits(selected ? .isSelected : [])
            }
          }
        }
        .onAppear { proxy.scrollTo(film.paper, anchor: .center) }
        .onChange(of: film.id) { _ in proxy.scrollTo(film.paper, anchor: .center) }
      }
    }.padding(.horizontal, 20).frame(height: 44)
  }
}
