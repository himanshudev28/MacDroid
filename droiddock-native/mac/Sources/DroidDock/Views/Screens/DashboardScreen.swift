import AppKit
import SwiftUI

/// Home: the phone at a glance, what needs attention, and the things you do most.
struct DashboardScreen: View {
    @Environment(AppModel.self) private var model
    @State private var shell = ShellState.shared
    @State private var manual = false
    @AppStorage(AppLayout.defaultsKey) private var layoutRaw = AppLayout.phoneTabs.rawValue
    @State private var chrome = ShellChrome.shared

    private var layout: AppLayout { AppLayout(rawValue: layoutRaw) ?? .phoneTabs }
    /// Is a phone card on screen next to the dashboard? (sidebar card, classic's phone panel, grouped's
    /// compact card) — the user can hide it, and grouped hides it while the rail is collapsed.
    private var phoneCardVisible: Bool {
        if chrome.isPhoneHidden(layout) { return false }
        if layout == .grouped, chrome.isCollapsed(.grouped) { return false }
        return true
    }
    /// The full card (not grouped's compact one) already shows the pairing QR: don't show a second one.
    private var cardShowsQR: Bool { phoneCardVisible && layout != .grouped }
    /// A visible card already shows name, battery and link: no second hero.
    private var cardShowsPhone: Bool { phoneCardVisible }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DD.Space.xl) {
                if let err = model.coreError {
                    Card { Label(err, systemImage: "exclamationmark.triangle").foregroundStyle(DD.bad).font(DD.Font.body) }
                }
                if let u = model.updateInfo { updateBanner(u) }
                if model.isConnected {
                    if !cardShowsPhone { hero }
                    stats
                    actions
                    recentNotifications
                    if model.activeTransferCount > 0 { transfers }
                } else { pairing }
                Text("DroidDock \(model.appVersion)").font(DD.Font.footnote).foregroundStyle(DD.faint)
            }
            .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .ddAnimation(Motion.state, value: model.isConnected)
        }
    }

    // MARK: Update

    private func updateBanner(_ u: UpdateInfo) -> some View {
        Card(padding: DD.Space.md) {
            HStack(spacing: DD.Space.md) {
                Image(systemName: "arrow.down.circle.fill").font(.system(size: 28))
                    .symbolRenderingMode(.hierarchical).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("DroidDock {v} is available", ["v": u.version])).font(DD.Font.headline).foregroundStyle(DD.fg)
                    Text(tr("You have {v}.", ["v": u.currentVersion])).font(DD.Font.callout).foregroundStyle(DD.dim)
                }
                Spacer()
                Button(tr("Later")) { model.clearUpdateInfo() }.buttonStyle(SecondaryButtonStyle(compact: true))
                Button(tr("Update")) { ShellActions.openUpdate() }.buttonStyle(PrimaryButtonStyle(compact: true))
            }
        }
        .transition(.opacity)
    }

    // MARK: Connected

    private var hero: some View {
        Card(padding: DD.Space.lg) {
            HStack(spacing: DD.Space.lg) {
                if let b = model.batteryPercent {
                    BatteryRing(level: b, charging: model.isCharging, size: 64, lineWidth: 5)
                        .overlay(Text(verbatim: "\(b)").font(DD.Font.title2).monospacedDigit().foregroundStyle(DD.fg)
                            .contentTransition(.numericText(value: Double(b))))
                } else {
                    Image(systemName: "iphone.gen3").font(.system(size: 34)).symbolRenderingMode(.hierarchical).foregroundStyle(.secondary)
                        .frame(width: 64, height: 64)
                }
                VStack(alignment: .leading, spacing: DD.Space.xs) {
                    Text(model.phoneName ?? tr("Phone")).font(DD.Font.title).foregroundStyle(DD.fg).lineLimit(1)
                    Text(subtitle).font(DD.Font.callout).foregroundStyle(DD.dim).lineLimit(1)
                    ConnectionPill(transport: model.devices.contains { $0.state == "device" && $0.transport != "wifi" } ? .wifiAndUsb : .wifi,
                                   link: model.linkQuality.map { LinkQualityLevel(grade: $0.grade) },
                                   rttMs: model.linkQuality?.rttMs.map { Int($0.rounded()) }, style: .standard)
                        .padding(.top, 2)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        if let m = model.deviceInfo?.model { parts.append(m) }
        if let a = model.deviceInfo?.android { parts.append("Android \(a)") }
        if model.isCharging { parts.append(tr("Charging")) }
        return parts.isEmpty ? tr("Linked") : parts.joined(separator: " · ")
    }

    private var stats: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: DD.Space.md), count: model.linkQuality == nil ? 2 : 3),
                  spacing: DD.Space.md) {
            stat(tr("Notifications"), value: "\(model.notifications.count)", symbol: "bell",
                 note: model.notifications.isEmpty ? tr("All caught up") : tr("Waiting for you")) { withDDPointerAnimation(Motion.screen) { model.selection = .notifications } }
            stat(tr("Transfers"), value: "\(model.activeTransferCount)", symbol: "arrow.up.arrow.down",
                 note: model.activeTransferCount == 0 ? tr("Nothing in flight") : tr("In progress")) { withDDPointerAnimation(Motion.screen) { model.selection = .transfers } }
            if let q = model.linkQuality {
                let lvl = LinkQualityLevel(grade: q.grade)
                stat(tr("Latency"), value: q.rttMs.map { "\(Int($0.rounded()))" } ?? "–", unit: q.rttMs == nil ? nil : "ms", symbol: "wifi",
                     note: lvl.title, noteColor: lvl.textColor) { withDDPointerAnimation(Motion.screen) { model.selection = .devices } }
            }
        }
    }

    private func stat(_ title: String, value: String, unit: String? = nil, symbol: String, note: String,
                      noteColor: Color = DD.dim, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: DD.Space.xs) {
                Label(title, systemImage: symbol).font(DD.Font.label).foregroundStyle(DD.dim)
                    .symbolRenderingMode(.hierarchical)
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(value).font(DD.Font.bigNumber).monospacedDigit().foregroundStyle(DD.fg)
                        .contentTransition(.numericText())
                    if let unit { Text(unit).font(DD.Font.callout).foregroundStyle(DD.dim) }
                }
                Text(note).font(DD.Font.caption).foregroundStyle(noteColor).lineLimit(1)
            }
            .padding(DD.Space.md)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(DashTileStyle(radius: DD.Radius.lg))
        .ddAnimation(Motion.content, value: value)
    }

    // MARK: Unpaired

    @ViewBuilder private var pairing: some View {
        if cardShowsQR {
            // The sidebar card is already showing the QR: say what to do in one line, don't repeat the steps.
            VStack(spacing: DD.Space.lg) {
                ContentUnavailableView {
                    Label(tr("Dock your phone"), systemImage: "qrcode.viewfinder").symbolRenderingMode(.hierarchical)
                } description: {
                    Text(tr("Scan the code beside this window.") + " " + tr("Both devices need the same Wi-Fi network."))
                }
                .frame(maxWidth: .infinity, minHeight: 220)
                manualPairing.frame(maxWidth: 420)
            }
            .frame(maxWidth: .infinity)
        } else {
            Card(padding: DD.Space.xl) {
                HStack(alignment: .center, spacing: DD.Space.xl) {
                    LockedQRView {
                        ZStack {
                            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white)
                            if let qr = shell.qr {
                                Image(nsImage: qr).interpolation(.none).resizable().scaledToFit().padding(12)
                                    .accessibilityLabel(tr("Pairing QR code"))
                            } else { ProgressView().controlSize(.small) }
                        }
                        .frame(width: 176, height: 176)
                        .ddHairline(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    VStack(alignment: .leading, spacing: DD.Space.md) {
                        Text(tr("Dock your phone")).font(DD.Font.largeTitle).foregroundStyle(DD.fg)
                        Text(tr("Open DroidDock on your Android, tap Pair with Mac, and scan this code. Both devices need the same Wi-Fi network."))
                            .font(DD.Font.body).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.mini)
                            Text(tr("Looking for your phone…")).font(DD.Font.callout).foregroundStyle(DD.dim)
                        }
                        manualPairing
                    }
                    Spacer(minLength: 0)
                }
            }
        }
    }

    /// The token pairs a phone just like the QR does, so it sits behind the same Touch ID lock.
    private var manualPairing: some View {
        LockedQRView {
            DisclosureGroup(isExpanded: $manual) {
                VStack(alignment: .leading, spacing: DD.Space.xs) {
                    copyRow(tr("Address"), model.pairing.map { "\($0.ips.first ?? $0.host):\($0.port)" })
                    copyRow(tr("Token"), model.pairing?.token ?? model.config?.token)
                }.padding(.top, DD.Space.xs)
            } label: {
                Text(tr("Pair with an IP address instead")).font(DD.Font.callout).foregroundStyle(DD.dim)
            }
        }
    }

    private func copyRow(_ label: String, _ value: String?) -> some View {
        HStack {
            Text(label).font(DD.Font.callout).foregroundStyle(DD.dim).frame(width: 60, alignment: .leading)
            Text(value ?? "–").font(DD.Font.mono).foregroundStyle(DD.fg).textSelection(.enabled)
                .lineLimit(1).truncationMode(.middle)
            Spacer(minLength: 0)
            if let value {
                IconButton(systemImage: "doc.on.doc", label: tr("Copy"), size: 24) {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(value, forType: .string)
                    model.toast(.ok, tr("Copied"))
                }
            }
        }
    }

    // MARK: Actions

    private var actions: some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            SectionHeader(tr("Quick actions"))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: DD.Space.md)], spacing: DD.Space.md) {
                tile(tr("Send file"), "arrow.up.doc", hint: "⌘U") { ShellActions.pickAndSend() }
                tile(model.mirror != nil ? tr("Stop mirror") : tr("Mirror"), "rectangle.on.rectangle", hint: "⌘⇧M",
                     isOn: model.mirror != nil) { ShellActions.toggleMirror() }
                tile(tr("Screenshot"), "camera.viewfinder") { ShellActions.screenshot() }
                tile(shell.ringing ? tr("Stop ringing") : tr("Ring phone"), "bell.and.waves.left.and.right",
                     isOn: shell.ringing) { ShellActions.toggleRing() }
                tile(tr("Send clipboard"), "doc.on.clipboard", hint: "⌘⇧V") { ShellActions.pushClipboard() }
                tile(tr("Browse files"), "folder") { model.selection = .files }
            }
        }
    }

    private func tile(_ title: String, _ symbol: String, hint: String? = nil, isOn: Bool = false,
                      action: @escaping () -> Void) -> some View {
        DashTile(title: title, symbol: symbol, hint: hint, isOn: isOn, action: action)
            .disabled(!model.isConnected)
    }

    // MARK: Notifications / transfers

    private var recentNotifications: some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            SectionHeader(tr("Recent notifications")) {
                if model.notifications.count > 4 {
                    Button(tr("Show all ({n})", ["n": model.notifications.count])) { model.selection = .notifications }
                        .buttonStyle(.link).font(DD.Font.label)
                }
            }
            Card(padding: DD.Space.xs) {
                if model.notifications.isEmpty {
                    HStack(spacing: DD.Space.md) {
                        Image(systemName: "checkmark.circle").font(.system(size: 18)).foregroundStyle(.secondary)
                            .symbolRenderingMode(.hierarchical)
                        Text(tr("Nothing new on your phone")).font(DD.Font.body).foregroundStyle(DD.dim)
                        Spacer()
                    }
                    .padding(DD.Space.md)
                } else {
                    let items = Array(model.notifications.prefix(4))
                    VStack(spacing: 0) {
                        ForEach(Array(items.enumerated()), id: \.element.key) { i, n in
                            ListRow(title: n.title ?? n.app ?? tr("Notification"), subtitle: n.text,
                                    meta: shellRelativeTime(n.time)) {
                                PhoneAppIcon(pkg: n.pkg, name: n.app ?? n.title ?? "?", size: 32)
                            } trailing: {
                                IconButton(systemImage: "xmark", label: tr("Dismiss"), size: 24) { model.dismissNotification(n.key) }
                            }
                            if i < items.count - 1 { Divider().overlay(DD.line).padding(.leading, 56) }
                        }
                    }
                }
            }
        }
    }

    private var transfers: some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            SectionHeader(tr("Transfers in flight")) {
                Button(tr("Show all")) { model.selection = .transfers }
                    .buttonStyle(.link).font(DD.Font.label)
            }
            Card(padding: DD.Space.xs) {
                VStack(spacing: 0) {
                    let active = model.transfers.filter(\.isActive).prefix(3)
                    ForEach(Array(active), id: \.key) { t in
                        ProgressRow(name: t.name, systemImage: "doc.fill", incoming: t.dir == "toMac",
                                    fraction: t.total > 0 ? min(1, t.done / t.total) : 0,
                                    detail: detail(t), state: .running)
                    }
                }
            }
        }
    }

    private func detail(_ t: TransferEntry) -> String {
        var s = t.unit == "bytes" ? "\(shellBytes(t.done)) \(tr("of")) \(shellBytes(t.total))" : "\(Int(t.done)) / \(Int(t.total))"
        if t.speed > 0, t.unit == "bytes" { s += " · \(shellBytes(t.speed))/s" }
        if let e = t.etaSecs, e > 0 { s += " · \(Int(e)) s" }
        return s
    }
}

