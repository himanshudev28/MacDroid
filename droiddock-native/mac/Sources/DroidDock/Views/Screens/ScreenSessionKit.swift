import SwiftUI

// Shared building blocks for the Mirror and Camera screens (ARCHITECTURE.md §K: shell-agnostic).

// MARK: State

enum MirrorMode: String, CaseIterable, Identifiable {
    case screen, desktop, second
    var id: String { rawValue }
    var title: String {
        switch self {
        case .screen: return tr("Phone screen")
        case .desktop: return tr("Desktop mode")
        case .second: return tr("Second screen")
        }
    }
    var summary: String {
        switch self {
        case .screen: return tr("Show your phone's screen in a window on this Mac.")
        case .desktop: return tr("A second, virtual Android display. The phone stays usable. Android 11+ and scrcpy 3.0 or newer.")
        case .second: return tr("A separate phone screen with its own home screen and apps. Your phone's own screen is never shown or touched, and apps opened there close with the window.")
        }
    }
    var symbol: String {
        switch self {
        case .screen: return "rectangle.on.rectangle"
        case .desktop: return "display"
        case .second: return "rectangle.portrait.on.rectangle.portrait"
        }
    }
    var tint: Color {
        switch self {
        case .screen: return Color(nsColor: .systemBlue)
        case .desktop: return Color(nsColor: .systemIndigo)
        case .second: return Color(nsColor: .systemTeal)
        }
    }
    var startTitle: String {
        switch self {
        case .screen: return tr("Start Mirroring")
        case .desktop: return tr("Start Desktop Mode")
        case .second: return tr("Open Second Screen")
        }
    }
}

enum MirrorLink: String, CaseIterable, Identifiable {
    case auto, wifi, adbWindow, adbInApp
    var id: String { rawValue }
}

enum CameraLink: String, CaseIterable, Identifiable {
    case wifi, adb
    var id: String { rawValue }
}

enum SessionKind { case mirror, camera }

struct ModeAvailability {
    var ok = true
    var reason: String?
    var fixTitle: String?
    var fix: (() -> Void)?
}

// MARK: Hero

/// The one prominent action: who it is for, whether it can start, the button.
struct SessionHero: View {
    let symbol: String
    let tint: Color
    let title: String
    let status: String
    let ready: Bool
    let button: String
    let blockedReason: String?
    let action: () -> Void

    private var info: some View {
        HStack(spacing: DD.Space.md) {
            IconTile(symbol: symbol, tint: tint, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(DD.Font.title3)
                Text(status).font(DD.Font.callout).foregroundStyle(ready ? DD.dim : DD.warnText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
    private var startButton: some View {
        Button(button, action: action)
            .buttonStyle(.borderedProminent).controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(!ready)
            .help(ready ? "" : (blockedReason ?? ""))
    }
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: DD.Space.md) { info; Spacer(minLength: DD.Space.md); startButton }
            VStack(alignment: .leading, spacing: DD.Space.md) { info; startButton }
        }.padding(.vertical, DD.Space.xs)
    }
}

// MARK: Mode row

/// One mode: tile, title, summary or the reason it is unavailable (warning colour), optional fix button.
struct ModeRow: View {
    let mode: MirrorMode
    @Binding var selection: MirrorMode
    let availability: ModeAvailability

    var body: some View {
        HStack(spacing: DD.Space.md) {
            Button { selection = mode } label: {
                HStack(spacing: DD.Space.md) {
                    IconTile(symbol: mode.symbol, tint: availability.ok ? mode.tint : Color(nsColor: .systemGray), size: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mode.title).foregroundStyle(availability.ok ? DD.fg : DD.dim)
                        Text(availability.reason ?? mode.summary)
                            .font(DD.Font.caption)
                            .foregroundStyle(availability.reason == nil ? DD.dim : DD.warnText)
                            .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }.contentShape(Rectangle())
            }
            .buttonStyle(.plain).disabled(!availability.ok)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(selection == mode ? .isSelected : [])
            if let fix = availability.fix, let t = availability.fixTitle {
                Button(t, action: fix).buttonStyle(.bordered).controlSize(.small)
            } else if selection == mode && availability.ok {
                Image(systemName: "checkmark").fontWeight(.semibold).foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)
            }
        }
    }
}

