import AppKit
import CoreImage
import Foundation
import Observation
import SwiftUI
import VideoToolbox

/// App-wide mirror state + glue between the core (frames, events), the decoder, and wherever the
/// picture is shown (pop-out window or the main window's Mirror/Camera screen).
@Observable @MainActor
final class MirrorSession {
    static let shared = MirrorSession()
    static let inWindowKey = "droiddock.mirrorInWindow"
    /// Opt-in (default off): also pause an embedded-scrcpy (USB/ADB) mirror while its window is hidden. The scrcpy
    /// server is started with `control=false`, so there is no way to ask it for a keyframe (its reset-video control
    /// message exists in the bundled server but needs a control socket we don't open): on resume the picture can
    /// freeze until the server's next natural keyframe (up to its I-frame interval, ~10 s by default).
    static let pauseHiddenAdbKey = "droiddock.pauseHiddenAdbMirror"
    /// "Prefer ADB mirroring when available" (default ON): Start mirroring uses embedded scrcpy when an ADB device exists.
    static let preferAdbKey = "droiddock.preferAdbMirror"

    enum Phase: Equatable { case idle, connecting, live, failed(String) }
    enum Placement: Equatable { case none, popout, inWindow }

    private(set) var phase: Phase = .idle
    private(set) var info: MirrorStarted?
    private(set) var placement: Placement = .none
    /// What the user asked for most recently (the core's `mirror-window open` carries no source).
    private(set) var requestedSource = "screen"
    private(set) var facing = "back"
    private(set) var note: String?
    private(set) var audioMuted = false
    /// True once the phone said it cannot be controlled (its accessibility service is off) and the Mac has
    /// no adb route to fall back on. Cleared when the stream ends or the user dismisses the hint.
    private(set) var controlHint = false
    /// A Wi-Fi screen mirror that asked the phone for audio. The phone announces audio once per capture, so a
    /// resumed or reconnected session may never announce it again; the mute button must not depend on that alone.
    private(set) var audioExpected = false
    var onTop = false { didSet { controller?.setOnTop(onTop) } }

    @ObservationIgnored let decoder = H26xDecoder()
    @ObservationIgnored let renderer = MirrorRenderer()
    @ObservationIgnored let input = MirrorInput()
    @ObservationIgnored let audio = PhoneAudioPlayer()
    @ObservationIgnored private var controller: MirrorWindowController?
    @ObservationIgnored private var attaching = false
    @ObservationIgnored private var noteTask: Task<Void, Never>?
    @ObservationIgnored private var installed = false
    @ObservationIgnored private var visibleSurfaces = Set<ObjectIdentifier>()
    @ObservationIgnored private var screensAsleep = false
    @ObservationIgnored private var viewerPaused = false
    @ObservationIgnored private var pauseTask: Task<Void, Never>?
    /// How long the picture must stay hidden before the stream is paused (Mission Control flicks, Cmd-Tab).
    static let pauseDelay: Duration = .milliseconds(1200)

    var source: String { info?.source ?? requestedSource }
    var isCamera: Bool { source == "camera" }
    var isLive: Bool { phase == .live }
    var showInWindow: Bool {
        get { UserDefaults.standard.bool(forKey: Self.inWindowKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.inWindowKey) }
    }
    /// True while a stream is on its way or running (either placement).
    var isActive: Bool { placement != .none || phase == .connecting || phase == .live }

    // MARK: Install (once, at launch)

