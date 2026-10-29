import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox

// MARK: - Pooled frame storage

/// One reusable access-unit buffer. The access unit is copied in once (at `headroom`), rewritten from
/// Annex-B to AVCC in place, wrapped in a `CMBlockBuffer` that returns the slab to the pool when released.
final class FrameSlab {
    let base: UnsafeMutableRawPointer
    let capacity: Int
    /// Guarded by `SlabPool.lock`: true while the slab sits in the free list (a second `recycle` is then ignored).
    var inPool = false
    init(capacity: Int) {
        self.capacity = capacity
        base = UnsafeMutableRawPointer.allocate(byteCount: capacity, alignment: 16)
    }
    deinit { base.deallocate() }
}

final class SlabPool: @unchecked Sendable {
    static let shared = SlabPool()
    /// Space before the source bytes so a 3-byte start code can grow into a 4-byte length in place.
    static let headroom = 64
    private let lock = NSLock()
    private var free: [FrameSlab] = []
    private(set) var created = 0

    func take(minCapacity: Int) -> FrameSlab {
        lock.lock()
        if let i = free.lastIndex(where: { $0.capacity >= minCapacity }) {
            let s = free.remove(at: i); s.inPool = false; lock.unlock(); return s
        }
        // Too-small spares are useless once frames got bigger: drop one so the pool does not fill with them.
        if free.count >= 4 { free.removeFirst().inPool = false }
        created += 1
        lock.unlock()
        let cap = max(256 * 1024, (minCapacity + 65535) & ~65535)
        return FrameSlab(capacity: cap)
    }

    func recycle(_ s: FrameSlab) {
        lock.lock()
        if !s.inPool, free.count < 8 { s.inPool = true; free.append(s) }
        lock.unlock()
    }
    var slabsCreated: Int { lock.lock(); defer { lock.unlock() }; return created }
}

private let slabFreeBlock: @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer, Int) -> Void = { refCon, _, _ in
    guard let refCon else { return }
    SlabPool.shared.recycle(Unmanaged<FrameSlab>.fromOpaque(refCon).takeRetainedValue())
}

private let vtOutput: VTDecompressionOutputCallback = { refCon, _, status, _, image, _, _ in
    guard let refCon else { return }
    Unmanaged<H26xDecoder>.fromOpaque(refCon).takeUnretainedValue().decoded(status: status, image: image)
}

/// Annex-B H.264 / HEVC access units -> `CVPixelBuffer`s via VideoToolbox.
///
/// - Drops everything until a keyframe with parameter sets has been seen.
/// - Parameter sets are cached, so a keyframe without inline sets reuses the last ones.
/// - A change in parameter sets (rotation, resolution) rebuilds the format description and,
///   if VideoToolbox can't accept it into the live session, the session itself.
/// - Everything runs on one private serial queue; garbage input is dropped, never fatal.
/// - The queue is bounded: past `maxInFlight` pending units non-keyframes are dropped until the next
///   keyframe, and `onNeedKeyframe` asks the phone for one.
/// - `paused` (viewer hidden) drops units at the door; clearing it waits for a keyframe.
final class H26xDecoder: @unchecked Sendable {
    enum Codec: Sendable { case h264, hevc }

    /// Called on a VideoToolbox thread for each decoded frame.
    var onFrame: (@Sendable (CVPixelBuffer) -> Void)?
    /// Called when the coded size changes (or first becomes known).
    var onSize: (@Sendable (Int, Int) -> Void)?
    /// Called (rate-limited, any thread) when the stream needs a fresh sync frame.
    var onNeedKeyframe: (@Sendable () -> Void)?

    static let maxInFlight = 8   // ~130 ms at 60 fps: rides out VT session creation on the first keyframe

    private let queue = DispatchQueue(label: "com.droiddock.mirror.decode", qos: .userInitiated)
    private let stateLock = NSLock()

