import Foundation

/// Derived state for the "hidden by Android" and live-progress notification features.
extension PhoneNotification {
    /// Android replaced the content (OTPs and other sensitive text on Android 15+). Never shown as text, never scanned for a code.
    var isHiddenByAndroid: Bool { redacted == true && type != "call" }

    var hasProgress: Bool { (progressMax ?? 0) > 0 || progressIndeterminate == true }

    /// A running timer or stopwatch.
    var isChronometer: Bool { chrono == true && chronoBase != nil }

    /// An ongoing progress, timer or stopwatch notification: gets a live card in the menu-bar panel.
    var isLive: Bool {
        type != "call" && ongoing == true && (hasProgress || isChronometer || category == "progress" || category == "stopwatch")
    }

    /// 0...1, nil when there is no determinate progress.
    var progressFraction: Double? {
        guard let max = progressMax, max > 0 else { return nil }
        return Swift.min(1, Swift.max(0, (progress ?? 0) / max))
    }

    /// Seconds to show for a timer at `date`: a countdown clamps at 0, a stopwatch counts up.
    func chronoSeconds(at date: Date) -> Int {
        guard let base = chronoBase else { return 0 }
        let t = base / 1000
        let d = chronoDown == true ? t - date.timeIntervalSince1970 : date.timeIntervalSince1970 - t
        return Swift.max(0, Int(d))
    }

    static func clock(_ seconds: Int) -> String {
        seconds >= 3600
            ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
