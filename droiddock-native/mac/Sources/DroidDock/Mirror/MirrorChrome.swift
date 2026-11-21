import AppKit
import Observation
import SwiftUI

extension Motion {
    /// AppKit twins of the `Motion` vocabulary for the mirror pop-out. `NSAnimationContext` and window alpha can't take a
    /// SwiftUI `Animation`, so the numbers live here, next to the tokens they copy. The video layer never animates.
    enum MirrorWindow {
        /// Window frame changes (⌘+ / ⌘−, rotation re-fit): same pacing as `Motion.expand`, ease-out, never blocking.
        static let frameDuration: TimeInterval = 0.30
        static let frameTiming = CAMediaTimingFunction(name: .easeOut)
        /// Traffic lights fading in / out with the chrome: `Motion.content` in, `Motion.exit` out (exits are faster).
        static let revealDuration: TimeInterval = 0.20
        static let concealDuration: TimeInterval = 0.14
        /// Chrome hides this long after the pointer last moved over the window (iPhone Mirroring / QuickTime style).
        static let idleHide: Duration = .milliseconds(2500)
        /// Grace after the pointer leaves the window, so moving down onto the control capsule doesn't flicker it.
        static let leaveGrace: Duration = .milliseconds(600)
        /// One ⌘+ / ⌘− step.
        static let sizeStep: CGFloat = 1.10
        /// Idle alpha of the traffic lights: present (so the window still reads as a window) but quiet.
        static let idleButtonAlpha: CGFloat = 0
    }
}

/// Show/hide state of the pop-out's chrome (traffic lights, title, the floating control capsule). Revealed while the pointer
/// is over the window or the capsule, hidden after it rests or leaves. Always shown while the stream isn't live (Stop must
/// be reachable while connecting) and while something holds it open (the control-hint popover).
@Observable @MainActor
final class MirrorChrome {
    private(set) var revealed = true
    private var byPointer = true
    private var overWindow = false
    private var overBar = false
    private var holds = 0

    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored weak var window: NSWindow?
    @ObservationIgnored weak var barPanel: NSPanel?
    @ObservationIgnored private var tracking = false

    /// Starts following the session phase (forced reveal while not live) and pushes state into AppKit.
    func start() {
        guard !tracking else { return }
        tracking = true
        voiceOverObservation = NSWorkspace.shared.observe(\.isVoiceOverEnabled) { [weak self] _, _ in
            Task { @MainActor in self?.recompute() }
        }
        track()
        armIdleHide()
    }

    func stop() {
        hideTask?.cancel(); hideTask = nil
        tracking = false
        voiceOverObservation = nil
    }

    /// VoiceOver or Full Keyboard Access: the controls must stay reachable, so the chrome never auto-hides.
    static var assistiveAccessOn: Bool { NSWorkspace.shared.isVoiceOverEnabled || NSApp.isFullKeyboardAccessEnabled }
    @ObservationIgnored private var voiceOverObservation: NSKeyValueObservation?

    private func track() {
        guard tracking else { return }
        withObservationTracking {
            _ = MirrorSession.shared.phase
        } onChange: { [weak self] in
            Task { @MainActor in self?.recompute(); self?.track() }
        }
        recompute()
    }

    // MARK: Pointer

    @ObservationIgnored private var lastArm = Date.distantPast

    /// Called on every pointer move over the window: re-arming the idle timer is throttled (moves arrive at display rate).
    func pointerMovedInWindow() {
        overWindow = true
        setByPointer(true)
        let now = Date()
        guard now.timeIntervalSince(lastArm) > 0.25 else { return }
        lastArm = now
        armIdleHide()
    }

    func pointerLeftWindow() {
        overWindow = false
        armLeaveHide()
    }

    func pointerInBar(_ inside: Bool) {
        overBar = inside
        if inside { hideTask?.cancel(); setByPointer(true) } else { armLeaveHide() }
    }

    /// Something (a popover anchored on the capsule) needs the chrome to stay put.
    func hold(_ on: Bool) {
        holds = max(0, holds + (on ? 1 : -1))
        if holds == 0 { armIdleHide() }
        recompute()
    }