// MARK: Quality

enum MirrorPreset {
    static func current(_ fps: Int, _ mbps: Int) -> String {
        switch (fps, mbps) {
        case (30, 6): return "efficiency"
        case (60, 12): return "balanced"
        case (60, 20): return "quality"
        default: return "custom"
        }
    }
    @MainActor static func apply(_ preset: String, model: AppModel) {
        let (fps, mbps): (Int, Int)
        switch preset {
        case "efficiency": (fps, mbps) = (30, 6)
        case "quality": (fps, mbps) = (60, 20)
        case "balanced": (fps, mbps) = (60, 12)
        default: return
        }
        model.setSetting("mirrorFps", fps); model.setSetting("mirrorBitrateMbps", mbps)
    }
    static func footer(_ preset: String) -> String {
        let base: String
        switch preset {
        case "efficiency": base = tr("Efficiency: 30 fps at 6 Mbps. Light on the phone's battery, the Wi-Fi and this Mac.")
        case "balanced": base = tr("Balanced: 60 fps at 12 Mbps.")
        case "quality": base = tr("Quality: 60 fps at 20 Mbps. Uses more battery and bandwidth.")
        default: base = tr("Custom: your own frame rate and bit rate (see Advanced).")
        }
        return base + " " + tr("Applies to the next session.")
    }
}

/// Shared by Mirror and Camera. `audio` and `adbOptions` are Mirror-only rows.
struct MirrorQualitySection: View {
    @Environment(AppModel.self) private var model
    @Binding var showAdvanced: Bool
    var audio = true
    var adbOptions = true

    var body: some View {
        let c = model.config ?? DroidConfig()
        let preset = MirrorPreset.current(c.mirrorFps, c.mirrorBitrateMbps)
        Section {
            Picker(tr("Preset"), selection: Binding(get: { preset }, set: { MirrorPreset.apply($0, model: model) })) {
                Text(tr("Efficiency")).tag("efficiency")
                Text(tr("Balanced")).tag("balanced")
                Text(tr("Quality")).tag("quality")
                if preset == "custom" { Text(tr("Custom")).tag("custom") }
            }.pickerStyle(.segmented)
            if audio {
                Toggle(isOn: model.cfg(\.mirrorAudio, "mirrorAudio")) {
                    RowLabel(title: tr("Play phone audio on this Mac"), detail: tr("Android 10+; some apps block capture."))
                }.toggleStyle(.switch)
            }
            DisclosureGroup(tr("Advanced"), isExpanded: $showAdvanced) {
                Picker(tr("Video codec"), selection: model.cfg(\.mirrorCodec, "mirrorCodec")) {
                    Text("H.264").tag("h264"); Text("H.265").tag("h265")
                }
                Picker(tr("Bit rate"), selection: model.cfg(\.mirrorBitrateMbps, "mirrorBitrateMbps")) {
                    ForEach([4, 6, 8, 12, 20, 30], id: \.self) { Text("\($0) Mbps").tag($0) }
                }
                Picker(tr("Frame rate"), selection: model.cfg(\.mirrorFps, "mirrorFps")) {
                    ForEach([30, 60, 90, 120], id: \.self) { Text("\($0) fps").tag($0) }
                }
                Picker(tr("Resolution cap"), selection: model.cfg(\.mirrorMaxSize, "mirrorMaxSize")) {
                    Text(tr("Phone's own")).tag(0)
                    ForEach([1920, 1280, 720], id: \.self) { Text("\($0) px").tag($0) }
                }
                if adbOptions {
                    Toggle(tr("Keep the phone awake"), isOn: model.cfg(\.scrcpyStayAwake, "scrcpyStayAwake"))
                    Toggle(tr("Blank the phone screen"), isOn: model.cfg(\.scrcpyTurnScreenOff, "scrcpyTurnScreenOff"))
                }
            }
        } header: { Text(tr("Quality")) } footer: { Text(MirrorPreset.footer(preset)) }
    }
}

