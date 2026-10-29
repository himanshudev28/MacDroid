import SwiftUI

// MARK: - General

struct GeneralSettingsPane: View {
    @Environment(AppModel.self) private var model
    @AppStorage("droiddock.holdToQuit") private var holdToQuit = true
    @AppStorage("droiddock.keepRunningOnQuit") private var keepRunning = false
    @AppStorage(DockPolicy.hideKey) private var hideDock = false
    @AppStorage(DockPolicy.openAtLaunchKey) private var openAtLaunch = true
    @State private var launchAtLogin = LaunchAtLogin.isEnabled
    @State private var needsApproval = LaunchAtLogin.needsApproval
    @State private var widgetOn: Bool?

    var body: some View {
        let c = model.config ?? DroidConfig()
        SettingsForm {
            DDSection(footer: needsApproval ? tr("Approve DroidDock in System Settings › General › Login Items.") : nil) {
                DescribedToggle(tr("Open at login"), detail: tr("Start DroidDock in the menu bar when you log in."),
                                symbol: "power", tint: Color(nsColor: .systemGreen),
                                isOn: Binding(get: { launchAtLogin }, set: { on in
                    do { try LaunchAtLogin.set(on) } catch { model.toast(.bad, error.localizedDescription) }
                    launchAtLogin = LaunchAtLogin.isEnabled
                    needsApproval = LaunchAtLogin.needsApproval
                }))
                DescribedToggle(tr("Always open window at launch"), detail: tr("Off starts DroidDock quietly in the menu bar."),
                                symbol: "macwindow", tint: Color(nsColor: .systemBlue), isOn: $openAtLaunch)
                DescribedToggle(tr("Hide Dock icon"), detail: tr("DroidDock lives in the menu bar only. Open the window or Settings from its menu bar icon."),
                                symbol: "dock.rectangle", tint: Color(nsColor: .systemIndigo),
                                isOn: Binding(get: { hideDock }, set: { on in
                    hideDock = on
                    DockPolicy.apply()
                    if on { model.toast(.info, tr("DroidDock now lives in the menu bar. Click its icon to open the window.")) }
                }))
                DescribedToggle(tr("Hold ⌘Q to quit"), detail: tr("Prevents quitting by accident. Hold the keys for a moment."),
                                symbol: "command", tint: Color(nsColor: .systemOrange), isOn: $holdToQuit)
                DescribedToggle(tr("Keep running after quit"), detail: tr("⌘Q only closes the windows. DroidDock keeps running in the menu bar, with the phone link and Quick Share still on. Choose Quit DroidDock in its menu to stop everything."),
                                symbol: "menubar.rectangle", tint: Color(nsColor: .systemTeal), isOn: $keepRunning)
            }

            DDSection(tr("Menu bar"), footer: tr("What the menu bar shows beside the icon while a phone is linked.")) {
                Picker(tr("Show beside the icon"), selection: model.cfg(\.menubarText, "menubarText")) {
                    Text(tr("Nothing")).tag("none")
                    Text(tr("Battery")).tag("battery")
                    Text(tr("Now playing")).tag("media")
                    Text(tr("Phone name")).tag("device")
                }
                if c.menubarText == "battery" {
                    Picker(tr("Battery style"), selection: model.cfg(\.menubarBatteryStyle, "menubarBatteryStyle")) {
                        Text("82%").tag("percent")
                        Text("▮▮▮▮▯").tag("bar")
                        Text("▮▮▮▮▯ 82%").tag("both")
                    }
                }
                if c.menubarText == "media" {
                    CommitSlider(title: tr("Maximum length"), range: 6...60, step: 2, value: c.menubarMaxLen,
                                 format: { tr("{n} characters", ["n": $0]) }) { model.setSetting("menubarMaxLen", $0) }
                }
                Picker(tr("Album art in the panel"), selection: model.cfg(\.menubarAlbumArt, "menubarAlbumArt")) {
                    Text(tr("Hidden")).tag("none")
                    Text(tr("Thumbnail")).tag("thumb")
                    Text(tr("Backdrop")).tag("background")
                }
                DescribedToggle(tr("Floating status widget"), detail: tr("A small always-on-top panel with battery and now-playing."),
                                isOn: Binding(get: { widgetOn ?? c.widgetEnabled }, set: { on in
                    widgetOn = on
                    Task { try? await Core.shared.widgetSet(show: on) }
                }))
            }

            DDSection(footer: tr("System follows your Mac's language. Anything not translated shows in English.")) {
                Picker(tr("Language"), selection: Binding(get: { L10n.shared.choice }, set: { L10n.shared.choice = $0 })) {
                    Text(tr("System")).tag(L10n.system)
                    Divider()
                    ForEach(L10n.languages) { Text($0.name).tag($0.code) }
                }
            }

            StorageSection()

            UpdatesSection()
        }
    }
}

