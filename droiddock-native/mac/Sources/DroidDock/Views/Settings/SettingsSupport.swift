import SwiftUI

/// The panes of the Settings window (also rendered inside the Classic layout's Settings destination).
enum SettingsPane: String, CaseIterable, Identifiable {
    case general, appearance, connection, notifications, mirroring, files, privacy, shortcuts, health, about
    var id: String { rawValue }
    static let defaultsKey = "droiddock.settingsPane"

    var title: String {
        switch self {
        case .general: tr("General")
        case .appearance: tr("Appearance")
        case .connection: tr("Connection")
        case .notifications: tr("Notifications")
        case .mirroring: tr("Mirroring & Media")
        case .files: tr("Files & Sharing")
        case .privacy: tr("Remote & Privacy")
        case .shortcuts: tr("Shortcuts")
        case .health: tr("Health")
        case .about: tr("About")
        }
    }
    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .appearance: "paintbrush"
        case .connection: "wifi"
        case .notifications: "bell.badge"
        case .mirroring: "rectangle.on.rectangle"
        case .files: "folder"
        case .privacy: "lock.shield"
        case .shortcuts: "command"
        case .health: "stethoscope"
        case .about: "info.circle"
        }
    }
    /// Sidebar icon tile colour (System Settings style).
    var tint: Color {
        switch self {
        case .general: Color(nsColor: .systemGray)
        case .appearance: Color(nsColor: .systemIndigo)
        case .connection: Color(nsColor: .systemBlue)
        case .notifications: Color(nsColor: .systemRed)
        case .mirroring: Color(nsColor: .systemPurple)
        case .files: Color(nsColor: .systemGreen)
        case .privacy: Color(nsColor: .systemTeal)
        case .shortcuts: Color(nsColor: .systemOrange)
        case .health: Color(nsColor: .systemPink)
        case .about: Color(nsColor: .systemGray)
        }
    }
}

extension AppModel {
    /// Binding onto a `DroidConfig` field that writes through `set_setting`.
    func cfg<T>(_ kp: KeyPath<DroidConfig, T>, _ key: String) -> Binding<T> {
        Binding(get: { (self.config ?? DroidConfig())[keyPath: kp] },
                set: { self.setSetting(key, $0) })
    }
}

/// Grouped-form section: native System Settings look (the Form draws its own card). The header carries an SF Symbol.
struct DDSection<Content: View>: View {
    let title: String?
    var symbol: String?
    var footer: String?
    @ViewBuilder var content: Content
    init(_ title: String? = nil, symbol: String? = nil, footer: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title; self.symbol = symbol; self.footer = footer; self.content = content()
    }
    var body: some View {
        Section {
            content
        } header: {
            if let title {
                if let sym = symbol ?? Self.defaultSymbol(title) {
                    Label(title, systemImage: sym).labelStyle(SectionHeaderLabelStyle())
                } else { Text(title) }
            }
        } footer: {
            if let footer {
                Text(footer).font(DD.Font.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Header symbols for the sections that existed before headers had icons (keyed by the English title).
    private static func defaultSymbol(_ title: String) -> String? {
        [tr("Menu bar"): "menubar.rectangle", tr("Software update"): "arrow.down.circle", tr("Storage"): "internaldrive",
         tr("Window layout"): "rectangle.3.group", tr("Theme"): "circle.lefthalf.filled", tr("Pair your phone"): "qrcode",
         tr("This Mac"): "desktopcomputer", tr("Share with the phone"): "arrow.left.arrow.right", tr("Pause"): "pause.circle",
         tr("Wireless ADB"): "antenna.radiowaves.left.and.right", tr("Notification click actions"): "cursorarrow.click.2",
         tr("Quality"): "dial.medium", tr("Desktop mode & app windows"): "macwindow", tr("scrcpy"): "terminal",
         tr("Freeform windows on the phone"): "macwindow.on.rectangle", tr("Mac files for the phone"): "externaldrive",
         tr("Phone in Finder"): "folder", tr("Photo sync"): "photo.on.rectangle", tr("Quick Share"): "bolt.horizontal",
         tr("Remote control"): "cursorarrow.motionlines", tr("Desktops"): "rectangle.on.rectangle", tr("Privacy"): "hand.raised",
         tr("Crash reports"): "ladybug", tr("Global shortcuts"): "command", tr("Keyboard shortcuts"): "keyboard",
         tr("Control Center"): "switch.2", tr("Technical"): "wrench.and.screwdriver", tr("Links"): "link"][title]
    }
}

/// Section header like System Settings: small accent-neutral symbol, then the title.
struct SectionHeaderLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon.symbolRenderingMode(.hierarchical).foregroundStyle(.secondary)
            configuration.title
        }
    }
}

/// Leading coloured rounded-square icon (System Settings row icon).
struct IconTile: View {
    let symbol: String
    var tint: Color = Color(nsColor: .systemBlue)
    var size: CGFloat = 22
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.55, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous).fill(tint.gradient))
            .accessibilityHidden(true)
    }
}

/// Row label: optional icon tile, title, secondary subtitle. Use as the `label:` of LabeledContent / Toggle / Picker.
struct RowLabel: View {
    let title: String
    var detail: String?
    var symbol: String?
    var tint: Color = Color(nsColor: .systemBlue)
    var body: some View {
        HStack(spacing: DD.Space.md) {
            if let symbol { IconTile(symbol: symbol, tint: tint) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let detail {
                    Text(detail).font(DD.Font.caption).foregroundStyle(DD.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// Standard pane wrapper: grouped Form on the window material.
struct SettingsForm<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        Form { content }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .contentMargins(.top, 8, for: .scrollContent)
    }
}

/// Toggle with an optional leading icon tile and a secondary description line.
struct DescribedToggle: View {
    let title: String
    var detail: String?
    var symbol: String?
    var tint: Color
    @Binding var isOn: Bool
    init(_ title: String, detail: String? = nil, symbol: String? = nil, tint: Color = Color(nsColor: .systemBlue), isOn: Binding<Bool>) {
        self.title = title; self.detail = detail; self.symbol = symbol; self.tint = tint; self._isOn = isOn
    }
    var body: some View {
        Toggle(isOn: $isOn) { RowLabel(title: title, detail: detail, symbol: symbol, tint: tint) }
            .toggleStyle(.switch)
    }
}

/// Slider that commits once on release (so dragging does not spam the core).
struct CommitSlider: View {
    let title: String
    let range: ClosedRange<Double>
    var step: Double = 1
    let value: Int
    var format: (Int) -> String = { "\($0)" }
    let commit: (Int) -> Void
    @State private var local: Double = 0
    @State private var editing = false

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: DD.Space.md) {
                Slider(value: $local, in: range, step: step) { editing = $0; if !$0 { commit(Int(local)) } }
                    .frame(width: 180)
                Text(format(Int(local))).monospacedDigit().foregroundStyle(DD.dim)
                    .frame(width: 64, alignment: .trailing)
            }
        }
        .onAppear { local = Double(value) }
        .onChange(of: value) { _, v in if !editing { local = Double(v) } }
    }
}
