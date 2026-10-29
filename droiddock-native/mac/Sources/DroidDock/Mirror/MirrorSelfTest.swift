import AVFoundation
import CoreMedia
import Foundation
import VideoToolbox

/// `DD_SELFTEST=mirror|audio|all build/DroidDock.app/Contents/MacOS/DroidDock` runs these at launch
/// (before the core starts, so no port is bound and no data dir is touched), prints results, exits.
enum DDSelfTest {
    static func runIfRequested() -> Bool {
        guard let mode = ProcessInfo.processInfo.environment["DD_SELFTEST"], !mode.isEmpty else { return false }
        if mode == "ui" {
            DispatchQueue.main.async {
                MirrorSession.shared.debugShowSynthetic()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    print("WINDOW \(MirrorSession.shared.debugWindowNumber)"); fflush(stdout)
                }
            }
            return true
        }
        DispatchQueue.global().async {
            var ok = true
            if mode == "mirror" || mode == "all" { ok = MirrorTest.run() && ok }
            if mode == "audio" || mode == "all" { ok = AudioTest.run() && ok }
            if mode == "bench" { BenchTest.run() }
            print(ok ? "SELFTEST PASS" : "SELFTEST FAIL"); fflush(stdout)
            exit(ok ? 0 : 1)
        }
        return true
    }
}

/// Thread-safe string list for callbacks that fire off the main thread.
final class LockedStrings: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func append(_ s: String) { lock.lock(); items.append(s); lock.unlock() }
    func clear() { lock.lock(); items = []; lock.unlock() }
    var values: [String] { lock.lock(); defer { lock.unlock() }; return items }
}

func stLog(_ s: String) { print("[selftest] \(s)"); fflush(stdout) }

// MARK: - Mirror

enum MirrorTest {
    static let sc: [UInt8] = [0, 0, 0, 1]

    final class Collector: @unchecked Sendable {
        var units: [(key: Bool, annexB: Data)] = []
        let lock = NSLock()
        let hevc: Bool
        var stripParamsAfterFirstKey = false
        private var keys = 0
        init(hevc: Bool) { self.hevc = hevc }