struct UpdatesSection: View {
    @Environment(AppModel.self) private var model
    private enum State_ { case idle, checking, current, available(UpdateInfo), opened, error(String) }
    @State private var state: State_ = .idle

    private var info: UpdateInfo? {
        if case .available(let i) = state { return i }
        return model.updateInfo
    }
    private var busy: Bool { if case .checking = state { return true } else { return false } }

    var body: some View {
        DDSection(tr("Software update"), footer: tr("Looks for a new release shortly after launch, at most once a day. Nothing downloads without your say-so.")) {
            LabeledContent {
                if let info {
                    Button(tr("Download…")) { install(info) }.buttonStyle(PrimaryButtonStyle(compact: true))
                } else {
                    Button(tr("Check for Updates")) { check() }.disabled(busy)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(info != nil ? tr("Update to {v}", ["v": info!.version]) : tr("Version {v}", ["v": model.appVersion]))
                    Text(subtitle).font(DD.Font.caption).foregroundStyle(statusColor)
                }
            }
            DescribedToggle(tr("Check for updates automatically"), isOn: model.cfg(\.autoCheckUpdates, "autoCheckUpdates"))
        }
    }

    private var subtitle: String {
        if let info { return "\(info.currentVersion) → \(info.version)" }
        switch state {
        case .checking: return tr("Checking…")
        case .current: return tr("You're on the latest release.")
        case .opened: return tr("Opened the release page.")
        case .error(let m): return m
        default: return tr("DroidDock updates from GitHub, not the App Store.")
        }
    }
    private var statusColor: Color { if case .error = state { DD.badText } else { DD.dim } }

    private func check() {
        state = .checking
        Task {
            do { state = (try await Core.shared.updateCheck()).map { .available($0) } ?? .current }
            catch { state = .error(error.localizedDescription) }
        }
    }
    private func install(_ i: UpdateInfo) {
        Task {
            do {
                if let s = try await Core.shared.updateInstall(), let url = URL(string: s) { NSWorkspace.shared.open(url) }
                state = .opened; model.clearUpdateInfo()
            } catch { state = .error(error.localizedDescription) }
        }
    }
}

// MARK: - Appearance

struct AppearanceSettingsPane: View {
    @Environment(AppModel.self) private var model
    @AppStorage(AppLayout.defaultsKey) private var layoutRaw = AppLayout.phoneTabs.rawValue

    var body: some View {
        @Bindable var look = AppearanceSettings.shared
        SettingsForm {
            DDSection(tr("Window layout"), footer: tr("Switches immediately. Phone tabs keeps the phone beside five tabs; Classic is a plain sidebar of every screen.")) {
                HStack(alignment: .top, spacing: DD.Space.md) {
                    ForEach(AppLayout.allCases) { l in
                        LayoutThumbnail(layout: l, selected: layoutRaw == l.rawValue) { layoutRaw = l.rawValue }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, DD.Space.xs)
            }
            DDSection(tr("Theme"), footer: tr("DroidDock uses the accent colour from System Settings › Appearance.")) {
                Picker(tr("Theme"), selection: $look.theme) {
                    Text(tr("System")).tag(ThemeChoice.system)
                    Text(tr("Light")).tag(ThemeChoice.light)
                    Text(tr("Dark")).tag(ThemeChoice.dark)
                }.pickerStyle(.segmented)
                DescribedToggle(tr("Reduce transparency"), detail: tr("Solid panels instead of translucent ones. Also follows the macOS accessibility setting."),
                                symbol: "square.on.square.dashed", tint: Color(nsColor: .systemGray), isOn: $look.reduceTransparencyPreference)
            }
            DDSection(tr("Window glass"), symbol: "drop.halffull",
                      footer: look.reduceTransparency
                        ? tr("Reduce transparency is on, so the window is solid.")
                        : tr("Slide left to let the desktop show through the window, right for a solid window. Text stays readable at every setting.")) {
                LabeledContent {
                    HStack(spacing: DD.Space.md) {
                        Image(systemName: "circle.dotted").foregroundStyle(DD.dim).accessibilityHidden(true)
                        Slider(value: $look.liquidOpacity, in: 0...1)
                            .frame(width: 180)
                            .accessibilityLabel(tr("Liquid opacity"))
                            .accessibilityValue("\(Int((look.liquidOpacity * 100).rounded())) %")
                        Image(systemName: "circle.fill").foregroundStyle(DD.dim).accessibilityHidden(true)
                        Text("\(Int((look.liquidOpacity * 100).rounded()))%").monospacedDigit().foregroundStyle(DD.dim)
                            .frame(width: 40, alignment: .trailing)
                    }
                    .disabled(look.reduceTransparency)
                } label: {
                    RowLabel(title: tr("Liquid opacity"), detail: tr("How see-through the window background is."),
                             symbol: "drop.fill", tint: Color(nsColor: .systemCyan))
                }
            }
        }
    }
}

/// A miniature wireframe of one window layout.
private struct LayoutThumbnail: View {
    let layout: AppLayout
    let selected: Bool
    let action: () -> Void
    private var accent: Color { .accentColor }

