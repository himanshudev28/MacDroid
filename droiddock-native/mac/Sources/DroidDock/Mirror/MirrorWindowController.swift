import AppKit
import SwiftUI

/// The pop-out: the window is the phone. A thin bezel around the picture, a slim top strip for the window buttons (they and
/// the title fade in only while the pointer is over the window), and the controls in a floating capsule panel attached
/// under it. An `NSPanel` so `WindowControl.mainWindow` (which skips panels) never mistakes it for the main window.
final class MirrorPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    /// ⌘+ / ⌘− / ⌘0 (iPhone Mirroring's Larger / Smaller / Actual Size). Handled here, so they win only while this window
    /// is key (⌘0 is Go › Dashboard elsewhere) and never reach the phone: command keys are not forwarded as text.
    /// Returns whether it acted; when it didn't (full screen), the key falls through to the menu (⌘0 = Go › Dashboard).
    var onSizeKey: ((MirrorWindowController.SizeStep) -> Bool)?

    override func performKeyEquivalent(with e: NSEvent) -> Bool {
        let mods = e.modifierFlags.intersection(.deviceIndependentFlagsMask).subtracting([.shift, .numericPad, .function, .capsLock])
        if mods == .command, let c = e.charactersIgnoringModifiers {
            switch c {
            case "=", "+": if onSizeKey?(.larger) == true { return true }
            case "-", "_": if onSizeKey?(.smaller) == true { return true }
            case "0": if onSizeKey?(.actual) == true { return true }
            // Keyboard route to end the session even with the capsule hidden (closing the window stops the stream).
            case "w": performClose(nil); return true
            default: break
            }
        }
        return super.performKeyEquivalent(with: e)
    }
}

/// The pop-out's frame around the phone screen. Nothing is drawn over the picture: the window buttons and the phone name
/// sit in the top strip, the controls float in their own panel under the window.
enum MirrorFrame {
    static let side: CGFloat = 6
    /// Title-bar strip: the traffic lights live here, and it keeps Android's swipe-down-from-the-top out of the title bar.
    static let top: CGFloat = 28
    static let bottom: CGFloat = 6
    static let background = Color(white: 0.075)

    /// Screen corner radius for a picture `width` points wide: phone-like, proportional, within sensible bounds.
    static func radius(forWidth width: CGFloat) -> CGFloat { min(22, max(8, (width * 0.055).rounded())) }

    /// Window content size for a screen of `video` size.
    static func content(forVideo video: CGSize) -> CGSize {
        CGSize(width: video.width + side * 2, height: video.height + top + bottom)
    }
}

@MainActor
final class MirrorWindowController: NSObject, NSWindowDelegate {
    enum SizeStep { case larger, smaller, actual }

    private weak var session: MirrorSession?
    private var panel: MirrorPanel?
    private var bar: MirrorBarPanel?
    let chrome = MirrorChrome()
    private var closing = false
    /// Phone screen aspect (width:height) the window keeps while resizing.
    private var aspect = CGSize(width: 9, height: 19.5)
    private static let autosaveName = "DroidDockMirror"

    init(session: MirrorSession) { self.session = session }

