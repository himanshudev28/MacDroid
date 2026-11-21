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
    // Camera: lenses, mode, recording (all optional on the wire; see `MirrorStarted`).
    enum CaptureMode: Equatable { case photo, video }
    private(set) var lenses: [MirrorLens] = []
    private(set) var lens: String?
    /// "phone" = stills taken on the phone at full resolution; "frame" = the Mac grabs the decoded picture.
    private(set) var photoMode = "frame"
    var captureMode: CaptureMode = .photo
    private(set) var recording = false
    /// Set when the first keyframe is written (nil while `recording` means "waiting for a keyframe").
    private(set) var recordStart: Date?
    private(set) var switchingLens = false
    /// Bumped on every shutter press; the picture view flashes on change.
    private(set) var shutterTick = 0
    @ObservationIgnored private var lensBeforeSwitch: String?
    @ObservationIgnored private var switchTask: Task<Void, Never>?
    @ObservationIgnored let recorder = MirrorRecorder()
    // Orientation: the phone's (event / announce) unless the user turned it by hand for this session.
    static let followKey = "droiddock.camera.followOrientation"
    private(set) var phoneRotation = 0
    private(set) var manualRotation: Int?
    @ObservationIgnored private var orientWatching = false
    var followsPhone: Bool { UserDefaults.standard.object(forKey: Self.followKey) as? Bool ?? true }
    /// Degrees clockwise the picture is turned for display. Camera sessions only.
    var rotation: Int { isCamera ? (manualRotation ?? (followsPhone ? phoneRotation : 0)) : 0 }
    func displaySize(width: Int, height: Int) -> CGSize {
        rotation % 180 != 0 ? CGSize(width: height, height: width) : CGSize(width: width, height: height)
    }
    /// R / the rotate button: quarter turns; overrides following until this session ends (the setting is left alone).
    func rotateManually() {
        guard isCamera, phase == .live else { return }
        manualRotation = (rotation + 90) % 360
        rotationChanged()
    }
    private func setPhoneRotation(_ r: Int) {
        let n = ((r % 360) + 360) % 360
        guard n % 90 == 0, n != phoneRotation else { return }
        phoneRotation = n
        rotationChanged()
    }
    private func rotationChanged() {
        recorder.rotation = rotation
        if let i = info {
            let d = displaySize(width: i.width, height: i.height)
            controller?.videoSizeChanged(width: Int(d.width), height: Int(d.height))
        }
    }
    private func zoomApply(_ s: MirrorStarted) {
        if let r = s.zoomRange, r.count == 2, r[0] < r[1] {
            let had = zoomRange != nil
            zoomRange = r[0]...r[1]
            if had, zoomPending, let z = s.zoom { zoomConfirmed(z) } else { resetZoom(range: r, zoom: s.zoom) }
        }
    }
    private func setOrientWatch(_ on: Bool) {
        guard on != orientWatching else { return }
        orientWatching = on
        Task { try? await Core.shared.cameraOrientWatch(on: on) }
    }
    // Zoom
    private(set) var zoomRange: ClosedRange<Double>?
    private(set) var confirmedZoom = 1.0
    private(set) var requestedZoom = 1.0
    private(set) var zoomPillVisible = false
    private(set) var zoomLagging = false
    var zoomDialOpen = false
    @ObservationIgnored private var zoomSendTask: Task<Void, Never>?
    @ObservationIgnored private var zoomPillTask: Task<Void, Never>?
    @ObservationIgnored private var zoomLagTask: Task<Void, Never>?
    @ObservationIgnored private var zoomPending = false
    @ObservationIgnored private var lastSentZoom: Double?
    private var zoomEmbedded: Bool { (lens ?? "").hasPrefix("adb:") }
    /// Preview crop for the gap between a requested zoom and the stream that confirms it (embedded restarts the
    /// stream; Wi-Fi only crops when the phone's confirmation lags).
    var cropScale: CGFloat {
        guard zoomRange != nil, requestedZoom > confirmedZoom, zoomEmbedded || zoomLagging else { return 1 }
        return CGFloat(requestedZoom / max(confirmedZoom, 0.01))
    }
    /// Returns true when zoom handled the gesture (a camera with a zoom range is live).
    @discardableResult func zoomBy(factor: Double) -> Bool {
        guard isCamera, phase == .live, zoomRange != nil, factor.isFinite, factor > 0 else { return false }
        setZoom(requestedZoom * factor); return true
    }
    @discardableResult func zoomKey(_ dir: Int) -> Bool {
        guard isCamera, phase == .live, zoomRange != nil else { return false }
        setZoom(requestedZoom * (dir > 0 ? 1.1 : 1 / 1.1)); return true
    }
    func setZoom(_ ratio: Double) {
        guard let r = zoomRange else { return }
        let z = min(max(ratio, r.lowerBound), r.upperBound)
        guard abs(z - requestedZoom) > 0.001 else { return }
        requestedZoom = z
        zoomPending = true
        zoomPillVisible = true
        zoomPillTask?.cancel()
        zoomPillTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1200))
            if !Task.isCancelled { self?.zoomPillVisible = false }
        }
        zoomLagTask?.cancel()
        zoomLagTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            if !Task.isCancelled, let self, self.zoomPending { self.zoomLagging = true }
        }
        // Coalesce a gesture's stream of values: latest wins, at most ~15 messages a second.
        zoomSendTask?.cancel()
        zoomSendTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(60))
            guard !Task.isCancelled, let self else { return }
            let v = self.requestedZoom
            self.lastSentZoom = v
            try? await Core.shared.cameraSetZoom(v)
            if !Task.isCancelled { self.zoomSendTask = nil }
            // The phone never confirmed this exact value (clamped, or lost): stop waiting, trust what it reported.
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled, self.lastSentZoom == v { self.lastSentZoom = nil; self.requestedZoom = self.confirmedZoom; self.zoomPending = false }
        }
    }
    private func zoomConfirmed(_ z: Double) {
        confirmedZoom = z
        zoomLagging = false
        // Settled (or nothing more in flight): the phone's value wins. Otherwise the user is still zooming.
        // A late confirmation of an older request must not undo a newer one: adopt only the value last sent.
        if lastSentZoom == nil || abs(lastSentZoom! - z) < 0.02 { requestedZoom = z; zoomPending = false; lastSentZoom = nil }
    }
    private func resetZoom(range: [Double]?, zoom: Double?) {
        if let r = range, r.count == 2, r[0] < r[1] { zoomRange = r[0]...r[1] } else { zoomRange = nil; zoomDialOpen = false }
        zoomSendTask?.cancel(); zoomSendTask = nil; zoomLagTask?.cancel()
        zoomPending = false; zoomLagging = false; lastSentZoom = nil
        confirmedZoom = zoom ?? 1; requestedZoom = confirmedZoom
    }
    var backLenses: [MirrorLens] { lenses.filter { $0.facing != "front" } }
    var frontLens: MirrorLens? { lenses.first { $0.facing == "front" } }
    var hasLenses: Bool { isCamera && !lenses.isEmpty }
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
        let rec = recorder
        decoder.onSample = { sample, fmt, key in rec.offer(sample, fmt, key) }
        recorder.onNeedKeyframe = { [weak self] in Task { @MainActor in self?.requestKeyframe() } }
        recorder.onBegan = { [weak self] in Task { @MainActor in
            guard let self, self.recording else { return }
            self.recordStart = Date() } }
        recorder.onSaved = { [weak self] url in Task { @MainActor in self?.recordingSaved(url) } }
        recorder.onFailed = { [weak self] msg in Task { @MainActor in
            guard let self else { return }
            self.recording = false; self.recordStart = nil
            AppModel.shared.toast(.bad, tr("Recording failed: {e}", ["e": msg])) } }
        let r = renderer
        decoder.onFrame = { pb in r.enqueue(pb) }
        audio.onError = { msg in Task { @MainActor in AppModel.shared.toast(.bad, tr("Phone audio: {e}", ["e": msg])) } }

        let m = AppModel.shared
        m.onMirrorWindow = { [weak self] op in op == "open" ? self?.openRequested() : self?.closeRequested() }
        m.onMirrorStarted = { [weak self] s in self?.started(s) }
        m.onMirrorStopped = { [weak self] in self?.stopped() }
        m.onMirrorError = { [weak self] e in self?.failed(e) }
        m.onCameraEvent = { [weak self] name, p in self?.cameraEvent(name, p) }
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
        manualRotation = nil; phoneRotation = 0
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
        let visible = phase != .live || recording || (!visibleSurfaces.isEmpty && !screensAsleep)
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

    // MARK: Camera: lenses, shutter, recording

    func selectLens(_ id: String) {
        guard hasLenses, id != lens, !switchingLens, let target = lenses.first(where: { $0.id == id }) else { return }
        lensBeforeSwitch = lens
        lens = id
        resetZoom(range: zoomRange.map { [$0.lowerBound, $0.upperBound] }, zoom: target.zoom ?? 1)
        facing = target.facing == "front" ? "front" : "back"
        beginSwitching()
        Task {
            do { try await Core.shared.cameraSelectLens(id) }
            catch { revertLens(error.localizedDescription) }
        }
    }

    private func beginSwitching() {
        switchingLens = true
        switchTask?.cancel()
        // The phone never answered: don't leave the UI stuck.
        switchTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            if !Task.isCancelled { self?.switchingLens = false }
        }
    }

    private func resetOrientation() { resetZoom(range: nil, zoom: nil)
        setOrientWatch(false); manualRotation = nil; phoneRotation = 0 }

    private func endSwitching() { switchTask?.cancel(); switchTask = nil; switchingLens = false }

    private func revertLens(_ msg: String?) {
        if let prev = lensBeforeSwitch {
            lens = prev
            facing = lenses.first { $0.id == prev }?.facing == "front" ? "front" : "back"
        }
        lensBeforeSwitch = nil
        endSwitching()
        if let msg, !msg.isEmpty { say(msg) }
    }

    /// Key 1-9: lens by position among the back lenses.
    func selectLens(index: Int) {
        let b = backLenses
        guard b.indices.contains(index) else { return }
        selectLens(b[index].id)
    }

    func flipCamera() {
        if hasLenses {
            if facing == "front" {
                let b = backLenses
                // The main lens by label first: the ultra-wide is also a zoom-1 lens (its own camera id).
                if let l = b.first(where: { $0.label == "1×" }) ?? b.first(where: { $0.zoom == 1 }) ?? b.first { selectLens(l.id) }
            } else if let f = frontLens { selectLens(f.id) }
            return
        }
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

    func shutter() {
        guard phase == .live, isCamera else { return }
        if captureMode == .video { toggleRecording() } else { takePhoto() }
    }

    func takePhoto() {
        guard phase == .live, isCamera else { return }
        shutterTick += 1
        guard photoMode == "phone" else { grabFrame(); return }
        Task {
            do { try await Core.shared.cameraTakePhoto() }       // the core emits camera-photo-saved / -error
            catch {
                // Not supported by this path (or phone): fall back to the picture we already have.
                if error.localizedDescription.lowercased().contains("unsupported") { grabFrame() }
                else { say(error.localizedDescription) }
            }
        }
    }

    func toggleRecording() {
        if recording { stopRecording(); return }
        guard phase == .live, isCamera else { return }
        recording = true
        recordStart = nil
        evaluateVisibility()
        recorder.rotation = rotation
        recorder.start()
        requestKeyframe()        // Wi-Fi: the phone sends one; embedded scrcpy: the next natural IDR starts the file
    }

    func stopRecording() {
        guard recording else { return }
        recording = false
        recordStart = nil
        recorder.stop()
        evaluateVisibility()
    }

    private func recordingSaved(_ url: URL) {
        say(tr("Saved {name}", ["name": url.lastPathComponent]))
        AppModel.shared.toast(.ok, tr("Saved {name}", ["name": url.lastPathComponent]))
    }

    private func cameraEvent(_ name: String, _ p: CameraEventPayload) {
        switch name {
        case "camera-photo-saved":
            if let path = p.path { say(tr("Saved {name}", ["name": (path as NSString).lastPathComponent])) }
        case "camera-photo-error":
            say(p.error ?? tr("Couldn't take the photo"))
        case "camera-lens-failed":
            revertLens(p.error ?? tr("That lens isn't available"))
        case "camera-zoom-state":
            if let z = p.ratio { zoomConfirmed(z) }
        case "camera-orientation":
            if let r = p.rotation { setPhoneRotation(r) }
        case "camera-switching":
            // An embedded restart: the window stays, the gap is not a stop. Not before the picture is live, and not
            // for the lens the phone already announced (a late event must not leave the pill stuck).
            guard phase == .live, let l = p.lens, l != info?.lens, lenses.contains(where: { $0.id == l }) else { break }
            lens = l
            beginSwitching()
        default: break
        }
    }

    /// Last decoded frame -> PNG -> `Pictures/DroidDock` via the core.
    func screenshot() { grabFrame() }

    /// Camera stills are JPEG (Camera.app-like), screen captures PNG.
    private func grabFrame() {
        guard let pb = decoder.lastPixelBuffer else { say(tr("Nothing to capture yet")); return }
        let jpeg = isCamera
        let rot = rotation
        let crop = Double(cropScale)
        Task.detached {
            var ci = CIImage(cvPixelBuffer: pb)
            // Upright, and never mirrored (the front preview is mirrored, the saved photo is not).
            switch rot { case 90: ci = ci.oriented(.right); case 180: ci = ci.oriented(.down); case 270: ci = ci.oriented(.left); default: break }
            if crop > 1.001 {
                let e = ci.extent, w = e.width / crop, h = e.height / crop
                ci = ci.cropped(to: CGRect(x: e.midX - w / 2, y: e.midY - h / 2, width: w, height: h))
            }
            let cs = CGColorSpace(name: CGColorSpace.sRGB)!
            let rep = jpeg
                ? CIContext().jpegRepresentation(of: ci, colorSpace: cs, options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.92])
                : CIContext().pngRepresentation(of: ci, format: .RGBA8, colorSpace: cs)
            guard let png = rep else { await self.say(tr("Nothing to capture yet")); return }
            do {
                let path = try await Core.shared.mirrorSaveCapture(png, ext: jpeg ? "jpg" : "png")
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
        // Re-sent announces (lens switch) carry the new lens state; older phones send none.
        if s.source == "camera" {
            if s.zoomRange != nil { zoomApply(s) } else { resetZoom(range: nil, zoom: nil) }
        }
        lenses = s.lenses ?? []
        photoMode = s.photo ?? "frame"
        if switchingLens, let target = lens, s.lens != nil, s.lens != target {
            // A stale announce from before the switch: keep the optimistic lens and keep waiting.
        } else {
            lens = s.lens
            lensBeforeSwitch = nil
            endSwitching()
        }
        if s.source != "camera" { stopRecording() }
        requestedSource = s.source
        if s.source == "camera" {
            if let r = s.rotation { phoneRotation = ((r % 360) + 360) % 360 }
            recorder.rotation = rotation
            setOrientWatch(true)
        }
        if first { decoder.configure(codecString: s.codec) }   // hint only; content wins
        phase = .live
        evaluateVisibility()
        let d = displaySize(width: s.width, height: s.height)
        controller?.videoSizeChanged(width: Int(d.width), height: Int(d.height))
    }

    private func stopped() {
        stopRecording(); lenses = []; lens = nil; endSwitching(); resetOrientation()
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
        stopRecording(); lenses = []; lens = nil; endSwitching(); resetOrientation()
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

#if DEBUG || DD_BENCH
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
#endif
