import AppKit
import SwiftUI

/// A scrcpy window showing its own virtual display (core event `vd-window`).
struct VDWindowInfo: Codable, Sendable, Equatable {
    var pid: Int
    var displayId: Int
    var serial: String
    /// "app" (a notification's app window) | "second" (Mirror › Second screen).
    var kind: String
    /// Android's home screen and taskbar are on the display, so Home has somewhere to go.
    var decorations: Bool?
    /// App windows: the app a notification opened.
    var package: String?
}

/// `vd-window-closed {pid}`.
struct VDWindowClosed: Codable, Sendable { var pid: Int }

/// One app open on a virtual display (`adb_vd_tasks`).
struct VDTask: Codable, Sendable, Equatable {
    var taskId: Int
    var package: String
    var visible: Bool
}

/// Android has no navigation bar on a virtual display, so each such scrcpy window gets a small
/// Back · Home · Recent bar of ours, kept just under the window (or inside its bottom edge when the
/// window sits at the bottom of the screen). Tracking runs only while such a window is open.
@MainActor
final class VirtualDisplayBars {
    static let shared = VirtualDisplayBars()

    private final class Bar {
        let info: VDWindowInfo
        let panel: NSPanel
        init(info: VDWindowInfo, panel: NSPanel) { self.info = info; self.panel = panel }
    }
    private var bars: [Int: Bar] = [:]
    private var timer: Timer?

    func add(_ info: VDWindowInfo) {
        remove(pid: info.pid)
        let width: CGFloat = 148
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: width, height: 38),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.contentView = NSHostingView(rootView: VDNavBar(info: info) { [weak self] in self?.showRecents(pid: info.pid) })
        bars[info.pid] = Bar(info: info, panel: panel)
        if timer == nil {
            let t = Timer(timeInterval: 0.2, repeats: true) { _ in MainActor.assumeIsolated { VirtualDisplayBars.shared.reposition() } }
            t.tolerance = 0.05
            RunLoop.main.add(t, forMode: .common)
            timer = t
        }
        reposition()
    }

    func remove(pid: Int) {
        bars.removeValue(forKey: pid)?.panel.orderOut(nil)
        if bars.isEmpty { timer?.invalidate(); timer = nil }
    }

    private func reposition() {
        guard !bars.isEmpty else { return }
        let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
        let primaryHeight = NSScreen.screens.first?.frame.height ?? 0
        for bar in bars.values {
            // Front-to-back list: the scrcpy window, and where our bar currently sits.
            guard let idx = list.firstIndex(where: {
                ($0[kCGWindowOwnerPID as String] as? Int) == bar.info.pid && ($0[kCGWindowLayer as String] as? Int) == 0
            }), let dict = list[idx][kCGWindowBounds as String] as? NSDictionary,
                  let r = CGRect(dictionaryRepresentation: dict), r.width > 80
            else { if bar.panel.isVisible { bar.panel.orderOut(nil) }; continue }
            // CoreGraphics measures from the top of the primary screen, AppKit from its bottom.
            let windowBottom = primaryHeight - r.maxY
            let size = bar.panel.frame.size
            var y = windowBottom - size.height - 6
            let center = NSPoint(x: r.midX, y: primaryHeight - r.midY)
            if let screen = NSScreen.screens.first(where: { $0.frame.contains(center) }), y < screen.visibleFrame.minY {
                y = windowBottom + 10
            }
            let frame = NSRect(x: (r.midX - size.width / 2).rounded(), y: y.rounded(), width: size.width, height: size.height)
            if bar.panel.frame != frame { bar.panel.setFrame(frame, display: false) }
            let mine = bar.panel.windowNumber
            let directlyAbove = idx > 0 && (list[idx - 1][kCGWindowNumber as String] as? Int) == mine
            if !bar.panel.isVisible || !directlyAbove {
                bar.panel.order(.above, relativeTo: list[idx][kCGWindowNumber as String] as? Int ?? 0)
            }
        }
    }

    /// "Recent": the apps open on this display. Android sends its own Recents key to the phone's main
    /// screen whatever display is named, so the list is built here instead.
    private func showRecents(pid: Int) {
        guard let bar = bars[pid] else { return }
        let info = bar.info
        Task { @MainActor in
            let tasks = (try? await Core.shared.adbVdTasks(serial: info.serial, displayId: info.displayId)) ?? []
            let menu = NSMenu()
            menu.autoenablesItems = false
            if tasks.isEmpty {
                let none = NSMenuItem(title: tr("No apps open"), action: nil, keyEquivalent: "")
                none.isEnabled = false
                menu.addItem(none)
            }
            var seen = Set<String>()
            for t in tasks where seen.insert(t.package).inserted {
                let name = ShellState.shared.appNames[t.package] ?? (t.package.split(separator: ".").last.map(String.init) ?? t.package)
                let item = BlockMenuItem(title: name) {
                    Task { try? await Core.shared.adbVdSwitch(serial: info.serial, displayId: info.displayId, package: t.package) }
                }
                item.state = t.visible ? .on : .off
                if let icon = await PhoneIconStore.shared.load(t.package) {
                    let small = NSImage(size: NSSize(width: 18, height: 18))
                    small.lockFocus(); icon.draw(in: NSRect(x: 0, y: 0, width: 18, height: 18)); small.unlockFocus()
                    item.image = small
                }
                menu.addItem(item)
            }
            guard let view = bar.panel.contentView else { return }
            menu.popUp(positioning: nil, at: NSPoint(x: view.bounds.maxX - 44, y: view.bounds.maxY + 4), in: view)
        }
    }
}

/// An NSMenuItem that runs a closure.
final class BlockMenuItem: NSMenuItem {
    private let run: () -> Void
    init(title: String, _ run: @escaping () -> Void) {
        self.run = run
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }
    @available(*, unavailable) required init(coder: NSCoder) { fatalError() }
    @objc private func fire() { run() }
}

private struct VDNavBar: View {
    let info: VDWindowInfo
    let onRecents: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            key("chevron.backward", tr("Back")) { send("back") }
            key("circle", tr("Home")) { send("home") }
            key("square.on.square", tr("Recent apps"), onRecents)
        }
        .padding(3)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(DD.line, lineWidth: 0.5))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func send(_ k: String) {
        Task {
            // No home screen on an app-only display: Home returns to that app's start screen.
            let pkg = info.decorations == false ? info.package : nil
            do { try await Core.shared.adbVdKey(serial: info.serial, displayId: info.displayId, key: k, package: pkg) }
            catch { AppModel.shared.toast(.bad, error.localizedDescription) }
        }
    }

    private func key(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 13, weight: .semibold))
                .frame(width: 44, height: 30).contentShape(Capsule())
        }
        .buttonStyle(VDNavButtonStyle())
        .help(label)
        .accessibilityLabel(label)
    }
}

private struct VDNavButtonStyle: ButtonStyle {
    @State private var hovering = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(DD.fg)
            .background(Capsule().fill(configuration.isPressed ? DD.panel3 : (hovering ? DD.hover : .clear)))
            .onHover { hovering = $0 }
    }
}
