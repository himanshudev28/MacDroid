import AppKit
import SwiftUI

/// DroidDock's own floating banner (top right, like a macOS notification). Used whenever the system banner can't be
/// relied on: no .app bundle, notifications denied or set to "None" in System Settings, or an ad-hoc signed build
/// (macOS ties notification permission to the code signature, so each re-signed build can silently lose it).
/// Click opens the notification; Reply sends the text to the phone.
@MainActor
final class BannerCenter {
    static let shared = BannerCenter()
    private var panels: [(id: String, panel: BannerPanel)] = []
    private let width: CGFloat = 360
    private init() {
        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.layout() }
        }
    }

    func show(_ n: NativeNotification, iconURL: URL?) {
        show(n, icon: iconURL.flatMap { NSImage(contentsOf: $0) })
    }

    func show(_ n: NativeNotification, icon: NSImage? = nil) {
        if n.sound == true { NSSound(named: "Glass")?.play() }
        panels.first { $0.id == n.id }.map { dismiss(id: $0.id, animated: false) }
        let model = BannerModel(n, icon: icon)
        let host = NSHostingView(rootView: BannerView(model: model,
            onOpen: { [weak self] in self?.dismiss(id: n.id); NotificationClickActions.shared.handleBannerClick(key: n.id) },
            onClose: { [weak self] in self?.dismiss(id: n.id) },
            onOpenPhone: { [weak self] in self?.dismiss(id: n.id); NotificationClickActions.shared.open(key: n.id) },
            onReply: { [weak self] text in
                Task { try? await Core.shared.notifReply(key: n.replyKey ?? n.id, text: text); AppModel.shared.toast(.ok, tr("Reply sent")) }
                self?.dismiss(id: n.id)
            }))
        // Measure at the real width: the content is pinned to 360 pt and fixed vertically, so wrapped text counts.
        host.frame.size = NSSize(width: width, height: 10)
        host.layoutSubtreeIfNeeded()
        host.frame.size = NSSize(width: width, height: host.fittingSize.height)
        let p = BannerPanel(contentRect: NSRect(origin: .zero, size: host.frame.size))
        p.contentView = host
        model.panel = p
        // Any later height change (text wrap, reply field, masked preview) reported by the SwiftUI content re-runs layout.
        model.onSizeChange = { [weak self, weak p, weak host] in
            guard let p, let host else { return }
            let h = model.contentHeight > 1 ? model.contentHeight : host.fittingSize.height
            guard abs(p.frame.height - h) > 0.5 else { return }
            p.setContentSize(NSSize(width: self?.width ?? 360, height: h)); self?.layout()
        }
        panels.append((n.id, p))
        layout(newest: p)
        p.alphaValue = 0
        p.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { $0.duration = 0.2; p.animator().alphaValue = 1 }
        model.armTimer { [weak self] in self?.dismiss(id: n.id) }
        while panels.count > 4 { dismiss(id: panels[0].id, animated: false) }
    }

    func dismiss(id: String, animated: Bool = true) {
        guard let i = panels.firstIndex(where: { $0.id == id }) else { return }
        let p = panels.remove(at: i).panel
        (p.contentView as? NSHostingView<BannerView>)?.rootView.model.cancelTimer()
        if animated {
            NSAnimationContext.runAnimationGroup({ $0.duration = 0.18; p.animator().alphaValue = 0 }, completionHandler: { p.orderOut(nil) })
        } else { p.orderOut(nil) }
        layout()
    }

    private func layout(newest: BannerPanel? = nil) {
        guard let area = (NSScreen.main ?? NSScreen.screens.first)?.visibleFrame else { return }
        var y = area.maxY - 12
        // Newest at the top (like macOS); older banners are pushed down with an 8 pt gap.
        for (_, p) in panels.reversed() {
            let h = p.frame.height
            let origin = NSPoint(x: area.maxX - width - 12, y: y - h)
            if p === newest { p.setFrameOrigin(origin) }
            else {
                NSAnimationContext.runAnimationGroup { c in
                    c.duration = 0.22; c.timingFunction = CAMediaTimingFunction(name: .easeOut)
                    p.animator().setFrame(NSRect(origin: origin, size: p.frame.size), display: true)
                }
            }
            y -= h + 8
        }
    }
}

final class BannerPanel: NSPanel {
    init(contentRect: NSRect) {
        super.init(contentRect: contentRect, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false; backgroundColor = .clear; hasShadow = true
        level = .statusBar; collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        isReleasedWhenClosed = false; hidesOnDeactivate = false
    }
    override var canBecomeKey: Bool { true }   // the reply field needs the keyboard
    override var canBecomeMain: Bool { false }
}

@MainActor @Observable
final class BannerModel {
    let n: NativeNotification
    let icon: NSImage?
    var replying = false { didSet { replying ? cancelTimer() : (hovering ? () : rearm()) ; onSizeChange?() } }
    var hovering = false { didSet { hovering ? cancelTimer() : (replying ? () : rearm()) } }
    /// Height of the SwiftUI content at the real width, reported by a preference (drives panel resizing).
    @ObservationIgnored var contentHeight: CGFloat = 0
    @ObservationIgnored weak var panel: BannerPanel?
    @ObservationIgnored var onSizeChange: (() -> Void)?
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var expire: (() -> Void)?
    init(_ n: NativeNotification, icon: NSImage?) { self.n = n; self.icon = icon }

