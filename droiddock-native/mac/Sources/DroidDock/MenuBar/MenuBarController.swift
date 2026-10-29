import AppKit
import SwiftUI

/// The menu bar item and its panel. Replaces `MenuBarExtra(.window)`, which gives no control over its window, so the
/// panel could not morph out of the status item. Behaviour kept from the old scene: the same icon / dot / title,
/// click toggles, click outside or Esc closes, the reply field takes keyboard focus, and it follows the status item
/// across displays (notch screens included) whatever the activation policy.
@MainActor
final class MenuBarController: NSObject {
    static let shared = MenuBarController()

    private var item: NSStatusItem?
    private var panel: IslandPanel?
    private var container: IslandContainerView?
    private var hosting: FitHostingView<AnyView>?
    private var monitors: [Any] = []
    private var keyObserver: NSObjectProtocol?
    private var observers: [NSObjectProtocol] = []
    private var closing = false
    private var shrinkWork: DispatchWorkItem?
    private var shadowLink: CADisplayLink?
    private var shadowUntil = Date.distantPast
    private var lastRenderedLook = ""

    private(set) var isOpen = false
    static let gap: CGFloat = 4

    // MARK: Install

    func install() {
        guard item == nil else { return }
        let it = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        it.behavior = []
        if let b = it.button {
            b.target = self
            b.action = #selector(clicked(_:))
            b.sendAction(on: [.leftMouseDown, .rightMouseDown])
            b.setAccessibilityLabel("DroidDock")
            b.imagePosition = .imageLeading
        }
        item = it
        trackLook()
        refreshLook()
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.close(animated: false, "screen change") }
        })
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.close(animated: false, "space change") }
        })
    }

    // MARK: Status item look (icon, notification dot, title)

    private func trackLook() {
        withObservationTracking {
            let m = AppModel.shared
            _ = m.isConnected; _ = m.hasNotifications; _ = m.activeCall?.state; _ = m.menubarTitle
        } onChange: { [weak self] in
            Task { @MainActor in self?.refreshLook(); self?.trackLook() }
        }
    }

    private func refreshLook() {
        guard let b = item?.button else { return }
        let m = AppModel.shared
        let dot = m.activeCall?.state == "ringing" || (m.hasNotifications && m.isConnected)
        let title = m.menubarTitle ?? ""
        let key = "\(m.isConnected)|\(dot)|\(title)"
        guard key != lastRenderedLook else { return }
        lastRenderedLook = key
        b.image = Self.glyph(connected: m.isConnected, dot: dot)
        b.title = title.isEmpty ? "" : " " + title
        b.font = .menuBarFont(ofSize: 0)
        b.imagePosition = title.isEmpty ? .imageOnly : .imageLeading
    }

    /// The SF Symbol phone (radio waves when linked) with the small notification dot, as one template image.
    private static func glyph(connected: Bool, dot: Bool) -> NSImage? {
        let name = connected ? "iphone.gen3.radiowaves.left.and.right" : "iphone.gen3"
        guard let base = NSImage(systemSymbolName: name, accessibilityDescription: "DroidDock")?
            .withSymbolConfiguration(.init(pointSize: 14, weight: .regular)) else { return nil }
        guard dot else { base.isTemplate = true; return base }
        let extra: CGFloat = 7
        let out = NSImage(size: NSSize(width: base.size.width + extra, height: base.size.height), flipped: false) { r in
            base.draw(in: NSRect(x: 0, y: 0, width: base.size.width, height: base.size.height))
            NSColor.black.setFill()
            NSBezierPath(ovalIn: NSRect(x: r.width - 5, y: r.height - 6, width: 5, height: 5)).fill()
            return true
        }
        out.isTemplate = true
        return out
    }

    // MARK: Toggle

    @objc private func clicked(_ sender: Any?) { toggle() }

    func toggle() { isOpen && !closing ? close() : open() }

    // MARK: Open

    private var opening = false

    func open() {
        guard (!isOpen || closing), !opening, let button = item?.button, button.window != nil else { return }
        opening = true
        if closing { finishClose() }
        let settings = AppearanceSettings.shared
        let width = DD.Size.menuBarWidth

        // Build the content first, off screen, and let SwiftUI settle: the notification list measures itself in a
        // second layout pass, so the panel is sized after two short turns of the run loop, not from the first guess.
        let root = AnyView(MenuBarPanel()
            .environment(AppModel.shared)
            .ddAppearance(settings)
            .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
            .frame(width: width))
        let host = FitHostingView(rootView: root)
        host.frame = NSRect(x: 0, y: 0, width: width, height: 400)
        hosting = host
        Task { @MainActor [weak self] in
            for _ in 0..<3 {
                host.layoutSubtreeIfNeeded()
                try? await Task.sleep(for: .milliseconds(6))
            }
            host.layoutSubtreeIfNeeded()
            self?.present(host: host, width: width, settings: settings)
        }
    }

    private func present(host: FitHostingView<AnyView>, width: CGFloat, settings: AppearanceSettings) {
        defer { opening = false }
        guard hosting === host, let button = item?.button, let bw = button.window else { return }
        let reduceMotion = Motion.systemReduceMotion
        let screen = bw.screen ?? NSScreen.main ?? NSScreen.screens[0]
        let vf = screen.visibleFrame
        let maxH = min(vf.height - 16, 720)
        let fit = min(max(host.fittingSize.height, 120), maxH)
        host.onFitChange = { [weak self] in self?.contentSizeChanged() }

        let btn = bw.frame
        let x = min(max(btn.midX - width / 2, vf.minX + 8), vf.maxX - width - 8)
        let top = min(btn.minY, vf.maxY) - Self.gap
        let frame = NSRect(x: x, y: top - fit, width: width, height: fit)

        let p = panel ?? makePanel()
        panel = p
        p.appearance = settings.theme == .system ? nil : NSAppearance(named: settings.theme == .dark ? .darkAqua : .aqua)
        let c = container ?? IslandContainerView(frame: NSRect(origin: .zero, size: frame.size))
        container = c
        p.contentView = c
        p.setFrame(frame, display: false)
        c.frame = NSRect(origin: .zero, size: frame.size)
        c.install(host, solid: settings.reduceTransparency)
        c.setFinalHeight(fit)
        c.layoutHost(height: fit)
        c.settle()
        c.pillWidthForClose = max(btn.width + Motion.Island.pillExtraWidth, 56)

        isOpen = true; closing = false
        button.highlight(true)
        UIActivity.shared.setPanelOpen(true)
        Task { try? await Core.shared.menubarOpened() }

        p.alphaValue = 1
        p.makeKeyAndOrderFront(nil)
        startShadowTracking(for: 0.7)
        c.open(pillWidth: btn.width + Motion.Island.pillExtraWidth, pillCenterX: btn.midX - frame.minX,
               reduceMotion: reduceMotion) { [weak self] in self?.p_settled() }
        installMonitors()
    }

    private func p_settled() { panel?.invalidateShadow() }

    private func makePanel() -> IslandPanel {
        let p = IslandPanel(contentRect: NSRect(x: 0, y: 0, width: DD.Size.menuBarWidth, height: 300),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        p.level = .popUpMenu
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.hasShadow = true
        p.isOpaque = false
        p.backgroundColor = .clear
        p.isReleasedWhenClosed = false
        p.animationBehavior = .none
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle, .transient]
        p.title = "DroidDock"
        p.onCancel = { [weak self] in self?.close() }
        p.setAccessibilityLabel("DroidDock")
        return p
    }

    // MARK: Close

    func close(animated: Bool = true, _ why: String = "api") {
        dlog("menubar close: \(why)")
        guard isOpen, !closing, let p = panel, let c = container else { return }
        removeMonitors()
        if !animated || !p.isVisible { finishClose(); return }
        closing = true
        item?.button?.highlight(false)
        startShadowTracking(for: Motion.Island.closeDuration + 0.1)
        c.close(reduceMotion: Motion.systemReduceMotion) { [weak self] in
            MainActor.assumeIsolated { self?.finishClose() }
        }
    }

    private func finishClose() {
        guard isOpen || closing else { return }
        isOpen = false; closing = false
        shrinkWork?.cancel()
        item?.button?.highlight(false)
        stopShadowTracking()
        panel?.orderOut(nil)
        container?.removeHost()
        container?.settle()
        hosting?.onFitChange = nil
        hosting = nil          // drops the SwiftUI tree: nothing renders or observes while the panel is closed
        UIActivity.shared.setPanelOpen(false)
    }

    // MARK: Content size changes while open

    private func contentSizeChanged() {
        guard isOpen, !closing else { return }
        DispatchQueue.main.async { [weak self] in self?.resizeToFit() }
    }

    private func resizeToFit() {
        dlog("menubar fit changed")
        guard isOpen, !closing, let p = panel, let c = container, let host = hosting else { return }
        let vf = (p.screen ?? NSScreen.main)?.visibleFrame ?? .zero
        let maxH = min(vf.height - 16, 720)
        let h = min(max(host.fittingSize.height, 120), maxH)
        guard abs(h - c.shapeHeight) > 0.5 else { return }
        let reduce = Motion.systemReduceMotion
        var f = p.frame
        let top = f.maxY
        shrinkWork?.cancel()
        let grow = h > f.height
        if grow {
            f.size.height = h; f.origin.y = top - h
            p.setFrame(f, display: false)
            c.frame.size = f.size
        }
        c.layoutHost(height: h)
        if reduce {
            c.setFinalHeight(h); c.settle()
            if !grow { f.size.height = h; f.origin.y = top - h; p.setFrame(f, display: false); c.frame.size = f.size; c.layoutHost(height: h); c.settle() }
            return
        }
        startShadowTracking(for: 0.4)
        c.animateHeight(to: h, duration: 0.28) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, let p = self.panel, let c = self.container, self.isOpen else { return }
                if !grow {
                    var f = p.frame; let top = f.maxY
                    f.size.height = h; f.origin.y = top - h
                    p.setFrame(f, display: false)
                    c.frame.size = f.size
                    c.layoutHost(height: h)
                    c.settle()
                }
                p.invalidateShadow()
            }
        }
    }

    // MARK: Click outside

    private func installMonitors() {
        removeMonitors()
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        if let g = NSEvent.addGlobalMonitorForEvents(matching: mask, handler: { [weak self] _ in
            Task { @MainActor in self?.close(animated: true, "outside click") }
        }) { monitors.append(g) }
        if let l = NSEvent.addLocalMonitorForEvents(matching: mask, handler: { [weak self] e in
            guard let self, self.isOpen else { return e }
            // Clicks inside the panel, or on our own status item (its action toggles), are not "outside".
            if e.window === self.panel || e.window === self.item?.button?.window { return e }
            // Another of our windows (or a sheet): close, let the click through.
            MainActor.assumeIsolated { self.close(animated: true, "click in another window") }
            return e
        }) { monitors.append(l) }
        keyObserver = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: panel, queue: .main) { [weak self] _ in
            // Key moved to another of our windows (the main window, Settings).
            MainActor.assumeIsolated {
                guard let self, self.isOpen, !self.closing else { return }
                if let k = NSApp.keyWindow, k !== self.panel { self.close(animated: true, "key moved to \(type(of: k))") }
            }
        }
    }

    private func removeMonitors() {
        for m in monitors { NSEvent.removeMonitor(m) }
        monitors.removeAll()
        if let o = keyObserver { NotificationCenter.default.removeObserver(o); keyObserver = nil }
    }

    // MARK: Window shadow follows the animated shape

    private func startShadowTracking(for seconds: TimeInterval) {
        shadowUntil = max(shadowUntil, Date().addingTimeInterval(seconds))
        guard shadowLink == nil, let c = container else { return }
        let link = c.displayLink(target: self, selector: #selector(shadowTick))
        link.add(to: .main, forMode: .common)
        shadowLink = link
    }

    @objc private func shadowTick() {
        panel?.invalidateShadow()
        if Date() > shadowUntil { stopShadowTracking() }
    }

    private func stopShadowTracking() { shadowLink?.invalidate(); shadowLink = nil }

    // MARK: QA

    #if DEBUG
    var debugWindowNumber: Int { panel?.isVisible == true ? panel!.windowNumber : 0 }
    var debugPanelFrame: NSRect? { panel?.frame }
    #endif
}
