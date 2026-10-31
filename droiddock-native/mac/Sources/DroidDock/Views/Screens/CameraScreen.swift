import SwiftUI

/// Camera screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct CameraScreen: View {
    @Environment(AppModel.self) private var model
    @AppStorage("droiddock.camera.link") private var storedLink = CameraLink.wifi
    @AppStorage("droiddock.mirror.advanced") private var showAdvanced = false
    @State private var busy = false
    @State private var lastStart: (() async throws -> Void)?

    private var session: MirrorSession { MirrorSession.shared }
    private var live: Bool { session.isActive && session.source == "camera" }
    private var tools: ToolsStatus { model.tools ?? ToolsStatus() }
    private var serial: String? { model.devices.first { $0.state == "device" }?.serial }
    private var adbReason: String? {
        if !tools.scrcpy { return tr("scrcpy not installed") }
        return serial == nil ? tr("No ADB device connected") : nil
    }
    private var adbOK: Bool { adbReason == nil }

    private enum Phase { case stage, running, connecting, unavailable, idle }
    private var stateKey: Phase {
        if live && session.placement == .inWindow { return .stage }
        if live && session.placement == .popout { return .running }
        if session.phase == .connecting || busy { return .connecting }
        if !model.isConnected && serial == nil { return .unavailable }
        return .idle
    }
    /// Wi-Fi when the phone is linked, otherwise ADB; an unusable stored choice falls back.
    private var link: CameraLink {
        switch storedLink {
        case .wifi: return model.isConnected ? .wifi : (adbOK ? .adb : .wifi)
        case .adb: return adbOK ? .adb : (model.isConnected ? .wifi : .adb)
        }
    }
    private var canStart: Bool { link == .wifi ? model.isConnected : adbOK }

    var body: some View {
        Group {
            switch stateKey {
            case .stage: MirrorStage(inWindow: true).padding(DD.Space.md)
            case .running: SessionRunningForm(kind: .camera)
            case .connecting: SessionConnecting(kind: .camera, needsApproval: link == .wifi, cancel: { session.stop(); busy = false }).ddTransition(.opacity)
            case .unavailable:
                EmptyState.disconnected(feature: tr("Camera"))
            case .idle: idleForm
            }
        }
        .ddAnimation(Motion.state, value: stateKey)
    }

    private var idleForm: some View {
        SettingsForm {
            if case .failed(let msg) = session.phase {
                SessionErrorSection(message: msg, retry: lastStart.map { f in { run(f) } }, dismiss: { session.clearFailure() })
            }
            Section {
                SessionHero(symbol: "camera", tint: Color(nsColor: .systemOrange),
                            title: tr("Phone camera"),
                            status: canStart ? (link == .wifi ? tr("Ready over Wi-Fi") : tr("Ready over USB")) : tr("Pair your phone or connect it with a cable"),
                            ready: canStart, button: tr("Start Camera"),
                            blockedReason: tr("Pair your phone or connect it with a cable"), action: start)
            }
            Section {
                Picker(tr("Connect over"), selection: $storedLink) {
                    Text(tr("Wi-Fi")).tag(CameraLink.wifi).disabled(!model.isConnected)
                    Text(tr("USB or wireless ADB")).tag(CameraLink.adb).disabled(!adbOK)
                }.pickerStyle(.menu)
            } header: { Text(tr("Connection")) } footer: {
                Text(link == .wifi ? tr("Streams the phone's back camera over Wi-Fi. Flip to the front camera once it's running.")
                                   : tr("Full-quality camera preview over USB or wireless ADB via scrcpy, in its own window."))
            }
            MirrorQualitySection(showAdvanced: $showAdvanced, audio: false, adbOptions: false)
            if !adbOK, let r = adbReason {
                Section {
                    LabeledContent(tr("USB or wireless ADB")) { Text(r).foregroundStyle(DD.warnText) }
                    Button(tr("Open Devices…")) { model.selection = .devices }
                }
            }
        }
    }

    private func start() {
        let work: () async throws -> Void
        if link == .wifi {
            work = {
                await session.start(source: "camera")
                // Only tell the user to approve if the start did not fail.
                if case .failed = session.phase { return }
                model.toast(.info, tr("Approve the camera on your phone…"))
            }
        } else {
            work = { try await Core.shared.adbCamera(serial: serial ?? "") }
        }
        lastStart = work
        run(work)
    }

    private func run(_ work: @escaping () async throws -> Void) {
        busy = true
        Task {
            do { try await work() }
            catch is CancellationError {}
            catch { model.toast(.bad, "\(tr("Couldn't start the camera")): \(error.localizedDescription)") }
            busy = false
        }
    }
}