    // Queue-confined state.
    private var codec: Codec = .h264
    private var vps: Data?
    private var sps: Data?
    private var pps: Data?
    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var needsKey = true
    private var frameIndex: Int64 = 0
    // Scratch (queue-confined, allocated once): NAL start/end offsets and the VCL subset.
    private static let maxNALs = 256
    private let nalStart = UnsafeMutablePointer<Int>.allocate(capacity: maxNALs)
    private let nalEnd = UnsafeMutablePointer<Int>.allocate(capacity: maxNALs)
    private let vcl = UnsafeMutablePointer<Int>.allocate(capacity: maxNALs)

    // Cross-thread state (stateLock).
    private var _lastPixelBuffer: CVPixelBuffer?
    private var _decoded = 0
    private var _errors = 0
    private var _dropped = 0
    private var _size = (0, 0)
    private var _inFlight = 0
    private var _dropUntilKey = false
    private var _paused = false
    private var lastKeyRequest = Date.distantPast

    var lastPixelBuffer: CVPixelBuffer? { stateLock.lock(); defer { stateLock.unlock() }; return _lastPixelBuffer }
    var decodedCount: Int { stateLock.lock(); defer { stateLock.unlock() }; return _decoded }
    var errorCount: Int { stateLock.lock(); defer { stateLock.unlock() }; return _errors }
    var droppedCount: Int { stateLock.lock(); defer { stateLock.unlock() }; return _dropped }
    var size: (Int, Int) { stateLock.lock(); defer { stateLock.unlock() }; return _size }
    var isPaused: Bool { stateLock.lock(); defer { stateLock.unlock() }; return _paused }

    /// `avc1.PPCCLL` -> H.264; `hvc1…`/`hev1…`/`h265`/`hevc` -> HEVC.
    static func codec(forString s: String) -> Codec {
        let l = s.lowercased()
        return (l.hasPrefix("hvc1") || l.hasPrefix("hev1") || l.contains("265") || l.contains("hevc")) ? .hevc : .h264
    }

    func configure(codecString: String) {
        let c = Self.codec(forString: codecString)
        queue.async { [self] in
            // Only a codec change invalidates what we hold. `needsKey` is NOT forced here: this call hops through
            // the main actor and can land after the stream's first keyframe was already decoded.
            if c != codec { codec = c; clearParams(); needsKey = true }
        }
    }

    /// Forget everything (stream ended). Keeps the last pixel buffer for stills unless `clearStill`.
    func reset(clearStill: Bool = false) {
        queue.async { [self] in
            clearParams()
            needsKey = true
            if clearStill { setLast(nil) }
        }
    }

    /// Viewer hidden / shown. Pausing drops every unit before it is copied or decoded; resuming discards
    /// deltas until a keyframe arrives (the picture is stale, so a delta would smear).
    func setPaused(_ on: Bool) {
        stateLock.lock()
        let changed = _paused != on
        _paused = on
        if changed && !on { _dropUntilKey = true }
        stateLock.unlock()
        if changed && !on { queue.async { [self] in needsKey = true } }
    }

    /// Zero-garbage entry: `bytes` (flag-stripped Annex-B access unit) is only valid for the call, so it is
    /// copied exactly once, into a pooled slab, and everything after that works on the slab.
    func submit(keyframe: Bool, bytes: UnsafeRawBufferPointer) {
        let n = bytes.count
        guard n > 4, let src = bytes.baseAddress else { return }
        stateLock.lock()
        if _paused { stateLock.unlock(); return }
        let overloaded = _inFlight >= Self.maxInFlight
        if keyframe { _dropUntilKey = false }
        let drop = !keyframe && (overloaded || _dropUntilKey)
        var ask = false
        if drop {
            _dropped += 1
            _dropUntilKey = true
            if Date().timeIntervalSince(lastKeyRequest) > 1 { lastKeyRequest = Date(); ask = true }
        } else {
            _inFlight += 1
        }
        stateLock.unlock()
        if ask { onNeedKeyframe?() }
        if drop { return }

        let slab = SlabPool.shared.take(minCapacity: n + SlabPool.headroom)
        memcpy(slab.base + SlabPool.headroom, src, n)
        queue.async { [self] in
            decode(slab: slab, length: n, flag: keyframe)
            stateLock.lock(); _inFlight -= 1; stateLock.unlock()
        }
    }

