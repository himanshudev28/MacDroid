import Foundation
import CDroidDockCore

struct CoreEvent: Sendable {
    let name: String
    let payload: Data
}

enum CoreError: Error, LocalizedError {
    case command(String)
    case start(Int32)
    case notStarted
    case badArgs

    var errorDescription: String? {
        switch self {
        case .command(let m): m
        case .start(let c): "DroidDock core failed to start (code \(c))"
        case .notStarted: "DroidDock core is not running"
        case .badArgs: "Invalid command arguments"
        }
    }
}

/// stderr logger (visible when the binary is run from a terminal).
/// Debug-build diagnostics only: release builds print nothing (messages can carry notification text and other private data).
func dlog(_ msg: @autoclosure () -> String) {
    #if DEBUG
    let f = DateFormatter(); f.dateFormat = "HH:mm:ss.SSS"
    FileHandle.standardError.write(Data("[\(f.string(from: Date()))] \(msg())\n".utf8))
    #endif
}

/// Swift side of the Rust core's C ABI (see core/include/droiddock_core.h).
/// Callbacks arrive on arbitrary core threads: copy bytes, hand off, never block.
final class Core: @unchecked Sendable {
    static let shared = Core()

    /// Single consumer: `AppModel`. Buffered, so events emitted before the consumer starts are kept.
    let events: AsyncStream<CoreEvent>
    private let eventSink: AsyncStream<CoreEvent>.Continuation

    private let lock = NSLock()
    private var pending: [UInt64: CheckedContinuation<Data, Error>] = [:]
    private var nextID: UInt64 = 1
    private var started = false
    private var _mirrorSink: (@Sendable (Bool, UnsafeRawBufferPointer) -> Void)?
    private var _audioSink: (@Sendable (Bool, UnsafeRawBufferPointer) -> Void)?

