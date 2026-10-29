import AppKit
import SwiftUI

/// The menu bar panel (hosted by `MenuBarController` in a Dynamic-Island-style panel): the phone at a glance, in
/// Control Center-style modules on a popover material. Segments appear only when relevant.
///
/// Grid: 12 pt outer margin, 8 pt between modules, 12 pt inside a module; modules are 14 pt continuous
/// (the panel itself is 22 pt, set by `Motion.Island.panelRadius`).
struct MenuBarPanel: View {
    @Environment(AppModel.self) private var model
    @State private var shell = ShellState.shared
    @State private var replyKey: String?
    @State private var replyText = ""
    @FocusState private var replyFocused: Bool
    @State private var listHeight: CGFloat = 0

    private var isPaired: Bool { model.isConnected || model.config?.deviceName?.isEmpty == false }
    private var visible: [PhoneNotification] {
        model.notifications.filter { model.activeCall == nil || $0.type != "call" }
    }

    var body: some View {
        VStack(spacing: DD.Space.sm) {
            if isPaired {
                header
                if model.isConnected { actionsModule }
                if let call = model.activeCall { callModule(call) }
                if model.isConnected, let np = shell.nowPlaying(model) { PanelNowPlaying(track: np) }
                if !visible.isEmpty { notificationsModule }
            } else {
                pairingModule
            }
            footer
        }
        .padding(DD.Space.md)
        .frame(width: DD.Size.menuBarWidth)
        .ddAnimation(Motion.content, value: visible.count)
        .ddAnimation(Motion.content, value: model.activeCall?.state)
        .ddAnimation(Motion.content, value: model.isConnected)
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: DD.Space.md) {
            if let b = model.batteryPercent, model.isConnected {
                BatteryRing(level: b, charging: model.isCharging, size: 36, lineWidth: 3.5)
            } else {
                Image(systemName: "iphone.gen3").font(.system(size: 22))
                    .symbolRenderingMode(.hierarchical).foregroundStyle(DD.dim).frame(width: 36, height: 36)
            }
            VStack(alignment: .leading, spacing: DD.Space.xs) {
                Text(model.phoneName ?? tr("No phone linked"))
                    .font(DD.Font.title3).foregroundStyle(DD.fg).lineLimit(1)
                if model.isConnected {
                    ConnectionPill(transport: model.pillTransport, link: model.linkQuality.map { LinkQualityLevel(grade: $0.grade) },
                                   rttMs: model.linkQuality?.rttMs.map { Int($0.rounded()) }, style: .standard)
                } else {
                    StatusPill(text: tr("Offline"), kind: .neutral)
                }
            }
            Spacer(minLength: 0)
            if model.isPaused { StatusPill(text: tr("Paused"), kind: .warn, systemImage: "pause.fill") }
        }
        .padding(.horizontal, DD.Space.xs).padding(.top, DD.Space.xs)
    }

    // MARK: Quick actions

    private struct Act: Identifiable {
        var id: String; var symbol: String; var label: String; var help: String; var on = false; var run: () -> Void
    }

    private var items: [Act] {[
        Act(id: "clip", symbol: "doc.on.clipboard", label: tr("Clipboard"), help: tr("Send clipboard")) { ShellActions.pushClipboard() },
        Act(id: "file", symbol: "arrow.up.doc", label: tr("File"), help: tr("Send file")) { ShellActions.pickAndSend() },
        Act(id: "ring", symbol: "bell.and.waves.left.and.right", label: tr("Ring"),
            help: shell.ringing ? tr("Stop ringing") : tr("Ring phone"), on: shell.ringing) { ShellActions.toggleRing() },
        Act(id: "cam", symbol: "camera", label: tr("Camera"), help: tr("Phone camera")) { ShellActions.toggleMirror(source: "camera") },
        Act(id: "mirror", symbol: "rectangle.on.rectangle", label: tr("Mirror"),
            help: model.mirror != nil ? tr("Stop mirror") : tr("Mirror"), on: model.mirror != nil) { ShellActions.toggleMirror() },
    ]}

    private var actionsModule: some View {
        PanelModule(padding: DD.Space.sm) {
            HStack(spacing: 0) {
                ForEach(items) { a in
                    QuickActionButton(symbol: a.symbol, label: a.label, help: a.help, isOn: a.on, action: a.run)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    // MARK: Call

    @ViewBuilder private func callModule(_ call: IncomingCall) -> some View {
        let who = call.callerName ?? call.callerNumber ?? tr("Unknown caller")
        let ringing = call.state == "ringing"
        PanelModule(tint: DD.ok.opacity(ringing ? 0.16 : 0.10)) {
            HStack(spacing: DD.Space.md) {
                AvatarView(name: who, size: 36, systemImage: call.callerName == nil ? "person.fill" : nil)
                VStack(alignment: .leading, spacing: 1) {
                    Text(who).font(DD.Font.headline).foregroundStyle(DD.fg).lineLimit(1)
                    if ringing {
                        Text(tr("Incoming call")).font(DD.Font.callout).foregroundStyle(DD.dim)
                    } else {
                        TimelineView(.periodic(from: .now, by: UIActivity.shared.period(1))) { ctx in
                            let s = Int(ctx.date.timeIntervalSince(shell.callStart ?? ctx.date))
                            Text(tr("In call") + String(format: " · %02d:%02d", s / 60, s % 60))
                                .font(DD.Font.callout).monospacedDigit().foregroundStyle(DD.linkText)
                        }
                    }
                }
                Spacer(minLength: 0)
                if ringing {
                    circle("phone.down.fill", tr("Decline"), DD.bad) { callAction("end") }
                    circle("phone.fill", tr("Answer"), DD.ok) { callAction("answer") }
                } else {
                    IconButton(systemImage: call.muted == true ? "mic.slash.fill" : "mic.fill", label: tr("Mute"),
                               isOn: call.muted == true) { callAction("mute", on: !(call.muted ?? false)) }
                    IconButton(systemImage: "speaker.wave.2.fill", label: tr("Speaker"),
                               isOn: call.speaker == true) { callAction("speaker", on: !(call.speaker ?? false)) }
                    circle("phone.down.fill", tr("End call"), DD.bad) { callAction("end") }
                }
            }
        }
        .ddTransition(.ddRow)
    }

    private func circle(_ sym: String, _ label: String, _ color: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: sym).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 32, height: 32).background(Circle().fill(color))
        }
        .buttonStyle(PressableButtonStyle()).help(label).accessibilityLabel(label)
    }

    private func callAction(_ a: String, on: Bool? = nil) {
        Task { do { _ = try await Core.shared.callAction(a, on: on) } catch { model.toast(.bad, error.localizedDescription) } }
    }

    // MARK: Notifications

    private static let maxRows = 8
    private static let maxListHeight: CGFloat = 244

    private var notificationsModule: some View {
        let rows = Array(visible.prefix(Self.maxRows))
        return PanelModule(padding: 0) {
            VStack(spacing: 0) {
                HStack {
                    Text(tr("Notifications")).font(DD.Font.label.weight(.semibold)).foregroundStyle(DD.dim)
                    Spacer()
                    if model.notifications.count > rows.count || model.notifications.count > 3 {
                        Button { WindowControl.showMain(); model.selection = .notifications; MenuBarController.shared.close() } label: {
                            Text(tr("Show all ({n})", ["n": model.notifications.count]))
                                .font(DD.Font.label).foregroundStyle(Color.accentColor)
                        }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, DD.Space.md).padding(.top, DD.Space.sm + 2).padding(.bottom, DD.Space.xs)

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.element.key) { i, n in
                            VStack(spacing: 0) {
                                NotificationCard(n: n, replying: replyKey == n.key,
                                                 onOpen: { NotificationClickActions.shared.open(key: n.key) },
                                                 onReply: { toggleReply(n) }, onDismiss: { model.dismissNotification(n.key) })
                                if replyKey == n.key { replyField(n).transition(.opacity) }
                                if i < rows.count - 1 { Divider().overlay(DD.line).padding(.leading, DD.Space.md + 34 + DD.Space.sm + 2) }
                            }
                            .ddTransition(.ddRow)
                        }
                    }
                    .background(GeometryReader { g in Color.clear.preference(key: ListHeightKey.self, value: g.size.height) })
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: min(max(listHeight, 1), Self.maxListHeight))
                .onPreferenceChange(ListHeightKey.self) { listHeight = $0 }
                .padding(.bottom, DD.Space.xs)
            }
        }
    }

    private func toggleReply(_ n: PhoneNotification) {
        withDDAnimation(Motion.expand) { replyKey = replyKey == n.key ? nil : n.key }
        if replyKey == n.key { replyFocused = true }
    }

    private func replyField(_ n: PhoneNotification) -> some View {
        HStack(spacing: DD.Space.sm) {
            TextField(tr("Reply…"), text: $replyText)
                .textFieldStyle(.roundedBorder).font(DD.Font.body)
                .focused($replyFocused)
                .onAppear { replyFocused = true }
                .onSubmit { send(n) }
            Button(tr("Send")) { send(n) }
                .buttonStyle(PrimaryButtonStyle(compact: true))
                .disabled(replyText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal, DD.Space.md).padding(.bottom, DD.Space.sm).padding(.leading, 34 + DD.Space.sm + 2)
    }

    private func send(_ n: PhoneNotification) {
        let text = replyText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        replyText = ""; replyKey = nil
        Task {
            do { try await Core.shared.notifReply(key: n.key, text: text); model.toast(.ok, tr("Reply sent")) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }

    // MARK: Unpaired

    private var pairingModule: some View {
        PanelModule(padding: DD.Space.lg) {
            VStack(spacing: DD.Space.md) {
                Image(systemName: "iphone.gen3").font(.system(size: 34)).symbolRenderingMode(.hierarchical).foregroundStyle(DD.dim)
                Text(tr("Pair a phone")).font(DD.Font.title2).foregroundStyle(DD.fg)
                Text(tr("Open DroidDock on your phone, then scan the code in the main window."))
                    .font(DD.Font.callout).foregroundStyle(DD.dim).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button(tr("Show Pairing Code")) {
                    ShellState.shared.forcePairing = true; model.selection = .dashboard; WindowControl.showMain()
                    MenuBarController.shared.close()
                }.buttonStyle(PrimaryButtonStyle())
            }.frame(maxWidth: .infinity)
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: DD.Space.sm) {
            FooterButton(symbol: "macwindow", title: tr("Open DroidDock"), help: tr("Open DroidDock")) {
                MenuBarController.shared.close(); WindowControl.showMain()
            }
            FooterButton(symbol: model.isPaused ? "play.circle" : "pause.circle",
                         help: model.isPaused ? tr("Resume Notifications") : tr("Pause for 1 Hour")) { ShellActions.togglePause() }
                .frame(width: 34)
            FooterButton(symbol: "gearshape", help: tr("Settings…") + "  ⌘,") {
                MenuBarController.shared.close(); ShellActions.showSettings()
            }.frame(width: 34)
            FooterButton(symbol: "power", help: tr("Quit DroidDock") + "  ⌘Q", destructive: true) { AppDelegate.quitNow() }
                .frame(width: 34)
        }
    }
}

private struct ListHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

// MARK: - Module surface

/// A Control Center-style tile: faint fill on the popover, 14-pt continuous corners, a hairline edge
/// (stronger under Increase Contrast).
private struct PanelModule<Content: View>: View {
    var padding: CGFloat = DD.Space.md
    var tint: Color?
    @ViewBuilder var content: Content
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.xl, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(DD.panel2))
            .background(shape.fill(tint ?? .clear))
            .overlay(shape.strokeBorder(contrast == .increased ? DD.lineStrong : DD.line.opacity(0.5), lineWidth: contrast == .increased ? 1 : 0.5))
    }
}

