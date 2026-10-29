import AppKit

/// Force Touch trackpad feedback. Fire at the *causal* moment only (snap, commit), never per frame.
/// Silently does nothing on Macs without a Force Touch trackpad or when the user disabled it.
///
/// - `alignment`: a slider/seek bar snapping to a detent, or a value crossing 0 % / 100 %.
/// - `levelChange`: a stepped value changing level (volume steps, segment switched by drag).
/// - `generic`: commit of a drag (file dropped onto the phone card).
@MainActor
enum Haptics {
    static func alignment() { perform(.alignment) }
    static func levelChange() { perform(.levelChange) }
    static func generic() { perform(.generic) }

    private static var last: TimeInterval = 0

    private static func perform(_ pattern: NSHapticFeedbackManager.FeedbackPattern) {
        // Throttle: a scrub can cross many detents per frame; one tick per 50 ms is plenty.
        let now = ProcessInfo.processInfo.systemUptime
        guard now - last > 0.05 else { return }
        last = now
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }
}
