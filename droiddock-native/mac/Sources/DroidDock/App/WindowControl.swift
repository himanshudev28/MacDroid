import AppKit

/// "Hide Dock icon" (`droiddock.hideDockIcon`) and "Always open window at launch" (`droiddock.openWindowAtLaunch`).
/// The menu-bar item always exists (a `MenuBarExtra` scene), so hiding the Dock icon never strands the user: its panel
/// opens the window and Settings.
enum DockPolicy {
    static let hideKey = "droiddock.hideDockIcon"
    static let openAtLaunchKey = "droiddock.openWindowAtLaunch"
    static var hidesDockIcon: Bool { UserDefaults.standard.bool(forKey: hideKey) }
    static var opensWindowAtLaunch: Bool { UserDefaults.standard.object(forKey: openAtLaunchKey) as? Bool ?? true }

    @MainActor static func apply() {
        let want: NSApplication.ActivationPolicy = hidesDockIcon ? .accessory : .regular
        guard NSApp.activationPolicy() != want else { return }
        NSApp.setActivationPolicy(want)
        // Switching policy deactivates the app; bring a visible window back to the front.
        if WindowControl.mainWindow?.isVisible == true { NSApp.activate() }
    }
}

/// Show/hide the main window and flip between Dock-visible (.regular) and menu-bar-only (.accessory).
@MainActor
enum WindowControl {
    /// Installed by the root view (SwiftUI `openWindow`), used when the NSWindow no longer exists.
    static var openMain: (() -> Void)?

    static var mainWindow: NSWindow? {
        NSApp.windows.first { !($0 is NSPanel) && $0.styleMask.contains(.titled) && $0.canBecomeMain }
    }

    static func showMain() {
        if !DockPolicy.hidesDockIcon, NSApp.activationPolicy() != .regular { NSApp.setActivationPolicy(.regular) }
        if let w = mainWindow {
            if w.isMiniaturized { w.deminiaturize(nil) }
            w.makeKeyAndOrderFront(nil)
        } else {
            openMain?()
        }
        NSApp.activate()
    }

    static func closeAllWindows() {
        for w in NSApp.windows where !(w is NSPanel) && w.styleMask.contains(.titled) { w.close() }
    }
}
