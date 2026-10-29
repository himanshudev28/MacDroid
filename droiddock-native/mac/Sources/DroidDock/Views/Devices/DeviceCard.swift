import SwiftUI

/// One ADB device: identity, state, battery, volume and actions.
struct DeviceCard: View {
    let device: AdbDevice
    let tools: ToolsStatus
    let isSelected: Bool
    let showsSelection: Bool
    let appInfo: AppDeviceInfo?
    var onSelect: () -> Void

    @Environment(AppModel.self) private var model
    @State private var info: DeviceInfo?
    @State private var busy: String?
    @State private var vol: Volume?
    @State private var volValue: Double = 0
    @State private var lastVolSent: Double = 0
    @State private var volTask: Task<Void, Never>?

    private var ready: Bool { device.state == "device" }
    private var isWifi: Bool { device.transport == "wifi" }
    private var name: String { appInfo?.model ?? info?.model ?? (device.model.isEmpty ? device.serial : device.model) }
    private var battery: Int? {
        if let b = appInfo?.battery ?? info?.battery { return Int(b.rounded()) }
        return nil
    }
    private var charging: Bool { appInfo?.charging ?? info?.charging ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.md) {
            header
            if ready {
                if let b = battery { batteryBar(b) }
                if let vol { volumeRow(vol) }
                actions
            } else {
                stateHint
            }
        }
        .padding(.vertical, DD.Space.xs)
        .contentShape(Rectangle())
        .onTapGesture { if showsSelection { onSelect() } }
        .ddAnimation(Motion.select, value: isSelected)
        .contextMenu {
            Button(tr("Copy Serial")) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(device.serial, forType: .string) }
            if ready { Button(tr("Take Screenshot")) { screenshot() } }
        }
        .task(id: device.serial + device.state) {
            guard ready else { return }
            info = try? await Core.shared.adbDeviceInfo(serial: device.serial)
        }
        .task(id: isSelected && ready) {
            guard isSelected, ready, let v = try? await Core.shared.adbVolumeGet() else { vol = nil; return }
            vol = v; volValue = v.level; lastVolSent = v.level
        }
        .accessibilityElement(children: .contain)
    }

    // MARK: Parts

    private var header: some View {
        HStack(spacing: DD.Space.md) {
            if showsSelection {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle").font(.system(size: 16))
                    .foregroundStyle(isSelected ? Color.accentColor : DD.faint)
                    .accessibilityHidden(true)
            }
            Image(systemName: "iphone.gen3").font(.system(size: 28)).symbolRenderingMode(.hierarchical)
                .foregroundStyle(ready ? DD.fg : DD.dim).frame(width: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(name).font(DD.Font.title3).foregroundStyle(DD.fg).lineLimit(1)
                HStack(spacing: 6) {
                    if let a = info?.android ?? appInfo?.android { Text("Android \(a)").font(DD.Font.callout).foregroundStyle(DD.dim) }
                    Text(device.serial).font(DD.Font.monoSmall).foregroundStyle(DD.dim)
                        .lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                }
            }
            Spacer(minLength: DD.Space.md)
            VStack(alignment: .trailing, spacing: 4) {
                StatusPill(text: isWifi ? tr("Wi-Fi") : "USB", kind: isWifi ? .link : .neutral,
                           systemImage: isWifi ? "wifi" : "cable.connector")
                StatusPill(text: stateLabel, kind: stateKind)
            }
        }
    }

    private var stateLabel: String {
        switch device.state {
        case "device": tr("Ready")
        case "unauthorized": tr("Unauthorized")
        case "offline": tr("Offline")
        default: device.state.capitalized
        }
    }
    private var stateKind: StatusKind {
        switch device.state { case "device": .ok; case "unauthorized": .warn; default: .bad }
    }

    private var stateHint: some View {
        Label(device.state == "unauthorized"
              ? tr("Allow USB debugging on the phone (tap Allow in the dialog), then it will appear as ready.")
              : tr("The phone isn't responding. Unplug and reconnect it, or tap Reconnect."),
              systemImage: device.state == "unauthorized" ? "lock.shield" : "bolt.slash")
            .font(DD.Font.callout).foregroundStyle(DD.dim)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func batteryBar(_ b: Int) -> some View {
        HStack(spacing: DD.Space.md) {
            Text(tr("Battery")).font(DD.Font.label).foregroundStyle(DD.dim).frame(width: 52, alignment: .leading)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(DD.panel3)
                    Capsule().fill(charging ? DD.ok : (b <= 15 ? DD.bad : DD.fg.opacity(0.8)))
                        .frame(width: max(6, g.size.width * CGFloat(b) / 100))
                }
            }
            .frame(height: 6)
            HStack(spacing: 2) {
                if charging { Image(systemName: "bolt.fill").font(.system(size: 10)).foregroundStyle(DD.ok) }
                Text("\(b)%").monospacedDigit().contentTransition(.numericText())
            }
            .font(DD.Font.caption).foregroundStyle(DD.dim).frame(width: 46, alignment: .trailing)
        }
        .ddAnimation(Motion.content, value: b)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Battery {n} percent", ["n": b]))
    }

    private func volumeRow(_ v: Volume) -> some View {
        HStack(spacing: DD.Space.md) {
            Text(tr("Volume")).font(DD.Font.label).foregroundStyle(DD.dim).frame(width: 52, alignment: .leading)
            Slider(value: $volValue, in: 0...max(v.max, 1), step: 1) { editing in
                if !editing, volValue <= 0 || volValue >= v.max { Haptics.levelChange() }
            }
            .controlSize(.small)
            .onChange(of: volValue) { _, new in
                volTask?.cancel()
                volTask = Task {
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    _ = try? await Core.shared.adbVolumeSet(level: new, currentLevel: lastVolSent)
                    lastVolSent = new
                }
            }
            Text("\(Int((volValue / max(v.max, 1) * 100).rounded()))%").font(DD.Font.caption).monospacedDigit()
                .foregroundStyle(DD.dim).frame(width: 46, alignment: .trailing)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(tr("Phone volume"))
    }

    private var actions: some View {
        HStack(spacing: DD.Space.sm) {
            Menu {
                Button { run("mirror") { try await Core.shared.adbMirror(serial: device.serial) } } label: {
                    Label(tr("Mirror Screen"), systemImage: "rectangle.on.rectangle") }
                Button { run("embedded") { try await Core.shared.scrcpyEmbeddedStart(serial: device.serial) } } label: {
                    Label(tr("Mirror in scrcpy Window"), systemImage: "macwindow") }
                    .disabled(!tools.scrcpy)
                Button { run("desktop") { try await Core.shared.adbDesktop(serial: device.serial) } } label: {
                    Label(tr("Desktop Mode"), systemImage: "display") }
                    .disabled(!tools.scrcpy || !tools.caps.virtualDisplay)
            } label: {
                Label(busy == "mirror" || busy == "embedded" || busy == "desktop" ? tr("Starting…") : tr("Mirror"),
                      systemImage: "rectangle.on.rectangle")
            } primaryAction: {
                run("mirror") { try await Core.shared.adbMirror(serial: device.serial) }
            }
            .menuStyle(.button).buttonStyle(PrimaryButtonStyle(compact: true)).fixedSize()
            .help(tr("Mirror the phone screen  ⌘⇧M"))

            Button { screenshot() } label: {
                Label(busy == "shot" ? tr("Saving…") : tr("Screenshot"), systemImage: "camera.viewfinder")
            }
            .buttonStyle(SecondaryButtonStyle(compact: true)).disabled(busy == "shot")
            .help(tr("Saves to Downloads"))

            if !isWifi {
                Button {
                    run("wireless") {
                        let addr = try await Core.shared.adbGoWireless(serial: device.serial)
                        model.toast(.ok, tr("Wireless ADB on at {addr}", ["addr": addr]))
                    }
                } label: { Label(busy == "wireless" ? tr("Switching…") : tr("Go Wireless"), systemImage: "wifi") }
                .buttonStyle(SecondaryButtonStyle(compact: true)).disabled(busy == "wireless")
                .help(tr("Switch this phone to Wi-Fi ADB so you can unplug it"))
            }
            Spacer(minLength: 0)
            if busy != nil { ProgressView().controlSize(.small).transition(.opacity) }
        }
        .ddAnimation(Motion.content, value: busy)
    }

    private func screenshot() {
        run("shot") {
            let path = try await Core.shared.adbScreenshot(serial: device.serial)
            model.toast(.ok, tr("Screenshot saved to Downloads"))
            _ = path
        }
    }

    private func run(_ key: String, _ work: @escaping () async throws -> Void) {
        guard busy == nil else { return }
        busy = key
        Task {
            do { try await work() } catch { model.toast(.bad, error.localizedDescription) }
            busy = nil
        }
    }
}