// MARK: Error

struct SessionErrorSection: View {
    let message: String
    var retry: (() -> Void)?
    let dismiss: () -> Void
    var body: some View {
        Section {
            Label {
                Text(message).font(DD.Font.callout).foregroundStyle(DD.fg)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(DD.warn)
            }
            HStack(spacing: DD.Space.sm) {
                if let retry { Button(tr("Try Again"), action: retry).buttonStyle(.bordered) }
                Button(tr("Dismiss"), action: dismiss).buttonStyle(.borderless)
            }
        }
    }
}

// MARK: Connecting

struct SessionConnecting: View {
    let kind: SessionKind
    /// True when the phone has to accept a capture request; false for ADB routes that need no tap.
    let needsApproval: Bool
    let cancel: () -> Void

    private var title: String {
        guard needsApproval else { return tr("Starting…") }
        return kind == .camera ? tr("Approve the camera on your phone") : tr("Approve on your phone")
    }
    var body: some View {
        VStack(spacing: DD.Space.md) {
            ProgressView().controlSize(.regular)
            Text(title).font(DD.Font.title2)
            if needsApproval {
                Text(tr("Accept the capture request to start streaming."))
                    .font(DD.Font.callout).foregroundStyle(DD.dim).multilineTextAlignment(.center)
            }
            Button(tr("Cancel"), action: cancel).buttonStyle(.bordered).controlSize(.large)
                .keyboardShortcut(.cancelAction)
        }
        .frame(maxWidth: 360).frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .onAppear { AccessibilityNotification.Announcement(title).post() }
    }
}

// MARK: Running (pop-out)

struct SessionRunningForm: View {
    @Environment(AppModel.self) private var model
    let kind: SessionKind
    private var session: MirrorSession { MirrorSession.shared }

    var body: some View {
        let isCamera = kind == .camera
        SettingsForm {
            Section {
                HStack(spacing: DD.Space.md) {
                    IconTile(symbol: isCamera ? "camera" : "rectangle.on.rectangle", tint: session.isLive ? Color(nsColor: .systemGreen) : Color(nsColor: .systemGray), size: 32)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(isCamera ? tr("Camera on {phone}", ["phone": model.phoneName ?? tr("your phone")])
                                      : tr("Mirroring {phone}", ["phone": model.phoneName ?? tr("your phone")]))
                            .font(DD.Font.title3)
                        StatusPill(text: session.isLive ? tr("Streaming") : tr("Waiting for the phone…"), kind: session.isLive ? .link : .neutral)
                    }
                    Spacer(minLength: DD.Space.md)
                    Button(tr("Bring to Front")) { session.setPlacement(inWindow: false) }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                }.padding(.vertical, DD.Space.xs)
            }
            Section(tr("Session controls")) {
                LabeledContent(isCamera ? tr("Show camera in") : tr("Show mirror in")) {
                    Picker("", selection: Binding(get: { session.placement == .inWindow },
                                                  set: { session.setPlacement(inWindow: $0) })) {
                        Text(tr("Pop-out window")).tag(false)
                        Text(tr("This window")).tag(true)
                    }.pickerStyle(.segmented).labelsHidden().fixedSize()
                }
                Toggle(tr("Keep on top"), isOn: Binding(get: { session.onTop }, set: { session.onTop = $0 }))
                if !isCamera && (model.audio != nil || session.audioExpected) {
                    Toggle(tr("Mute phone audio"), isOn: Binding(get: { session.audioMuted }, set: { if $0 != session.audioMuted { session.toggleMute() } }))
                }
                Button(tr("Save Screenshot")) { session.screenshot() }
                if isCamera || session.isCamera { Button(tr("Flip Camera")) { session.flipCamera() } }
            }
            Section {
                Button(isCamera ? tr("Stop Camera") : tr("Stop Mirroring"), role: .destructive) { session.stop() }
                    .foregroundStyle(DD.bad)
            }
        }
    }
}