/// Round button with a small label under it (Control Center's labelled toggles).
private struct QuickActionButton: View {
    var symbol: String
    var label: String
    var help: String
    var isOn: Bool
    var action: () -> Void
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        Button(action: action) {
            VStack(spacing: DD.Space.xs + 2) {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .medium))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(isOn ? DD.accentInk : DD.fg)
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(isOn ? Color.accentColor : (hovering ? DD.panel3 : DD.panel2)))
                Text(label).font(.system(size: 10, weight: .medium)).foregroundStyle(isOn ? DD.fg : DD.dim).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle(scale: 0.94))
        .onHover { hovering = $0 && enabled }
        .ddAnimation(Motion.hover, value: hovering)
        .ddAnimation(Motion.select, value: isOn)
        .opacity(enabled ? 1 : 0.4)
        .help(help)
        .accessibilityLabel(help)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

private struct FooterButton: View {
    var symbol: String
    var title: String?
    var help: String
    var destructive = false
    var action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: DD.Space.sm) {
                Image(systemName: symbol).font(.system(size: 13, weight: .medium)).symbolRenderingMode(.hierarchical)
                if let title { Text(title).font(DD.Font.body.weight(.medium)) }
            }
            .foregroundStyle(hovering && destructive ? DD.bad : DD.fg)
            .frame(maxWidth: .infinity).frame(height: 34)
            .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(hovering ? DD.panel3 : DD.panel2))
            .contentShape(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous))
        }
        .buttonStyle(PressableButtonStyle(scale: 0.97))
        .onHover { hovering = $0 }
        .ddAnimation(Motion.hover, value: hovering)
        .help(help)
        .accessibilityLabel(help)
    }
}

