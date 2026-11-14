import Foundation

/// A small in-memory ring of recent link/health log lines for the support report.
///
/// Never persisted and never sent anywhere. Lines are redacted *on write*, so
/// nothing sensitive is ever held in memory to be leaked later. Callers should
/// still pass only event descriptions (never notification text, clipboard
/// content, message bodies or numbers); redaction is the backstop.
final class DiagLog: @unchecked Sendable {
    static let shared = DiagLog()
    static let capacity = 200

    private let lock = NSLock()
    private var lines: [String] = []
    private static let clock: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }()

    func add(_ message: String) {
        lock.lock(); defer { lock.unlock() }
        let line = "\(Self.clock.string(from: Date())) \(Self.redact(message))"
        lines.append(line)
        if lines.count > Self.capacity { lines.removeFirst(lines.count - Self.capacity) }
    }

    func recent(_ n: Int = 60) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return Array(lines.suffix(n))
    }

    // MARK: Redaction

    private static let rules: [(NSRegularExpression, String)] = {
        let specs: [(String, String)] = [
            // key=value secrets, in any casing
            (#"(?i)\b(token|pin|secret|password|passcode|otp|code|key|authorization)\b["']?\s*[=:]\s*\S+"#, "$1=[redacted]"),
            (#"(?i)\b(bearer|token)\s+\S+"#, "$1 [redacted]"),
            // adb mDNS names and serials embedded in tool output
            (#"adb-[\w.-]+"#, "[redacted]"),
            // colon-separated hex (TLS fingerprints / pins)
            (#"(?i)\b(?:[0-9a-f]{2}:){7,}[0-9a-f]{2}\b"#, "[redacted]"),
            // e-mail addresses
            (#"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"#, "[redacted]"),
            // long opaque strings: tokens, base64, hashes
            (#"[A-Za-z0-9+/_=-]{24,}"#, "[redacted]"),
            // phone numbers (7+ digits with common separators)
            (#"\+?\d[\d ()./-]{5,}\d"#, "[number]"),
        ]
        return specs.compactMap { p, t in (try? NSRegularExpression(pattern: p)).map { ($0, t) } }
    }()

    static func redact(_ s: String) -> String {
        var out = s.replacingOccurrences(of: "\n", with: " ")
        for (re, template) in rules {
            out = re.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: template)
        }
        return String(out.prefix(300))
    }
}
