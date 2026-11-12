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
  let apply: (RawIspFilmLook, FilmBorder, FilmBorder.TextSettings) -> Void
  @State private var film: RawIspFilmLook
  @State private var border: FilmBorder
  @State private var filter = "all_films"
  @State private var sampleSource: (path: String, image: UIImage)?
  @State private var sample: UIImage?
  @State private var borderText = FilmBorder.TextSettings.current
  @State private var editingBorderText = false
  @State private var customDraft = ""
  @FocusState private var borderTextFocused: Bool
  @Environment(\.colorScheme) private var scheme
  @Environment(\.dismiss) private var dismiss
  init(films: [RawIspFilmLook], selected: RawIspFilmLook, selectedBorder: FilmBorder,
       apply: @escaping (RawIspFilmLook, FilmBorder, FilmBorder.TextSettings) -> Void) {
    self.films = films
    self.apply = apply
    _film = State(initialValue: selected)
    _border = State(initialValue: selectedBorder)
  }
  var body: some View {
    GeometryReader { geometry in
      content(compact: geometry.size.height < 500)
    }
    .onAppear(perform: updateSample)
    .onChange(of: film.sampleAssetPath) { _ in updateSample() }
    .onChange(of: border) { _ in updateSample() }
    .onChange(of: borderText) { _ in updateSample() }
    .sheet(isPresented: $editingBorderText) {
      if #available(iOS 16, *) {
        borderTextEditor.presentationDetents([.height(150)])
      } else {
        borderTextEditor
      }
    }
  }

  private func content(compact: Bool) -> some View {
    let p = CameraPalette(dark: scheme == .dark)
    return VStack(spacing: 0) {
      HStack(spacing: 14) {
        FilmIcon().frame(width: 66, height: 48)
        Text(film.title).font(.system(size: 23, weight: .medium))
        Spacer()
      }.padding(.horizontal, 20).frame(height: compact ? 48 : 85)
      printRow(p, compact: compact)
      borderRow(p, compact: compact)
      borderTextRow(p, compact: compact)
      GeometryReader { geometry in
        if let sample {
          Image(uiImage: sample).resizable().scaledToFit()
            .frame(width: geometry.size.width, height: geometry.size.height)
        } else {
          FilmIcon().frame(maxWidth: .infinity, maxHeight: .infinity).foregroundColor(p.muted)
        }
      }.background(p.background).clipShape(RoundedRectangle(cornerRadius: 7))
        .padding(.horizontal, 20).padding(.top, 8).accessibilityLabel(L10n.text("sample_description", film.title))
      Text(L10n.text("sample_caption", film.title, film.finishTitle)).font(.system(size: 11))
        .foregroundColor(p.muted).frame(height: compact ? 20 : 30)
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
      }.padding(.horizontal, 20).frame(height: compact ? 36 : 44)
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
                }.frame(width: 102, height: compact ? 64 : 84).background(film.id == option.id ? p.soft : p.background)
                  .clipShape(RoundedRectangle(cornerRadius: 9))
                  .overlay(
                    RoundedRectangle(cornerRadius: 9).stroke(
                      film.id == option.id ? p.ink : .clear, lineWidth: 1))
              }.accessibilityLabel(L10n.text("choose_film", option.title)).accessibilityAddTraits(
                film.id == option.id ? .isSelected : [])
            }
          }.padding(.horizontal, 20)
        }.onAppear { proxy.scrollTo(film.id, anchor: .center) }
      }.frame(height: compact ? 64 : 100)
      HStack {
        Spacer()
        Button {
          apply(film, border, borderText)
          dismiss()
        } label: {
          Label(L10n.text("camera"), systemImage: "arrow.down.right").font(.system(size: 14, weight: .medium))
            .frame(width: 106, height: 44).foregroundColor(p.panel).background(p.ink).clipShape(
              RoundedRectangle(cornerRadius: 10))
        }.accessibilityLabel(L10n.text("return_to_camera"))
      }.padding(.horizontal, 20).frame(height: compact ? 44 : 56)
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

  private func updateSample() {
    let path = film.sampleAssetPath
    if sampleSource?.path != path {
      if let root = Bundle.main.resourceURL,
         let image = UIImage(contentsOfFile: root.appendingPathComponent(path).path) {
        sampleSource = (path, image)
      } else {
        sampleSource = nil
      }
    }
    sample = sampleSource.map { border.render($0.image, caption: borderText.caption(for: border, film: film.title)) }
  }

  private func printRow(_ palette: CameraPalette, compact: Bool) -> some View {
    let label = film.group == "motion_picture_negative" ? L10n.text("print_film") : L10n.text("print_paper")
    return HStack(spacing: 10) {
      Text(label).font(.system(size: 13, weight: .medium)).foregroundColor(palette.muted)
        .fixedSize()
      if film.prints.isEmpty {
        Text(film.finishTitle).font(.system(size: 13)).foregroundColor(palette.muted)
        Spacer()
      } else {
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
                    .frame(minHeight: compact ? 36 : 44).contentShape(Rectangle())
                }.accessibilityAddTraits(selected ? .isSelected : [])
              }
            }
          }
          .onAppear { proxy.scrollTo(film.paper, anchor: .center) }
          .onChange(of: film.id) { _ in proxy.scrollTo(film.paper, anchor: .center) }
        }
      }
    }.padding(.horizontal, 20).frame(height: compact ? 36 : 44)
  }

  private func borderRow(_ palette: CameraPalette, compact: Bool) -> some View {
    HStack(spacing: 10) {
      Text(L10n.text("film_border")).font(.system(size: 13, weight: .medium))
        .foregroundColor(palette.muted).fixedSize()
      HStack(spacing: 5) {
        ForEach(FilmBorder.allCases) { option in
          let selected = option == border
          Button {
            border = option
          } label: {
            VStack(spacing: 2) {
              Image(uiImage: option.swatch).accessibilityHidden(true)
              Text(option.title).font(.system(size: 11)).lineLimit(1).minimumScaleFactor(0.8)
            }.frame(maxWidth: .infinity).frame(height: compact ? 44 : 50)
              .background(selected ? palette.soft : palette.background)
              .clipShape(RoundedRectangle(cornerRadius: 8))
              .overlay(RoundedRectangle(cornerRadius: 8)
                .strokeBorder(selected ? palette.ink : palette.line))
          }
          .accessibilityIdentifier("film-border-\(option.id)")
          .accessibilityAddTraits(selected ? .isSelected : [])
        }
      }
    }.padding(.horizontal, 20).frame(height: compact ? 44 : 52)
  }

  private func borderTextRow(_ palette: CameraPalette, compact: Bool) -> some View {
    HStack {
      Text(L10n.text("film_border_text")).font(.system(size: 13, weight: .medium))
        .foregroundColor(palette.muted)
      Spacer()
      Menu {
        ForEach(FilmBorder.TextMode.allCases) { mode in
          Button {
            if mode == .custom {
              customDraft = borderText.custom
              editingBorderText = true
            } else {
              borderText = FilmBorder.TextSettings(mode: mode, custom: borderText.custom)
            }
          } label: {
            if mode == borderText.mode { Label(mode.title, systemImage: "checkmark") }
            else { Text(mode.title) }
          }
        }
      } label: {
        HStack(spacing: 6) {
          Text(borderText.mode.title).font(.system(size: 13))
          Image(systemName: "chevron.down").font(.system(size: 9, weight: .medium))
        }
        .padding(.horizontal, 10).frame(height: 32)
        .foregroundColor(palette.ink).background(palette.background)
        .clipShape(RoundedRectangle(cornerRadius: 7))
        .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(palette.line))
        .frame(height: compact ? 36 : 44).contentShape(Rectangle())
      }
      .accessibilityLabel(L10n.text("film_border_text"))
      .accessibilityValue(borderText.mode.title)
    }.padding(.horizontal, 20).frame(height: compact ? 36 : 44)
  }

  private var borderTextEditor: some View {
    let palette = CameraPalette(dark: scheme == .dark)
    return VStack(spacing: 16) {
      HStack {
        Button(L10n.text("cancel")) { editingBorderText = false }
        Spacer()
        Text(L10n.text("film_border_text")).font(.headline)
        Spacer()
        Button(L10n.text("done"), action: applyCustomText).font(.body.weight(.semibold))
      }.frame(minHeight: 44)
      TextField(L10n.text("film_border_text_placeholder"), text: $customDraft)
        .textFieldStyle(.roundedBorder)
        .focused($borderTextFocused)
        .submitLabel(.done)
        .onSubmit(applyCustomText)
      Spacer(minLength: 0)
    }.padding(20).foregroundColor(palette.ink).background(palette.panel.ignoresSafeArea())
      .onAppear { borderTextFocused = true }
  }

  private func applyCustomText() {
    borderText = FilmBorder.TextSettings(mode: .custom, custom: customDraft)
    editingBorderText = false
  }
}
