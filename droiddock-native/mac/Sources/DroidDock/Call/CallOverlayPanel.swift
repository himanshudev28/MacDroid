import AppKit
import SwiftUI

/// Floating, non-activating call banner (top-right). Ringing: Decline / Message / Answer. In a call: timer, mute, hang up.
/// Never steals focus from what the user is typing; ⏎ answers and Esc declines while a call rings.
@MainActor
final class CallOverlayController {
    static let shared = CallOverlayController()

    private var panel: CallPanel?
    private var keyMonitor: Any?
    private var visibleState: String?
    /// The call state the user closed the pop-up in. It stays hidden until the call changes state or ends.
    private var dismissedState: String?
    private let width: CGFloat = 340
    /// Transparent room around the card so the hover × can sit on its corner without being clipped.
    private let margin: CGFloat = 10

    private init() {}

    func install() {
        track()
        apply()
    }

    private func track() {
        withObservationTracking {
            _ = AppModel.shared.activeCall
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.apply()
                self?.track()
            }
        }
    }

    private func apply() {
        let call = AppModel.shared.activeCall
        CallControls.shared.sync(call)
        if call?.state == "ringing", NotifPrefs.callRing { CallRinger.shared.start() } else { CallRinger.shared.stop() }
        guard let call, ["ringing", "active", "dialing"].contains(call.state) else { dismissedState = nil; hide(); return }
        if let d = dismissedState {
            if d == call.state { hide(); return }
            dismissedState = nil
        }
        // Settings › Notifications › Call alert: the floating panel announces a ringing call only in "Pop-up" mode
        // (it still appears once the call is answered, for mute / hang up).
        if call.state == "ringing", NotifPrefs.callAlert != "popup" { hide(); return }
        show(for: call.state)
    }

    /// The × on the pop-up: hide it without touching the call.
    func dismiss() {
        dismissedState = AppModel.shared.activeCall?.state
        hide()
    }

    private func height(for state: String) -> CGFloat { state == "ringing" ? 88 : 76 }

    private func ensurePanel() -> CallPanel {
        if let panel { return panel }
        let p = CallPanel(contentRect: NSRect(x: 0, y: 0, width: width + 2 * margin, height: 88 + 2 * margin),
                          styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        p.level = .floating
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.hasShadow = true
        p.isOpaque = false
        p.backgroundColor = .clear
        p.isMovableByWindowBackground = true
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        p.animationBehavior = .none
        let root = CallOverlayView()
            .environment(AppModel.shared)
            .ddAppearance(AppearanceSettings.shared)
            .frame(width: width)
            .padding(margin)
        let host = NSHostingView(rootView: root)
        host.sizingOptions = []
        p.contentView = host
        panel = p
        return p
    }

    private func frame(height: CGFloat) -> NSRect {
        let screen = NSScreen.main ?? NSScreen.screens.first
        let vf = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1200, height: 800)
        return NSRect(x: vf.maxX - width - 16 - margin, y: vf.maxY - height - 12 - margin,
                      width: width + 2 * margin, height: height + 2 * margin)
    }

    private func show(for state: String) {
        let p = ensurePanel()
        let h = height(for: state)
        if !p.isVisible {
            p.setFrame(frame(height: h), display: true)
            p.alphaValue = 0
            p.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = Motion.systemReduceMotion ? 0.1 : 0.2
                p.animator().alphaValue = 1
            }
        } else if visibleState == nil, p.alphaValue < 1 {
            // Closed with the × (or mid-fade) and the call changed state: bring the same panel back.
            p.setFrame(frame(height: h), display: true)
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = Motion.systemReduceMotion ? 0.1 : 0.2
                p.animator().alphaValue = 1
            }
        } else if visibleState != state {
            // Same top-right corner; only the height changes (ringing -> in-call).
            var f = p.frame
            f.origin.y += f.height - (h + 2 * margin)
            f.size.height = h + 2 * margin
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = Motion.systemReduceMotion ? 0 : 0.2
                p.animator().setFrame(f, display: true)
            }
        }
        visibleState = state
        UIActivity.shared.setCallOverlayShown(true)
        installKeys()
    }

    private func hide() {
        removeKeys()
        visibleState = nil
        UIActivity.shared.setCallOverlayShown(false)
        guard let p = panel, p.isVisible else { return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = Motion.systemReduceMotion ? 0.1 : 0.14
            p.animator().alphaValue = 0
        }, completionHandler: { [weak p] in
            Task { @MainActor in
                // Also when only the × hid it: an invisible panel must not keep swallowing clicks at the screen corner.
                if self.visibleState == nil { p?.orderOut(nil) }
            }
        })
    }

    // MARK: Keyboard

    private func installKeys() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard AppModel.shared.activeCall?.state == "ringing" else { return event }
            // Esc/Return belong to whatever window the user is in: only act when the call panel itself is key.
            guard let panel = self?.panel, event.window === panel else { return event }
            // Never hijack typing: only act when no text field has focus and no modifiers are held.
            if NSApp.keyWindow?.firstResponder is NSText { return event }
            guard event.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty else { return event }
            switch event.keyCode {
            case 36, 76:
                if AppModel.shared.activeCall?.canAnswer == true { CallControls.shared.answer(); return nil }
            case 53:
                CallControls.shared.end(); return nil
            default: break
            }
            return event
        }
    }

    private func removeKeys() {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
    }
}