    func show(aspect: CGSize?) {
        guard let session else { return }
        if panel != nil { bringToFront(); return }
        let a = aspect ?? CGSize(width: 9, height: 19.5)
        self.aspect = a
        let p = MirrorPanel(contentRect: Self.contentRect(for: a), styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                            backing: .buffered, defer: false)
        p.title = Self.title(for: session)
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.appearance = NSAppearance(named: .darkAqua)
        p.isMovableByWindowBackground = true
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = false
        p.becomesKeyOnlyIfNeeded = false
        p.animationBehavior = .documentWindow          // the system's open/close animation (Reduce Motion aware)
        p.backgroundColor = NSColor(white: 0.075, alpha: 1)
        p.contentMinSize = Self.minContent(for: a)
        p.delegate = self
        p.onSizeKey = { [weak self] in self?.step($0) ?? false }
        let root = MirrorStage(inWindow: false, chrome: chrome)
            .environment(AppModel.shared)
            .ddAppearance(AppearanceSettings.shared)
        let host = NSHostingView(rootView: root)
        host.sizingOptions = []
        host.safeAreaRegions = []
        p.contentView = host
        panel = p
        // Size and position come back from last time; the aspect is re-fitted to this stream's, and the frame is pulled
        // back onto a screen (it may have been saved on a display that is gone) and within the minimum size.
        if p.setFrameUsingName(Self.autosaveName) {
            refit(p, to: a)
            if !NSScreen.screens.contains(where: { $0.visibleFrame.intersects(p.frame) }) { p.center() }
            setVideoSize(videoSize(ofContent: p.contentRect(forFrameRect: p.frame).size), animate: false)
        } else { p.center() }
        p.setFrameAutosaveName(Self.autosaveName)

        let b = MirrorBarPanel(chrome: chrome)
        b.collectionBehavior = [.fullScreenAuxiliary, .ignoresCycle]
        bar = b
        chrome.window = p
        chrome.barPanel = b

        setOnTop(session.onTop)
        NSApp.activate()
        p.makeKeyAndOrderFront(nil)
        attachBar()
        chrome.start()
        chrome.apply()
    }

    private static func title(for session: MirrorSession) -> String {
        session.isCamera ? tr("Phone camera") : (AppModel.shared.phoneName ?? tr("Phone screen"))
    }

    private static func minContent(for a: CGSize) -> CGSize {
        let ratio = a.width / max(a.height, 1)
        let minVideoW: CGFloat = ratio >= 1 ? 260 : 180
        return MirrorFrame.content(forVideo: CGSize(width: minVideoW, height: minVideoW / ratio))
    }

    /// Initial window content rect: the screen as large as fits, plus the frame.
    static func contentRect(for a: CGSize) -> NSRect {
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let maxH = screen.height * 0.82 - MirrorFrame.top - MirrorFrame.bottom - MirrorBarPanel.reserve
        let maxW = screen.width * 0.7 - MirrorFrame.side * 2
        let ratio = a.width / max(a.height, 1)
        var h = maxH, w = h * ratio
        if w > maxW { w = maxW; h = w / ratio }
        let c = MirrorFrame.content(forVideo: CGSize(width: w.rounded(), height: h.rounded()))
        return NSRect(origin: .zero, size: c)
    }

    func bringToFront() {
        guard let panel else { return }
        if panel.isMiniaturized { panel.deminiaturize(nil) }
        NSApp.activate()
        panel.makeKeyAndOrderFront(nil)
        attachBar()
    }

    var windowNumber: Int { panel?.windowNumber ?? 0 }

    func setOnTop(_ on: Bool) {
        panel?.level = on ? .floating : .normal
        bar?.level = panel?.level ?? .normal
    }

    // MARK: Control capsule

    /// (Re)attach the capsule panel as a child, so it moves, changes Space and enters full screen with the window.
    private func attachBar() {
        guard let panel, let bar, panel.isVisible, !panel.isMiniaturized else { return }
        if bar.parent !== panel { panel.addChildWindow(bar, ordered: .above) }
        bar.level = panel.level
        bar.place(under: panel)
        bar.orderFront(nil)
    }

    private func detachBar() {
        guard let panel, let bar else { return }
        if bar.parent === panel { panel.removeChildWindow(bar) }
        bar.orderOut(nil)
    }

    func windowDidResize(_ notification: Notification) { if let panel { bar?.place(under: panel) } }
    /// The capsule follows the window as its child; re-place only when the screen-edge fallback flips (no per-tick jitter).
    func windowDidMove(_ notification: Notification) {
        if let panel, let bar, bar.needsTuck(under: panel) != bar.isTucked { bar.place(under: panel) }
    }
    func windowDidChangeScreen(_ notification: Notification) { if let panel { bar?.place(under: panel) } }
    func windowWillMiniaturize(_ notification: Notification) { detachBar() }
    func windowDidDeminiaturize(_ notification: Notification) { attachBar() }
    func windowDidEnterFullScreen(_ notification: Notification) { attachBar() }
    func windowDidExitFullScreen(_ notification: Notification) { attachBar() }

