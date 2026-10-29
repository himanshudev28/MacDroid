import AppKit
import AVFoundation
import CoreMedia
import SwiftUI

/// Owns the `AVSampleBufferDisplayLayer` that decoded frames are pushed into. One per session; the
/// layer is re-parented between the pop-out window and the in-window pane, so playback survives a switch.
final class MirrorRenderer: @unchecked Sendable {
    let layer = AVSampleBufferDisplayLayer()
    private let lock = NSLock()
    private var fmt: CMVideoFormatDescription?
    private var _videoSize = CGSize.zero
    var videoSize: CGSize { lock.lock(); defer { lock.unlock() }; return _videoSize }
    var onVideoSize: (@Sendable (CGSize) -> Void)?
    private(set) var enqueued = 0

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
        layer.isOpaque = true
    }

    private var fmtKey = (0, 0, UInt32(0))
    private(set) var dropped = 0

    func enqueue(_ pb: CVPixelBuffer) {
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let key = (w, h, CVPixelBufferGetPixelFormatType(pb))
        lock.lock()
        let sizeChanged = _videoSize != CGSize(width: w, height: h)
        _videoSize = CGSize(width: w, height: h)
        if fmt == nil || key != fmtKey {
            var f: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb,
                                                         formatDescriptionOut: &f)
            fmt = f; fmtKey = key
        }
        let f = fmt
        lock.unlock()
        guard let f else { return }
        if layer.status == .failed { layer.flush() }
        // Backpressure: a live mirror never queues. If the layer is still busy this frame is stale already.
        guard layer.isReadyForMoreMediaData else { dropped += 1; return }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .invalid, decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescription: f,
            sampleTiming: &timing, sampleBufferOut: &sb) == noErr, let sb else { return }
        if let arr = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true),
           CFArrayGetCount(arr) > 0 {
            let d = unsafeBitCast(CFArrayGetValueAtIndex(arr, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(d, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        layer.enqueue(sb)
        enqueued += 1
        if sizeChanged { onVideoSize?(CGSize(width: w, height: h)) }
    }

    func flush() { layer.flushAndRemoveImage() }
}

/// AppKit surface: hosts the renderer layer, aspect-fits it, and turns pointer/keyboard events into
/// `MirrorInput` calls (screen mode only).
final class MirrorSurfaceView: NSView {
    var renderer: MirrorRenderer? { didSet { attachLayer() } }
    var input: MirrorInput?
    var inputEnabled = false
    var mirrored = false { didSet { applyTransform() } }
    /// Clip with the video layer's own corner radius (no SwiftUI clip mask over the live layer).
    var cornerRadius: CGFloat = 0 { didSet { applyCorners() } }
    var videoHidden = false { didSet { renderer?.layer.isHidden = videoHidden } }
    private var visObservers: [NSObjectProtocol] = []
    private var lastVisible: Bool?

    nonisolated(unsafe) static weak var current: MirrorSurfaceView?
    static func refocus() { if let v = current { v.window?.makeFirstResponder(v) } }
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window != nil { Self.current = self; window?.makeFirstResponder(self) }
        observeVisibility()
    }
    override func viewDidHide() { super.viewDidHide(); reportVisibility() }
    override func viewDidUnhide() { super.viewDidUnhide(); reportVisibility() }

    /// "Can anyone see this?" = in a window that is on screen (not occluded / minimised / closed), app not
    /// hidden, view not hidden. Reported to the session, which debounces before pausing the stream.
    private func observeVisibility() {
        visObservers.forEach { NotificationCenter.default.removeObserver($0) }
        visObservers = []
        guard let w = window else { reportVisibility(); return }
        let nc = NotificationCenter.default
        for name in [NSWindow.didChangeOcclusionStateNotification, NSWindow.didMiniaturizeNotification,
                     NSWindow.didDeminiaturizeNotification, NSWindow.willCloseNotification] {
            visObservers.append(nc.addObserver(forName: name, object: w, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.reportVisibility() } })
        }
        for name in [NSApplication.didHideNotification, NSApplication.didUnhideNotification] {
            visObservers.append(nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.reportVisibility() } })
        }
        reportVisibility()
    }

    private func reportVisibility() {
        let visible: Bool = {
            guard let w = window, !isHiddenOrHasHiddenAncestor, !w.isMiniaturized, w.isVisible else { return false }
            return w.occlusionState.contains(.visible) && !NSApp.isHidden
        }()
        guard visible != lastVisible else { return }
        lastVisible = visible
        MirrorSession.shared.surface(ObjectIdentifier(self), visible: visible)
    }

    deinit {
        visObservers.forEach { NotificationCenter.default.removeObserver($0) }
        let id = ObjectIdentifier(self)
        DispatchQueue.main.async { MainActor.assumeIsolated { MirrorSession.shared.surface(id, visible: false) } }
    }

    override var acceptsFirstResponder: Bool { true }
    override var isFlipped: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
    }
    required init?(coder: NSCoder) { fatalError() }

    private func attachLayer() {
        guard let renderer, let host = layer else { return }
        if renderer.layer.superlayer !== host {
            renderer.layer.removeFromSuperlayer()
            host.addSublayer(renderer.layer)
        }
        needsLayout = true
        applyTransform()
        applyCorners()
        renderer.layer.isHidden = videoHidden
    }

    private func applyCorners() {
        guard let l = renderer?.layer else { return }
        l.cornerRadius = cornerRadius
        l.cornerCurve = .continuous
        l.masksToBounds = cornerRadius > 0
    }

    private func applyTransform() {
        renderer?.layer.setAffineTransform(mirrored ? CGAffineTransform(scaleX: -1, y: 1) : .identity)
    }

    override func layout() {
        super.layout()
        fitLayer()
    }

    /// SwiftUI resizes this view by frame; the video layer must follow immediately, not on the next layout pass
    /// (a stale frame leaves the picture off-centre inside the view).
    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        fitLayer()
    }

    private func fitLayer() {
        CATransaction.begin(); CATransaction.setDisableActions(true)
        renderer?.layer.frame = bounds
        CATransaction.commit()
    }

    /// The aspect-fit rectangle the video occupies inside the view (view coords, top-left origin).
    private func videoRect() -> CGRect {
        let s = renderer?.videoSize ?? .zero
        guard s.width > 0, s.height > 0, bounds.width > 0, bounds.height > 0 else { return bounds }
        let k = min(bounds.width / s.width, bounds.height / s.height)
        let w = s.width * k, h = s.height * k
        return CGRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
    }

    private func frac(_ e: NSEvent) -> (Double, Double) {
        let p = convert(e.locationInWindow, from: nil)
        let r = videoRect()
        guard r.width > 0, r.height > 0 else { return (0, 0) }
        return (Double((p.x - r.minX) / r.width), Double((p.y - r.minY) / r.height))
    }

    override func mouseDown(with e: NSEvent) {
        window?.makeFirstResponder(self)
        guard inputEnabled else { return super.mouseDown(with: e) }
        let (x, y) = frac(e); input?.pointerDown(x: x, y: y)
    }
    override func mouseUp(with e: NSEvent) {
        guard inputEnabled else { return super.mouseUp(with: e) }
        let (x, y) = frac(e); input?.pointerUp(x: x, y: y)
    }
    override func rightMouseDown(with e: NSEvent) {
        guard inputEnabled else { return super.rightMouseDown(with: e) }
        input?.key("back")
    }
    override func scrollWheel(with e: NSEvent) {
        guard inputEnabled, e.momentumPhase == [] else { return super.scrollWheel(with: e) }
        input?.scroll(domDeltaY: Double(-e.scrollingDeltaY))
    }
    override func keyDown(with e: NSEvent) {
        if inputEnabled, input?.keyDown(e) == true { return }
        super.keyDown(with: e)
    }
}

/// SwiftUI wrapper. Same view in the pop-out window and in `MirrorScreen`.
struct MirrorLayerView: NSViewRepresentable {
    let renderer: MirrorRenderer
    var input: MirrorInput?
    var inputEnabled: Bool
    var mirrored: Bool = false
    var cornerRadius: CGFloat = 0
    var hidden: Bool = false

    func makeNSView(context: Context) -> MirrorSurfaceView {
        let v = MirrorSurfaceView()
        v.renderer = renderer
        v.input = input
        return v
    }
    func updateNSView(_ v: MirrorSurfaceView, context: Context) {
        if v.renderer !== renderer { v.renderer = renderer }
        v.input = input
        v.inputEnabled = inputEnabled
        v.mirrored = mirrored
        v.cornerRadius = cornerRadius
        v.videoHidden = hidden
    }
}
