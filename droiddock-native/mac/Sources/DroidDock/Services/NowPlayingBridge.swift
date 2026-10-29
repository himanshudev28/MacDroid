import AppKit
import MediaPlayer
import Observation

/// Mirrors the phone's now-playing state into Control Center / the lock-screen widget, and routes Mac media
/// keys and Control Center buttons back to the phone via `media_cmd`. Setting: `droiddock.nowPlayingBridge`.
@MainActor
final class NowPlayingBridge {
    static let shared = NowPlayingBridge()
    static let defaultsKey = "droiddock.nowPlayingBridge"
    static var isEnabled: Bool { UserDefaults.standard.object(forKey: defaultsKey) as? Bool ?? true }

    private static let debounce: Duration = .milliseconds(350)
    private static let echoWindow: TimeInterval = 1.0

    private var started = false
    private var pending: Task<Void, Never>?
    private var lastCommandAt: Date = .distantPast
    private var commandTargets: [Any] = []
    private var published = false
    private var lastArtKey: String?
    private var artwork: MPMediaItemArtwork?
    /// DEBUG only (`DD_FAKE_MEDIA=1`): a sample state used instead of the model's.
    private var fake: (MediaState, String?)?
    private var lastSig: Sig?

    /// What a publish consists of. Elapsed time is extrapolated by the system from (position, rate), so a new
    /// position only matters when it jumped (seek) rather than advanced with the clock.
    private struct Sig {
        var title: String?, artist: String?, app: String?, dur: Double?
        var playing: Bool, pos: Double, art: Int
        var at = Date()
        func sameTrackState(_ o: Sig) -> Bool {
            guard title == o.title, artist == o.artist, app == o.app, dur == o.dur, playing == o.playing, art == o.art else { return false }
            let expected = playing ? pos + o.at.timeIntervalSince(at) * 1000 : pos
            return abs(o.pos - expected) < 2500
        }
    }

    private init() {}

    /// Begin observing `AppModel`. Safe to call twice.
    func start() {
        guard !started else { return }
        started = true
        #if DEBUG
        if ProcessInfo.processInfo.environment["DD_FAKE_MEDIA"] == "1" {
            var m = MediaState()
            m.active = true; m.title = "Fake Song"; m.artist = "Fake Artist"; m.app = "Spotify"
            m.playing = true; m.pos = 42_000; m.dur = 215_000
            fake = (m, nil)
        }
        #endif
        // No global UserDefaults observer: the Settings toggles call `refresh()` themselves.
        observe()
        schedule()
    }

    /// Re-evaluate now (call after the Settings toggle flips).
    func refresh() { if started { schedule() } else { start() } }

    /// Lazy start: wait until the phone reports active media (so MediaPlayer/MPRemoteCommandCenter stay untouched
    /// until needed), then start for good.
    func startWhenNeeded() {
        guard !started else { return }
        #if DEBUG
        if ProcessInfo.processInfo.environment["DD_FAKE_MEDIA"] == "1" { start(); return }
        #endif
        if AppModel.shared.media?.active == true { start(); return }
        withObservationTracking {
            _ = AppModel.shared.media?.active
        } onChange: { [weak self] in
            Task { @MainActor in self?.startWhenNeeded() }
        }
    }

    // MARK: Observation