        func add(_ sb: CMSampleBuffer) {
            let attach = (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]])?.first
            let key = !(attach?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
            var out = Data()
            if key {
                keys += 1
                if !(stripParamsAfterFirstKey && keys > 1), let fd = CMSampleBufferGetFormatDescription(sb) {
                    var count = 0
                    if hevc { CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) }
                    else { CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) }
                    for i in 0..<count {
                        var p: UnsafePointer<UInt8>?; var n = 0
                        if hevc { CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &p, parameterSetSizeOut: &n, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) }
                        else { CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &p, parameterSetSizeOut: &n, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) }
                        if let p { out.append(contentsOf: i % 2 == 0 ? sc : [0, 0, 1]); out.append(p, count: n) }  // mix 3/4-byte codes
                    }
                }
            }
            guard let bb = CMSampleBufferGetDataBuffer(sb) else { return }
            var len = 0; var ptr: UnsafeMutablePointer<CChar>?
            CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &len, dataPointerOut: &ptr)
            guard let ptr else { return }
            let raw = UnsafeRawPointer(ptr)
            var o = 0
            while o + 4 <= len {
                let l = Int(raw.loadUnaligned(fromByteOffset: o, as: UInt32.self).bigEndian)
                o += 4
                guard l > 0, o + l <= len else { break }
                out.append(contentsOf: sc); out.append(Data(bytes: raw + o, count: l)); o += l
            }
            lock.lock(); units.append((key, out)); lock.unlock()
        }
    }

    static func encode(hevc: Bool, w: Int, h: Int, frames: Int, keyEvery: Int, stripParams: Bool = false) -> [(key: Bool, annexB: Data)]? {
        let col = Collector(hevc: hevc)
        col.stripParamsAfterFirstKey = stripParams
        var session: VTCompressionSession?
        let st = VTCompressionSessionCreate(allocator: nil, width: Int32(w), height: Int32(h),
            codecType: hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
            encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &session)
        guard st == noErr, let session else { stLog("encoder create failed \(st)"); return nil }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: keyEvery as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(session)
        for i in 0..<frames {
            var pb: CVPixelBuffer?
            CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_32BGRA, nil, &pb)
            guard let pb else { return nil }
            CVPixelBufferLockBaseAddress(pb, [])
            if let base = CVPixelBufferGetBaseAddress(pb) {
                let stride = CVPixelBufferGetBytesPerRow(pb)
                for y in 0..<h {
                    let row = base.advanced(by: y * stride).assumingMemoryBound(to: UInt8.self)
                    for x in 0..<w { row[x*4] = UInt8((x + i * 7) & 255); row[x*4+1] = UInt8((y + i * 3) & 255); row[x*4+2] = UInt8(i * 9 & 255); row[x*4+3] = 255 }
                }
            }
            CVPixelBufferUnlockBaseAddress(pb, [])
            let props: CFDictionary? = i == 0 ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
            VTCompressionSessionEncodeFrame(session, imageBuffer: pb, presentationTimeStamp: CMTime(value: Int64(i), timescale: 30),
                duration: .invalid, frameProperties: props, infoFlagsOut: nil) { status, _, sb in
                if status == noErr, let sb { col.add(sb) }
            }
        }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        VTCompressionSessionInvalidate(session)
        col.lock.lock(); defer { col.lock.unlock() }
        return col.units
    }

    /// What `Core.handleFrame` hands the sink: flags byte prefixed, then split again.
    static func feed(_ d: H26xDecoder, _ u: (key: Bool, annexB: Data)) {
        var framed = Data([u.key ? 1 : 0]); framed.append(u.annexB)
        d.submit(keyframe: (framed[0] & 1) == 1, data: Data(framed.dropFirst()))
        d.waitQueue()   // paced like a live stream: the bounded queue must not drop what a real phone would pace
    }

    static func run() -> Bool {
        var ok = true
        func check(_ c: Bool, _ m: String) { stLog((c ? "ok   " : "FAIL ") + m); if !c { ok = false } }

        for hevc in [false, true] {
            let name = hevc ? "HEVC" : "H.264"
            guard let a = encode(hevc: hevc, w: 320, h: 240, frames: 20, keyEvery: 10),
                  let b = encode(hevc: hevc, w: 240, h: 320, frames: 12, keyEvery: 6) else {
                check(false, "\(name) encoder unavailable"); continue
            }
            check(a.count == 20 && a[0].key, "\(name) encoded \(a.count) frames, \(a.filter(\.key).count) keyframes, first \(a[0].annexB.count) B")

            let d = H26xDecoder()
            let sizeLog = LockedStrings()
            d.onFrame = { pb in sizeLog.append("\(CVPixelBufferGetWidth(pb))x\(CVPixelBufferGetHeight(pb))") }

            // garbage + empty must never crash
            for junk in [Data(), Data([0]), Data([0, 0, 1]), Data([0, 0, 0, 1, 0x67]), Data((0..<2000).map { _ in UInt8.random(in: 0...255) }),
                         Data(repeating: 0, count: 500), Data([0, 0, 1, 0xFF, 0xFF, 0, 0, 1])] {
                d.submit(keyframe: true, data: junk); d.submit(keyframe: false, data: junk)
            }
            d.drain()
            check(d.decodedCount == 0, "\(name) garbage produced no frames, no crash")

            // delta before any keyframe is dropped
            feed(d, a[1]); d.drain()
            check(d.decodedCount == 0, "\(name) delta before keyframe dropped")

            for u in a { feed(d, u) }
            d.drain()
            check(d.decodedCount == 20, "\(name) decoded \(d.decodedCount)/20 @ \(d.size.0)x\(d.size.1)")
            check(d.size == (320, 240), "\(name) format size 320x240")

            // rotation: params change -> new format + session
            for u in b { feed(d, u) }
            d.drain()
            let last = sizeLog.values.last ?? "-"
            check(d.decodedCount == 32 && last == "240x320", "\(name) rotation: total \(d.decodedCount)/32, last frame \(last)")
            check(d.lastPixelBuffer.map { CVPixelBufferGetWidth($0) == 240 } ?? false, "\(name) last pixel buffer kept")

            // keyframe without inline params reuses cached sets
            if let c = encode(hevc: hevc, w: 240, h: 320, frames: 12, keyEvery: 4, stripParams: true) {
                let before = d.decodedCount
                for u in c { feed(d, u) }
                d.drain()
                check(d.decodedCount - before == 12, "\(name) keyframes lacking params use cache: \(d.decodedCount - before)/12")
            }
            // mid-stream loss: reset requires new keyframe
            d.reset(); d.drain()
            let before = d.decodedCount
            feed(d, a[3]); d.drain()
            check(d.decodedCount == before, "\(name) after reset, delta dropped until keyframe")
            feed(d, a[10]); feed(d, a[11]); d.drain()
            check(d.decodedCount - before == 2, "\(name) resumes on keyframe (\(d.decodedCount - before)/2)")
        }

        // Pause / resume + pooled slabs + bounded queue (H.264 only; same code path for HEVC).
        if let a = encode(hevc: false, w: 320, h: 240, frames: 60, keyEvery: 20) {
            let d = H26xDecoder()
            let asked = LockedStrings()
            d.onNeedKeyframe = { asked.append("k") }
            d.setPaused(true)
            for u in a.prefix(5) { feed(d, u) }
            d.drain()
            check(d.decodedCount == 0 && d.isPaused, "paused: units dropped before decode (\(d.decodedCount))")
            d.setPaused(false)
            feed(d, a[1]); feed(d, a[2]); d.drain()   // deltas right after resume: dropped, ask for a sync frame
            check(d.decodedCount == 0, "resume waits for a keyframe (decoded \(d.decodedCount))")
            for u in a[20..<40] { feed(d, u) }
            d.drain()
            check(d.decodedCount == 20, "decodes from the next keyframe: \(d.decodedCount)/20")
            // paced: the pool recycles slabs instead of allocating
            let created0 = SlabPool.shared.slabsCreated
            for _ in 0..<3 { for u in a { feed(d, u) } }
            d.drain()
            check(SlabPool.shared.slabsCreated - created0 <= 2, "slab pool reuse: \(SlabPool.shared.slabsCreated - created0) new slabs for 180 units")
            // flood without waiting: the queue must stay bounded and recover on the next keyframe
            let before = d.decodedCount, droppedBefore = d.droppedCount
            for _ in 0..<5 { for u in a { d.submit(keyframe: u.key, data: u.annexB) } }
            d.drain()
            check(d.droppedCount > droppedBefore && d.decodedCount > before, "bounded queue: dropped \(d.droppedCount - droppedBefore), decoded \(d.decodedCount - before)")
            check(asked.values.count >= 1, "dropping under load asked the phone for a sync frame (\(asked.values.count)x)")
        }
        return ok
    }
}

