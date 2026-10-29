import AppKit
import SwiftUI

/// Floating always-on-top status widget: phone battery + now playing. Shown while
/// `config.widgetEnabled` is true; draggable anywhere, remembers its position. Not a WidgetKit widget.
@MainActor
final class StatusWidgetController {
    static let shared = StatusWidgetController()
    private var panel: NSPanel?
    private var started = false
    private static let frameName = "DroidDockStatusWidget"
    private static let size = NSSize(width: 264, height: 96)

    /// Call once at launch; follows `AppModel.config.widgetEnabled` from then on.
    func install() {
        guard !started else { return }
        started = true
        track()
    }

    private func track() {
        let want = withObservationTracking {
            AppModel.shared.config?.widgetEnabled ?? false
        } onChange: {
            Task { @MainActor [weak self] in self?.track() }
        }
        want ? show() : hide()
    }

    private func show() {
        UIActivity.shared.setWidgetShown(true)
        if let panel { panel.orderFrontRegardless(); return }
        let p = WidgetPanel(contentRect: NSRect(origin: .zero, size: Self.size),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.level = .floating
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.isMovableByWindowBackground = true
        p.isReleasedWhenClosed = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        p.title = "DroidDock Status"
        let host = NSHostingView(rootView:
            StatusWidgetView()
                .environment(AppModel.shared)
                .ddAppearance(AppearanceSettings.shared)
                .frame(width: Self.size.width, height: Self.size.height))
        host.frame = NSRect(origin: .zero, size: Self.size)
        p.contentView = host
        p.setContentSize(Self.size)
        if !p.setFrameUsingName(Self.frameName) || !Self.onScreen(p.frame) { Self.placeDefault(p) }
        p.setFrameAutosaveName(Self.frameName)
        p.setContentSize(Self.size)
        p.orderFrontRegardless()
        panel = p
    }

    private func hide() {
        UIActivity.shared.setWidgetShown(false)
        panel?.orderOut(nil)
        panel?.contentView = nil
        panel = nil
    }

    private static func onScreen(_ f: NSRect) -> Bool {
        NSScreen.screens.contains { $0.visibleFrame.intersects(f.insetBy(dx: 40, dy: 20)) }
    }

    private static func placeDefault(_ p: NSPanel) {
        guard let s = NSScreen.main?.visibleFrame else { return }
        p.setFrameOrigin(NSPoint(x: s.maxX - size.width - 20, y: s.maxY - size.height - 20))
    }
}

private final class WidgetPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private struct StatusWidgetView: View {
    @Environment(AppModel.self) private var model
    @State private var hovering = false
    @State private var art: NSImage?

    private var track: MediaState? {
        guard let m = model.media, m.active, (m.title?.isEmpty == false || m.artist?.isEmpty == false) else { return nil }
        return m
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            header
            if let b = model.batteryPercent { batteryBar(b) }
            Group {
                if let m = track { nowPlaying(m) } else { idle }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: model.batteryPercent == nil && track == nil ? .leading : .topLeading)
        .windowMaterial(.popover, radius: DD.Radius.xl)
        .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.xl, style: .continuous))
        .onHover { hovering = $0 }
        .ddAnimation(Motion.content, value: track?.trackKey)
        .task(id: model.mediaArt) {
            guard let b = model.mediaArt else { art = nil; return }
            art = await ImageCache.shared.image(fromDataURL: b, maxPixel: 600)   // shared decode (same entry as the card's)
        }
        .contextMenu {
            Button(tr("Open DroidDock")) { WindowControl.showMain() }
            Button(tr("Hide Widget")) { Task { try? await Core.shared.widgetSet(show: false) } }
        }
    }

    private var header: some View {
        HStack(spacing: 7) {
            Circle().fill(model.isConnected ? DD.link : DD.faint).frame(width: 7, height: 7)
            Text(model.isConnected ? (model.phoneName ?? tr("Phone")) : tr("Not linked"))
                .font(.system(size: 12, weight: .semibold)).foregroundStyle(DD.fg).lineLimit(1)
            Spacer(minLength: 4)
            if let b = model.batteryPercent {
                HStack(spacing: 2) {
                    if model.isCharging { Image(systemName: "bolt.fill").font(.system(size: 10)).foregroundStyle(DD.ok) }
                    Text("\(b)%").monospacedDigit().contentTransition(.numericText())
                }
                .font(.system(size: 12, weight: .medium)).foregroundStyle(DD.fg)
            }
            Button { WindowControl.showMain() } label: {
                Image(systemName: "arrow.up.forward.app").font(.system(size: 11, weight: .medium))
                    .foregroundStyle(DD.dim).frame(width: 20, height: 20)
            }
            .buttonStyle(.plain).opacity(hovering ? 1 : 0).help(tr("Open DroidDock"))
            .accessibilityLabel(tr("Open DroidDock"))
        }
        .ddAnimation(Motion.hover, value: hovering)
    }

    private func batteryBar(_ b: Int) -> some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(DD.panel3)
                Capsule().fill(model.isCharging ? DD.ok : (b <= 15 ? DD.bad : Color.accentColor))
                    .frame(width: max(5, g.size.width * CGFloat(b) / 100))
            }
        }
        .frame(height: 5)
        .accessibilityHidden(true)
    }

    private func nowPlaying(_ m: MediaState) -> some View {
        HStack(spacing: 8) {
            Group {
                if let art { Image(nsImage: art).resizable().aspectRatio(contentMode: .fill) }
                else { Rectangle().fill(DD.panel2).overlay(Image(systemName: "music.note").font(.system(size: 11)).foregroundStyle(DD.dim)) }
            }
            .frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(m.title ?? "").font(.system(size: 11.5, weight: .medium)).foregroundStyle(DD.fg).lineLimit(1)
                Text(m.artist ?? m.app ?? "").font(.system(size: 10.5)).foregroundStyle(DD.dim).lineLimit(1)
            }
            Spacer(minLength: 2)
            ctl("backward.fill", tr("Previous")) { cmd("prev") }
            Button { cmd(m.playing == true ? "pause" : "play") } label: {
                Image(systemName: m.playing == true ? "pause.fill" : "play.fill").font(.system(size: 10))
                    .foregroundStyle(Color(nsColor: .alternateSelectedControlTextColor)).frame(width: 24, height: 24).background(Circle().fill(Color.accentColor))
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(m.playing == true ? tr("Pause") : tr("Play"))
            ctl("forward.fill", tr("Next")) { cmd("next") }
        }
        .transition(.opacity)
    }

    private var idle: some View {
        HStack(spacing: 6) {
            Image(systemName: model.isConnected ? "music.note" : "iphone.slash").font(.system(size: 11)).foregroundStyle(DD.dim)
            Text(model.isConnected ? tr("Nothing playing") : tr("Open DroidDock to pair"))
                .font(.system(size: 11)).foregroundStyle(DD.dim)
        }
    }

    private func ctl(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 10)).foregroundStyle(DD.dim).frame(width: 22, height: 24)
        }
        .buttonStyle(.plain).help(label).accessibilityLabel(label)
    }

    private func cmd(_ c: String) { Task { try? await Core.shared.mediaCmd(c) } }
}