// MARK: - Notification row

private struct NotificationCard: View {
    var n: PhoneNotification
    var replying: Bool
    var onOpen: () -> Void
    var onReply: () -> Void
    var onDismiss: () -> Void
    @State private var hovering = false
    @State private var dx: CGFloat = 0
    @State private var gone = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .top, spacing: DD.Space.sm + 2) {
            PhoneAppIcon(pkg: n.pkg, name: n.app ?? n.title ?? "?", size: 34)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: DD.Space.xs) {
                    Text(n.app ?? tr("Notification")).font(DD.Font.label.weight(.semibold)).foregroundStyle(DD.dim).lineLimit(1)
                    if let t = shellRelativeTime(n.time) {
                        Text("·").font(DD.Font.label).foregroundStyle(DD.faint)
                        Text(t).font(DD.Font.label).foregroundStyle(DD.faint).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Button(action: dismiss) {
                        Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(DD.dim)
                            .frame(width: 18, height: 18).background(Circle().fill(DD.panel3))
                    }
                    .buttonStyle(.plain).help(tr("Dismiss")).accessibilityLabel(tr("Dismiss"))
                    .opacity(hovering ? 1 : 0)
                }
                if let title = n.title, !title.isEmpty {
                    Text(title).font(DD.Font.body.weight(.semibold)).foregroundStyle(DD.fg).lineLimit(1)
                }
                if let text = n.text, !text.isEmpty {
                    Text(text).font(DD.Font.callout).foregroundStyle(DD.dim).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                let canOpen = n.type != "call" && NotificationClickActions.shared.canOpenOnPhone(key: n.key)
                if n.replyable == true || canOpen {
                    HStack(spacing: DD.Space.xs + 2) {
                        if canOpen {
                            Button(action: onOpen) {
                                Label(tr("Open"), systemImage: "iphone")
                                    .font(DD.Font.label.weight(.semibold))
                                    .padding(.horizontal, DD.Space.sm + 2).padding(.vertical, 3)
                                    .background(Capsule().fill(DD.panel3))
                                    .foregroundStyle(Color.accentColor)
                            }
                            .buttonStyle(PressableButtonStyle(scale: 0.96))
                            .accessibilityLabel(tr("Open on your phone"))
                        }
                        if n.replyable == true {
                            Button(action: onReply) {
                                Label(tr("Reply"), systemImage: "arrowshape.turn.up.left.fill")
                                    .font(DD.Font.label.weight(.semibold))
                                    .padding(.horizontal, DD.Space.sm + 2).padding(.vertical, 3)
                                    .background(Capsule().fill(replying ? Color.accentColor : DD.panel3))
                                    .foregroundStyle(replying ? DD.accentInk : Color.accentColor)
                            }
                            .buttonStyle(PressableButtonStyle(scale: 0.96))
                            .accessibilityLabel(tr("Reply"))
                        }
                    }.padding(.top, 3)
                }
            }
        }
        .padding(.horizontal, DD.Space.md).padding(.vertical, DD.Space.sm)
        .background(hovering ? DD.hover.opacity(0.6) : .clear)
        .contentShape(Rectangle())
        .offset(x: dx).opacity(gone ? 0 : Double(1 - min(abs(dx) / 220, CGFloat(0.6))))
        .onHover { hovering = $0 }
        .ddAnimation(Motion.hover, value: hovering)
        // Swipe sideways to dismiss (a click-drag, since the panel has no touch input).
        .gesture(DragGesture(minimumDistance: 16)
            .onChanged { g in if abs(g.translation.width) > abs(g.translation.height) { dx = g.translation.width } }
            .onEnded { g in
                if abs(g.translation.width) > 80 { dismiss() } else { withDDAnimation(Motion.select) { dx = 0 } }
            })
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: tr("Dismiss")) { onDismiss() }
    }

    private func dismiss() {
        if reduceMotion { onDismiss(); return }
        withAnimation(Motion.exit) { gone = true; dx = dx == 0 ? -40 : dx * 2 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.14) { onDismiss() }
    }
}

