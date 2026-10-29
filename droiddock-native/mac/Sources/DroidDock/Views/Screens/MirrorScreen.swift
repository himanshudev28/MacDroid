import SwiftUI

/// Mirror screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct MirrorScreen: View {
    @Environment(AppModel.self) private var model
    @AppStorage(MirrorSession.inWindowKey) private var inWindow = false
    @State private var busy: String?

    private var session: MirrorSession { MirrorSession.shared }
    private var live: Bool { session.isActive && session.source == "screen" }
    private var tools: ToolsStatus { model.tools ?? ToolsStatus() }
    private var serial: String? { model.devices.first { $0.state == "device" }?.serial }
    private var defaultMode: String { model.config?.defaultMirrorMode ?? "wifi" }

    var body: some View {
        Group {
            if live && session.placement == .inWindow {
                MirrorStage(inWindow: true).padding(DD.Space.lg)
            } else if live && session.placement == .popout {
                runningCard
            } else {
                launcher
            }
        }
        .animation(Motion.state, value: live)
        .onDisappear {
            // An in-window stream nobody can see still costs the phone's encoder, the network and a 60 fps decode.
            // Leaving the screen moves it to its own window; if the main window itself is gone, it stops.
            guard session.placement == .inWindow else { return }
            if WindowControl.mainWindow?.isVisible == true { session.openPopout() } else { session.stop() }
        }
        .onChange(of: session.phase) { _, p in
            if case .failed = p { busy = nil }
            if p == .live { busy = nil }
        }
    }

    // MARK: Running in pop-out

    private var runningCard: some View {
        VStack { Spacer(minLength: 0)
            Card(padding: DD.Space.xl, raised: true) {
                VStack(spacing: DD.Space.md) {
                    Image(systemName: "rectangle.on.rectangle").font(.system(size: 28))
                        .symbolRenderingMode(.hierarchical).foregroundStyle(DD.dim)
                    StatusPill(text: session.isLive ? tr("Mirroring") : tr("Waiting for the phone…"),
                               kind: session.isLive ? .link : .neutral)
                    Text(tr("Your phone is streaming in its own window. Move it, resize it, or pin it on top."))
                        .font(DD.Font.callout).foregroundStyle(DD.dim).multilineTextAlignment(.center)
                        .frame(maxWidth: 300)
                    HStack(spacing: DD.Space.sm) {
                        Button(tr("Bring to front")) { session.setPlacement(inWindow: false) }
                            .buttonStyle(SecondaryButtonStyle())
                        Button(tr("Show here")) { session.setPlacement(inWindow: true) }
                            .buttonStyle(SecondaryButtonStyle())
                        Button(tr("Stop")) { session.stop() }.buttonStyle(DestructiveButtonStyle())
                    }
                }.frame(maxWidth: .infinity)
            }.frame(maxWidth: 380)
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity)
    }

    // MARK: Launcher

    private var launcher: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DD.Space.lg) {
                VStack(alignment: .leading, spacing: DD.Space.xs) {
                    Text(tr("Mirror")).font(DD.Font.largeTitle).foregroundStyle(DD.fg)
                    Text(tr("Put your phone's screen in a window on this Mac.")).font(DD.Font.body).foregroundStyle(DD.dim)
                }
                if case .failed(let msg) = session.phase {
                    Label(msg, systemImage: "exclamationmark.triangle")
                        .font(DD.Font.callout).foregroundStyle(DD.bad)
                        .padding(DD.Space.md).frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(DD.bad.opacity(0.10)))
                }

                MirrorLaunchCard(
                    symbol: "wifi", title: tr("Wi-Fi mirror"),
                    subtitle: tr("Opens a phone-shaped window over Wi-Fi. No ADB, scrcpy, or Developer Options needed."),
                    tag: "Wi-Fi", primary: defaultMode == "wifi", live: model.isConnected,
                    requirement: model.isConnected ? nil : tr("Phone app link required"),
                    hint: tr("Pair the DroidDock phone app from the Dashboard to mirror over Wi-Fi."),
                    button: tr("Start mirroring"), busy: busy == "wifi" || session.phase == .connecting) {
                    run("wifi") {
                        // "Prefer ADB mirroring": the embedded scrcpy stream has no screen-share prompt.
                        if UserDefaults.standard.object(forKey: MirrorSession.preferAdbKey) as? Bool ?? true,
                           let serial, tools.scrcpy {
                            session.markRequested(source: "screen")
                            do { try await Core.shared.scrcpyEmbeddedStart(serial: serial); return }
                            catch { dlog("mirror: ADB preferred but failed, using Wi-Fi: \(error)") }
                        }
                        await session.start(source: "screen")
                        model.toast(.info, tr("Approve screen capture on your phone…"))
                    }
                }

                MirrorLaunchCard(
                    symbol: "cable.connector", title: tr("ADB mirror"),
                    subtitle: tr("Full-quality, low-latency mirroring over USB or wireless ADB via scrcpy, in its own window."),
                    tag: "ADB", primary: defaultMode == "adb", live: serial != nil,
                    requirement: adbRequirement(), hint: adbHint(),
                    button: tr("Mirror via ADB"), busy: busy == "adb") {
                    run("adb") { try await Core.shared.adbMirror(serial: serial ?? "") }
                }

                MirrorLaunchCard(
                    symbol: "macwindow.badge.plus", title: tr("ADB mirror, in this app"),
                    subtitle: tr("Same scrcpy stream, but it plays in DroidDock instead of a separate scrcpy window, and needs no “Allow screen capture” tap on the phone."),
                    tag: "ADB", primary: false, live: serial != nil,
                    requirement: adbRequirement(), hint: adbHint(),
                    button: tr("Mirror in this app"), busy: busy == "embedded" || session.phase == .connecting) {
                    run("embedded") {
                        session.markRequested(source: "screen")
                        try await Core.shared.scrcpyEmbeddedStart(serial: serial ?? "")
                    }
                }

                MirrorLaunchCard(
                    symbol: "display", title: tr("Desktop mode"),
                    subtitle: tr("Mirrors a second, virtual Android display instead of the phone's own screen. The phone stays usable. Needs Android 11+ and scrcpy 3.0 or newer."),
                    tag: "ADB", primary: defaultMode == "desktop", live: serial != nil,
                    requirement: desktopRequirement(), hint: desktopHint(),
                    button: tr("Start desktop"), busy: busy == "desktop") {
                    run("desktop") { try await Core.shared.adbDesktop(serial: serial ?? "") }
                }

                MirrorLaunchCard(
                    symbol: "rectangle.portrait.on.rectangle.portrait", title: tr("Second screen"),
                    subtitle: tr("A separate phone screen with its own home screen and apps, in a Mac window. Your phone's own screen is never shown or touched, and apps opened there close with the window."),
                    tag: "ADB", primary: false, live: serial != nil,
                    requirement: desktopRequirement(), hint: desktopHint(),
                    button: tr("Open second screen"), busy: busy == "second") {
                    run("second") { _ = try await Core.shared.adbSecondScreen(serial: serial ?? "") }
                }

                placementSection
                qualitySection
                layoutSection
            }
            .padding(DD.Space.content)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
    }

    private var placementSection: some View {
        SettingsSection(tr("Where it appears"), footer: tr("Applies to Wi-Fi mirror and mirror-in-app. You can also switch while it is running.")) {
            SettingsRow(tr("Show the mirror in"), systemImage: "macwindow") {
                Picker("", selection: $inWindow) {
                    Text(tr("Pop-out window")).tag(false)
                    Text(tr("This window")).tag(true)
                }.pickerStyle(.segmented).labelsHidden().frame(width: 220)
            }
        }
    }

    private var qualitySection: some View {
        let c = model.config ?? DroidConfig()
        return SettingsSection(tr("Quality"), footer: tr("Applies to the next Wi-Fi mirror or camera session.")) {
            SettingsRow(tr("Preset"), description: tr("Efficiency: 30 fps at 6 Mbps. Light on the phone's battery, the Wi-Fi and this Mac."), systemImage: "leaf") {
                Picker("", selection: Binding(get: { Self.preset(c.mirrorFps, c.mirrorBitrateMbps) },
                                              set: { Self.apply(preset: $0, model: model) })) {
                    Text(tr("Efficiency")).tag("efficiency")
                    Text(tr("Balanced")).tag("balanced")
                    Text(tr("Quality")).tag("quality")
                    if Self.preset(c.mirrorFps, c.mirrorBitrateMbps) == "custom" { Text(tr("Custom")).tag("custom") }
                }.pickerStyle(.segmented).labelsHidden().frame(width: 260)
            }
            SettingsRow(tr("Video codec"), description: tr("H.265 is smaller at the same quality; the phone falls back to H.264 if it can't encode it."), systemImage: "film") {
                Picker("", selection: Binding(get: { c.mirrorCodec }, set: { model.setSetting("mirrorCodec", $0) })) {
                    Text("H.264").tag("h264"); Text("H.265").tag("h265")
                }.pickerStyle(.segmented).labelsHidden().frame(width: 140)
            }
            SettingsRow(tr("Bitrate"), systemImage: "speedometer") {
                Picker("", selection: Binding(get: { c.mirrorBitrateMbps }, set: { model.setSetting("mirrorBitrateMbps", $0) })) {
                    ForEach([4, 6, 8, 12, 20, 30], id: \.self) { Text("\($0) Mbps").tag($0) }
                }.labelsHidden().frame(width: 110)
            }
            SettingsRow(tr("Frame rate"), systemImage: "timer") {
                Picker("", selection: Binding(get: { c.mirrorFps }, set: { model.setSetting("mirrorFps", $0) })) {
                    ForEach([30, 60, 90, 120], id: \.self) { Text("\($0) fps").tag($0) }
                }.labelsHidden().frame(width: 100)
            }
            SettingsRow(tr("Phone audio"), description: tr("Play the phone's audio through this Mac while mirroring (Android 10+)."), systemImage: "speaker.wave.2") {
                Toggle("", isOn: Binding(get: { c.mirrorAudio }, set: { model.setSetting("mirrorAudio", $0) }))
                    .toggleStyle(.switch).labelsHidden()
            }
        }
    }

    private static func preset(_ fps: Int, _ mbps: Int) -> String {
        switch (fps, mbps) {
        case (30, 6): return "efficiency"
        case (60, 12): return "balanced"
        case (60, 20): return "quality"
        default: return "custom"
        }
    }

    private static func apply(preset: String, model: AppModel) {
        let (fps, mbps): (Int, Int)
        switch preset {
        case "efficiency": (fps, mbps) = (30, 6)
        case "quality": (fps, mbps) = (60, 20)
        case "balanced": (fps, mbps) = (60, 12)
        default: return
        }
        model.setSetting("mirrorFps", fps); model.setSetting("mirrorBitrateMbps", mbps)
    }

    private var layoutSection: some View {
        let c = model.config ?? DroidConfig()
        let unsupported = !tools.caps.virtualDisplay && tools.scrcpy
        return SettingsSection(tr("Window layout"),
            footer: unsupported ? tr("scrcpy {v} can't create virtual displays; this applies from 3.0 onward.", ["v": tools.scrcpyVersion ?? "?"])
                                : tr("Which layout Android serves on the virtual display. Takes effect the next time you start desktop mode or open an app on this Mac.")) {
            SettingsRow(tr("Android layout"), systemImage: "square.stack") {
                Picker("", selection: Binding(get: { c.desktopUiMode }, set: { model.setSetting("desktopUiMode", $0) })) {
                    Text(tr("Desktop")).tag("desktop"); Text(tr("Tablet")).tag("tablet"); Text(tr("Phone")).tag("phone")
                }.pickerStyle(.segmented).labelsHidden().frame(width: 240).disabled(unsupported)
            }
        }
    }

    // MARK: Helpers

    private func adbRequirement() -> String? {
        if !tools.scrcpy { return tr("scrcpy not installed") }
        return serial == nil ? tr("No ADB device connected") : nil
    }
    private func adbHint() -> String {
        tools.scrcpy ? tr("Connect a phone via USB or wireless ADB from the Devices tab.")
                     : tr("Install scrcpy first (Devices tab → Tools).")
    }
    private func desktopRequirement() -> String? {
        if !tools.scrcpy { return tr("scrcpy not installed") }
        if !tools.caps.virtualDisplay { return tr("scrcpy {v} is too old", ["v": tools.scrcpyVersion ?? "?"]) }
        return serial == nil ? tr("No ADB device connected") : nil
    }
    private func desktopHint() -> String {
        if !tools.scrcpy { return tr("Install scrcpy first (Devices tab → Tools).") }
        if !tools.caps.virtualDisplay { return tr("Virtual displays need scrcpy 3.0 or newer. Run `brew upgrade scrcpy`, then reopen DroidDock.") }
        return tr("Connect a phone via USB or wireless ADB from the Devices tab.")
    }

    private func run(_ key: String, _ work: @escaping () async throws -> Void) {
        busy = key
        Task {
            do { try await work() } catch { model.toast(.bad, error.localizedDescription) }
            busy = nil
        }
    }
}