private final class CallPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

// MARK: - SwiftUI content

struct CallOverlayView: View {
    @Environment(AppModel.self) private var model
    @State private var controls = CallControls.shared
    @State private var appeared = false
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let call = model.activeCall { content(call) }
        }
        .windowMaterial(.popover, radius: 18)
        .ddHairline(RoundedRectangle(cornerRadius: 18, style: .continuous))
        // Close button, like a macOS notification: appears on hover at the top-left corner. Hides the pop-up only;
        // the call carries on (it comes back if the call changes state, e.g. when answered).
        .overlay(alignment: .topLeading) {
            if hovering && model.activeCall != nil {
                Button { CallOverlayController.shared.dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(DD.dim)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(.regularMaterial))
                        .overlay(Circle().strokeBorder(DD.line))
                        .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .focusable(false)
                .help(tr("Close"))
                .accessibilityLabel(tr("Close"))
                .offset(x: -6, y: -6)
                .transition(.opacity)
            }
        }
        .onHover { h in withDDAnimation(Motion.content) { hovering = h } }
        // Slide + scale from 0.96 (never 0), anchored to the top edge it drops from. Reduce Motion: fade only.
        .opacity(appeared ? 1 : 0)
        .scaleEffect(appeared || reduceMotion ? 1 : 0.96, anchor: .top)
        .offset(y: appeared || reduceMotion ? 0 : -10)
        .ddAnimation(Motion.content, value: model.activeCall?.state)
        .onAppear { present() }
        // The panel (and this view) is reused for every call: replay the entrance each time one starts.
        .onChange(of: model.activeCall == nil) { _, gone in
            if gone {
                withDDAnimation(Motion.exit) { appeared = false }
            } else {
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) { appeared = false }
                present()
            }
        }
    }

    private func present() {
        Task { @MainActor in withDDAnimation(Motion.toast) { appeared = true } }
    }

    @ViewBuilder
    private func content(_ call: IncomingCall) -> some View {
        let ringing = call.state == "ringing"
        let title = call.callerName ?? call.callerNumber ?? tr("Unknown caller")
        HStack(spacing: 10) {
            AvatarView(name: title, size: 40, systemImage: call.callerName == nil ? "person.fill" : nil)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(DD.Font.headline).foregroundStyle(DD.fg).lineLimit(1)
                Group {
                    if ringing {
                        if let app = call.app, !app.isEmpty {
                            Text(tr("{app} call", ["app": app]))
                        } else {
                            Text(call.callerName != nil ? (call.callerNumber ?? tr("Incoming call")) : tr("Incoming call"))
                        }
                    } else if call.state == "dialing" {
                        Text(tr("Calling…"))
                    } else if let since = controls.activeSince {
                        CallTimer(since: since)
                    } else {
                        Text(tr("In call"))
                    }
                }
                .font(DD.Font.callout).foregroundStyle(DD.dim).lineLimit(1)
            }
            Spacer(minLength: DD.Space.xs)
            if ringing {
                if let number = call.callerNumber, call.voip != true {
                    CallCircle(symbol: "message.fill", tint: DD.dim, label: tr("Reply with a message"), size: 34) {
                        InboxRouter.shared.openMessages(to: MessageTarget(number: number, name: call.callerName ?? number))
                    }
                }
                CallCircle(symbol: "phone.down.fill", tint: DD.bad, label: tr("Decline  Esc"), size: 34) { controls.end() }
                    .keyboardShortcut(.cancelAction)
                CallCircle(symbol: "phone.fill", tint: DD.ok, label: tr("Answer  ⏎"), size: 34) { controls.answer() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(call.canAnswer != true)
            } else {
                let muted = controls.muted ?? call.muted ?? false
                CallCircle(symbol: muted ? "mic.slash.fill" : "mic.fill", tint: DD.dim,
                           label: muted ? tr("Unmute") : tr("Mute"), filled: muted, size: 34) { controls.toggleMute() }
                CallCircle(symbol: "phone.down.fill", tint: DD.bad, label: tr("Hang Up")) { controls.end() }
            }
        }
        .padding(.horizontal, 14)
        .frame(maxHeight: .infinity)
        .accessibilityElement(children: .contain)
    }
}
