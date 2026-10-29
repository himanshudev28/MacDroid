import AppKit
import Darwin
import Foundation
import MetricKit

// Async-signal-safe state: plain C buffers filled once at install time, nothing allocated in the handler.
nonisolated(unsafe) private var markerPathC: UnsafeMutablePointer<CChar>?
private let handledSignals: [Int32] = [SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE, SIGTRAP]

/// Appends `n` in decimal to `buf` at `off` (no allocation, no locale).
private func appendInt(_ buf: UnsafeMutablePointer<UInt8>, _ off: inout Int, _ n: Int) {
    var tmp: (UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8, UInt8) = (0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
    var v = n < 0 ? 0 : n
    var count = 0
    withUnsafeMutableBytes(of: &tmp) { t in
        repeat { t[count] = UInt8(48 + v % 10); v /= 10; count += 1 } while v > 0 && count < 20
        var i = count - 1
        while i >= 0 { buf[off] = t[i]; off += 1; i -= 1 }
    }
}

private func appendStr(_ buf: UnsafeMutablePointer<UInt8>, _ off: inout Int, _ s: StaticString) {
    s.withUTF8Buffer { for b in $0 { buf[off] = b; off += 1 } }
}

/// Fatal-signal handler: write a minimal marker with open/write/close, then re-raise with the default action.
private func fatalSignalHandler(_ sig: Int32) {
    if let path = markerPathC {
        let fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        if fd >= 0 {
            let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: 128)   // malloc is not strictly safe, but we are about to die
            var off = 0
            appendStr(buf, &off, "DroidDock crashed\nsignal ")
            appendInt(buf, &off, Int(sig))
            appendStr(buf, &off, "\ntime ")
            appendInt(buf, &off, Int(time(nil)))
            appendStr(buf, &off, "\npid ")
            appendInt(buf, &off, Int(getpid()))
            appendStr(buf, &off, "\n")
            _ = write(fd, buf, off)
            close(fd)
        }
    }
    signal(sig, SIG_DFL)
    raise(sig)
}

/// Local-only crash reporting into `~/Library/Logs/DroidDock/`. Nothing ever leaves the Mac.
/// - fatal signals leave `last-crash.marker`; uncaught ObjC exceptions leave `exception-<ts>.txt` + marker
/// - MetricKit diagnostic payloads are saved as `diagnostic-<ts>.json` (delivered by the system on a later launch)
/// - on the next launch a marker triggers a toast; `revealInFinder()` opens the folder.
final class CrashReporter: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let shared = CrashReporter()

    static var directory: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/DroidDock", isDirectory: true)
    }
    private static var markerURL: URL { directory.appendingPathComponent("last-crash.marker") }

    private var installed = false

    /// Install as early as possible in launch.
    func install() {
        guard !installed else { return }
        installed = true
        try? FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        markerPathC = strdup(Self.markerURL.path)

        NSSetUncaughtExceptionHandler { ex in
            let text = "Uncaught exception: \(ex.name.rawValue)\n\(ex.reason ?? "")\n\n"
                + ex.callStackSymbols.joined(separator: "\n") + "\n"
            CrashReporter.writeReport(name: "exception", text: text)
            try? text.write(to: CrashReporter.markerURL, atomically: true, encoding: .utf8)
        }
        for s in handledSignals {
            var sa = sigaction()
            sa.__sigaction_u.__sa_handler = fatalSignalHandler
            sigemptyset(&sa.sa_mask)
            sa.sa_flags = 0
            sigaction(s, &sa, nil)
        }
        MXMetricManager.shared.add(self)
        dlog("crash reporter: installed, logs in \(Self.directory.path)")

        #if DEBUG
        if ProcessInfo.processInfo.environment["DD_TEST_CRASH_MARKER"] == "1" {
            // Simulates what the signal handler does, without crashing.
            let text = "DroidDock crashed\nsignal 0 (test)\ntime \(Int(Date().timeIntervalSince1970))\n"
            try? text.write(to: Self.markerURL, atomically: true, encoding: .utf8)
            dlog("crash reporter: wrote TEST marker")
        }
        #endif
    }

    /// Call after `AppModel` is up (needs the toast). Reports a crash left by the previous run once.
    @MainActor
    func reportPreviousCrashIfAny() {
        let fm = FileManager.default
        guard fm.fileExists(atPath: Self.markerURL.path) else { return }
        let stamp = Self.stamp()
        let kept = Self.directory.appendingPathComponent("crash-\(stamp).txt")
        try? fm.moveItem(at: Self.markerURL, to: kept)
        dlog("crash reporter: previous run crashed, report \(kept.lastPathComponent)")
        AppModel.shared.toast(.bad, tr("DroidDock closed unexpectedly last time. Settings › Crash reports can show the report in Finder."))
    }

    // MARK: Files

    static func reportFiles() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return all.filter { $0.lastPathComponent != "last-crash.marker" }
            .sorted { ($0.modDate) > ($1.modDate) }
    }

    @MainActor static func revealInFinder() {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let newest = reportFiles().first { NSWorkspace.shared.activateFileViewerSelecting([newest]) }
        else { NSWorkspace.shared.open(directory) }
    }

    @MainActor static func clear() {
        for f in reportFiles() { try? FileManager.default.removeItem(at: f) }
        try? FileManager.default.removeItem(at: markerURL)
    }

    private static func stamp() -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd-HHmmss"
        return f.string(from: Date())
    }

    private static func writeReport(name: String, text: String) {
        let url = directory.appendingPathComponent("\(name)-\(stamp()).txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    // MARK: MXMetricManagerSubscriber

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for p in payloads {
            let url = Self.directory.appendingPathComponent("diagnostic-\(Self.stamp())-\(UUID().uuidString.prefix(4)).json")
            try? p.jsonRepresentation().write(to: url)
            dlog("crash reporter: saved MetricKit diagnostic \(url.lastPathComponent)")
        }
    }

}

private extension URL {
    var modDate: Date { (try? resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
}
