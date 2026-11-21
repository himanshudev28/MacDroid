import AVFoundation
import CoreMedia
import Foundation

/// Records the phone camera's encoded stream to `~/Movies/DroidDock/DroidDock_yyyy-MM-dd_HH.mm.ss.mov` WITHOUT re-encoding:
/// the access units the decoder already built (H.264 or HEVC, AVCC) are written passthrough by AVAssetWriter.
///
/// - Tapped from `H26xDecoder.onSample` on the decode queue; `offer` only counts and hops to this recorder's own serial
///   queue. The queue is bounded: when it backs up, samples are dropped (never blocking decode) and the file then waits
///   for the next keyframe, so what is written always decodes.
/// - Timestamps are real (host clock), rebased so the first keyframe is time zero. The decoder's own `frameIndex / 60`
///   stamps are not used.
/// - A new format description (a lens switch restarted the encoder with new parameter sets) finishes the current file and
///   starts a new segment on that keyframe, automatically.
/// - `stop()` (also called when the stream stops) finishes the file; a finished file is reported through `onSaved`.
final class MirrorRecorder: @unchecked Sendable {
    /// All callbacks arrive on the main queue.
    var onBegan: (@Sendable () -> Void)?
    var onSaved: (@Sendable (URL) -> Void)?
    var onFailed: (@Sendable (String) -> Void)?
    /// A keyframe is needed (queue overflow): the owner asks the phone for one.
    var onNeedKeyframe: (@Sendable () -> Void)?

    static let maxPending = 24
    static let directory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Movies/DroidDock", isDirectory: true)

    private let lock = NSLock()
    private var _active = false
    private var pending = 0
    private var waitKey = true          // drop everything until a keyframe (start, or after an overflow)

    private let queue = DispatchQueue(label: "droiddock.recorder", qos: .userInitiated)
    // Touched on `queue` only.
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var fileURL: URL?
    private var format: CMFormatDescription?
    private var baseTime: CFTimeInterval = 0
    private var lastPTS = CMTime.invalid
    private var wrote = 0
    private var _rotation = 0
    /// Display rotation (degrees clockwise) written as the track transform when a file starts. A later change does not
    /// touch the file being written (no segmenting for it); only a new segment picks up the current value.
    var rotation: Int { get { lock.lock(); defer { lock.unlock() }; return _rotation }
                        set { lock.lock(); _rotation = newValue; lock.unlock() } }

    var isActive: Bool { lock.lock(); defer { lock.unlock() }; return _active }

    func start() {
        lock.lock(); _active = true; waitKey = true; pending = 0; lock.unlock()
    }

    /// Ends the recording; the file is finalized asynchronously.
    func stop() {
        lock.lock()
        let was = _active
        _active = false
        lock.unlock()
        guard was else { return }
        queue.async { [self] in finishSegment() }
    }

    /// Quit path: finalizes the file and waits (at most `timeout` seconds) so the .mov is playable.
    func stopAndWait(timeout: TimeInterval = 2) {
        lock.lock(); let was = _active; _active = false; lock.unlock()
        guard was else { return }
        let sem = DispatchSemaphore(value: 0)
        queue.async { [self] in finishSegment { sem.signal() } }
        _ = sem.wait(timeout: .now() + timeout)
    }

    /// Decode-queue entry. Cheap: a flag check, then a hop.
    func offer(_ sample: CMSampleBuffer, _ fmt: CMVideoFormatDescription, _ isKey: Bool) {
        let now = CACurrentMediaTime()
        lock.lock()
        guard _active else { lock.unlock(); return }
        if waitKey && !isKey { lock.unlock(); return }
        if pending >= Self.maxPending {
            waitKey = true          // a gap: nothing until the next keyframe
            lock.unlock()
            onNeedKeyframe?()
            return
        }
        if isKey { waitKey = false }
        pending += 1
        lock.unlock()
        queue.async { [self] in
            append(sample, fmt, isKey, at: now)
            lock.lock(); pending -= 1; lock.unlock()
        }
    }

    // MARK: Queue side