    // MARK: Size

    /// Keep the window's long edge, re-fit to the new aspect (rotation / camera flip).
    func videoSizeChanged(width: Int, height: Int) {
        guard let panel, width > 0, height > 0 else { return }
        if let session { panel.title = Self.title(for: session) }
        let a = CGSize(width: width, height: height)
        guard a.width / a.height != aspect.width / aspect.height else { return }
        aspect = a
        panel.contentMinSize = Self.minContent(for: a)
        guard !panel.styleMask.contains(.fullScreen) else { return }
        let cur = videoSize(ofContent: panel.contentLayoutRect.size)
        let long = max(cur.width, cur.height)
        let ratio = a.width / a.height
        let video = ratio >= 1 ? CGSize(width: long, height: long / ratio) : CGSize(width: long * ratio, height: long)
        setVideoSize(video, animate: true)
    }

    @discardableResult
    private func step(_ s: SizeStep) -> Bool {
        guard let panel, !panel.styleMask.contains(.fullScreen) else { return false }
        // Held ⌘± repeats while the frame is still animating: step from where it is going, not from the in-flight frame.
        let cur = pendingVideo ?? videoSize(ofContent: panel.contentLayoutRect.size)
        let target: CGSize
        switch s {
        case .larger: target = CGSize(width: cur.width * Motion.MirrorWindow.sizeStep, height: cur.height * Motion.MirrorWindow.sizeStep)
        case .smaller: target = CGSize(width: cur.width / Motion.MirrorWindow.sizeStep, height: cur.height / Motion.MirrorWindow.sizeStep)
        case .actual:
            // Actual size: one phone pixel per display pixel, as long as it fits; otherwise the default fit.
            let scale = panel.backingScaleFactor
            if let i = session?.info, i.width > 0, i.height > 0 {
                target = CGSize(width: CGFloat(i.width) / scale, height: CGFloat(i.height) / scale)
            } else {
                target = videoSize(ofContent: Self.contentRect(for: aspect).size)
            }
        }
        setVideoSize(target, animate: true)
        return true
    }

    /// Video size the running frame animation is heading to (nil when settled), and a counter so an interrupted
    /// animation's completion doesn't clear a newer target.
    private var pendingVideo: CGSize?
    private var animationGeneration = 0