    /// `(keyframe, annexB access unit)`; called synchronously on a core thread. The buffer is only valid
    /// for the duration of the call (it points into the core's frame): copy once or drop.
    var mirrorSink: (@Sendable (Bool, UnsafeRawBufferPointer) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _mirrorSink }
        set { lock.lock(); _mirrorSink = newValue; lock.unlock() }
    }
    /// `(resetAfterGap, interleaved s16le PCM)`; same lifetime rule as `mirrorSink`.
    var audioSink: (@Sendable (Bool, UnsafeRawBufferPointer) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _audioSink }
        set { lock.lock(); _audioSink = newValue; lock.unlock() }
    }

    // ── BLE fallback (docs/BLE-NEARBY-DESIGN.md §8) ──
    private var _bleTxSink: (@Sendable (Data) -> Void)?
    private var _bleControlSink: (@Sendable (Data) -> Void)?
    /// Stream 3 (`DD_STREAM_BLE_TX`): `[16-byte conn UUID][L2 frame]`, copied, called on a core thread. Must not block.
    var bleTxSink: (@Sendable (Data) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _bleTxSink }
        set { lock.lock(); _bleTxSink = newValue; lock.unlock() }
    }
    /// `ble-control` event JSON, intercepted before the AsyncStream: it never reaches AppModel.
    var bleControlSink: (@Sendable (Data) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _bleControlSink }
        set { lock.lock(); _bleControlSink = newValue; lock.unlock() }
    }

    var isStarted: Bool { lock.lock(); defer { lock.unlock() }; return started }

    private init() {
        var cont: AsyncStream<CoreEvent>.Continuation!
        events = AsyncStream(CoreEvent.self, bufferingPolicy: .unbounded) { cont = $0 }
        eventSink = cont
    }

    /// `~/Library/Application Support/com.droiddock.mac`, or `$DD_DATA_DIR` (dev override).
    static func defaultDataDir() -> URL {
        if let o = ProcessInfo.processInfo.environment["DD_DATA_DIR"], !o.isEmpty {
            return URL(fileURLWithPath: o, isDirectory: true)
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("com.droiddock.mac", isDirectory: true)
    }

    func start(dataDir: URL = Core.defaultDataDir()) throws {
        lock.lock()
        if started { lock.unlock(); return }
        lock.unlock()
        try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "dev"
        let dev = ProcessInfo.processInfo.environment["DD_DATA_DIR"] != nil || Bundle.main.bundleIdentifier == nil
        let cfg: [String: Any] = [
            "dataDir": dataDir.path,
            "identifier": "com.droiddock.mac",
            "version": version,
            "dev": dev,
        ]
        let json = String(decoding: try JSONSerialization.data(withJSONObject: cfg), as: UTF8.self)
        let cbs = dd_callbacks(
            ctx: Unmanaged.passUnretained(self).toOpaque(),
            on_event: coreOnEvent,
            on_frame: coreOnFrame,
            on_result: coreOnResult
        )
        let rc = json.withCString { dd_core_start($0, cbs) }
        guard rc == 0 else { throw CoreError.start(rc) }
        lock.lock(); started = true; lock.unlock()
        dlog("core started (abi \(dd_abi_version()), data dir \(dataDir.path))")
    }

    func shutdown() {
        guard isStarted else { return }
        dd_core_shutdown()
    }

    // MARK: Invoke

    func invoke<T: Decodable>(_ cmd: String, _ args: [String: Any] = [:], as: T.Type = T.self) async throws -> T {
        let data = try await raw(cmd, args, body: nil)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            dlog("decode \(cmd) as \(T.self) failed: \(error)")
            throw error
        }
    }

    func invoke(_ cmd: String, _ args: [String: Any] = [:]) async throws {
        _ = try await raw(cmd, args, body: nil)
    }

    /// For `mirror_save_capture {ext}`: returns the saved path.
    func invokeBytes(_ cmd: String, _ args: [String: Any], body: Data) async throws -> String {
        let data = try await raw(cmd, args, body: body)
        return try JSONDecoder().decode(String.self, from: data)
    }

    func raw(_ cmd: String, _ args: [String: Any], body: Data?) async throws -> Data {
        guard isStarted else { throw CoreError.notStarted }
        let norm = Core.normalize(args)
        guard JSONSerialization.isValidJSONObject(norm),
              let argData = try? JSONSerialization.data(withJSONObject: norm) else { throw CoreError.badArgs }
        let argStr = String(decoding: argData, as: UTF8.self)
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            lock.lock()
            let id = nextID; nextID += 1
            pending[id] = cont      // registered before the call: the result can arrive instantly
            lock.unlock()
            cmd.withCString { c in
                argStr.withCString { a in
                    if let body {
                        body.withUnsafeBytes { buf in
                            dd_core_invoke_bytes(c, a, buf.bindMemory(to: UInt8.self).baseAddress, buf.count, id)
                        }
                    } else {
                        dd_core_invoke(c, a, id)
                    }
                }
            }
        }
    }

    /// Replace JSONValue / Optional / nested containers with JSONSerialization-friendly values.
    static func normalize(_ v: Any) -> Any {
        switch v {
        case let j as JSONValue: return j.any
        case let d as [String: Any]: return d.mapValues { normalize($0) }
        case let a as [Any]: return a.map { normalize($0) }
        case Optional<Any>.none: return NSNull()
        default:
            let m = Mirror(reflecting: v)
            if m.displayStyle == .optional {
                if let c = m.children.first { return normalize(c.value) }
                return NSNull()
            }
            return v
        }
    }

    // MARK: Callback targets (any thread)

    fileprivate func handleResult(_ id: UInt64, ok: Bool, json: Data) {
        lock.lock(); let cont = pending.removeValue(forKey: id); lock.unlock()
        guard let cont else { return }
        if ok {
            cont.resume(returning: json)
        } else {
            let msg = (try? JSONDecoder().decode(String.self, from: json)) ?? String(decoding: json, as: UTF8.self)
            cont.resume(throwing: CoreError.command(msg))
        }
    }

    // High-rate progress events are latest-wins per key and flushed in one batch every ~250 ms. Anything else flushes
    // the pending ones first (order is preserved), and terminal payloads (`"done":true`) are never held back.
    private static let coalescible: Set<String> = [
        "transfer-progress", "quickshare-send-progress", "quickshare-progress", "photosync-progress", "link-quality",
    ]
    private static let idMarker = Data("\"transferId\":".utf8)
    private static let doneMarkers = [Data("\"done\":true".utf8), Data("\"done\": true".utf8)]
    private let slotLock = NSLock()
    private var slots: [String: CoreEvent] = [:]
    private var slotOrder: [String] = []
    private var flushArmed = false
    private let flushQueue = DispatchQueue(label: "droiddock.event-coalesce", qos: .utility)

    private static func coalesceKey(_ name: String, _ payload: Data) -> String? {
        guard coalescible.contains(name) else { return nil }
        if doneMarkers.contains(where: { payload.range(of: $0) != nil }) { return nil }
        guard let r = payload.range(of: idMarker) else { return name }
        var end = r.upperBound
        while end < payload.endIndex, payload[end] >= 0x30, payload[end] <= 0x39 { end = payload.index(after: end) }
        return name + "#" + String(decoding: payload[r.upperBound..<end], as: UTF8.self)
    }

    fileprivate func handleEvent(_ name: String, _ payload: Data) {
        if name == "ble-control" {
            lock.lock(); let sink = _bleControlSink; lock.unlock()
            sink?(payload)
            return
        }
        let ev = CoreEvent(name: name, payload: payload)
        if let key = Self.coalesceKey(name, payload) {
            slotLock.lock()
            if slots.updateValue(ev, forKey: key) == nil { slotOrder.append(key) }
            let arm = !flushArmed
            flushArmed = true
            slotLock.unlock()
            if arm {
                let t = DispatchWorkItem { [weak self] in self?.flushSlots() }
                flushQueue.asyncAfter(deadline: .now() + .milliseconds(250), execute: t)
            }
        } else {
            slotLock.lock()
            drainSlotsLocked()
            eventSink.yield(ev)
            slotLock.unlock()
        }
    }

    private func flushSlots() {
        slotLock.lock()
        drainSlotsLocked()
        slotLock.unlock()
    }

    private func drainSlotsLocked() {
        for k in slotOrder { if let e = slots[k] { eventSink.yield(e) } }
        slots.removeAll(keepingCapacity: true)
        slotOrder.removeAll(keepingCapacity: true)
        flushArmed = false
    }

    /// `bytes` = `[flags][payload]`, borrowed from the core for this call only: no `Data`, no copy here.
    fileprivate func handleFrame(_ stream: UInt32, _ bytes: UnsafeRawBufferPointer) {
        if stream == UInt32(DD_STREAM_BLE_TX) {
            lock.lock(); let sink = _bleTxSink; lock.unlock()
            if bytes.count > 16 { sink?(Data(bytes)) }
            return
        }
        guard bytes.count >= 2 else { return }
        let flag = (bytes[0] & 1) == 1
        lock.lock()
        let sink = stream == 1 ? _mirrorSink : (stream == 2 ? _audioSink : nil)
        lock.unlock()
        sink?(flag, UnsafeRawBufferPointer(rebasing: bytes[1...]))
    }
}