    var body: some View {
        Button(action: action) {
            VStack(spacing: DD.Space.sm) {
                wire
                    .frame(width: 112, height: 76)
                    .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(DD.content))
                    .overlay(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous)
                        .strokeBorder(selected ? accent : DD.line, lineWidth: selected ? 2 : 1))
                Text(layout.title).font(DD.Font.callout)
                    .fontWeight(selected ? .semibold : .regular)
                    .foregroundStyle(selected ? DD.fg : DD.dim)
            }
        }
        .buttonStyle(PressableButtonStyle())   // tile-like custom button: press 0.97
        .ddAnimation(Motion.select, value: selected)
        .accessibilityLabel(layout.title)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }

    private func bar(_ w: CGFloat, _ h: CGFloat = 4, _ o: Double = 0.3) -> some View {
        Capsule().fill(DD.dim.opacity(o)).frame(width: w, height: h)
    }
    private var card: some View {
        RoundedRectangle(cornerRadius: 5, style: .continuous).fill(selected ? accent.opacity(0.85) : DD.dim.opacity(0.45))
    }
    private var sideBG: some View { Rectangle().fill(DD.panel2) }

    @ViewBuilder private var wire: some View {
        HStack(spacing: 0) {
            switch layout {
            case .phoneTabs:
                sideBG.frame(width: 34).overlay { card.padding(4) }
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 3) { ForEach(0..<5, id: \.self) { i in
                        Capsule().fill(i == 0 ? accent.opacity(0.6) : DD.dim.opacity(0.25)).frame(width: 9, height: 5) } }
                    ForEach(0..<4, id: \.self) { i in bar(CGFloat(58 - i * 7)) }
                    Spacer()
                }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
            case .minimal:
                sideBG.frame(width: 34).overlay {
                    VStack(spacing: 3) { card.frame(height: 32)
                        HStack(spacing: 2) { ForEach(0..<4, id: \.self) { _ in Circle().fill(DD.dim.opacity(0.35)).frame(width: 4, height: 4) } }
                        Spacer() }.padding(4)
                }
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 3) { ForEach(0..<3, id: \.self) { i in
                        Circle().fill(i == 0 ? accent.opacity(0.6) : DD.dim.opacity(0.25)).frame(width: 6, height: 6) } }
                    ForEach(0..<3, id: \.self) { _ in bar(50, 6, 0.2) }
                    Spacer()
                }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
            case .grouped:
                sideBG.frame(width: 34).overlay {
                    VStack(alignment: .leading, spacing: 3) {
                        card.frame(height: 20)
                        ForEach(0..<2, id: \.self) { _ in bar(16, 3, 0.45) ; bar(22, 3, 0.25); bar(20, 3, 0.25) }
                        Spacer()
                    }.padding(4)
                }
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(0..<5, id: \.self) { i in bar(CGFloat(60 - (i * 5) % 17), 5, 0.2) }
                    Spacer()
                }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
            case .classic:
                sideBG.frame(width: 34).overlay {
                    VStack(alignment: .leading, spacing: 3) {
                        bar(14, 3, 0.45)
                        ForEach(0..<3, id: \.self) { i in
                            RoundedRectangle(cornerRadius: 2).fill(i == 0 ? accent.opacity(0.55) : DD.dim.opacity(0.2)).frame(height: 5) }
                        bar(14, 3, 0.45)
                        ForEach(0..<3, id: \.self) { _ in RoundedRectangle(cornerRadius: 2).fill(DD.dim.opacity(0.2)).frame(height: 5) }
                        Spacer()
                    }.padding(4)
                }
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(0..<5, id: \.self) { i in bar(CGFloat(60 - (i * 5) % 17), 5, 0.2) }
                    Spacer()
                }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous))
    }
}