/// Launch card shared by the Mirror and Camera screens.
struct MirrorLaunchCard: View {
    let symbol: String, title: String, subtitle: String, tag: String
    var primary = false, live = false
    var requirement: String?, hint: String
    let button: String
    var busy = false
    let action: () -> Void

    private var buttonLabel: some View {
        HStack(spacing: DD.Space.sm) {
            if busy { ProgressView().controlSize(.small) }
            Text(busy ? tr("Waiting for the phone…") : button)
        }.frame(maxWidth: .infinity)
    }

    var body: some View {
        Card(padding: DD.Space.lg, raised: primary) {
            VStack(alignment: .leading, spacing: DD.Space.md) {
                HStack(alignment: .top, spacing: DD.Space.md) {
                    Image(systemName: symbol).font(.system(size: 16)).symbolRenderingMode(.hierarchical)
                        .foregroundStyle(DD.fg).frame(width: 36, height: 36)
                        .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(DD.panel3))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: DD.Space.xs) {
                        HStack(spacing: DD.Space.sm) {
                            Text(title).font(DD.Font.headline).foregroundStyle(DD.fg)
                            StatusPill(text: tag, kind: live ? .link : .neutral)
                        }
                        Text(subtitle).font(DD.Font.callout).foregroundStyle(DD.dim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if let requirement {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(requirement).font(DD.Font.bodyEmphasis).foregroundStyle(DD.fg)
                        Text(hint).font(DD.Font.caption).foregroundStyle(DD.dim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(DD.Space.md).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(DD.panel3.opacity(0.6)))
                } else {
                    if primary {
                        Button(action: action) { buttonLabel }.buttonStyle(PrimaryButtonStyle()).disabled(busy)
                    } else {
                        Button(action: action) { buttonLabel }.buttonStyle(SecondaryButtonStyle()).disabled(busy)
                    }
                }
            }
        }
    }
}
