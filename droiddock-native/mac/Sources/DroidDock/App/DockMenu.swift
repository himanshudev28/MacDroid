import AppKit

/// Dock menu: the four things you reach for without opening the window.
@MainActor
enum DockMenu {
    private final class Target: NSObject {
        let run: () -> Void
        init(_ run: @escaping () -> Void) { self.run = run }
        @objc func fire() { MainActor.assumeIsolated { run() } }
    }
    private static var targets: [Target] = []

    static func make() -> NSMenu {
        targets.removeAll()
        let m = AppModel.shared
        let menu = NSMenu()
        func add(_ title: String, _ symbol: String, enabled: Bool = true, _ run: @escaping () -> Void) {
            let t = Target(run); targets.append(t)
            let item = NSMenuItem(title: title, action: #selector(Target.fire), keyEquivalent: "")
            item.target = t; item.isEnabled = enabled
            item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
            menu.addItem(item)
        }
        add(tr("Send File…"), "arrow.up.doc", enabled: m.isConnected) { ShellActions.pickAndSend() }
        add(m.mirror != nil ? tr("Stop Mirror") : tr("Mirror"), "rectangle.on.rectangle", enabled: m.isConnected || m.mirror != nil) {
            ShellActions.toggleMirror()
        }
        add(tr("Ring Phone"), "bell.and.waves.left.and.right", enabled: m.isConnected) { ShellActions.toggleRing() }
        menu.addItem(.separator())
        add(m.isPaused ? tr("Resume Notifications") : tr("Pause Notifications"), m.isPaused ? "play.circle" : "pause.circle") {
            ShellActions.togglePause()
        }
        return menu
    }
}