// MARK: - C callbacks (file level; no captures)

private func coreOnEvent(_ ctx: UnsafeMutableRawPointer?, _ name: UnsafePointer<CChar>?, _ payload: UnsafePointer<CChar>?) {
    guard let ctx, let name else { return }
    let core = Unmanaged<Core>.fromOpaque(ctx).takeUnretainedValue()
    let p = payload.map { Data(bytes: $0, count: strlen($0)) } ?? Data("null".utf8)
    core.handleEvent(String(cString: name), p)
}

private func coreOnFrame(_ ctx: UnsafeMutableRawPointer?, _ stream: UInt32, _ data: UnsafePointer<UInt8>?, _ len: Int) {
    guard let ctx, let data, len > 0 else { return }
    let core = Unmanaged<Core>.fromOpaque(ctx).takeUnretainedValue()
    core.handleFrame(stream, UnsafeRawBufferPointer(start: data, count: len))
}

private func coreOnResult(_ ctx: UnsafeMutableRawPointer?, _ id: UInt64, _ ok: Int32, _ json: UnsafePointer<CChar>?) {
    guard let ctx else { return }
    let core = Unmanaged<Core>.fromOpaque(ctx).takeUnretainedValue()
    let d = json.map { Data(bytes: $0, count: strlen($0)) } ?? Data("null".utf8)
    core.handleResult(id, ok: ok != 0, json: d)
}


/// Runs submitted jobs one at a time, in submission order. The core handles every invoke as its own task, so
/// callers that need ordering (mirror input, rapid setting changes) go through one of these.
final class SerialLane: @unchecked Sendable {
    private let continuation: AsyncStream<@Sendable () async -> Void>.Continuation
    init() {
        let (stream, cont) = AsyncStream.makeStream(of: (@Sendable () async -> Void).self)
        continuation = cont
        Task.detached { for await job in stream { await job() } }
    }
    func submit(_ job: @escaping @Sendable () async -> Void) { continuation.yield(job) }
}
