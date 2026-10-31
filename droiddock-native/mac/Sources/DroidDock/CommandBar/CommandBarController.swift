import AppKit
import Carbon.HIToolbox
import QuartzCore
import SwiftUI

/// Non-activating panel that still takes the keyboard, so the frontmost app stays frontmost (focus returns to it for free).
final class CBPanel: NSPanel {
    var onCancel: (() -> Void)?
    var onResign: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func cancelOperation(_ sender: Any?) { onCancel?() }
    override func resignKey() { super.resignKey(); onResign?() }
}

/// Spotlight-style quick command bar (⌥Space). Lazy: nothing exists until the first open; afterwards the panel is kept
/// and only `orderOut`-ed. Hidden = no timers, no observers, no monitors.
@MainActor
final class CommandBarController {
    static let shared = CommandBarController()
    static let recentsKey = "droiddock.commandbar.recents"

    private let state = CommandBarState()
    private var panel: CBPanel?
    private var container: NSView?
    private var keyMonitor: Any?
    private var visibleToken = 0
    private var index = CBIndex()
    private var indexSignature = ""
    private var loadedApps: [PhoneApp] = []
    private var loadingApps = false
    private var loadingContacts = false
    private var currentRows = -1
    private var recents: [CBRecent] = []
    /// A message that failed to send: reopening the bar restores what the user typed.
    private var restoreQuery: String?
    #if DEBUG
    fileprivate var debugSeeded = false
    fileprivate func debugSeed(contacts: [Contact], apps: [PhoneApp]) { debugSeeded = true; index = CBIndex(contacts: contacts, apps: apps) }
    #endif

    private var model: AppModel { AppModel.shared }
    var isVisible: Bool { panel?.isVisible == true }

    private init() {}

    private var isOpen = false
    func toggle() { isOpen ? hide() : show() }

    // MARK: Show

    func show() {
        #if DEBUG
        let t0 = CACurrentMediaTime()
        #endif
        let p = panel ?? makePanel()
        let settings = AppearanceSettings.shared
        p.appearance = settings.theme == .system ? nil : NSAppearance(named: settings.theme == .dark ? .darkAqua : .aqua)
        state.rtl = L10n.shared.isRTL
        state.reduceTransparency = settings.reduceTransparency
        applyBackground(solid: settings.reduceTransparency)
        let initial = restoreQuery ?? ""
        restoreQuery = nil
        state.query = initial
        state.field?.stringValue = initial
        state.armedID = nil
        state.openCount += 1
        isOpen = true
        recents = loadRecents()   // once per open, not per keystroke
        refreshIndexIfNeeded()
        recompute()
        state.connected = model.isConnected
        place(p)

        visibleToken += 1
        let reduceMotion = Motion.systemReduceMotion
        // Reopened mid-fade-out: keep the current alpha and fade up from there (no flicker, no replayed scale).
        let wasVisible = p.isVisible
        if !wasVisible { p.alphaValue = 0 }
        p.makeKeyAndOrderFront(nil)
        container?.layoutSubtreeIfNeeded()   // first open: the SwiftUI field must exist before it can take focus
        p.makeFirstResponder(state.field)
        installMonitor()
        animateIn(p, reduceMotion: reduceMotion, scale: !wasVisible)
        loadMissingData()
        #if DEBUG
        dlog(String(format: "commandbar: open took %.1f ms", (CACurrentMediaTime() - t0) * 1000))
        #endif
    }

    // MARK: Hide

