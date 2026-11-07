import Foundation

/// Verification-code settings (UserDefaults; bind with `@AppStorage(OTPPrefs.showCopyActionKey)` etc.).
enum OTPPrefs {
    static let showCopyActionKey = "otpShowCopyAction"
    static let autoCopyKey = "otpAutoCopy"
    static let clearSecondsKey = "otpClearSeconds"
    static let scriptingAllowSendKey = "scriptingAllowSend"

    static let showCopyActionDefault = true
    static let autoCopyDefault = false
    static let clearSecondsDefault = 60          // 0 = never
    static let clearSecondsChoices = [30, 60, 120, 0]

    private static func bool(_ key: String, _ def: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) == nil ? def : UserDefaults.standard.bool(forKey: key)
    }
    static var showCopyAction: Bool { bool(showCopyActionKey, showCopyActionDefault) }
    static var autoCopy: Bool { bool(autoCopyKey, autoCopyDefault) }
    static var clearSeconds: Int {
        UserDefaults.standard.object(forKey: clearSecondsKey) == nil ? clearSecondsDefault : UserDefaults.standard.integer(forKey: clearSecondsKey)
    }

    /// Auto-copy applies only to live notifications, never a replayed backlog. `time` is the phone's epoch milliseconds.
    static let liveWindow: TimeInterval = 60
    static func isLive(_ time: Double?, window: TimeInterval = liveWindow, now: Date = Date()) -> Bool {
        guard let t = time, t > 0 else { return false }
        let secs = t > 1e11 ? t / 1000 : t
        return abs(now.timeIntervalSince1970 - secs) <= window
    }
}
