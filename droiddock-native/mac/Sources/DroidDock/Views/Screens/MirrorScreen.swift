import SwiftUI

/// Mirror screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct MirrorScreen: View {
    @Environment(AppModel.self) private var model
    @AppStorage(MirrorSession.inWindowKey) private var inWindow = false
    @AppStorage("droiddock.mirror.mode") private var storedMode = MirrorMode.screen
    @AppStorage("droiddock.mirror.link") private var storedLink = MirrorLink.auto
    @AppStorage("droiddock.mirror.advanced") private var showAdvanced = false
    @State private var busy: String?
    @State private var approving = false
    @State private var lastStart: (() async throws -> Void)?

    private var session: MirrorSession { MirrorSession.shared }
    private var live: Bool { session.isActive && session.source == "screen" }
    private var tools: ToolsStatus { model.tools ?? ToolsStatus() }
    private var serial: String? { model.devices.first { $0.state == "device" }?.serial }
    private var adbOK: Bool { adbRequirement() == nil }

    private enum Phase { case stage, running, connecting, unavailable, idle }
    private var stateKey: Phase {
        if live && session.placement == .inWindow { return .stage }
        if live && session.placement == .popout { return .running }
        if session.phase == .connecting || busy != nil { return .connecting }
        if !model.isConnected && serial == nil { return .unavailable }
        return .idle
    }

    var body: some View {
        Group {
            switch stateKey {
            case .stage: MirrorStage(inWindow: true).padding(DD.Space.md)
            case .running: SessionRunningForm(kind: .mirror)
            case .connecting: SessionConnecting(kind: .mirror, needsApproval: approving, cancel: cancel).ddTransition(.opacity)
            case .unavailable:
                EmptyState.disconnected(feature: tr("Mirror"))
            case .idle: idleForm
            }
        }
        .ddAnimation(Motion.state, value: stateKey)
        .onAppear(perform: seedDefaults)
        .onDisappear {
            // An in-window stream nobody can see still costs the phone's encoder, the network and a 60 fps decode.
            // Leaving the screen moves it to its own window; if the main window itself is gone, it stops.
            guard session.placement == .inWindow else { return }
            if WindowControl.mainWindow?.isVisible == true { session.openPopout() } else { session.stop() }
        }
        .onChange(of: session.phase) { _, p in
            if case .failed = p { busy = nil; approving = false }
            if p == .live { busy = nil; approving = false }
        }
    }

    // MARK: Effective selection

    private var mode: MirrorMode {
        availability(storedMode).ok ? storedMode : .screen
    }
    /// The route a "Start" press takes for Phone screen.
    private var link: MirrorLink {
        switch storedLink {
        case .auto: return model.isConnected ? .wifi : .adbInApp
        case .wifi: return model.isConnected ? .wifi : (adbOK ? .adbInApp : .wifi)
        case .adbWindow, .adbInApp: return adbOK ? storedLink : (model.isConnected ? .wifi : storedLink)
        }
    }
    private var canStart: Bool {
        switch mode {
        case .screen: return link == .wifi ? model.isConnected : adbOK
        case .desktop, .second: return desktopRequirement() == nil
        }
    }
    private var readiness: String {
        if !canStart {
            switch mode {
            case .screen: return tr("Pair your phone or connect it with a cable")
            case .desktop, .second: return desktopRequirement() ?? ""
            }
        }
        if mode == .screen && link == .wifi { return tr("Ready over Wi-Fi") }
        return tr("Ready over USB")
    }

    /// First run only: translate the old "default mirror mode" setting into the new mode + route.
    private func seedDefaults() {
        let d = UserDefaults.standard
        guard d.object(forKey: "droiddock.mirror.mode") == nil else { return }
        switch model.config?.defaultMirrorMode ?? "wifi" {
        case "adb": storedMode = .screen; storedLink = .adbWindow
        case "desktop": storedMode = .desktop; storedLink = .auto
        default: storedMode = .screen; storedLink = .auto
        }
    }

    // MARK: Idle form

    private var idleForm: some View {
        SettingsForm {
            if case .failed(let msg) = session.phase {
                SessionErrorSection(message: msg, retry: lastStart.map { f in { run(busy ?? "retry", f) } }, dismiss: { session.clearFailure() })
            }

            Section {
                SessionHero(symbol: mode.symbol, tint: mode.tint,
                            title: model.phoneName ?? tr("Your phone"), status: readiness, ready: canStart,
                            button: mode.startTitle, blockedReason: readiness, action: start)
            }

            Section {
                ForEach(MirrorMode.allCases) { m in
                    ModeRow(mode: m, selection: Binding(get: { mode }, set: { storedMode = $0 }), availability: availability(m))
                }
            } header: { Text(tr("Mirror mode")) }

            if mode == .screen {
                Section {
                    Picker(tr("Connect over"), selection: $storedLink) {
                        Text(tr("Automatic")).tag(MirrorLink.auto)
                        Text(tr("Wi-Fi")).tag(MirrorLink.wifi).disabled(!model.isConnected)
                        Text(tr("USB or wireless ADB")).tag(MirrorLink.adbWindow).disabled(!adbOK)
                        Text(tr("USB or wireless ADB, in this app")).tag(MirrorLink.adbInApp).disabled(!adbOK)
                    }.pickerStyle(.menu)
                    LabeledContent(tr("Show the mirror in")) {
                        Picker("", selection: $inWindow) {
                            Text(tr("Pop-out window")).tag(false)
                            Text(tr("This window")).tag(true)
                        }.pickerStyle(.segmented).labelsHidden().fixedSize().disabled(link == .adbWindow)
                    }
                } header: { Text(tr("Connection")) } footer: { Text(connectionFooter) }
            }

            MirrorQualitySection(showAdvanced: $showAdvanced)

            if mode != .screen { layoutSection }

            Section {
                Button(tr("More Options in Settings…")) { ShellActions.showSettings() }.buttonStyle(.link)
            }
        }
    }

    private var connectionFooter: String {
        switch link {
        case .wifi: return tr("Opens a phone-shaped window over Wi-Fi. No ADB, scrcpy, or Developer Options needed.")
        case .adbWindow: return tr("Full-quality, low-latency mirroring over USB or wireless ADB via scrcpy, in its own window.")
        case .adbInApp: return tr("Same scrcpy stream, but it plays in DroidDock instead of a separate scrcpy window, and needs no “Allow screen capture” tap on the phone.")
        case .auto: return ""
        }
    }

    private var layoutSection: some View {
        let c = model.config ?? DroidConfig()
        let unsupported = !tools.caps.virtualDisplay && tools.scrcpy
        return Section {
            Picker(tr("Android layout"), selection: Binding(get: { c.desktopUiMode }, set: { model.setSetting("desktopUiMode", $0) })) {
                Text(tr("Desktop")).tag("desktop"); Text(tr("Tablet")).tag("tablet"); Text(tr("Phone")).tag("phone")
            }.pickerStyle(.segmented).disabled(unsupported)
        } header: { Text(tr("Android layout")) } footer: {
            Text(unsupported ? tr("scrcpy {v} can't create virtual displays; this applies from 3.0 onward.", ["v": tools.scrcpyVersion ?? "?"])
                             : tr("Which layout Android serves on the virtual display. Takes effect the next time you start desktop mode or open an app on this Mac."))
        }
    }

    // MARK: Availability

    private func availability(_ m: MirrorMode) -> ModeAvailability {
        switch m {
        case .screen:
            return ModeAvailability(ok: model.isConnected || adbOK)
        case .desktop, .second:
            guard let reason = desktopRequirement() else { return ModeAvailability() }
            let needsDevices = !tools.scrcpy || serial == nil
            return ModeAvailability(ok: false, reason: reason,
                                    fixTitle: needsDevices ? tr("Open Devices…") : nil,
                                    fix: needsDevices ? { model.selection = .devices } : nil)
        }
    }

    // MARK: Starting

    private func start() {
        let work: () async throws -> Void
        let key: String
        switch mode {
        case .screen:
            switch link {
            case .wifi, .auto: key = "wifi"; work = wifiStart
            case .adbWindow: key = "adb"; work = { try await Core.shared.adbMirror(serial: serial ?? "") }
            case .adbInApp: key = "embedded"; work = embeddedStart
            }
        case .desktop: key = "desktop"; work = { try await Core.shared.adbDesktop(serial: serial ?? "") }
        case .second: key = "second"; work = { _ = try await Core.shared.adbSecondScreen(serial: serial ?? "") }
        }
        lastStart = work
        run(key, work)
    }

    private func wifiStart() async throws {
        // "Prefer ADB mirroring": the embedded scrcpy stream has no screen-share prompt.
        if UserDefaults.standard.object(forKey: MirrorSession.preferAdbKey) as? Bool ?? true,
           let serial, tools.scrcpy {
            approving = false
            session.markRequested(source: "screen")
            do { try await Core.shared.scrcpyEmbeddedStart(serial: serial); return }
            catch { dlog("mirror: ADB preferred but failed, using Wi-Fi: \(error)") }
        }
        approving = true
        await session.start(source: "screen")
        // Only tell the user to approve if the start did not fail.
        if case .failed = session.phase { return }
        model.toast(.info, tr("Approve screen capture on your phone…"))
    }

    private func embeddedStart() async throws {
        session.markRequested(source: "screen")
        try await Core.shared.scrcpyEmbeddedStart(serial: serial ?? "")
    }

    private func cancel() { session.stop(); busy = nil; approving = false }

    // MARK: Helpers

    private func adbRequirement() -> String? {
        if !tools.scrcpy { return tr("scrcpy not installed") }
        return serial == nil ? tr("No ADB device connected") : nil
    }
    private func desktopRequirement() -> String? {
        if !tools.scrcpy { return tr("scrcpy not installed") }
        if !tools.caps.virtualDisplay { return tr("scrcpy {v} is too old", ["v": tools.scrcpyVersion ?? "?"]) }
        return serial == nil ? tr("No ADB device connected") : nil
    }

    private func run(_ key: String, _ work: @escaping () async throws -> Void) {
        busy = key
        Task {
            do { try await work() }
            catch is CancellationError {}
            catch { model.toast(.bad, "\(tr("Couldn't start mirroring")): \(error.localizedDescription)") }
            busy = nil
            approving = false
        }
    }
}