// MARK: - Audio

private enum AudioTest {
    static func run() -> Bool {
        var ok = true
        func check(_ c: Bool, _ m: String) { stLog((c ? "ok   " : "FAIL ") + m); if !c { ok = false } }

        let p = PhoneAudioPlayer()
        p.volume = 0.08
        let errorLog = LockedStrings()
        p.onError = { errorLog.append($0) }

        p.start(sampleRate: 48000, channels: 2, format: "opus")
        Thread.sleep(forTimeInterval: 0.1)
        check(!p.isLive && !errorLog.values.isEmpty, "refuses non pcm_s16le (\(errorLog.values.first ?? "no error"))")
        errorLog.clear()

        p.start(sampleRate: 48000, channels: 2, format: "pcm_s16le")
        Thread.sleep(forTimeInterval: 0.2)
        check(p.isLive && errorLog.values.isEmpty, "engine started \(errorLog.values.first ?? "")")
        let chunkFrames = 960   // 20 ms
        var phase = 0.0
        let step = 2 * Double.pi * 440 / 48000
        for c in 0..<50 {
            var d = Data(capacity: chunkFrames * 4)
            for _ in 0..<chunkFrames {
                let v = Int16(sin(phase) * 12000); phase += step
                withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0); d.append(contentsOf: $0) }
            }
            p.submit(reset: c == 0, pcm: d)
            Thread.sleep(forTimeInterval: 0.02)
        }
        Thread.sleep(forTimeInterval: 0.3)
        check(p.scheduledFrames == 48000, "scheduled \(p.scheduledFrames)/48000 frames (1 s of 440 Hz stereo)")
        check(p.scheduledBuffers >= 10 && p.scheduledBuffers <= 30, "coalesced 50 chunks into \(p.scheduledBuffers) buffers")
        check(p.buffersCreated <= 8, "pooled buffers: \(p.buffersCreated) created")
        // idle: engine stops after idleStop s without data, restarts on the next chunk
        Thread.sleep(forTimeInterval: 4.6)
        check(!p.isEngineRunning && p.idleStops == 1, "engine stopped after silence (idleStops \(p.idleStops))")
        var d2 = Data(capacity: chunkFrames * 4)
        for _ in 0..<chunkFrames { withUnsafeBytes(of: Int16(1000)) { d2.append(contentsOf: $0); d2.append(contentsOf: $0) } }
        p.submit(reset: true, pcm: d2)
        Thread.sleep(forTimeInterval: 0.2)
        check(p.isEngineRunning, "engine restarts on data")
        // odd-sized garbage chunk must not crash
        p.submit(reset: false, pcm: Data([1, 2, 3]))
        p.stop()
        Thread.sleep(forTimeInterval: 0.1)
        check(!p.isLive && errorLog.values.isEmpty, "stopped cleanly")
        return ok
    }
}