    /// Convenience for tests / synthetic feeders.
    func submit(keyframe: Bool, data: Data) {
        data.withUnsafeBytes { submit(keyframe: keyframe, bytes: $0) }
    }

    /// Block until every accepted unit has left the decode queue (tests pace a live stream with this).
    func waitQueue() { queue.sync {} }

    /// Block until everything submitted so far has been decoded (tests).
    func drain() {
        queue.sync {}
        if let s = queue.sync(execute: { session }) { VTDecompressionSessionWaitForAsynchronousFrames(s) }
    }

    // MARK: Internals

    fileprivate func decoded(status: OSStatus, image: CVImageBuffer?) {
        if status == noErr, let image {
            stateLock.lock(); _decoded += 1; _lastPixelBuffer = image; stateLock.unlock()
            onFrame?(image)
        } else {
            bump(errors: 1)
            if status == kVTInvalidSessionErr || status == kVTVideoDecoderMalfunctionErr {
                queue.async { self.dropSession() }
            }
        }
    }

    private func setLast(_ pb: CVPixelBuffer?) {
        stateLock.lock(); _lastPixelBuffer = pb; stateLock.unlock()
    }
    private func bump(errors: Int) {
        stateLock.lock(); _errors += errors; stateLock.unlock()
    }

    private func clearParams() {
        vps = nil; sps = nil; pps = nil; format = nil
        if let s = session { VTDecompressionSessionInvalidate(s) }
        session = nil
    }

    /// Split an Annex-B buffer (3- or 4-byte start codes) into NAL ranges.
    static func splitAnnexB(_ d: UnsafeBufferPointer<UInt8>) -> [Range<Int>] {
        let starts = UnsafeMutablePointer<Int>.allocate(capacity: maxNALs)
        let ends = UnsafeMutablePointer<Int>.allocate(capacity: maxNALs)
        defer { starts.deallocate(); ends.deallocate() }
        let n = scan(d.baseAddress!, d.count, starts, ends)
        return (0..<n).map { starts[$0]..<ends[$0] }
    }

    /// Fill `starts`/`ends` with NAL payload ranges (trailing zero bytes trimmed). Returns the count.
    @inline(__always)
    private static func scan(_ d: UnsafePointer<UInt8>, _ n: Int, _ starts: UnsafeMutablePointer<Int>,
                             _ ends: UnsafeMutablePointer<Int>) -> Int {
        var count = 0
        var prevSC = -1       // start-code position of the NAL being closed
        var i = 0
        func close(_ sc: Int) {
            guard count > 0 else { return }
            var e = sc
            while e > starts[count - 1] && d[e - 1] == 0 { e -= 1 }
            ends[count - 1] = e
        }
        while i + 2 < n {
            if d[i + 2] > 1 { i += 3; continue }
            if d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 {
                close(i)
                if count < maxNALs { starts[count] = i + 3; ends[count] = n; count += 1 } else { return count }
                prevSC = i; i += 3
            } else { i += 1 }
        }
        _ = prevSC
        // Last NAL: trim trailing zeros against the end of the buffer.
        if count > 0 { close(n) }
        // Drop empties (back-to-back start codes).
        var w = 0
        for r in 0..<count where ends[r] > starts[r] { starts[w] = starts[r]; ends[w] = ends[r]; w += 1 }
        return w
    }

    private static func differs(_ cached: Data?, _ p: UnsafePointer<UInt8>, _ len: Int) -> Bool {
        guard let c = cached, c.count == len else { return true }
        return c.withUnsafeBytes { memcmp($0.baseAddress!, p, len) != 0 }
    }