    func hide() {
        guard let p = panel, isOpen else { return }
        isOpen = false
        removeMonitor()
        visibleToken += 1
        let token = visibleToken
        let reduceMotion = Motion.systemReduceMotion
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = reduceMotion ? 0.06 : 0.09
            ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
            p.animator().alphaValue = 0
        }, completionHandler: { [weak self, weak p] in
            MainActor.assumeIsolated {
                guard let self, let p, token == self.visibleToken else { return }
                p.orderOut(nil)   // kept for reuse
            }
        })
    }

    // MARK: Panel

    private func makePanel() -> CBPanel {
        let size = NSSize(width: CBMetrics.width, height: CBMetrics.maxHeight)
        let p = CBPanel(contentRect: NSRect(origin: .zero, size: size),
                        styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel], backing: .buffered, defer: true)
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.standardWindowButton(.closeButton)?.isHidden = true
        p.standardWindowButton(.miniaturizeButton)?.isHidden = true
        p.standardWindowButton(.zoomButton)?.isHidden = true
        p.isMovable = false
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.level = .floating
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.animationBehavior = .none
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        p.title = tr("Command bar")
        p.setAccessibilityLabel(tr("Command bar"))
        p.onCancel = { [weak self] in self?.hide() }
        p.onResign = { [weak self] in
            // Clicking outside / switching app: close. (Not while we are the ones taking focus.)
            DispatchQueue.main.async { MainActor.assumeIsolated { if self?.panel?.isKeyWindow == false { self?.hide() } } }
        }

        let root = NSView(frame: NSRect(origin: .zero, size: size))
        root.wantsLayer = true
        root.layer?.cornerRadius = CBMetrics.radius
        root.layer?.cornerCurve = .continuous
        root.layer?.masksToBounds = true
        root.layer?.borderWidth = 0.5
        root.layer?.borderColor = NSColor.separatorColor.cgColor

        let effect = NSVisualEffectView(frame: root.bounds)
        effect.material = .hudWindow
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.autoresizingMask = [.width, .height]
        effect.identifier = NSUserInterfaceItemIdentifier("cb-effect")
        root.addSubview(effect)

        let host = NSHostingView(rootView: CommandBarView(state: state)
            .ddAppearance(AppearanceSettings.shared))
        host.frame = root.bounds
        host.autoresizingMask = [.width, .height]
        root.addSubview(host)

        p.contentView = root
        state.onQueryChange = { [weak self] _ in self?.queryChanged() }
        state.onActivate = { [weak self] i in self?.activate(i, secondary: NSEvent.modifierFlags.contains(.command)) }
        panel = p
        container = root
        return p
    }

    /// Reduce Transparency: a solid window colour instead of the blur.
    private func applyBackground(solid: Bool) {
        guard let root = container else { return }
        root.subviews.first { $0.identifier?.rawValue == "cb-effect" }?.isHidden = solid
        root.effectiveAppearance.performAsCurrentDrawingAppearance {
            root.layer?.backgroundColor = solid ? NSColor.windowBackgroundColor.cgColor : nil
            root.layer?.borderColor = NSColor.separatorColor.cgColor
        }
    }

    /// Top edge fixed ~22 % down from the top of the active screen's visible area; height follows the row count.
    private func place(_ p: NSPanel) {
        let screen = Self.activeScreen()
        let area = screen.visibleFrame
        let top = area.maxY - area.height * 0.2
        let h = CBMetrics.height(rows: state.items.count)
        currentRows = state.items.count
        let frame = NSRect(x: (area.midX - CBMetrics.width / 2).rounded(), y: (top - h).rounded(), width: CBMetrics.width, height: h)
        p.setFrame(frame, display: false)
        panelTop = top
    }
    private var panelTop: CGFloat = 0

    /// The screen the user is working on: the one under the mouse (the frontmost app's screen is not knowable cheaply).
    private static func activeScreen() -> NSScreen {
        let m = NSEvent.mouseLocation
        return NSScreen.screens.first { NSMouseInRect(m, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
    }

    private func resizeIfNeeded() {
        guard let p = panel, state.items.count != currentRows else { return }
        currentRows = state.items.count
        let h = CBMetrics.height(rows: state.items.count)
        p.setFrame(NSRect(x: p.frame.minX, y: panelTop - h, width: p.frame.width, height: h), display: true)
        p.invalidateShadow()
    }

    // MARK: Motion

    private func animateIn(_ p: CBPanel, reduceMotion: Bool, scale: Bool) {
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = reduceMotion ? 0.08 : 0.13
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            p.animator().alphaValue = 1
        }
        guard !reduceMotion, scale, let layer = container?.layer else { return }
        // Scale 0.98 -> 1 about the centre, whatever the layer's anchor point is.
        let b = layer.bounds, a = layer.anchorPoint, s: CGFloat = 0.98
        let tx = (1 - s) * (b.width / 2 - a.x * b.width), ty = (1 - s) * (b.height / 2 - a.y * b.height)
        var t = CATransform3DMakeTranslation(tx, ty, 0)
        t = CATransform3DScale(t, s, s, 1)
        let anim = CABasicAnimation(keyPath: "transform")
        anim.fromValue = t
        anim.toValue = CATransform3DIdentity
        anim.duration = 0.13
        anim.timingFunction = CAMediaTimingFunction(name: .easeOut)
        layer.add(anim, forKey: "cb-in")
    }

    // MARK: Data

    private func context() -> CBContext {
        let m = model
        var c = CBContext()
        c.connected = m.isConnected
        c.openAppsOnMac = m.config?.openAppsOnMac ?? false
        c.macWindowPossible = m.devices.contains { $0.state == "device" }
        c.battery = m.batteryPercent
        c.charging = m.isCharging
        c.mediaActive = m.media?.active == true
        c.mediaPlaying = m.media?.playing == true
        c.mirroring = m.mirror != nil
        c.ringing = ShellState.shared.ringing
        c.appsLoading = loadingApps
        return c
    }

    private var phoneApps: [PhoneApp] {
        let names = ShellState.shared.appNames
        if !names.isEmpty { return names.map { PhoneApp(pkg: $0.key, label: $0.value) } }
        return loadedApps
    }

    /// Rebuilds the folded arrays only when the underlying lists changed (not per keystroke, not per open).
    private func refreshIndexIfNeeded() {
        #if DEBUG
        if debugSeeded { return }
        #endif
        let contacts = ContactsStore.shared.contacts
        let apps = phoneApps
        // A hash of every id, once per open: a number edited in the middle of the list is picked up too.
        var hasher = Hasher()
        for c in contacts { hasher.combine(c.number); hasher.combine(c.name) }
        for a in apps { hasher.combine(a.pkg); hasher.combine(a.label) }
        let sig = "\(contacts.count)|\(apps.count)|\(hasher.finalize())"
        guard sig != indexSignature else { return }
        indexSignature = sig
        index = CBIndex(contacts: contacts, apps: apps)
    }

    private func loadMissingData() {
        #if DEBUG
        if debugSeeded { return }
        #endif
        guard model.isConnected else { return }
        let cs = ContactsStore.shared
        if cs.contacts.isEmpty, !loadingContacts {
            loadingContacts = true
            Task {
                await cs.load()
                loadingContacts = false
                dataArrived()
            }
        }
        if phoneApps.isEmpty, !loadingApps {
            loadingApps = true
            Task {
                if let apps = try? await Core.shared.appsList() { loadedApps = apps }
                loadingApps = false
                dataArrived()
            }
        }
    }

    private func dataArrived() {
        guard isOpen else { return }
        refreshIndexIfNeeded()
        recompute(keepSelection: true)
    }

    private func queryChanged() { recompute() }

    private func recompute(keepSelection: Bool = false) {
        let items = CBSearch.results(query: state.query, index: index, ctx: context(), recents: recents)
        state.connected = model.isConnected
        let keptID = keepSelection && state.items.indices.contains(state.selection) ? state.items[state.selection].id : nil
        state.items = items
        state.selection = keptID.flatMap { id in items.firstIndex { $0.id == id } } ?? 0
        state.armedID = nil
        resizeIfNeeded()
        announceSelection()
    }

    // MARK: Keys

    private func installMonitor() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self else { return e }
            // Copy what we need into a Sendable value; NSEvent itself never crosses into the main-actor closure.
            let key = CBKey(keyCode: Int(e.keyCode),
                            mods: e.modifierFlags.intersection(.deviceIndependentFlagsMask).subtracting([.numericPad, .function, .capsLock]).rawValue,
                            windowID: e.window.map(ObjectIdentifier.init))
            let consumed = MainActor.assumeIsolated { self.handle(key) }
            return consumed ? nil : e
        }
    }

    private func removeMonitor() {
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        keyMonitor = nil
    }

    private func handle(_ e: CBKey) -> Bool {
        guard let panel, e.windowID == ObjectIdentifier(panel) else { return false }
        let mods = NSEvent.ModifierFlags(rawValue: e.mods)
        // Let an input method finish its composition first.
        if let tv = panel.firstResponder as? NSTextView, tv.hasMarkedText() { return false }
        switch e.keyCode {
        case kVK_UpArrow where mods.isEmpty: move(-1); return true
        case kVK_DownArrow where mods.isEmpty: move(1); return true
        case kVK_ANSI_P where mods == .control: move(-1); return true
        case kVK_ANSI_N where mods == .control: move(1); return true
        case kVK_Return, kVK_ANSI_KeypadEnter:
            if mods.isEmpty { activate(state.selection, secondary: false); return true }
            if mods == .command { activate(state.selection, secondary: true); return true }
            return false
        case kVK_Escape where mods.isEmpty: hide(); return true
        default: return false
        }
    }

    private func move(_ d: Int) {
        let n = state.items.count
        guard n > 0 else { return }
        state.selection = (state.selection + d + n) % n
        state.armedID = nil
        announceSelection()
    }

    private func announceSelection() {
        guard NSWorkspace.shared.isVoiceOverEnabled, state.items.indices.contains(state.selection), let p = panel else { return }
        let it = state.items[state.selection]
        let text = [it.title, state.disabled(it) ? tr("Connect your phone first") : it.subtitle].filter { !$0.isEmpty }.joined(separator: ", ")
        NSAccessibility.post(element: p, notification: .announcementRequested,
                             userInfo: [.announcement: text, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    // MARK: Running

    func activate(_ i: Int, secondary: Bool) {
        guard state.items.indices.contains(i) else { return }
        state.connected = model.isConnected   // fresh, not whatever it was when the results were built
        let item = state.items[i]
        let action = secondary ? item.secondary : item.primary
        if action == .none { return }
        if case .prefill(let s) = action { state.query = s; state.openCount += 1; recompute(); return }
        // The link requirement belongs to the ACTION: "Show contact" and a Mac-window open work offline.
        let needsLink = secondary ? action.needsLink : item.needsLink
        if needsLink && !state.connected {
            feedback(.info, tr("Connect your phone first"))
            return
        }
        // Not unambiguous: the first ↩ only arms the row, a second one sends.
        if !secondary, item.confirm, state.armedID != item.id {
            state.armedID = item.id
            if NSWorkspace.shared.isVoiceOverEnabled, let p = panel {
                NSAccessibility.post(element: p, notification: .announcementRequested,
                                     userInfo: [.announcement: item.armSubtitle, .priority: NSAccessibilityPriorityLevel.high.rawValue])
            }
            return
        }
        let query = state.query
        hide()
        perform(action, query: query)
    }

    /// Async actions are remembered only once they succeed; a failed text keeps the query for the next open.
    private func perform(_ action: CBAction, query: String) {
        let m = model
        switch action {
        case .none, .prefill: break
        case .ring:
            let next = !ShellState.shared.ringing
            Task {
                do {
                    ShellState.shared.ringing = try await Core.shared.ringPhone(on: next)
                    remember(action)
                    feedback(.info, ShellState.shared.ringing ? tr("Ringing your phone. It stops by itself after a minute.") : tr("Stopped ringing"))
                } catch { fail(error) }
            }
        case .mirror: remember(action); ShellActions.toggleMirror()
        case .screenshot: remember(action); ShellActions.screenshot()
        case .playPause:
            guard m.media?.active == true else { feedback(.info, tr("Nothing is playing on the phone")); return }
            let willPlay = m.media?.playing != true
            remember(action)
            ShellActions.media(.playPause)
            feedback(.ok, willPlay ? tr("Playing on the phone") : tr("Paused on the phone"))
        case .sendClipboard: remember(action); ShellActions.pushClipboard()
        case .sms(let number, let name, let body):
            Task {
                do {
                    try await Core.shared.smsSend(address: number, text: body)
                    remember(action)
                    feedback(.ok, tr("Message sent to {name}", ["name": name]))
                } catch { fail(error, retry: query) }
            }
        case .call(let number, let name):
            Task {
                do {
                    try await Core.shared.actionCall(number: number)
                    remember(action)
                    feedback(.info, tr("Calling {name}…", ["name": name]))
                } catch { fail(error) }
            }
        case .openConversation(let number, let name):
            remember(action)
            InboxRouter.shared.openMessages(to: MessageTarget(number: number, name: name))
        case .showContact:
            m.selection = .contacts
            WindowControl.showMain()
        case .openApp(let pkg, let label, let onMac):
            let serial = m.devices.first { $0.state == "device" }?.serial
            Task {
                do {
                    if onMac, let serial {
                        try await Core.shared.adbMirrorApp(serial: serial, package: pkg, newDisplay: true)
                        feedback(.ok, tr("Opening {name} in a Mac window…", ["name": label]))
                    } else {
                        try await Core.shared.appLaunch(pkg: pkg)
                        feedback(.ok, tr("Opened {name} on your phone", ["name": label]))
                    }
                    ShellState.shared.recordRecent(pkg)
                    remember(action)
                } catch { fail(error) }
            }
        }
    }

    // MARK: Feedback (the single failure/feedback path: a `.bad` toast like AppModel.run, plus a banner when the main window is hidden)

    /// `retry`: the query to put back when the bar is next opened, so a message that did not go out is not lost.
    private func fail(_ error: Error, retry: String? = nil) {
        let text = FileFormat.errorText(error.localizedDescription)
        if let retry {
            restoreQuery = retry
            feedback(.bad, tr("Message not sent: {error}. Reopen the command bar to retry.", ["error": text]))
        } else {
            feedback(.bad, text)
        }
    }

    /// The existing toast, plus a banner when the main window is not on screen (toasts only render inside it).
    private func feedback(_ kind: Toast.Kind, _ text: String) {
        model.toast(kind, text)
        if WindowControl.mainWindow?.isVisible != true {
            BannerCenter.shared.show(NativeNotification(id: "commandbar-feedback", title: "DroidDock", body: text, replyKey: nil,
                                                        sound: false, hidePreview: nil))
        }
    }

    // MARK: Recents (UserDefaults; the action kind and a number or package only: no bodies, no names)

    private func loadRecents() -> [CBRecent] {
        guard let d = UserDefaults.standard.data(forKey: Self.recentsKey),
              let r = try? JSONDecoder().decode([CBRecent].self, from: d) else { return [] }
        return r
    }

    /// Called when the phone is forgotten (DevicesScreen): nothing about its contacts or apps should linger.
    func clearRecents() {
        recents = []
        restoreQuery = nil
        UserDefaults.standard.removeObject(forKey: Self.recentsKey)
    }

    private func remember(_ action: CBAction) {
        let r: CBRecent
        switch action {
        case .ring: r = CBRecent(kind: "ring")
        case .mirror: r = CBRecent(kind: "mirror")
        case .screenshot: r = CBRecent(kind: "screenshot")
        case .playPause: r = CBRecent(kind: "playPause")
        case .sendClipboard: r = CBRecent(kind: "sendClipboard")
        case .sms(let n, _, _), .openConversation(let n, _): r = CBRecent(kind: "text", a: n)
        case .call(let n, _): r = CBRecent(kind: "call", a: n)
        case .openApp(let pkg, _, _): r = CBRecent(kind: "openApp", a: pkg)
        case .none, .prefill, .showContact: return
        }
        var all = loadRecents().filter { $0.key != r.key }
        all.insert(r, at: 0)
        all = Array(all.prefix(5))
        recents = all
        if let d = try? JSONEncoder().encode(all) { UserDefaults.standard.set(d, forKey: Self.recentsKey) }
    }
}

/// The few fields of a key event the handler needs, copied out so no NSEvent crosses an isolation boundary.
struct CBKey: Sendable {
    let keyCode: Int
    let mods: UInt
    let windowID: ObjectIdentifier?
}

#if DEBUG
extension CommandBarController {
    /// QA only (`DD_CMDBAR_DEMO=<query>`): fake phone + sample contacts/apps, then open the bar. No core, no network.
    func debugDemo(query: String) {
        AppModel.shared.debugFakeConnected()
        let people = [("Ada Lovelace", "+14155550134"), ("Adam Smith", "+14155550111"), ("José Álvarez", "+34600111222"), ("Mom", "+14155550100")]
        debugSeed(contacts: people.map { Contact(name: $0.0, number: $0.1, starred: false) },
                  apps: ["Spotify", "WhatsApp", "Gmail", "Chrome"].map { PhoneApp(pkg: "com.example.\($0.lowercased())", label: $0) })
        show()
        state.query = query
        state.field?.stringValue = query
        recompute()
    }
    var debugWindowNumber: Int { panel?.windowNumber ?? 0 }
}
#endif