enum MirrorTestSupport {
    static func encodeH264(w: Int, h: Int, n: Int) -> [(key: Bool, annexB: Data)]? {
        MirrorTest.encode(hevc: false, w: w, h: h, frames: n, keyEvery: 30)
    }
}


// MARK: - Bench (DD_SELFTEST=bench [DD_BENCH_SECS=30] [DD_BENCH_HEVC=1])

/// Replays a synthetic 1080x2400 clip at 60 fps real time through the decoder (the same entry the FFI
/// feeds) and prints one JSON line: process CPU % over the run, per-frame submit->decoded latency.
enum BenchTest {
    static func run() {
        let env = ProcessInfo.processInfo.environment
        let secs = Double(env["DD_BENCH_SECS"] ?? "") ?? 30
        let hevc = env["DD_BENCH_HEVC"] == "1"
        guard let clip = MirrorTest.encode(hevc: hevc, w: 1080, h: 2400, frames: 120, keyEvery: 60) else {
            print("BENCH unavailable"); return
        }
        let avg = clip.reduce(0) { $0 + $1.annexB.count } / max(1, clip.count)
        let d = H26xDecoder()
        let stamps = LockedDoubles()
        let lat = LockedDoubles()
        d.onFrame = { _ in lat.append(Self.ms(mach_continuous_time()) - stamps.popFirst()) }
        func cpu() -> Double {
            var u = rusage(); getrusage(RUSAGE_SELF, &u)
            return Double(u.ru_utime.tv_sec + u.ru_stime.tv_sec) + Double(u.ru_utime.tv_usec + u.ru_stime.tv_usec) / 1e6
        }
        AllocCounter.start()
        let c0 = cpu(), t0 = Date()
        let frames = Int(secs * 60)
        for i in 0..<frames {
            let u = clip[i % clip.count]
            let dropped0 = d.droppedCount
            stamps.append(Self.ms(mach_continuous_time()))
            // `i % count == 0` is a keyframe; a wrap mid-GOP is fine because clip[0] is a keyframe.
            d.submit(keyframe: u.key, data: u.annexB)
            if d.droppedCount != dropped0 { _ = stamps.popLast() }   // dropped at the door: no output to match
            let next = t0.addingTimeInterval(Double(i + 1) / 60)
            let wait = next.timeIntervalSinceNow
            if wait > 0 { Thread.sleep(forTimeInterval: wait) }
        }
        d.drain()
        let wall = Date().timeIntervalSince(t0), used = cpu() - c0
        let allocs = AllocCounter.stop()
        let l = lat.values.sorted()
        func pct(_ p: Double) -> Double { l.isEmpty ? 0 : l[min(l.count - 1, Int(Double(l.count) * p))] }
        print(String(format: "BENCH {\"codec\":\"%@\",\"avg_au_bytes\":%d,\"frames\":%d,\"decoded\":%d,\"dropped\":%d,\"errors\":%d,\"cpu_pct\":%.2f,\"allocs_per_frame\":%.1f,\"p50_ms\":%.2f,\"p95_ms\":%.2f,\"p99_ms\":%.2f}",
                     hevc ? "hevc" : "h264", avg, frames, d.decodedCount, d.droppedCount, d.errorCount, used / wall * 100, Double(allocs) / Double(frames), pct(0.5), pct(0.95), pct(0.99)))
        fflush(stdout)
    }
    static func ms(_ t: UInt64) -> Double {
        var tb = mach_timebase_info_data_t(); mach_timebase_info(&tb)
        return Double(t) * Double(tb.numer) / Double(tb.denom) / 1e6
    }
}