    private func append(_ sample: CMSampleBuffer, _ fmt: CMVideoFormatDescription, _ isKey: Bool, at host: CFTimeInterval) {
        guard isActive else { return }
        if let f = format, !CFEqual(f, fmt) { finishSegment() }      // new parameter sets: close this file
        if writer == nil {
            guard isKey else { return }
            beginSegment(fmt, at: host)
        }
        guard let writer, let input, writer.status == .writing else {
            if let w = writer, w.status == .failed { fail(w.error?.localizedDescription ?? "Recording failed") }
            return
        }
        guard input.isReadyForMoreMediaData else {
            // The disk can't keep up: skip and resync on the next keyframe.
            lock.lock(); waitKey = true; lock.unlock()
            onNeedKeyframe?()
            return
        }
        var pts = CMTime(seconds: max(0, host - baseTime), preferredTimescale: 90_000)
        if lastPTS.isValid, pts <= lastPTS { pts = CMTimeAdd(lastPTS, CMTime(value: 1, timescale: 90_000)) }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var copy: CMSampleBuffer?
        guard CMSampleBufferCreateCopyWithNewTiming(allocator: kCFAllocatorDefault, sampleBuffer: sample,
                                                    sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                                    sampleBufferOut: &copy) == noErr, let copy else { return }
        if let arr = CMSampleBufferGetSampleAttachmentsArray(copy, createIfNecessary: true),
           CFArrayGetCount(arr) > 0 {
            let d = unsafeBitCast(CFArrayGetValueAtIndex(arr, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(d, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                                 Unmanaged.passUnretained(isKey ? kCFBooleanFalse : kCFBooleanTrue).toOpaque())
        }
        if input.append(copy) { lastPTS = pts; wrote += 1 }
        else if writer.status == .failed { fail(writer.error?.localizedDescription ?? "Recording failed") }
    }

    private func beginSegment(_ fmt: CMVideoFormatDescription, at host: CFTimeInterval) {
        do {
            try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
            let url = Self.newFileURL()
            let w = try AVAssetWriter(outputURL: url, fileType: .mov)
            let i = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: fmt)
            i.expectsMediaDataInRealTime = true
            i.transform = CGAffineTransform(rotationAngle: CGFloat(rotation) * .pi / 180)
            guard w.canAdd(i) else { throw NSError(domain: "DroidDock", code: 1, userInfo: [NSLocalizedDescriptionKey: "Can't write this video format"]) }
            w.add(i)
            guard w.startWriting() else { throw w.error ?? NSError(domain: "DroidDock", code: 2) }
            w.startSession(atSourceTime: .zero)
            writer = w; input = i; fileURL = url; format = fmt
            baseTime = host; lastPTS = .invalid; wrote = 0
            DispatchQueue.main.async { [onBegan] in onBegan?() }
        } catch {
            fail(error.localizedDescription)
        }
    }

    /// Closes the current file (if any) and reports it. Does not change `active`.
    private func finishSegment(done: (() -> Void)? = nil) {
        guard let w = writer, let i = input, let url = fileURL else { writer = nil; input = nil; format = nil; done?(); return }
        let n = wrote
        writer = nil; input = nil; fileURL = nil; format = nil
        guard w.status == .writing else { done?(); return }
        i.markAsFinished()
        w.finishWriting { [onSaved, onFailed] in
            defer { done?() }
            if w.status == .completed, n > 0 {
                DispatchQueue.main.async { onSaved?(url) }
            } else {
                try? FileManager.default.removeItem(at: url)
                if w.status == .failed { DispatchQueue.main.async { onFailed?(w.error?.localizedDescription ?? "Recording failed") } }
            }
        }
    }

    private func fail(_ message: String) {
        lock.lock(); _active = false; lock.unlock()
        let w = writer, url = fileURL
        writer = nil; input = nil; fileURL = nil; format = nil
        w?.cancelWriting()
        if let url { try? FileManager.default.removeItem(at: url) }
        DispatchQueue.main.async { [onFailed] in onFailed?(message) }
    }

    private static func newFileURL() -> URL {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HH.mm.ss"
        let base = "DroidDock_" + f.string(from: Date())
        var url = directory.appendingPathComponent(base + ".mov")
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = directory.appendingPathComponent("\(base)-\(n).mov"); n += 1
        }
        return url
    }
}