/// A widget-like tile surface: faint fill, 1-px container edge, a quiet hover wash, accent only when `isOn`.
/// No shadow, no lift (Apple's widgets and Control Center tiles are flat).
private struct DashTileStyle: ButtonStyle {
    var radius: CGFloat = DD.Radius.lg
    var isOn = false
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)   // tile-like custom button
            .animation(Motion.press, value: configuration.isPressed)
            .background {
                shape.fill(isOn ? Color.accentColor
                           : configuration.isPressed ? DD.panel3
                           : hovering && enabled ? DD.panel2 : DD.panel)
            }
            .ddHairline(shape, DD.containerEdge)
            .contentShape(shape)
            .opacity(enabled ? 1 : 0.45)
            .onHover { hovering = $0 }
            .ddAnimation(Motion.hover, value: hovering)
    }
}

private struct DashTile: View {
    var title: String
    var symbol: String
    var hint: String?
    var isOn = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: DD.Space.sm) {
                Image(systemName: symbol).font(.system(size: 20, weight: .regular))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(isOn ? DD.accentInk : DD.dim)
                    .frame(height: 24)
                Text(title).font(DD.Font.label).foregroundStyle(isOn ? DD.accentInk : DD.fg).lineLimit(1)
            }
            .frame(maxWidth: .infinity).padding(.vertical, DD.Space.md + 2)
        }
        .buttonStyle(DashTileStyle(radius: DD.Radius.md, isOn: isOn))
        .help(hint.map { "\(title)  \($0)" } ?? title)
    }
}
