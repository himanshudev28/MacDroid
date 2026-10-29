import AppKit
import Observation
import SwiftUI

/// Feature #5: hold ⌘Q for ~1 s to quit. A small HUD shows the progress.
@Observable @MainActor
final class QuitHoldModel {
    var progress: Double = 0
}

@MainActor
final class QuitHold {
    static let shared = QuitHold()
    static let defaultsKey = "droiddock.holdToQuit"
    static let duration: TimeInterval = 1.0

    static var enabled: Bool { UserDefaults.standard.object(forKey: defaultsKey) as? Bool ?? true }

    private let model = QuitHoldModel()
    private var panel: NSPanel?
    private var task: Task<Void, Never>?
    private var monitor: Any?
    private(set) var satisfied = false
    var isHolding: Bool { task != nil }

    func installMonitor() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp, .flagsChanged]) { [weak self] e in
            guard let self else { return e }
            // Local monitors always fire on the main thread; NSEvent just isn't Sendable.
            let inbound = EventBox(event: e)
            return MainActor.assumeIsolated { EventBox(event: self.handle(inbound.event!)) }.event
        }
    }

    private struct EventBox: @unchecked Sendable { let event: NSEvent? }

    private func isCmdQ(_ e: NSEvent) -> Bool {
        e.charactersIgnoringModifiers?.lowercased() == "q"
            && e.modifierFlags.intersection([.command, .shift, .option, .control]) == .command
    }

    private func handle(_ e: NSEvent) -> NSEvent? {
        switch e.type {
        case .keyDown:
            guard Self.enabled, !AppDelegate.forceQuit, isCmdQ(e) else { return e }
            if !e.isARepeat { begin() }
            return nil
        case .keyUp:
            if isHolding, e.charactersIgnoringModifiers?.lowercased() == "q" { cancel() }
            return e
        case .flagsChanged:
            if isHolding, !e.modifierFlags.contains(.command) { cancel() }
            return e
        default:
            return e
        }
    }

    func begin() {
        guard task == nil else { return }
        model.progress = 0
        show()
        let start = Date()
        task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                guard let self, !Task.isCancelled else { return }
                let p = Date().timeIntervalSince(start) / Self.duration
                self.model.progress = min(1, p)
                if p >= 1 {
                    self.satisfied = true
                    self.task = nil
                    self.hide()
                    NSApp.terminate(nil)
                    self.satisfied = false
                    return
                }
            }
        }
    }

    func cancel() {
        task?.cancel(); task = nil
        hide()
    }

    private func show() {
        let p = panel ?? makePanel()
        panel = p
        if let s = NSScreen.main {
            let size = p.frame.size
            p.setFrameOrigin(NSPoint(x: s.frame.midX - size.width / 2, y: s.frame.minY + s.frame.height * 0.22))
        }
        p.alphaValue = 0
        p.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { $0.duration = 0.12; p.animator().alphaValue = 1 }
    }

    private func hide() {
        guard let p = panel else { return }
        NSAnimationContext.runAnimationGroup({ $0.duration = 0.15; p.animator().alphaValue = 0 },
                                             completionHandler: { p.orderOut(nil) })
    }

    private func makePanel() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 260, height: 84),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false; p.backgroundColor = .clear; p.hasShadow = true
        p.level = .statusBar; p.hidesOnDeactivate = false; p.ignoresMouseEvents = true
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        p.contentView = NSHostingView(rootView: QuitHoldHUD(model: model).ddAppearance(AppearanceSettings.shared))
        return p
    }
}

struct QuitHoldHUD: View {
    let model: QuitHoldModel
    var body: some View {
        VStack(spacing: DD.Space.md) {
            Text(tr("Hold ⌘Q to quit")).font(DD.Font.headline).foregroundStyle(DD.fg)
            ProgressView(value: model.progress)
                .progressViewStyle(.linear)
                .animation(nil, value: model.progress)
        }
        .padding(DD.Space.xl)
        .frame(width: 260, height: 84)
        .windowMaterial(.hudWindow, radius: DD.Radius.xl)
        .accessibilityElement(children: .combine)
        .accessibilityValue("\(Int(model.progress * 100)) percent")
    }
}
