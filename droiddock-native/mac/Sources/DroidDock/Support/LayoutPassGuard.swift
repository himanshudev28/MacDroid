import AppKit
import ObjectiveC
import OSLog

/// Survives AppKit's "more Update Constraints in Window passes than there are views in the window" exception.
///
/// AppKit throws it when a window's display cycle never settles (a SwiftUI view keeps invalidating layout). The message
/// itself says further markings can be ignored, i.e. AppKit can carry on, but the display-cycle observer routes every
/// exception to `-[NSApplication _crashOnException:]`, which traps regardless of `NSApplicationCrashOnExceptions`
/// (verified with a QA throw). DroidDock 3.7.0 crash-looped on it. This wraps that one method: exactly this exception is
/// logged (with the last screen from NavTrail) and the run loop continues; every other exception still crashes as before.
enum LayoutPassGuard {
    private static let log = Logger(subsystem: "com.droiddock.mac", category: "layout-guard")
    private static var installed = false

    static func isLayoutPassLimit(_ e: NSException) -> Bool {
        // Match on the message only: AppKit's exception name for this is not NSInternalInconsistencyException (a name
        // check let the real one through in 3.7.0's first guard build). The message is specific to this limit.
        guard let r = e.reason else { return false }
        return r.contains("more Update Constraints in Window passes than there are views")
            || r.contains("more Layout Window passes than there are views")
    }

    @MainActor static func install() {
        guard !installed else { return }
        let sel = NSSelectorFromString("_crashOnException:")
        guard let method = class_getInstanceMethod(NSApplication.self, sel) else {
            log.notice("layout guard: _crashOnException: not found, not installed")
            return
        }
        installed = true
        typealias Original = @convention(c) (AnyObject, Selector, NSException) -> Void
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let block: @convention(block) (AnyObject, NSException) -> Void = { app, exception in
            if isLayoutPassLimit(exception) {
                log.error("survived AppKit layout-pass limit: \(exception.reason ?? "", privacy: .public)")
                NavTrail.log("layout-pass limit survived")
                return
            }
            original(app, sel, exception)
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}
