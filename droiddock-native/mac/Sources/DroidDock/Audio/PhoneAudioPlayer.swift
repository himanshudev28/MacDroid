import Accelerate
import AVFoundation
import Foundation

/// Plays the phone's interleaved s16le PCM stream. All work happens on one private serial queue
/// (frames arrive on a core thread; nothing hops to the main actor).
///
/// Efficiency: the phone's 20 ms chunks are coalesced into ~40 ms buffers (half the wake-ups), the
/// `AVAudioPCMBuffer`s are pooled, Int16 -> Float is `vDSP`, and the engine is stopped after `idleStop`
/// seconds without data (the phone stops sending in silence) and restarted by the next chunk.
///
/// Jitter handling: after start, reset, or an underrun the player pre-rolls `preroll` seconds before
/// playing; if more than `maxLead` seconds ever queue up (clock drift, burst) the schedule is flushed
/// rather than letting latency grow without bound.
final class PhoneAudioPlayer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.droiddock.audio", qos: .userInitiated)
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private var format: AVAudioFormat?
    private var channels = 2
    private var sampleRate = 48000.0
    private var generation = 0
    private var queuedFrames = 0
    private var playing = false
    private var live = false
    private var wired = false
    private var observer: NSObjectProtocol?

    // Coalescing + pooling (queue-confined).
    private var accum: AVAudioPCMBuffer?
    private var accumToken = 0
    private var freeBuffers: [AVAudioPCMBuffer] = []
    private var lastDataAt = Date()
    private var idleTimer: DispatchSourceTimer?

    // Staging for the borrowed bytes handed over by the core thread (one copy), recycled after use.
    private let stageLock = NSLock()
    private var stageFree: [UnsafeMutableRawPointer] = []
    private static let stageSize = 32 * 1024

    let preroll = 0.10
    let maxLead = 0.40
    /// Target size of one scheduled buffer.
    let coalesce = 0.040
    /// Longest wait for a partial buffer to fill before it is scheduled anyway.
    let flushAfter = 0.060
    /// Seconds without data before the engine is stopped (releases the output device).
    let idleStop = 3.0

    /// Frames handed to the engine since `start` (tests/log).
    private(set) var scheduledFrames = 0
    private(set) var scheduledBuffers = 0
    private(set) var buffersCreated = 0
    private(set) var idleStops = 0
    /// Restart back-off (queue-confined): failures in a row, and when the next attempt is allowed.
    private var restartFailures = 0
    private var nextRestart = Date.distantPast
    var onError: (@Sendable (String) -> Void)?

    private var _volume: Float = 1
    private var _muted = false
    var volume: Float { get { queue.sync { _volume } } set { queue.async { self._volume = newValue; self.applyVolume() } } }
    var muted: Bool { get { queue.sync { _muted } } set { queue.async { self._muted = newValue; self.applyVolume() } } }
    var isLive: Bool { queue.sync { live } }
    var isEngineRunning: Bool { queue.sync { engine.isRunning } }

    private func applyVolume() { node.volume = _muted ? 0 : _volume }

    func start(sampleRate: Double, channels: Int, format fmt: String) {
        queue.async { [self] in
            // A format we don't understand must never be played as s16le: that is loud noise.
            guard fmt.isEmpty || fmt == "pcm_s16le" else {
                live = false
                onError?("unsupported audio format \(fmt)")
                return
            }
            self.sampleRate = sampleRate > 0 ? sampleRate : 48000
            self.channels = min(8, max(1, channels))
            guard let f = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: self.sampleRate,
                                        channels: AVAudioChannelCount(self.channels), interleaved: false) else {
                onError?("audio format rejected"); return
            }
            teardownEngine()
            freeBuffers = []     // pooled buffers are tied to the old format
            format = f
            do {
                try buildEngine(f)
                live = true
                lastDataAt = Date()
                generation += 1
                resetSchedule()
                startIdleTimer()
            } catch {
                live = false
                onError?("audio engine: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async { [self] in
            live = false
            teardownEngine()
            format = nil
        }
    }

    /// `reset` = flag bit0 (a gap preceded this chunk: flush and re-buffer). `pcm` is only valid for the
    /// call (it points into the core's frame): it is copied once into a pooled staging block.
    func submit(reset: Bool, pcm: UnsafeRawBufferPointer) {
        guard pcm.count > 0, let src = pcm.baseAddress else { return }
        let n = pcm.count
        nonisolated(unsafe) let block = takeStage(n)
        memcpy(block, src, n)
        queue.async { [self] in
            defer { returnStage(block, n) }
            guard live, format != nil else { return }
            lastDataAt = Date()
            if reset { resetSchedule() }
            guard ensureRunning() else { return }   // woken from idle-stop; drop the chunk if CoreAudio won't start
            let bytesPerFrame = channels * 2
            let frames = n / bytesPerFrame
            guard frames > 0 else { return }
            if playing, Double(queuedFrames) / sampleRate > maxLead { resetSchedule() }
            append(block, frames: frames)
        }
    }

    func submit(reset: Bool, pcm: Data) {
        pcm.withUnsafeBytes { submit(reset: reset, pcm: $0) }
    }

    // MARK: Staging pool (any thread)

    private func takeStage(_ n: Int) -> UnsafeMutableRawPointer {
        if n <= Self.stageSize {
            stageLock.lock()
            if let p = stageFree.popLast() { stageLock.unlock(); return p }
            stageLock.unlock()
            return UnsafeMutableRawPointer.allocate(byteCount: Self.stageSize, alignment: 16)
        }
        return UnsafeMutableRawPointer.allocate(byteCount: n, alignment: 16)
    }

    private func returnStage(_ p: UnsafeMutableRawPointer, _ n: Int) {
        if n <= Self.stageSize {
            stageLock.lock()
            if stageFree.count < 8 { stageFree.append(p); stageLock.unlock(); return }
            stageLock.unlock()
        }
        p.deallocate()
    }

    // MARK: Queue-confined helpers

    /// Convert `frames` of interleaved s16le at `block` into the accumulating buffer (vDSP), scheduling
    /// it whenever it reaches the coalescing target.
    private func append(_ block: UnsafeMutableRawPointer, frames: Int) {
        guard let f = format else { return }
        let ch = channels
        let target = max(1, Int(coalesce * sampleRate))
        var done = 0
        while done < frames {
            let buf: AVAudioPCMBuffer
            if let a = accum { buf = a } else {
                guard let b = takeBuffer(f) else { return }
                buf = b; accum = b; b.frameLength = 0
                accumToken += 1
                let tok = accumToken, gen = generation
                queue.asyncAfter(deadline: .now() + flushAfter) { [weak self] in
                    guard let self, tok == self.accumToken, gen == self.generation else { return }
                    self.scheduleAccum()
                }
            }
            let at = Int(buf.frameLength)
            let n = min(frames - done, Int(buf.frameCapacity) - at)
            guard n > 0, let out = buf.floatChannelData else { scheduleAccum(); continue }
            let src = (block + done * ch * 2).assumingMemoryBound(to: Int16.self)
            var k: Float = 1.0 / 32768.0
            for c in 0..<ch {
                let dst = out[c] + at
                vDSP_vflt16(src + c, vDSP_Stride(ch), dst, 1, vDSP_Length(n))
                vDSP_vsmul(dst, 1, &k, dst, 1, vDSP_Length(n))
            }
            buf.frameLength = AVAudioFrameCount(at + n)
            done += n
            if Int(buf.frameLength) >= target { scheduleAccum() }
        }
    }

    private func takeBuffer(_ f: AVAudioFormat) -> AVAudioPCMBuffer? {
        if let b = freeBuffers.popLast() { return b }
        // Room for one target-size buffer plus a full incoming chunk.
        let cap = AVAudioFrameCount(sampleRate * (coalesce + 0.030))
        buffersCreated += 1
        return AVAudioPCMBuffer(pcmFormat: f, frameCapacity: cap)
    }

    private func scheduleAccum() {
        guard let buf = accum else { return }
        accum = nil
        accumToken += 1
        let frames = Int(buf.frameLength)
        guard frames > 0 else { freeBuffers.append(buf); return }
        queuedFrames += frames
        scheduledFrames += frames
        scheduledBuffers += 1
        let gen = generation
        node.scheduleBuffer(buf, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.queue.async {
                guard let self else { return }
                if self.freeBuffers.count < 8 && self.generation == gen { self.freeBuffers.append(buf) }
                guard self.generation == gen else { return }
                self.queuedFrames = max(0, self.queuedFrames - frames)
                if self.queuedFrames == 0 && self.playing {
                    // Underrun: stop and re-buffer so the next chunks rebuild a cushion.
                    self.node.stop(); self.playing = false
                }
            }
        }
        if !playing, Double(queuedFrames) / sampleRate >= preroll { startPlayback() }
    }

    private func buildEngine(_ f: AVAudioFormat) throws {
        if !wired {
            engine.attach(node)
            wired = true
        }
        engine.disconnectNodeOutput(node)
        engine.connect(node, to: engine.mainMixerNode, format: f)
        applyVolume()
        engine.prepare()
        try engine.start()
        if let o = observer { NotificationCenter.default.removeObserver(o) }   // never stack observers across rebuilds
        observer = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
        ) { [weak self] _ in
            self?.queue.async {
                guard let self, self.live, let f = self.format else { return }
                do { try self.buildEngine(f); self.resetSchedule() }
                catch { self.onError?("audio engine: \(error.localizedDescription)") }
            }
        }
    }

    private func teardownEngine() {
        idleTimer?.cancel(); idleTimer = nil
        if let o = observer { NotificationCenter.default.removeObserver(o); observer = nil }
        node.stop()
        engine.stop()
        playing = false; queuedFrames = 0; accum = nil
        generation += 1
    }

    private func resetSchedule() {
        generation += 1
        node.stop()
        playing = false
        queuedFrames = 0
        accum = nil
    }

    /// The engine is running, restarting it if the idle-stop (or a device change) stopped it. On failure (output
    /// device gone, CoreAudio busy) retries back off 0.25 s, 0.5 s ... 5 s and the error is reported once per streak.
    private func ensureRunning() -> Bool {
        if engine.isRunning { restartFailures = 0; return true }
        guard Date() >= nextRestart else { return false }
        do {
            try engine.start()
            restartFailures = 0
            return engine.isRunning
        } catch {
            restartFailures += 1
            nextRestart = Date().addingTimeInterval(min(5, 0.25 * pow(2, Double(restartFailures - 1))))
            if restartFailures == 1 { onError?("audio engine: \(error.localizedDescription)") }
            return false
        }
    }

    private func startPlayback() {
        // `play()` on a stopped engine raises an Objective-C exception, so never call it unless the engine is up.
        guard ensureRunning() else { return }
        node.play()
        playing = true
    }

    /// 1 Hz check; only exists while a stream is live. Stops the engine after `idleStop` s without data.
    private func startIdleTimer() {
        idleTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1, leeway: .milliseconds(500))
        t.setEventHandler { [weak self] in
            guard let self, self.live, self.engine.isRunning,
                  Date().timeIntervalSince(self.lastDataAt) > self.idleStop else { return }
            self.resetSchedule()
            self.engine.stop()
            self.idleStops += 1
        }
        t.resume()
        idleTimer = t
    }
}
