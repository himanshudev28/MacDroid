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
    private var installing: Bool { model.updateInstallState == .installing }

    var body: some View {
        DDSection(tr("Software update"), footer: tr("Looks for a new release shortly after launch, at most once a day. Nothing downloads without your say-so. After an update, macOS may ask for Accessibility access again.")) {
            LabeledContent {
                if installing {
                    ProgressView(value: (model.updateProgress?.percent ?? 0) / 100).frame(width: 120)
                } else if model.updateInstallState == .updated {
                    EmptyView()
                } else if let info {
                    HStack(spacing: DD.Space.sm) {
                        if case .failed = model.updateInstallState {
                            Button(tr("Open release page")) { model.openReleasePage(info.htmlUrl) }.buttonStyle(SecondaryButtonStyle(compact: true))
                        }
                        Button(info.installAvailable == true ? tr("Install and restart") : tr("Download…")) { install(info) }
                            .buttonStyle(PrimaryButtonStyle(compact: true))
                    }
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
        if installing { return model.updateProgressText }
        if case .failed(let m) = model.updateInstallState { return m }
        if model.updateInstallState == .updated { return model.updateProgressText }
        if let info { return "\(info.currentVersion) → \(info.version)" }
        switch state {
        case .checking: return tr("Checking…")
        case .current: return tr("You're on the latest release.")
        case .opened: return tr("Opened the release page.")
        case .error(let m): return m
        default: return tr("DroidDock updates from GitHub, not the App Store.")
        }
    }
    private var statusColor: Color {
        if case .error = state { return DD.badText }
        if case .failed = model.updateInstallState { return DD.badText }
        return DD.dim
    }

    private func check() {
        state = .checking
        Task {
            do { state = (try await Core.shared.updateCheck()).map { .available($0) } ?? .current }
            catch { state = .error(error.localizedDescription) }
        }
    }
    private func install(_ i: UpdateInfo) {
        Task {
            if case .openedPage = await model.installUpdate() { state = .opened }
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
            DDSection(tr("Window layout"),
                      footer: (AppLayout(rawValue: layoutRaw) ?? .phoneTabs).summary + " " + tr("Switches immediately.")) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120, maximum: 168), spacing: DD.Space.lg, alignment: .top)],
                          alignment: .leading, spacing: DD.Space.lg) {
                    ForEach(AppLayout.allCases) { l in
                        LayoutTile(layout: l, selected: layoutRaw == l.rawValue) { layoutRaw = l.rawValue }
                    }
                }
                .padding(.vertical, DD.Space.sm)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(tr("Window layout"))
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

/// A miniature wireframe of one window layout, drawn in system colours (like System Settings › Appearance).
private struct LayoutTile: View {
    let layout: AppLayout
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.colorSchemeContrast) private var contrast
    private var accent: Color { .accentColor }
    private var sideFill: Color { Color(nsColor: .quaternarySystemFill) }
    private var ink: Color { Color(nsColor: .tertiaryLabelColor) }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                wire
                    .aspectRatio(16.0 / 10.0, contentMode: .fit)
                    .background(Color(nsColor: .windowBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(DD.line, lineWidth: 1))
                    .padding(3)
                    .overlay {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(selected ? accent : (hovering ? DD.hover : .clear),
                                          lineWidth: selected ? (contrast == .increased ? 3.5 : 3) : 3)
                    }
                    .accessibilityHidden(true)
                Text(layout.title).font(DD.Font.callout).fontWeight(.medium).foregroundStyle(DD.fg)
            }
        }
        .buttonStyle(PressableButtonStyle())   // tile-like custom button: press 0.97
        .onHover { h in withDDAnimation(Motion.hover) { hovering = h } }
        .ddAnimation(Motion.select, value: selected)
        .help(layout.summary)
        .accessibilityLabel(layout.title)
        .accessibilityHint(layout.summary)
        .accessibilityValue(selected ? tr("Selected") : "")
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }

    private func bar(_ w: CGFloat, _ h: CGFloat = 3, _ o: Double = 0.5) -> some View {
        Capsule().fill(ink.opacity(o)).frame(width: w, height: h)
    }
    private var card: some View {
        RoundedRectangle(cornerRadius: 4, style: .continuous).fill(selected ? accent.opacity(0.85) : ink.opacity(0.9))
    }
    private func row(_ on: Bool = false) -> some View {
        RoundedRectangle(cornerRadius: 2, style: .continuous).fill(on ? accent.opacity(0.7) : ink.opacity(0.35)).frame(height: 4)
    }
    private var toolbarStrip: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { _ in Circle().fill(ink.opacity(0.7)).frame(width: 3.5, height: 3.5) }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 5).frame(height: 10).frame(maxWidth: .infinity)
    }
    private var contentBars: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(0..<4, id: \.self) { i in bar(CGFloat(56 - i * 8), 4, 0.25) }
            Spacer(minLength: 0)
        }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var wire: some View {
        GeometryReader { g in
            let W = g.size.width, H = g.size.height
            VStack(spacing: 0) {
                switch layout {
                case .phoneTabs:
                    HStack(spacing: 0) {
                        toolbarStrip.frame(width: W * 0.30)
                        HStack(spacing: 2) {
                            ForEach(0..<5, id: \.self) { i in
                                Capsule().fill(i == 0 ? accent.opacity(0.75) : ink.opacity(0.4)).frame(width: 8, height: 4) }
                        }.frame(maxWidth: .infinity)
                    }.frame(height: 10)
                    HStack(spacing: 0) {
                        ZStack(alignment: .bottom) {
                            sideFill
                            VStack(spacing: 3) {
                                card.padding(.horizontal, 4).padding(.top, 2)
                                HStack(spacing: 3) { ForEach(0..<5, id: \.self) { _ in Circle().fill(ink.opacity(0.6)).frame(width: 3, height: 3) } }
                                    .padding(.bottom, 3)
                            }
                        }.frame(width: W * 0.30)
                        contentBars
                    }
                case .classic:
                    HStack(spacing: 0) {
                        toolbarStrip.frame(width: W * 0.22)
                        Spacer(minLength: 0)
                    }.frame(height: 10)
                    HStack(spacing: 0) {
                        VStack(alignment: .leading, spacing: 3) {
                            bar(12, 2.5, 0.6)
                            row(true); row(); row()
                            bar(12, 2.5, 0.6)
                            row(); row(); row()
                            Spacer(minLength: 0)
                        }.padding(4).frame(width: W * 0.22).background(sideFill)
                        contentBars
                        card.padding(4).frame(width: W * 0.24, height: H * 0.7, alignment: .top).frame(maxHeight: .infinity, alignment: .top)
                            .background(sideFill)
                    }
                case .grouped:
                    HStack(spacing: 0) { toolbarStrip.frame(width: W * 0.30); Spacer(minLength: 0) }.frame(height: 10)
                    HStack(spacing: 0) {
                        VStack(alignment: .leading, spacing: 3) {
                            card.frame(height: (H - 10) * 0.36)
                            bar(12, 2.5, 0.6); row(true); row(); row()
                            bar(12, 2.5, 0.6); row(); row()
                            Spacer(minLength: 0)
                        }.padding(4).frame(width: W * 0.30).background(sideFill)
                        contentBars
                    }
                case .minimal:
                    HStack(spacing: 0) {
                        toolbarStrip.frame(width: W * 0.34)
                        HStack(spacing: 3) {
                            ForEach(0..<5, id: \.self) { i in Circle().fill(i == 0 ? accent.opacity(0.75) : ink.opacity(0.4)).frame(width: 4.5, height: 4.5) }
                            Capsule().fill(ink.opacity(0.4)).frame(width: 6, height: 3)
                        }.frame(maxWidth: .infinity)
                    }.frame(height: 10)
                    HStack(spacing: 0) {
                        VStack(spacing: 3) {
                            card.frame(height: (H - 10) * 0.62)
                            HStack(spacing: 2) { ForEach(0..<6, id: \.self) { _ in Circle().fill(ink.opacity(0.6)).frame(width: 3.5, height: 3.5) } }
                            Spacer(minLength: 0)
                        }.padding(4).frame(width: W * 0.34).background(sideFill)
                        VStack(alignment: .leading, spacing: 5) {
                            ForEach(0..<3, id: \.self) { _ in bar(50, 6, 0.2) }
                            Spacer(minLength: 0)
                        }.padding(6).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }
}