    private func decode(slab: FrameSlab, length n: Int, flag: Bool) {
        var consumed = false
        defer { if !consumed { SlabPool.shared.recycle(slab) } }

        let h = SlabPool.headroom
        let src = (slab.base + h).assumingMemoryBound(to: UInt8.self)
        let count = Self.scan(src, n, nalStart, nalEnd)
        guard count > 0 else { return }

        // Content beats the hint: a keyframe carrying H.264 SPS+PPS or HEVC VPS+SPS+PPS names its codec.
        var m264: UInt64 = 0, m265: UInt64 = 0
        for k in 0..<count {
            let b0 = src[nalStart[k]]
            m264 |= 1 << UInt64(b0 & 0x1F)
            if nalEnd[k] - nalStart[k] >= 2 { m265 |= 1 << UInt64((b0 >> 1) & 0x3F) }
        }
        let h264Sets = m264 & (1 << 7) != 0 && m264 & (1 << 8) != 0
        let h265Sets = m265 & (1 << 32) != 0 && m265 & (1 << 33) != 0 && m265 & (1 << 34) != 0
        if h265Sets && !h264Sets && codec != .hevc { codec = .hevc; clearParams() }
        else if h264Sets && !h265Sets && codec != .h264 { codec = .h264; clearParams() }

        var isKey = flag
        var vclCount = 0
        var changed = false
        for k in 0..<count {
            let len = nalEnd[k] - nalStart[k]
            let p = src + nalStart[k]
            let b0 = p[0]
            switch codec {
            case .h264:
                let t = Int(b0 & 0x1F)
                switch t {
                case 7: if Self.differs(sps, p, len) { sps = Data(bytes: p, count: len); changed = true }
                case 8: if Self.differs(pps, p, len) { pps = Data(bytes: p, count: len); changed = true }
                case 1...5:
                    if t == 5 { isKey = true }
                    vcl[vclCount] = k; vclCount += 1
                default: break   // SEI, AUD, filler...
                }
            case .hevc:
                guard len >= 2 else { continue }
                let t = Int((b0 >> 1) & 0x3F)
                switch t {
                case 32: if Self.differs(vps, p, len) { vps = Data(bytes: p, count: len); changed = true }
                case 33: if Self.differs(sps, p, len) { sps = Data(bytes: p, count: len); changed = true }
                case 34: if Self.differs(pps, p, len) { pps = Data(bytes: p, count: len); changed = true }
                case 0...9, 16...21:
                    if t >= 16 { isKey = true }
                    vcl[vclCount] = k; vclCount += 1
                default: break
                }
            }
        }
        if changed || format == nil { rebuildFormat() }

        if needsKey {
            // Stays set until a keyframe has actually been handed to VideoToolbox (see `decodeSucceeded`).
            guard isKey, format != nil, vclCount > 0 else { return }
        }
        guard vclCount > 0, let fmt = format else { return }
        if session == nil { makeSession(fmt) }
        guard let session else { return }

        // In-place Annex-B -> AVCC (4-byte big-endian lengths). Output chases the read position from behind;
        // `out + 4 <= p` keeps it from overwriting NALs not yet moved (only a pathological number of 3-byte
        // start codes could break that, and then the frame is dropped).
        let base = slab.base.assumingMemoryBound(to: UInt8.self)
        var out = 0
        for j in 0..<vclCount {
            let k = vcl[j]
            let p = h + nalStart[k], len = nalEnd[k] - nalStart[k]
            guard out + 4 <= p else { lostFrame(); return }
            base[out] = UInt8((len >> 24) & 0xFF); base[out + 1] = UInt8((len >> 16) & 0xFF)
            base[out + 2] = UInt8((len >> 8) & 0xFF); base[out + 3] = UInt8(len & 0xFF)
            memmove(base + out + 4, base + p, len)
            out += 4 + len
        }
        let total = out

        let ref = Unmanaged.passRetained(slab)
        var source = CMBlockBufferCustomBlockSource(version: UInt32(kCMBlockBufferCustomBlockSourceVersion),
                                                    AllocateBlock: nil, FreeBlock: slabFreeBlock,
                                                    refCon: ref.toOpaque())
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: slab.base, blockLength: total,
            blockAllocator: kCFAllocatorNull, customBlockSource: &source, offsetToData: 0,
            dataLength: total, flags: 0, blockBufferOut: &block) == noErr, let block else {
            ref.release()   // creation failed: nothing owns the retain; `consumed` stays false -> recycled
            lostFrame()
            return
        }
        consumed = true     // the block buffer's free block now owns the slab