    /// Clamp to the screen and the minimum, keep the window centred where it is, then resize. Animated through the
    /// window's animator: asynchronous (never `setFrame(animate: true)`, which runs a blocking loop on the main thread
    /// and stalls input). Reduce Motion: instant.
    private func setVideoSize(_ want: CGSize, animate: Bool) {
        guard let panel else { return }
        let ratio = aspect.width / max(aspect.height, 1)
        let vis = (panel.screen ?? NSScreen.main)?.visibleFrame.size ?? CGSize(width: 1440, height: 900)
        let maxW = vis.width * 0.95 - MirrorFrame.side * 2
        let maxH = vis.height * 0.95 - MirrorFrame.top - MirrorFrame.bottom - MirrorBarPanel.reserve
        let minW = videoSize(ofContent: panel.contentMinSize).width
        var w = max(minW, min(want.width, maxW, maxH * ratio))
        var h = w / ratio
        if h > maxH { h = maxH; w = h * ratio }
        let content = MirrorFrame.content(forVideo: CGSize(width: w.rounded(), height: h.rounded()))
        var f = panel.frameRect(forContentRect: NSRect(origin: .zero, size: content))
        f.origin = NSPoint(x: (panel.frame.midX - f.width / 2).rounded(), y: (panel.frame.midY - f.height / 2).rounded())
        if let screen = (panel.screen ?? NSScreen.main)?.visibleFrame {
            f.origin.x = min(max(f.origin.x, screen.minX), screen.maxX - f.width)
            // Leave room under the window for the capsule, so it never has to tuck over the picture's gesture edge.
            f.origin.y = min(max(f.origin.y, screen.minY + MirrorBarPanel.reserve), screen.maxY - f.height)
        }
        let video = CGSize(width: w.rounded(), height: h.rounded())
        guard f != panel.frame else { pendingVideo = nil; return }
        if !animate || Motion.systemReduceMotion {
            pendingVideo = nil
            panel.setFrame(f, display: true)
            bar?.place(under: panel)
            return
        }
        pendingVideo = video
        animationGeneration += 1
        let gen = animationGeneration
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = Motion.MirrorWindow.frameDuration
            ctx.timingFunction = Motion.MirrorWindow.frameTiming
            panel.animator().setFrame(f, display: true)
        } completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                guard let self, gen == self.animationGeneration else { return }
                self.pendingVideo = nil
                if let p = self.panel { self.bar?.place(under: p) }
            }
        }
    }

    /// A restored frame from another stream: keep its height (and centre), fit the width to this aspect.
    private func refit(_ p: NSWindow, to a: CGSize) {
        let v = videoSize(ofContent: p.contentRect(forFrameRect: p.frame).size)
        let ratio = a.width / max(a.height, 1)
        let content = MirrorFrame.content(forVideo: CGSize(width: (v.height * ratio).rounded(), height: v.height.rounded()))
        var f = p.frameRect(forContentRect: NSRect(origin: .zero, size: content))
        f.origin = NSPoint(x: (p.frame.midX - f.width / 2).rounded(), y: p.frame.minY)
        p.setFrame(f, display: false)
    }

    /// Zoom (the green button / double-click): the largest phone-shaped frame that fits, not a letterboxed full screen.
    func windowWillUseStandardFrame(_ window: NSWindow, defaultFrame newFrame: NSRect) -> NSRect {
        let ratio = aspect.width / max(aspect.height, 1)
        let avail = videoSize(ofContent: window.contentRect(forFrameRect: newFrame).size)
        var h = avail.height - MirrorBarPanel.reserve, w = h * ratio
        if w > avail.width { w = avail.width; h = w / ratio }
        let content = MirrorFrame.content(forVideo: CGSize(width: w.rounded(), height: h.rounded()))
        var f = window.frameRect(forContentRect: NSRect(origin: .zero, size: content))
        f.origin = NSPoint(x: (newFrame.midX - f.width / 2).rounded(), y: newFrame.maxY - f.height)
        return f
    }

    private func videoSize(ofContent c: CGSize) -> CGSize {
        CGSize(width: max(1, c.width - MirrorFrame.side * 2), height: max(1, c.height - MirrorFrame.top - MirrorFrame.bottom))
    }

    /// Keep the screen's aspect while resizing, with the frame added on (a plain `contentAspectRatio` would
    /// stretch the frame along with the picture).
    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        guard !sender.styleMask.contains(.fullScreen) else { return frameSize }
        let ratio = aspect.width / max(aspect.height, 1)
        let proposed = sender.contentRect(forFrameRect: NSRect(origin: .zero, size: frameSize)).size
        let current = sender.contentRect(forFrameRect: sender.frame).size
        let v = videoSize(ofContent: proposed)
        let heightDriven = abs(proposed.height - current.height) > abs(proposed.width - current.width)
        let video = heightDriven ? CGSize(width: v.height * ratio, height: v.height)
                                 : CGSize(width: v.width, height: v.width / ratio)
        let content = MirrorFrame.content(forVideo: CGSize(width: video.width.rounded(), height: video.height.rounded()))
        return sender.frameRect(forContentRect: NSRect(origin: .zero, size: content)).size
    }

    // MARK: Close

    func close(notify: Bool) {
        guard let panel else { return }
        closing = !notify
        panel.close()
    }

    func windowWillClose(_ notification: Notification) {
        let notify = !closing
        chrome.stop()
        detachBar()
        bar?.close()
        bar = nil
        panel?.delegate = nil
        panel?.onSizeKey = nil
        panel?.contentView = nil
        panel = nil
        session?.popoutClosed(notify: notify)
    }
}
