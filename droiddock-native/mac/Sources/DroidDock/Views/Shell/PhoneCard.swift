import AppKit
import SwiftUI

/// Which quick actions the card carries (the minimal layout has no list below it, so it gets the screens too).
enum PhoneCardActionSet { case standard, minimal }

/// AppModel-aware wrapper around `DDPhoneCard`: builds the model, wires the actions, owns file drops.
struct PhoneCard: View {
    @Environment(AppModel.self) private var model
    var actionSet: PhoneCardActionSet = .standard
    /// Asks the hosting shell to show a destination (Files, Messages, Calls…).
    var onNavigate: (Destination) -> Void = { AppModel.shared.selection = $0 }

    @State private var shell = ShellState.shared
    @State private var dropTargeted = false
    @State private var showDetails = false

    var body: some View {
        let card = DDPhoneCard(model: cardModel, actions: actions, isDropTarget: dropTargeted)
            .dropDestination(for: URL.self) { urls, _ in
                guard model.isConnected else { model.toast(.info, tr("Link your phone to send files")); return false }
                ShellActions.sendFiles(urls)
                Haptics.generic()
                return true
            } isTargeted: { dropTargeted = $0 }
            .popover(isPresented: $showDetails, arrowEdge: .bottom) { ConnectionDetails() }
        if case .pairing = cardModel.state {
            LockedQRView { card }
        } else {
            card
        }
    }

    // MARK: Model

    private var isPaired: Bool {
        (model.config?.deviceName?.isEmpty == false) || model.config?.deviceGuid != nil
    }

    private var cardModel: DDPhoneCardModel {
        let state: DDPhoneCardModel.State
        if model.isConnected { state = .connected }
        else if !isPaired || shell.forcePairing { state = .pairing(qr: shell.qr, code: pairingCode) }
        else if model.isReconnecting { state = .connecting }
        else { state = .disconnected }

        let usb = model.devices.contains { $0.state == "device" && $0.transport != "wifi" }
        var m = DDPhoneCardModel(state: state, name: model.wifi.phoneName ?? "")
        m.battery = model.batteryPercent
        m.charging = model.isCharging
        m.transport = model.onBluetooth ? .ble : (model.isConnected ? (usb ? .wifiAndUsb : .wifi) : (usb ? .usb : .none))
        m.link = model.linkQuality.map { LinkQualityLevel(grade: $0.grade) }
        m.rttMs = model.linkQuality?.rttMs.map { Int($0.rounded()) }
        m.nowPlaying = shell.nowPlaying(model)
        m.recentApps = shell.recentApps()
        m.wallpaper = shell.wallpaper
        m.tint = shell.tint
        if let v = model.media?.vol, let mx = model.media?.volMax, mx > 0 { m.volume = v / mx }
        return m
    }

    private var pairingCode: String? {
        guard let p = model.pairing else { return nil }
        return "\(p.ips.first ?? p.host):\(p.port)"
    }

    // MARK: Actions

    private var actions: DDPhoneCardActions {
        var a = DDPhoneCardActions(
            onPair: { withDDAnimation(Motion.state) { shell.forcePairing = true } },
            onConnectionDetails: { showDetails = true },
            onMedia: { ShellActions.media($0) },
            onLaunchApp: { ShellActions.launchApp($0) },
            onRemoveRecent: { shell.removeRecent($0) },
            onVolume: model.media?.volMax == nil ? nil : { v in
                let mx = model.media?.volMax ?? 15
                Task { try? await Core.shared.mediaCmd("setvol", value: (v * mx).rounded()) }
            })
        a.quickActions = Self.quickActions(actionSet, model: model, shell: shell, onNavigate: onNavigate)
        return a
    }

    static func quickActions(_ actionSet: PhoneCardActionSet, model: AppModel, shell: ShellState,
                             onNavigate: @escaping (Destination) -> Void) -> [DDQuickAction] {
        let mirror = DDQuickAction(id: "mirror", title: tr("Mirror"), systemImage: "rectangle.on.rectangle",
                                   shortcut: "⌘⇧M", isOn: model.mirror != nil) { ShellActions.toggleMirror() }
        let send = DDQuickAction(id: "send", title: tr("Send file"), systemImage: "arrow.up.doc",
                                 shortcut: "⌘U") { ShellActions.pickAndSend() }
        let clip = DDQuickAction(id: "clip", title: tr("Send clipboard"), systemImage: "doc.on.clipboard",
                                 shortcut: "⌘⇧V") { ShellActions.pushClipboard() }
        switch actionSet {
        case .standard:
            return [mirror, send,
                    DDQuickAction(id: "shot", title: tr("Screenshot"), systemImage: "camera.viewfinder") { ShellActions.screenshot() },
                    DDQuickAction(id: "ring", title: shell.ringing ? tr("Stop ringing") : tr("Ring phone"),
                                  systemImage: "bell.and.waves.left.and.right", isOn: shell.ringing,
                                  isEnabled: model.wifi.supports("ring")) { ShellActions.toggleRing() },
                    clip]
        case .minimal:
            return [mirror, send,
                    DDQuickAction(id: "files", title: tr("Files"), systemImage: "folder") { onNavigate(.files) },
                    clip,
                    DDQuickAction(id: "msgs", title: tr("Messages"), systemImage: "message") { onNavigate(.messages) },
                    DDQuickAction(id: "calls", title: tr("Calls"), systemImage: "phone") { onNavigate(.calls) }]
        }
    }
}

/// Popover from the connection pill: how the phone is linked and where to find it.
struct ConnectionDetails: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.md) {
            Text(model.phoneName ?? tr("No phone linked")).font(DD.Font.title3).foregroundStyle(DD.fg)
            VStack(alignment: .leading, spacing: DD.Space.sm) {
                row(tr("Link"), model.isConnected ? tr("Wi-Fi") : tr("Offline"))
                if let q = model.linkQuality {
                    row(tr("Quality"), LinkQualityLevel(grade: q.grade).title)
                    if let r = q.rttMs { row(tr("Latency"), "\(Int(r.rounded())) ms") }
                }
                if let info = model.deviceInfo {
                    if let mdl = info.model { row(tr("Model"), mdl) }
                    if let a = info.android { row("Android", a) }
                }
                if let p = model.pairing {
                    row(tr("Mac address"), p.ips.map { "\($0):\(p.port)" }.joined(separator: "\n"), mono: true)
                }
                ForEach(model.devices.filter { $0.state == "device" }, id: \.serial) { d in
                    row("ADB", d.serial, mono: true)
                }
            }
        }
        .padding(DD.Space.lg)
        .frame(width: 280, alignment: .leading)
        .ddAppearance(AppearanceSettings.shared)
    }

    private func row(_ k: String, _ v: String, mono: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(DD.Font.callout).foregroundStyle(DD.dim)
            Spacer(minLength: DD.Space.md)
            Text(v).font(mono ? DD.Font.monoSmall : DD.Font.callout).foregroundStyle(DD.fg)
                .multilineTextAlignment(.trailing).textSelection(.enabled)
        }
    }
}