    func armTimer(_ f: @escaping () -> Void) { expire = f; rearm() }
    private func rearm() {
        timer?.cancel()
        let secs = Double(NotifPrefs.bannerSeconds)
        timer = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(secs))
            if !Task.isCancelled { self?.expire?() }
        }
    }
    func cancelTimer() { timer?.cancel(); timer = nil }
}

private struct BannerView: View {
    let model: BannerModel
    let onOpen: () -> Void
    let onClose: () -> Void
    let onOpenPhone: () -> Void
    let onReply: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    private var canOpenOnPhone: Bool { NotificationClickActions.shared.canOpenOnPhone(key: model.n.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            HStack(alignment: .top, spacing: DD.Space.md) {
                Image(nsImage: model.icon ?? NSApp.applicationIconImage).resizable().frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    let c = NotificationService.split(model.n)
                    Text(c.title).font(DD.Font.headline).lineLimit(1)
                    if let sub = c.subtitle { Text(sub).font(DD.Font.callout).foregroundStyle(.secondary).lineLimit(1) }
                    if !c.body.isEmpty { Text(c.body).font(DD.Font.callout).foregroundStyle(.secondary).lineLimit(3) }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            if model.n.replyKey != nil || canOpenOnPhone {
                if model.replying {
                    HStack(spacing: DD.Space.sm) {
                        TextField(tr("Reply…"), text: $text).textFieldStyle(.roundedBorder).focused($focused)
                            .onSubmit(send)
                            .onExitCommand { model.replying = false }
                        Button(tr("Send"), action: send).buttonStyle(.borderedProminent).controlSize(.small)
                            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } else {
                    HStack(spacing: DD.Space.sm) {
                        Spacer()
                        if canOpenOnPhone { Button(tr("Open"), action: onOpenPhone).controlSize(.small) }
                        if model.n.replyKey != nil {
                            Button(tr("Reply")) { model.replying = true; model.panel?.makeKey(); focused = true }.controlSize(.small)
                        }
                    }
                }
            }
        }
        .padding(DD.Space.md)
        .frame(width: 360)
        .fixedSize(horizontal: false, vertical: true)
        .background(GeometryReader { g in Color.clear.preference(key: BannerHeightKey.self, value: g.size.height) })
        .onPreferenceChange(BannerHeightKey.self) { h in
            MainActor.assumeIsolated { if abs(model.contentHeight - h) > 0.5 { model.contentHeight = h; model.onSizeChange?() } }
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onClose) { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                .buttonStyle(.plain).foregroundStyle(.secondary).padding(8).help(tr("Dismiss"))
        }
        .windowMaterial(.popover, radius: DD.Radius.xl)
        .clipShape(RoundedRectangle(cornerRadius: DD.Radius.xl, style: .continuous))
        .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.xl, style: .continuous))
        .preferredColorScheme(AppearanceSettings.shared.theme.colorScheme)
        .onHover { model.hovering = $0 }
        .onChange(of: model.replying) { _, v in if v { Task { @MainActor in focused = true } } }
    }

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        onReply(t)
    }
}

private struct BannerHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

#if DEBUG
extension BannerCenter {
    /// QA: posts 5 banners quickly (short and 3-line texts), then logs every panel frame and asserts none intersect.
    func debugStress(shotPrefix: String?) async {
        let long = "First line of a long message that keeps going and going so that it wraps\nSecond line also long enough to wrap around the banner width for sure\nThird line of the message"
        let bodies = ["Short", long, "Received 10 rupees", long, "Ok"]
        for (i, b) in bodies.enumerated() {
            show(NativeNotification(id: "stress-\(i)", title: "App \(i)", body: b, replyKey: i % 2 == 0 ? "k" : nil, sound: false))
            try? await Task.sleep(for: .milliseconds(60))
        }
        try? await Task.sleep(for: .milliseconds(900))
        let ps = NSApp.windows.filter { $0 is BannerPanel && $0.isVisible }
        var bad = 0
        for (i, w) in ps.enumerated() {
            dlog("stress banner[\(i)] frame=\(NSStringFromRect(w.frame))")
            for w2 in ps[(i + 1)...] where w.frame.intersects(w2.frame) { bad += 1; dlog("stress OVERLAP \(NSStringFromRect(w.frame)) vs \(NSStringFromRect(w2.frame))") }
            if let pre = shotPrefix { DebugWindowProbe.capture(w.windowNumber, "\(pre)-\(i).png") }
        }
        dlog(bad == 0 && ps.count > 0 ? "stress PASS: \(ps.count) banners, no intersections" : "stress FAIL: overlaps=\(bad) count=\(ps.count)")
    }
}
#endif
