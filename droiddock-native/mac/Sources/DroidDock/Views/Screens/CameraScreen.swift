import SwiftUI

/// Camera screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct CameraScreen: View {
    @Environment(AppModel.self) private var model
    @State private var busy = false

    private var session: MirrorSession { MirrorSession.shared }
    private var live: Bool { session.isActive && session.source == "camera" }
    private var tools: ToolsStatus { model.tools ?? ToolsStatus() }
    private var serial: String? { model.devices.first { $0.state == "device" }?.serial }

    var body: some View {
        Group {
            if live && session.placement == .inWindow {
                MirrorStage(inWindow: true).padding(DD.Space.lg)
            } else if live && session.placement == .popout {
                running
            } else {
                launcher
            }
        }
        .animation(Motion.state, value: live)
    }

    private var running: some View {
        VStack { Spacer(minLength: 0)
            Card(padding: DD.Space.xl, raised: true) {
                VStack(spacing: DD.Space.md) {
                    Image(systemName: "camera").font(.system(size: 28))
                        .symbolRenderingMode(.hierarchical).foregroundStyle(DD.dim)
                    StatusPill(text: session.isLive ? tr("Camera live") : tr("Waiting for the phone…"),
                               kind: session.isLive ? .link : .neutral)
                    Text(tr("Your phone's camera is streaming in its own window. Flip front/back from there."))
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

    private var launcher: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DD.Space.lg) {
                VStack(alignment: .leading, spacing: DD.Space.xs) {
                    Text(tr("Camera")).font(DD.Font.largeTitle).foregroundStyle(DD.fg)
                    Text(tr("Use your phone's camera as a live feed on this Mac.")).font(DD.Font.body).foregroundStyle(DD.dim)
                }
                if case .failed(let msg) = session.phase {
                    Label(msg, systemImage: "exclamationmark.triangle")
                        .font(DD.Font.callout).foregroundStyle(DD.bad)
                        .padding(DD.Space.md).frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(DD.bad.opacity(0.10)))
                }
                MirrorLaunchCard(
                    symbol: "camera", title: tr("Wi-Fi camera"),
                    subtitle: tr("Streams the phone's back camera over Wi-Fi. Flip to the front camera once it's running."),
                    tag: "Wi-Fi", primary: true, live: model.isConnected,
                    requirement: model.isConnected ? nil : tr("Phone app link required"),
                    hint: tr("Pair the DroidDock phone app from the Dashboard to stream over Wi-Fi."),
                    button: tr("Start camera"), busy: session.phase == .connecting) {
                    Task {
                        await session.start(source: "camera")
                        model.toast(.info, tr("Approve the camera on your phone…"))
                    }
                }
                MirrorLaunchCard(
                    symbol: "cable.connector", title: tr("ADB camera"),
                    subtitle: tr("Full-quality camera preview over USB or wireless ADB via scrcpy, in its own window."),
                    tag: "ADB", live: serial != nil,
                    requirement: !tools.scrcpy ? tr("scrcpy not installed") : (serial == nil ? tr("No ADB device connected") : nil),
                    hint: tools.scrcpy ? tr("Connect a phone via USB or wireless ADB from the Devices tab.")
                                       : tr("Install scrcpy first (Devices tab → Tools)."),
                    button: tr("Stream via ADB"), busy: busy) {
                    busy = true
                    Task {
                        do { try await Core.shared.adbCamera(serial: serial ?? "") }
                        catch { model.toast(.bad, error.localizedDescription) }
                        busy = false
                    }
                }
            }
            .padding(DD.Space.content)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
    }
}