final class LockedDoubles: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Double] = []
    private var head = 0
    func append(_ v: Double) { lock.lock(); items.append(v); lock.unlock() }
    func popLast() -> Double? { lock.lock(); defer { lock.unlock() }; return items.popLast() }
    func popFirst() -> Double { lock.lock(); defer { lock.unlock() }; guard head < items.count else { return 0 }; head += 1; return items[head - 1] }
    var values: [Double] { lock.lock(); defer { lock.unlock() }; return items }
}

/// Counts malloc-family allocations through libsystem_malloc's `malloc_logger` hook (includes CF objects).
enum AllocCounter {
    // Raw pointers: the hook runs on every thread, so no Swift exclusivity-checked statics.
    static let count: UnsafeMutablePointer<Int64> = { let p = UnsafeMutablePointer<Int64>.allocate(capacity: 1); p.pointee = 0; return p }()
    static let lock: UnsafeMutablePointer<os_unfair_lock> = {
        let p = UnsafeMutablePointer<os_unfair_lock>.allocate(capacity: 1); p.initialize(to: os_unfair_lock()); return p }()
    typealias Hook = @convention(c) (UInt32, UInt, UInt, UInt, UInt, UInt32) -> Void
    static func start() {
        guard let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "malloc_logger") else { return }
        count.pointee = 0
        let c = count, l = lock
        _ = (c, l)
        let hook: Hook = { type, _, _, _, _, _ in
            if type & 2 != 0 {
                os_unfair_lock_lock(AllocCounter.lock); AllocCounter.count.pointee += 1; os_unfair_lock_unlock(AllocCounter.lock)
            }
        }
        sym.assumingMemoryBound(to: Hook?.self).pointee = hook
    }
    static func stop() -> Int64 {
        if let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "malloc_logger") {
            sym.assumingMemoryBound(to: Hook?.self).pointee = nil
        }
        return count.pointee
    }
}
