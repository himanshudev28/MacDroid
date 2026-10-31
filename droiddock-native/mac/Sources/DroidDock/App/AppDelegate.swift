import AppKit
import CoreServices

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    /// Set by the menu-bar "Quit DroidDock": skips hold-to-quit and keep-running.
    static var forceQuit = false
    static let keepRunningKey = "droiddock.keepRunningOnQuit"

    /// Kept alive for the process lifetime: SIGTERM / SIGINT / SIGHUP become an ordinary quit so
    /// `applicationWillTerminate` (the full service cleanup) runs. A plain `kill <pid>` used to skip it.
    private var signalSources: [DispatchSourceSignal] = []
    private var cleanedUp = false

    private func installSignalHandlers() {
        for sig in [SIGTERM, SIGINT, SIGHUP] {
            // An empty handler, not SIG_IGN: an ignored signal stays ignored across exec, so every child
            // we spawn (adb, scrcpy) would shrug off the SIGTERM we send them at quit. A handler is reset
            // to the default in a child, and the dispatch source below still sees the signal.
            signal(sig) { _ in }
            let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            src.setEventHandler {
                dlog("signal \(sig): quitting")
                // Safety net: if terminate is blocked (a modal panel), still end the process after the
                // bounded core cleanup (<= ~8 s).
                DispatchQueue.global().asyncAfter(deadline: .now() + 12) { _exit(0) }
                AppDelegate.quitNow()
            }
            src.resume()
            signalSources.append(src)
        }
    }

    static func quitNow() {
        forceQuit = true
        NSApp.terminate(nil)
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        CrashReporter.shared.install()
        installSignalHandlers()
        NSAppleEventManager.shared().setEventHandler(
            self, andSelector: #selector(handleGetURL(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if DEBUG || DD_BENCH
        if CommandBarSelfTest.runIfRequested() { return }   // DD_SELFTEST_CMDBAR=1
        #endif
        if DDSelfTest.runIfRequested() { return }   // DD_SELFTEST=mirror|audio|all (WP4)
        AppModel.shared.start()
        #if DEBUG
        // Test hook: quit through the app's own terminate path after N seconds.
        if let v = ProcessInfo.processInfo.environment["DD_TEST_QUIT_AFTER"], let secs = Double(v) {
            DispatchQueue.main.asyncAfter(deadline: .now() + secs) { AppDelegate.quitNow() }
        }
        #endif
        #if DEBUG
        DebugWindowProbe.runIfRequested()
        CommandBarSelfTest.demoIfRequested()
        #endif
        DockPolicy.apply()
        ShellRouter.install()
        MenuBarController.shared.install()
        MirrorSession.shared.install()
        NotificationService.shared.configure()
        QuitHold.shared.installMonitor()
        HotKeys.shared.start()
        CallOverlayController.shared.install()
        // A MenuBarExtra scene can keep the Window scene from opening itself at launch.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400), tolerance: .milliseconds(100))
            if DockPolicy.opensWindowAtLaunch || !UserDefaults.standard.bool(forKey: OnboardingState.doneKey) {
                if WindowControl.mainWindow == nil { WindowControl.showMain() }
            } else {
                WindowControl.closeAllWindows()   // stay in the menu bar; Dock click or the menu-bar panel reopens it
            }
        }
        // Non-critical work waits for the first frame: file IO, the widget panel, the Now Playing bridge
        // (MediaPlayer, only needed once something plays) and window-visibility tracking.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600), tolerance: .milliseconds(200))
            UIActivity.shared.start()
            StatusWidgetController.shared.install()
            NowPlayingBridge.shared.startWhenNeeded()
            CrashReporter.shared.reportPreviousCrashIfAny()
        }
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if Self.forceQuit { return .terminateNow }
        // Fallback when the key monitor didn't see the shortcut (menu key equivalent).
        if QuitHold.enabled, !QuitHold.shared.satisfied,
           let e = NSApp.currentEvent, e.type == .keyDown,
           e.charactersIgnoringModifiers?.lowercased() == "q" {
            QuitHold.shared.begin()
            return .terminateCancel
        }
        // Feature #6: keep running in the menu bar.
        // A system-initiated quit (logout, restart, shutdown, macOS update) carries a quit-reason attribute
        // ('why?'); cancelling it would block the restart. Only a user quit is turned into "hide".
        if NSAppleEventManager.shared().currentAppleEvent?.attributeDescriptor(forKeyword: AEKeyword(0x7768_793F)) != nil {
            return .terminateNow
        }
        if UserDefaults.standard.bool(forKey: Self.keepRunningKey) {
            WindowControl.closeAllWindows()
            NSApp.setActivationPolicy(.accessory)
            return .terminateCancel
        }
        return .terminateNow
    }

    func applicationWillTerminate(_ notification: Notification) {
        guard !cleanedUp else { return }
        cleanedUp = true
        NowPlayingBridge.shared.shutdown()
        Core.shared.shutdown()
    }

    func applicationDockMenu(_ sender: NSApplication) -> NSMenu? { DockMenu.make() }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        WindowControl.showMain()
        return false
    }

    @objc private func handleGetURL(_ event: NSAppleEventDescriptor, withReplyEvent reply: NSAppleEventDescriptor) {
        guard let s = event.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))?.stringValue else { return }
        dlog("open url: \(s)")
        AppModel.shared.openURL(s)
    }
}