    private func armIdleHide() {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: Motion.MirrorWindow.idleHide)
            guard !Task.isCancelled, let self, !self.overBar else { return }
            self.setByPointer(false)
        }
    }

    private func armLeaveHide() {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: Motion.MirrorWindow.leaveGrace)
            guard !Task.isCancelled, let self, !self.overBar else { return }
            // Back over the window during the grace: hide on idle instead, so the chrome can't get stuck revealed.
            if self.overWindow { self.armIdleHide(); return }
            self.setByPointer(false)
        }
    }

    private func setByPointer(_ v: Bool) {
        guard byPointer != v else { return }
        byPointer = v
        recompute()
    }

    private func recompute() {
        let v = byPointer || holds > 0 || MirrorSession.shared.phase != .live || Self.assistiveAccessOn
        guard v != revealed else { return }
        revealed = v
        apply()
    }

    /// AppKit side: the invisible capsule panel must not eat clicks, and the traffic lights fade with the rest.
    func apply() {
        barPanel?.ignoresMouseEvents = !revealed
        guard let w = window else { return }
        let alpha: CGFloat = revealed ? 1 : Motion.MirrorWindow.idleButtonAlpha
        let buttons = [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].compactMap { w.standardWindowButton($0) }
        if Motion.systemReduceMotion {
            buttons.forEach { $0.alphaValue = alpha }
            return
        }
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = revealed ? Motion.MirrorWindow.revealDuration : Motion.MirrorWindow.concealDuration
            ctx.timingFunction = Motion.MirrorWindow.frameTiming
            buttons.forEach { $0.animator().alphaValue = alpha }
        }
    }
}

// MARK: - Floating control panel

/// Borderless, non-activating, transparent child panel that carries the control capsule under the pop-out. It never
/// becomes key, so clicking a control leaves keyboard focus (typing into the phone) on the mirror window.
final class MirrorBarPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    /// Height of the panel: the capsule plus room for its shadow.
    static let height: CGFloat = 64
    /// Gap between the window's bottom edge and the capsule.
    static let gap: CGFloat = 2
    /// Wide enough for the longest capsule (camera + audio + pin) even under a narrow window.
    static let minWidth: CGFloat = 512
    /// Top of the panel that is only shadow room (the capsule starts below it).
    static let shadowRoom: CGFloat = 14
    /// Space the capsule needs under the window's bottom edge (gap + capsule + its shadow). Window sizing reserves it.
    static let reserve: CGFloat = height - shadowRoom + gap
    /// True while the screen had no room below and the capsule sits inside the window's bottom edge.
    private(set) var isTucked = false

    init(chrome: MirrorChrome) {
        super.init(contentRect: NSRect(x: 0, y: 0, width: Self.minWidth, height: Self.height),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false          // the capsule draws its own floating shadow
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        isMovable = false
        becomesKeyOnlyIfNeeded = true
        animationBehavior = .none
        let host = NSHostingView(rootView: MirrorPopoutBar(chrome: chrome)
            .environment(AppModel.shared)
            .ddAppearance(AppearanceSettings.shared))
        host.sizingOptions = []
        host.safeAreaRegions = []
        contentView = host
    }

    /// Centre under `parent`; if the screen has no room below it, tuck the capsule inside the window's bottom edge.
    func needsTuck(under parent: NSWindow) -> Bool {
        guard let vis = (parent.screen ?? NSScreen.main)?.visibleFrame else { return false }
        return parent.frame.minY - Self.reserve < vis.minY
    }

    func place(under parent: NSWindow) {
        let pf = parent.frame
        let w = max(Self.minWidth, pf.width)
        isTucked = needsTuck(under: parent)
        let y = isTucked ? pf.minY + MirrorFrame.side + 4 : pf.minY - Self.height - Self.gap + Self.shadowRoom
        setFrame(NSRect(x: (pf.midX - w / 2).rounded(), y: y.rounded(), width: w, height: Self.height), display: false)
    }
}

/// The capsule as the pop-out shows it: fades in with a 4 pt rise while the chrome is revealed, out (faster) when not.
struct MirrorPopoutBar: View {
    let chrome: MirrorChrome
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shown = chrome.revealed
        MirrorControlBar(inWindow: false, chrome: chrome)
            .onHover { chrome.pointerInBar($0) }
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 4)
            .ddAnimation(shown ? Motion.content : Motion.exit, value: shown)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.top, MirrorBarPanel.shadowRoom)
            .accessibilityHidden(!shown)
    }
}