    func install() {
        guard !installed else { return }
        installed = true
        // Frames go straight to the decoder / player queues: no main-actor hop.
        let dec = decoder, aud = audio
        Core.shared.mirrorSink = { key, bytes in dec.submit(keyframe: key, bytes: bytes) }
        Core.shared.audioSink = { reset, bytes in aud.submit(reset: reset, pcm: bytes) }
        // Overloaded / resumed decoder wants a sync frame: `mirror-resume` makes the phone emit one (and, for
        // phones without the pause cap, the core turns it into the equivalent `mirror-start` on screen sessions).
        decoder.onNeedKeyframe = { [weak self] in
            Task { @MainActor in self?.requestKeyframe() }
        }
        let nc = NotificationCenter.default, ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.screensAsleep = true; self?.evaluateVisibility() } }
        ws.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.screensAsleep = false; self?.evaluateVisibility() } }
        _ = nc
        let r = renderer
        decoder.onFrame = { pb in r.enqueue(pb) }
        audio.onError = { msg in Task { @MainActor in AppModel.shared.toast(.bad, "Phone audio: \(msg)") } }

        let m = AppModel.shared
        m.onMirrorWindow = { [weak self] op in op == "open" ? self?.openRequested() : self?.closeRequested() }
        m.onMirrorStarted = { [weak self] s in self?.started(s) }
        m.onMirrorStopped = { [weak self] in self?.stopped() }
        m.onMirrorError = { [weak self] e in self?.failed(e) }
        m.onMirrorControlUnavailable = { [weak self] in if self?.isActive == true { self?.controlHint = true } }
        m.onAudioStarted = { [weak self] a in self?.audio.start(sampleRate: a.sampleRate, channels: a.channels, format: a.format) }
        m.onAudioStopped = { [weak self] in self?.audio.stop() }

        Task {
            // HEVC only if this Mac can hardware-decode it; the core never asks the phone otherwise.
            let hevc = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
            try? await Core.shared.mirrorSetHevcSupported(hevc)
            if let a = try? await Core.shared.audioAttach() {
                audio.start(sampleRate: a.sampleRate, channels: a.channels, format: a.format)
            }
        }
    }

    // MARK: Commands

    func start(source: String) async {
        requestedSource = source
        facing = "back"
        audioExpected = source == "screen" && (AppModel.shared.config?.mirrorAudio ?? true)
        AppModel.shared.clearAudioError()
        phase = .connecting
        // Size the stream to what this Mac can show: window pixels (unless the user pinned a max in settings,
        // which the core prefers) and the display's refresh rate. The phone reads these at encoder start only.
        let hints: [String: Any] = ["type": "mirror-hints", "maxSize": source == "camera" ? 0 : autoMaxSize(),
                                    "maxFps": NSScreen.main?.maximumFramesPerSecond ?? 0]
        nonisolated(unsafe) let h = hints
        try? await Core.shared.mirrorInput(h)    // awaited: must land before `mirror_popout` builds the start message
        do { try await Core.shared.mirrorPopout(source: source) }
        catch {
            phase = .failed(error.localizedDescription)
            AppModel.shared.toast(.bad, error.localizedDescription)
        }
    }

    /// Long edge, in backing pixels, of the window the picture will be shown in; rounded up to 16, 720...1920.
    func autoMaxSize() -> Int {
        let scale = NSScreen.main?.backingScaleFactor ?? 2
        let a = info.map { CGSize(width: $0.width, height: $0.height) } ?? CGSize(width: 9, height: 19.5)
        var longPt = max(MirrorWindowController.contentRect(for: a).size.width, MirrorWindowController.contentRect(for: a).size.height)
        if showInWindow, let w = NSApp.windows.first(where: { !($0 is NSPanel) && $0.isVisible }) {
            longPt = max(w.contentLayoutRect.height, 640)
        }
        let px = Int((longPt * scale / 16).rounded(.up)) * 16
        return min(1920, max(720, px))
    }

    // MARK: Viewer visibility (pause while nobody can see the picture)

    /// A `MirrorSurfaceView` reports whether it is on screen.
    func surface(_ id: ObjectIdentifier, visible: Bool) {
        if visible { visibleSurfaces.insert(id) } else { visibleSurfaces.remove(id) }
        evaluateVisibility()
    }

    fileprivate func evaluateVisibility() {
        // Before the picture is live there is nothing to pause. Then: visible unless every surface is hidden.
        let visible = phase != .live || (!visibleSurfaces.isEmpty && !screensAsleep)
        if visible {
            pauseTask?.cancel(); pauseTask = nil
            if viewerPaused { setViewerPaused(false) }
        } else if !viewerPaused, pauseTask == nil {
            pauseTask = Task { [weak self] in
                try? await Task.sleep(for: Self.pauseDelay)
                guard !Task.isCancelled, let self else { return }
                // An ADB (embedded scrcpy) session is only ever paused if the user opted in.
                if !UserDefaults.standard.bool(forKey: Self.pauseHiddenAdbKey),
                   (try? await Core.shared.scrcpyEmbeddedRunning()) == true {
                    if !Task.isCancelled { self.pauseTask = nil }
                    return
                }
                guard !Task.isCancelled else { return }
                self.pauseTask = nil
                self.setViewerPaused(true)
            }
        }
    }

    private func setViewerPaused(_ on: Bool) {
        viewerPaused = on
        decoder.setPaused(on)          // locally: stop copying / decoding / displaying immediately
        input.send(["type": on ? "mirror-pause" : "mirror-resume", "source": source])
    }

    func requestKeyframe() {
        guard phase == .live, !viewerPaused else { return }
        input.send(["type": "mirror-resume", "source": source])
    }

    /// For flows where the core opens the window itself (embedded scrcpy).
    func markRequested(source: String) { requestedSource = source; audioExpected = false; if phase != .live { phase = .connecting } }

    /// Brings a live mirror forward (pop-out window or the main window's Mirror screen).
    func bringToFront() {
        if placement == .popout { controller?.bringToFront() } else if placement == .inWindow { surfaceToMain() }
    }

    func stop() { Task { try? await Core.shared.mirrorStop() } }

    /// Dismiss a failed-start banner (the launcher's "Dismiss").
    func clearFailure() { if case .failed = phase { phase = .idle } }

    func dismissControlHint() { controlHint = false }

    func toggleMute() { audioMuted.toggle(); audio.muted = audioMuted }

    func flipCamera() {
        let next = facing == "front" ? "back" : "front"
        facing = next
        input.flipCamera(to: next)
    }

    func setPlacement(inWindow: Bool) {
        showInWindow = inWindow
        guard isActive else { return }
        if inWindow, placement == .popout {
            controller?.close(notify: false); controller = nil
            placement = .inWindow
            surfaceToMain()
        } else if !inWindow, placement == .inWindow {
            openPopout()
        }
    }

    func say(_ text: String) {
        note = text
        noteTask?.cancel()
        noteTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { self?.note = nil }
        }
    }

    /// Last decoded frame -> PNG -> `Pictures/DroidDock` via the core.
    func screenshot() {
        guard let pb = decoder.lastPixelBuffer else { say(tr("Nothing to capture yet")); return }
        Task.detached {
            let ci = CIImage(cvPixelBuffer: pb)
            let rep = CIContext().pngRepresentation(of: ci, format: .RGBA8,
                                                     colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)
            guard let png = rep else { await self.say(tr("Nothing to capture yet")); return }
            do {
                let path = try await Core.shared.mirrorSaveCapture(png, ext: "png")
                await self.say(tr("Saved {name}", ["name": (path as NSString).lastPathComponent]))
            } catch { await self.say(error.localizedDescription) }
        }
    }

    // MARK: Core events

    private func openRequested() {
        if phase == .idle { phase = .connecting }
        if placement == .none {
            if showInWindow { placement = .inWindow; surfaceToMain() } else { openPopout() }
        } else if placement == .popout {
            controller?.bringToFront()
        } else { surfaceToMain() }
        attach()
    }

    private func closeRequested() {
        switch placement {
        case .popout: controller?.close(notify: true)      // windowWillClose -> mirror_closed
        case .inWindow: placement = .none; Task { try? await Core.shared.mirrorClosed() }
        case .none: Task { try? await Core.shared.mirrorClosed() }   // keep the core's window_open flag honest
        }
    }

    private func attach() {
        guard !attaching else { return }
        attaching = true
        Task {
            let s = try? await Core.shared.mirrorAttach()
            attaching = false
            if let s, phase != .live || info != s { started(s) }
        }
    }

    func started(_ s: MirrorStarted) {
        let first = phase != .live
        info = s
        facing = s.facing ?? facing
        requestedSource = s.source
        if first { decoder.configure(codecString: s.codec) }   // hint only; content wins
        phase = .live
        evaluateVisibility()
        controller?.videoSizeChanged(width: s.width, height: s.height)
    }

    private func stopped() {
        if case .failed = phase {} else { phase = .idle }
        info = nil
        controlHint = false
        audioExpected = false
        resetViewerPause()
        decoder.reset()
        renderer.flush()
        if placement == .popout { controller?.close(notify: false); controller = nil }
        placement = .none
    }

    private func resetViewerPause() {
        pauseTask?.cancel(); pauseTask = nil
        viewerPaused = false
        decoder.setPaused(false)
    }

    private func failed(_ e: String?) {
        phase = .failed(e ?? tr("The stream ended unexpectedly."))
        info = nil
        controlHint = false
        resetViewerPause()
        decoder.reset()
        renderer.flush()
        if placement == .popout { controller?.close(notify: true) } else { placement = .none
            Task { try? await Core.shared.mirrorClosed() } }
    }

    // MARK: Placement

    func openPopout() {
        let c = controller ?? MirrorWindowController(session: self)
        controller = c
        placement = .popout
        c.show(aspect: info.map { CGSize(width: $0.width, height: $0.height) })
    }

    /// Called by the controller when the user closes the pop-out.
    func popoutClosed(notify: Bool) {
        controller = nil
        guard placement == .popout else { return }
        placement = .none
        if notify { Task { try? await Core.shared.mirrorClosed() } }
    }

    private func surfaceToMain() {
        AppModel.shared.selection = isCamera || requestedSource == "camera" ? .camera : .mirror
        WindowControl.showMain()
    }
}

extension MirrorSession {
    /// DD_SELFTEST=ui: pop-out with synthetic H.264 frames, no core. Prints the window number.
    func debugShowSynthetic() {
        let r = renderer; decoder.onFrame = { r.enqueue($0) }
        started(MirrorStarted(width: 320, height: 240, codec: "avc1.42E01E", source: "screen", facing: nil))
        openPopout()
        DispatchQueue.global().async { [decoder] in
            guard let frames = MirrorTestSupport.encodeH264(w: 320, h: 240, n: 10) else { return }
            for u in frames { decoder.submit(keyframe: u.key, data: u.annexB) }
        }
    }
    func debugShowControlHint() { controlHint = true }
    var debugWindowNumber: Int { controller?.windowNumber ?? 0 }
}
