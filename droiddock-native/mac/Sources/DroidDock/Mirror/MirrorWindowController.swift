import AppKit
import SwiftUI

/// Borderless-feeling pop-out: hidden title bar, full-size content, the phone screen held inside a dark frame.
/// An `NSPanel` so `WindowControl.mainWindow` (which skips panels) never mistakes it for the main window.
final class MirrorPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// The pop-out's frame around the phone screen. Nothing is drawn over the picture: the window buttons and the
/// phone name sit in the top strip, the controls in the bottom strip.
enum MirrorFrame {
    static let side: CGFloat = 10
    static let top: CGFloat = 34        // title bar (traffic lights) + a little air
    static let bottom: CGFloat = 52     // control bar
    static let radius: CGFloat = 16     // screen corners
    static let background = Color(white: 0.075)

    /// Window content size for a screen of `video` size.
    static func content(forVideo video: CGSize) -> CGSize {
        CGSize(width: video.width + side * 2, height: video.height + top + bottom)
    }
}

@MainActor
final class MirrorWindowController: NSObject, NSWindowDelegate {
    private weak var session: MirrorSession?
    private var panel: MirrorPanel?
    private var closing = false
    /// Phone screen aspect (width:height) the window keeps while resizing.
    private var aspect = CGSize(width: 9, height: 19.5)

    init(session: MirrorSession) { self.session = session }

    func show(aspect: CGSize?) {
        guard let session else { return }
        if let p = panel { bringToFront(); _ = p; return }
        let a = aspect ?? CGSize(width: 9, height: 19.5)
        self.aspect = a
        let p = MirrorPanel(contentRect: Self.contentRect(for: a), styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                            backing: .buffered, defer: false)
        p.title = "DroidDock Mirror"
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.appearance = NSAppearance(named: .darkAqua)
        p.isMovableByWindowBackground = true
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = false
        p.becomesKeyOnlyIfNeeded = false
        p.backgroundColor = NSColor(white: 0.075, alpha: 1)
        let minVideoW: CGFloat = 180
        p.contentMinSize = MirrorFrame.content(forVideo: CGSize(width: minVideoW, height: minVideoW * a.height / max(a.width, 1)))
        p.delegate = self
        let root = MirrorStage(inWindow: false)
            .environment(AppModel.shared)
            .ddAppearance(AppearanceSettings.shared)
        let host = NSHostingView(rootView: root)
        host.sizingOptions = []
        host.safeAreaRegions = []
        p.contentView = host
        p.center()
        panel = p
        setOnTop(session.onTop)
        NSApp.activate()
        p.makeKeyAndOrderFront(nil)
    }

    /// Initial window content rect: the screen as large as fits, plus the frame.
    static func contentRect(for a: CGSize) -> NSRect {
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let maxH = screen.height * 0.82 - MirrorFrame.top - MirrorFrame.bottom
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
    }

    var windowNumber: Int { panel?.windowNumber ?? 0 }

    func setOnTop(_ on: Bool) { panel?.level = on ? .floating : .normal }

    /// Keep the window's long edge, re-fit to the new aspect (rotation / camera flip).
    func videoSizeChanged(width: Int, height: Int) {
        guard let panel, width > 0, height > 0 else { return }
        let a = CGSize(width: width, height: height)
        guard a.width / a.height != aspect.width / aspect.height else { return }
        aspect = a
        let cur = videoSize(ofContent: panel.contentLayoutRect.size)
        let long = max(cur.width, cur.height)
        let ratio = a.width / a.height
        var w = ratio >= 1 ? long : long * ratio
        var h = ratio >= 1 ? long / ratio : long
        let vis = (panel.screen ?? NSScreen.main)?.visibleFrame.size ?? CGSize(width: 1440, height: 900)
        let k = min(1, (vis.width * 0.9 - MirrorFrame.side * 2) / w, (vis.height * 0.9 - MirrorFrame.top - MirrorFrame.bottom) / h)
        w *= k; h *= k
        let minVideoW: CGFloat = ratio >= 1 ? 260 : 180
        panel.contentMinSize = MirrorFrame.content(forVideo: CGSize(width: minVideoW, height: minVideoW / ratio))
        let content = MirrorFrame.content(forVideo: CGSize(width: w.rounded(), height: h.rounded()))
        var f = panel.frameRect(forContentRect: NSRect(origin: .zero, size: content))
        f.origin = NSPoint(x: panel.frame.midX - f.width / 2, y: panel.frame.midY - f.height / 2)
        panel.setFrame(f, display: true, animate: !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
    }

    private func videoSize(ofContent c: CGSize) -> CGSize {
        CGSize(width: max(1, c.width - MirrorFrame.side * 2), height: max(1, c.height - MirrorFrame.top - MirrorFrame.bottom))
    }

    /// Keep the screen's aspect while resizing, with the frame added on (a plain `contentAspectRatio` would
    /// stretch the frame along with the picture).
    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
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

    func close(notify: Bool) {
        guard let panel else { return }
        closing = !notify
        panel.close()
    }

    func windowWillClose(_ notification: Notification) {
        let notify = !closing
        panel?.delegate = nil
        panel?.contentView = nil
        panel = nil
        session?.popoutClosed(notify: notify)
    }
}