// MARK: - Now playing

/// Control Center's media module: artwork, title and artist, scrubber, transport.
private struct PanelNowPlaying: View {
    var track: DDNowPlaying
    @State private var scrub: Double?

    private var hasLength: Bool { track.duration > 0 && track.position <= track.duration + 1 }

    var body: some View {
        PanelModule {
            VStack(spacing: DD.Space.md) {
                HStack(spacing: DD.Space.md) {
                    artwork
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title.isEmpty ? tr("Unknown track") : track.title)
                            .font(DD.Font.body.weight(.semibold)).foregroundStyle(DD.fg).lineLimit(1)
                        Text(track.artist ?? track.app ?? "").font(DD.Font.callout).foregroundStyle(DD.dim).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .help([track.title, track.artist].compactMap { $0 }.joined(separator: " – "))
                }
                .ddAnimation(Motion.content, value: track.trackKey)
                if hasLength {
                    TimelineView(.periodic(from: .now, by: track.isPlaying ? UIActivity.shared.period(1) : 3600)) { ctx in
                        SeekBar(position: scrub ?? live(at: ctx.date), duration: track.duration,
                                onScrub: { scrub = $0 }, onCommit: { v in scrub = nil; ShellActions.media(.seek(v)) })
                    }
                }
                HStack(spacing: DD.Space.xl) {
                    transport("backward.fill", tr("Previous"), .previous, glyph: 15)
                    transport(track.isPlaying ? "pause.fill" : "play.fill", track.isPlaying ? tr("Pause") : tr("Play"), .playPause, glyph: 22)
                    transport("forward.fill", tr("Next"), .next, glyph: 15)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(tr("Now playing {t}", ["t": track.title]))
    }

    private func live(at date: Date) -> Double {
        guard track.isPlaying else { return track.position }
        return min(track.duration, track.position + date.timeIntervalSince(track.positionAsOf))
    }

    private var artwork: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous)
        return Group {
            if let art = track.artwork {
                Image(nsImage: art).resizable().scaledToFill()
            } else {
                ZStack {
                    LinearGradient(colors: [DDTint.color(for: track.title).opacity(0.7), DDTint.color(for: track.title)],
                                   startPoint: .top, endPoint: .bottom)
                    Image(systemName: "music.note").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white.opacity(0.9))
                }
            }
        }
        .frame(width: 52, height: 52).clipShape(shape).ddHairline(shape)
        .id(track.artwork == nil ? "none" : track.trackKey ?? "art")
        .transition(.opacity)
        .ddAnimation(Motion.backdrop, value: track.artwork != nil)
        .accessibilityHidden(true)
    }

    private func transport(_ symbol: String, _ label: String, _ cmd: DDMediaCommand, glyph: CGFloat) -> some View {
        Button { ShellActions.media(cmd) } label: {
            Image(systemName: symbol).font(.system(size: glyph)).contentTransition(.symbolEffect(.replace))
                .frame(width: 40, height: 32).contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle(scale: 0.88)).foregroundStyle(DD.fg)
        .help(label).accessibilityLabel(label)
    }
}