    private func observe() {
        withObservationTracking {
            let m = AppModel.shared
            // Not the whole `media` struct: position ticks must not re-fire this.
            _ = m.media?.title; _ = m.media?.artist; _ = m.media?.app; _ = m.media?.active; _ = m.media?.playing
            _ = m.media?.dur; _ = m.media?.trackKey; _ = m.mediaArt; _ = m.isConnected
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.schedule()
                self.observe()
            }
        }
    }

    /// Debounce, and push the update past the echo window if we just sent a command.
    private func schedule() {
        pending?.cancel()
        let echoLeft = max(0, Self.echoWindow - Date().timeIntervalSince(lastCommandAt))
        let delay = Duration.seconds(max(0.35, echoLeft))
        pending = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.apply()
        }
    }

    // MARK: Publish

    private func apply() {
        let model = AppModel.shared
        let state: MediaState?
        let art: String?
        if let fake { state = fake.0; art = fake.1 }
        else { state = model.media; art = model.mediaArt }
        guard Self.isEnabled, fake != nil || model.isConnected, let s = state, s.active,
              s.title != nil || s.artist != nil else {
            clear()
            return
        }
        setCommandsEnabled(true)

        // Identical publish (a pos tick, a re-sent state): skip the XPC to mediaremoted.
        let sig = Sig(title: s.title, artist: s.artist, app: s.app, dur: s.dur, playing: s.playing ?? false,
                      pos: s.pos ?? 0, art: art?.utf8.count ?? 0)
        if published, let last = lastSig, last.sameTrackState(sig) { return }
        lastSig = sig

        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = s.title ?? s.app ?? "DroidDock"
        if let a = s.artist { info[MPMediaItemPropertyArtist] = a }
        if let app = s.app { info[MPMediaItemPropertyAlbumTitle] = app }
        if let d = s.dur, d > 0 { info[MPMediaItemPropertyPlaybackDuration] = d / 1000 }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = (s.pos ?? 0) / 1000
        info[MPNowPlayingInfoPropertyPlaybackRate] = (s.playing ?? false) ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        if lastArtKey != art { lastArtKey = art; artwork = nil; loadArtwork(art) }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }

        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        center.playbackState = (s.playing ?? false) ? .playing : .paused
        published = true
        dlog("nowplaying: published \"\(s.title ?? "?")\" — \(s.artist ?? "?") playing=\(s.playing ?? false) art=\(artwork != nil)")
    }

    /// Quit: withdraw the Control Center entry and the media-key handlers so no stale DroidDock card is left.
    func shutdown() {
        guard started else { return }
        pending?.cancel()
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
        published = false
        setCommandsEnabled(false)
    }

    private func clear() {
        guard published else { setCommandsEnabled(false); return }
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
        published = false; artwork = nil; lastArtKey = nil; lastSig = nil
        setCommandsEnabled(false)
        dlog("nowplaying: cleared")
    }

    /// Uses the shared downsampled decode (off the main actor); re-publishes once the image is ready.
    private func loadArtwork(_ b64: String?) {
        guard let b64, !b64.isEmpty else { return }
        Task { [weak self] in
            guard let img = await ImageCache.shared.image(fromDataURL: b64, maxPixel: 600) else { return }
            guard let self, self.lastArtKey == b64 else { return }
            self.artwork = Self.makeArtwork(img)
            self.lastSig = nil
            self.schedule()
        }
    }

    /// The request handler runs on an arbitrary queue, so build it outside the main actor.
    private nonisolated static func makeArtwork(_ img: NSImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: img.size) { _ in img }
    }

    // MARK: Remote commands

    private func setCommandsEnabled(_ on: Bool) {
        let c = MPRemoteCommandCenter.shared()
        if on, commandTargets.isEmpty {
            commandTargets = [
                c.playCommand.addTarget { [weak self] _ in self?.send("play") ?? .commandFailed },
                c.pauseCommand.addTarget { [weak self] _ in self?.send("pause") ?? .commandFailed },
                c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.sendToggle() ?? .commandFailed },
                c.nextTrackCommand.addTarget { [weak self] _ in self?.send("next") ?? .commandFailed },
                c.previousTrackCommand.addTarget { [weak self] _ in self?.send("prev") ?? .commandFailed },
                c.changePlaybackPositionCommand.addTarget { [weak self] e in
                    guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
                    return self?.send("seek", value: e.positionTime * 1000) ?? .commandFailed
                },
            ]
        } else if !on, !commandTargets.isEmpty {
            let targets = commandTargets
            commandTargets = []
            for (cmd, t) in zip([c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.nextTrackCommand,
                                 c.previousTrackCommand, c.changePlaybackPositionCommand], targets) {
                cmd.removeTarget(t)
            }
        }
        for cmd in [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.nextTrackCommand,
                    c.previousTrackCommand, c.changePlaybackPositionCommand] { cmd.isEnabled = on }
    }

    private func sendToggle() -> MPRemoteCommandHandlerStatus {
        let playing = (fake?.0 ?? AppModel.shared.media)?.playing ?? false
        return send(playing ? "pause" : "play")
    }

    private func send(_ cmd: String, value: Double = 0) -> MPRemoteCommandHandlerStatus {
        lastCommandAt = Date()
        dlog("nowplaying: remote command \(cmd)")
        Task { try? await Core.shared.mediaCmd(cmd, value: value) }
        return .success
    }
}
