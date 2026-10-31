import AppKit
import Observation
import SwiftUI

/// Is anyone looking? True while a regular window is on screen (not occluded/miniaturized/closed), the menu-bar
/// panel is open, or the status widget is shown. Views gate timers/animations on it, and the core is told
/// (`app_set_active`, debounced) so it can slow its probes when the app is menu-bar-only.
@Observable @MainActor
final class UIActivity {
    static let shared = UIActivity()

    /// A titled window is visible on screen.
    private(set) var windowVisible = true
    /// The `MenuBarExtra` panel is showing.
    private(set) var panelOpen = false
    /// The floating status widget is showing (it renders live battery / now playing).
    private(set) var widgetShown = false
    /// The floating call pop-up is showing (its call timer must tick even with the main window hidden).
    private(set) var callOverlayShown = false
    /// What timers and animations should follow.
    var visible: Bool { windowVisible || panelOpen || widgetShown || callOverlayShown }
    /// What the core should follow.
    var appActive: Bool { windowVisible || panelOpen || widgetShown }

    @ObservationIgnored private var started = false
    @ObservationIgnored private var lastSent: Bool?
    @ObservationIgnored private var sendTask: Task<Void, Never>?
    @ObservationIgnored private var windowWasVisible = true
    @ObservationIgnored private var activeChain: Task<Void, Never>?
    @ObservationIgnored private var visibleWaiters: [Int: CheckedContinuation<Void, Never>] = [:]
    @ObservationIgnored private var nextWaiterID = 0
    @ObservationIgnored private var purgeTask: Task<Void, Never>?

    func start() {
        guard !started else { return }
        started = true
        let nc = NotificationCenter.default
        let names: [Notification.Name] = [
            NSWindow.didChangeOcclusionStateNotification, NSWindow.didMiniaturizeNotification,
            NSWindow.didDeminiaturizeNotification, NSWindow.didBecomeKeyNotification,
            NSApplication.didHideNotification, NSApplication.didUnhideNotification,
            NSApplication.didBecomeActiveNotification, NSApplication.didResignActiveNotification,
        ]
        for n in names {
            nc.addObserver(forName: n, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.recompute() }
            }
        }
        // willClose fires before the window leaves `NSApp.windows`/is hidden; look again next turn.
        nc.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.recompute() }
        }
        recompute()
    }

    /// Returns at once while a main window is on screen; otherwise parks (no timer) until one is, or the task is cancelled.
    /// `orderOut`/occlusion do not fire `onDisappear`, so polling `.task` loops call this each turn.
    func waitUntilWindowVisible() async {
        guard !windowVisible else { return }
        nextWaiterID += 1
        let id = nextWaiterID
        await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                if windowVisible || Task.isCancelled { c.resume() } else { visibleWaiters[id] = c }
            }
        } onCancel: {
            Task { @MainActor in UIActivity.shared.visibleWaiters.removeValue(forKey: id)?.resume() }
        }
    }

    /// Runs `body` every `interval` while the main window is on screen, and not at all while it is hidden or covered.
    func pollWhileWindowVisible(every interval: Duration, tolerance: Duration, _ body: @MainActor () async -> Void) async {
        while !Task.isCancelled {
            await waitUntilWindowVisible()
            if Task.isCancelled { return }
            await body()
            try? await Task.sleep(for: interval, tolerance: tolerance)
        }
    }

    func setPanelOpen(_ open: Bool) {
        guard panelOpen != open else { return }
        panelOpen = open
        publish()
    }

    func setCallOverlayShown(_ shown: Bool) {
        guard callOverlayShown != shown else { return }
        callOverlayShown = shown
    }

    func setWidgetShown(_ shown: Bool) {
        guard widgetShown != shown else { return }
        widgetShown = shown
        publish()
    }

    private func recompute() {
        // Only occlusion, miniaturize, close and app-hide count. A fully visible window beside another app (second
        // monitor dashboard, Transfers page) is still being looked at even though the app is inactive.
        let any = !NSApp.isHidden && NSApp.windows.contains {
            !($0 is NSPanel) && $0.styleMask.contains(.titled) && $0.isVisible && !$0.isMiniaturized
                && $0.occlusionState.contains(.visible)
        }
        if any != windowVisible { windowVisible = any }
        if any { let w = visibleWaiters; visibleWaiters = [:]; w.values.forEach { $0.resume() } }
        if any != windowWasVisible {
            windowWasVisible = any
            purgeTask?.cancel()
            if any { ShellState.shared.restoreWindowAssets() }
            else {
                // Not for a brief cover/Space switch: only when it stays gone.
                purgeTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(30), tolerance: .seconds(10))
                    guard !Task.isCancelled, let self, !self.windowVisible else { return }
                    Self.purgeWindowCaches()
                }
            }
        }
        publish()
    }

    /// Main window gone (closed to the menu bar, hidden, fully covered): free image caches that only it needs.
    private static func purgeWindowCaches() {
        ShellState.shared.purgeWindowAssets()
        PhotoStore.purgeAll()
    }

    /// Calls reach the core in the order they were made (each waits for the previous), so a late "inactive" can never
    /// overtake the "active" that followed it.
    private func sendActive(_ on: Bool) {
        let prev = activeChain
        activeChain = Task {
            await prev?.value
            try? await Core.shared.setAppActive(on)
        }
    }

    private func publish() {
        let want = appActive
        guard want != lastSent else { return }
        sendTask?.cancel()
        if want {
            // Becoming active: tell the core right away so the UI is fresh.
            lastSent = true
            sendActive(true)
        } else {
            // Going inactive: debounce, so flicking between windows/Spaces doesn't flap the core.
            sendTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(2), tolerance: .milliseconds(500))
                guard !Task.isCancelled, let self, !self.appActive else { return }
                self.lastSent = false
                self.sendActive(false)
            }
        }
    }
}

extension UIActivity {
    /// `TimelineView` period that goes quiet (once an hour) while nothing is on screen.
    func period(_ seconds: Double) -> Double { visible ? seconds : 3600 }
}

/// Debounces the reload that screens do on `.task(id: model.isConnected)`. The first load is immediate; later ones
/// wait 1.5 s (a flapping link cancels the task, so nothing loads). Callers only run `ready()` from a task keyed on
/// the connection state, so any later call is a reconnect and always reloads: the phone may have changed meanwhile.
@MainActor
final class ReloadGate {
    private var lastLoad: Date?
    func ready() async -> Bool {
        if lastLoad != nil {
            try? await Task.sleep(for: .milliseconds(1500), tolerance: .milliseconds(300))
            if Task.isCancelled { return false }
        }
        lastLoad = Date()
        return true
    }
}