        var sample: CMSampleBuffer?
        var size = total
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: CMTime(value: frameIndex, timescale: 60),
                                        decodeTimeStamp: .invalid)
        frameIndex += 1
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: fmt, sampleCount: 1,
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
            sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { lostFrame(); return }

        let st = VTDecompressionSessionDecodeFrame(
            session, sampleBuffer: sample, flags: [._EnableAsynchronousDecompression], frameRefcon: nil,
            infoFlagsOut: nil)
        if st != noErr {
            bump(errors: 1)
            if st == kVTInvalidSessionErr || st == kVTVideoDecoderMalfunctionErr { dropSession() }
            else { lostFrame(countError: false) }
        } else {
            needsKey = false
        }
    }

    /// A unit that could not be turned into a decoded picture: whatever follows would reference it, so wait for a
    /// keyframe and ask the phone for one.
    private func lostFrame(countError: Bool = true) {
        if countError { bump(errors: 1) }
        needsKey = true
        onNeedKeyframe?()
    }

    private func dropSession() {
        if let s = session { VTDecompressionSessionInvalidate(s) }
        session = nil
        needsKey = true
    }

    private func rebuildFormat() {
        guard let sps, let pps else { return }
        if codec == .hevc && vps == nil { return }
        let sets: [Data] = codec == .hevc ? [vps!, sps, pps] : [sps, pps]
        var ptrs: [UnsafePointer<UInt8>] = []
        var sizes: [Int] = []
        var allocs: [UnsafeMutablePointer<UInt8>] = []
        defer { allocs.forEach { $0.deallocate() } }
        for s in sets {
            let m = UnsafeMutablePointer<UInt8>.allocate(capacity: max(1, s.count))
            s.copyBytes(to: m, count: s.count)
            allocs.append(m); ptrs.append(UnsafePointer(m)); sizes.append(s.count)
        }
        var fd: CMVideoFormatDescription?
        let st: OSStatus
        if codec == .hevc {
            st = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: ptrs.count, parameterSetPointers: ptrs,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &fd)
        } else {
            st = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: ptrs.count, parameterSetPointers: ptrs,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &fd)
        }
        guard st == noErr, let fd else { bump(errors: 1); return }
        format = fd
        if let s = session, !VTDecompressionSessionCanAcceptFormatDescription(s, formatDescription: fd) {
            VTDecompressionSessionWaitForAsynchronousFrames(s)
            VTDecompressionSessionInvalidate(s)
            session = nil
        }
        let dim = CMVideoFormatDescriptionGetDimensions(fd)
        stateLock.lock(); _size = (Int(dim.width), Int(dim.height)); stateLock.unlock()
        onSize?(Int(dim.width), Int(dim.height))
    }

    private func makeSession(_ fmt: CMVideoFormatDescription) {
        // Native hardware output (420 biplanar) so nothing converts; hardware is required so a silent
        // software fallback at phone resolution + 60 fps can't happen, with one relaxed retry.
        let nv12: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any](),
        ]
        let attempts: [([CFString: Any], [CFString: Any]?)] = [
            ([kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: true], nv12),
            ([kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true], nv12),
            ([kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true], nil),
        ]
        var record = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: vtOutput, decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque())
        for (spec, attrs) in attempts {
            var s: VTDecompressionSession?
            let st = VTDecompressionSessionCreate(
                allocator: kCFAllocatorDefault, formatDescription: fmt,
                decoderSpecification: spec as CFDictionary, imageBufferAttributes: attrs as CFDictionary?,
                outputCallback: &record, decompressionSessionOut: &s)
            if st == noErr, let s { session = s; return }
        }
        bump(errors: 1)
    }

    deinit {
        if let s = session {
            VTDecompressionSessionWaitForAsynchronousFrames(s)
            VTDecompressionSessionInvalidate(s)
        }
        nalStart.deallocate(); nalEnd.deallocate(); vcl.deallocate()
    }
}
