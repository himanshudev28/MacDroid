import AppKit
import Observation
import SwiftUI

/// Per-layout "collapse sidebar" / "hide phone" state, persisted as `droiddock.sidebarCollapsed.<layout>` and
/// `droiddock.phoneHidden.<layout>`. Phone tabs and Minimal use the phone as the sidebar, so for them collapsing
/// the sidebar and hiding the phone are the same switch (stored under `phoneHidden`).
@Observable @MainActor
final class ShellChrome {
    static let shared = ShellChrome()

    private var collapsed: [String: Bool] = [:]
    private var hidden: [String: Bool] = [:]

    private init() {
        let d = UserDefaults.standard
        for l in AppLayout.allCases {
            collapsed[l.rawValue] = d.bool(forKey: "droiddock.sidebarCollapsed.\(l.rawValue)")
            hidden[l.rawValue] = d.bool(forKey: "droiddock.phoneHidden.\(l.rawValue)")
        }
    }

    /// Layouts whose sidebar is the phone itself.
    static func phoneIsSidebar(_ l: AppLayout) -> Bool { l == .phoneTabs || l == .minimal }

    func isPhoneHidden(_ l: AppLayout) -> Bool { hidden[l.rawValue] ?? false }

    /// The phone panel is shown only if the user wants it AND the screen can hold the layout with it. When it can't,
    /// the panel auto-hides instead of the sidebar being clipped (the saved preference is untouched).
    func effectivePhoneHidden(_ l: AppLayout) -> Bool {
        if isPhoneHidden(l) { return true }
        let rawCollapsed = Self.phoneIsSidebar(l) ? false : (collapsed[l.rawValue] ?? false)
        return LayoutMetrics.minWidth(l, phoneHidden: false, collapsed: rawCollapsed) > WindowFit.availableWidth()
    }

    /// Smallest window width at which every column of `l` keeps its text unclipped, in the current panel/sidebar state.
    func requiredWidth(_ l: AppLayout) -> CGFloat {
        let raw = Self.phoneIsSidebar(l) ? false : (collapsed[l.rawValue] ?? false)
        return min(LayoutMetrics.minWidth(l, phoneHidden: effectivePhoneHidden(l), collapsed: raw), WindowFit.availableWidth())
    }
    func isCollapsed(_ l: AppLayout) -> Bool {
        Self.phoneIsSidebar(l) ? isPhoneHidden(l) : (collapsed[l.rawValue] ?? false)
    }

    func setPhoneHidden(_ l: AppLayout, _ v: Bool) {
        guard hidden[l.rawValue] != v else { return }
        withDDPointerAnimation(Motion.expand) { hidden[l.rawValue] = v }
        UserDefaults.standard.set(v, forKey: "droiddock.phoneHidden.\(l.rawValue)")
        if !v, l == Self.currentLayout { WindowFit.grow(to: requiredWidth(l)) }
    }

    func setCollapsed(_ l: AppLayout, _ v: Bool) {
        if Self.phoneIsSidebar(l) { setPhoneHidden(l, v); return }
        guard collapsed[l.rawValue] != v else { return }
        let before = WindowControl.mainWindow
        let frame = before?.frame
        withDDPointerAnimation(Motion.expand) { collapsed[l.rawValue] = v }
        UserDefaults.standard.set(v, forKey: "droiddock.sidebarCollapsed.\(l.rawValue)")
        if !v, l == Self.currentLayout { WindowFit.grow(to: requiredWidth(l)) }
        keepWindowSize(before, frame)
    }

    /// Collapsing only moves the sidebar divider: the window must keep its frame. SwiftUI re-derives the split view's
    /// minimum size when the column widths change, and (seen live) can pull the window with it, so put it back.
    private func keepWindowSize(_ w: NSWindow?, _ frame: NSRect?) {
        guard let w, let frame, !w.isMiniaturized, !w.styleMask.contains(.fullScreen) else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            if WindowFit.recentlyFitted { return }
            if w.frame != frame, !w.inLiveResize, !w.isZoomed { dlog("window frame drifted \(w.frame) -> restoring \(frame)"); w.setFrame(frame, display: true) }
        }
    }

    func toggleSidebar(_ l: AppLayout) { setCollapsed(l, !isCollapsed(l)) }
    func togglePhone(_ l: AppLayout) { setPhoneHidden(l, !isPhoneHidden(l)) }

    static var currentLayout: AppLayout {
        AppLayout(rawValue: UserDefaults.standard.string(forKey: AppLayout.defaultsKey) ?? "") ?? .phoneTabs
    }
}

/// One quiet sidebar-footer action: icon + secondary label (icon only in the collapsed rail), hover wash, tooltip.
struct SidebarChromeRow: View {
    let symbol: String
    let title: String
    var shortcut: String?
    var iconOnly = false
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: DD.Space.sm) {
                Image(systemName: symbol)
                    .font(.system(size: 14))
                    .symbolRenderingMode(.hierarchical)
                    .frame(width: 20)
                    .contentTransition(.symbolEffect(.replace))
                if !iconOnly {
                    Text(title).font(.system(size: 13)).lineLimit(1)
                    Spacer(minLength: 0)
                }
            }
            .foregroundStyle(hovering ? .primary : .secondary)
            .padding(.horizontal, iconOnly ? 0 : DD.Space.sm)
            .frame(maxWidth: .infinity, minHeight: 28, alignment: iconOnly ? .center : .leading)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color.primary.opacity(hovering ? 0.08 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withDDAnimation(Motion.hover) { hovering = h } }
        .help(shortcut.map { "\(title)  \($0)" } ?? title)
        .accessibilityLabel(title)
    }
}

/// The two rows at the bottom of a sidebar. `showCollapse` is false where the phone is the sidebar.
struct SidebarChromeBar: View {
    let layout: AppLayout
    var showCollapse = true
    var iconOnly = false
    /// One row of two icon buttons (tooltips carry the names) for sidebars that are short of height.
    var inline = false
    @State private var chrome = ShellChrome.shared

    var body: some View {
        Group {
            if inline && !iconOnly { HStack(spacing: DD.Space.xs) { rows(iconOnly: true).frame(maxWidth: 44); Spacer(minLength: 0) } }
            else { VStack(spacing: 2) { rows(iconOnly: iconOnly) } }
        }
        .padding(.horizontal, DD.Space.sm).padding(.vertical, DD.Space.xs)
        .background(alignment: .top) { Divider() }
    }

    @ViewBuilder private func rows(iconOnly: Bool) -> some View {
        Group {
            if showCollapse {
                let c = chrome.isCollapsed(layout)
                SidebarChromeRow(symbol: "sidebar.left", title: c ? tr("Expand sidebar") : tr("Collapse sidebar"),
                                 shortcut: "⌃⌘S", iconOnly: iconOnly) { chrome.toggleSidebar(layout) }
            }
            let h = chrome.isPhoneHidden(layout)
            SidebarChromeRow(symbol: h ? "iphone.slash" : "iphone", title: h ? tr("Show phone") : tr("Hide phone"),
                             shortcut: "⌥⌘S", iconOnly: iconOnly) { chrome.togglePhone(layout) }
        }
    }
}

/// One icon of the collapsed rail: a 28 pt row, centred, with a small dot at the top-right for badges. Selection is the
/// list's own rounded highlight (like Xcode's navigator icons).
struct RailIcon: View {
    /// Wide enough for the traffic lights (~76 pt) plus the list's own inset.
    static let width: CGFloat = 80
    let destination: Destination
    let badge: Int

    var body: some View {
        Image(systemName: destination.symbol)
            .font(.system(size: 14))
            .frame(width: 28, height: 28)
            .overlay(alignment: .topTrailing) {
                if badge > 0 { Circle().fill(Color.accentColor).frame(width: 7, height: 7).offset(x: -1, y: 3) }
            }
            .frame(maxWidth: .infinity)
            .listRowInsets(EdgeInsets(top: 3, leading: 0, bottom: 3, trailing: 0))
            .help(destination.title).accessibilityLabel(destination.title)
    }
}

/// Detail-toolbar button that expands the collapsed rail. Shown only while collapsed: it replaces the system sidebar
/// toggle (removed from the rail's column), and keeps the unified toolbar so nothing sits under the window buttons.
struct RailToggleItem: ToolbarContent {
    let layout: AppLayout
    let collapsed: Bool

    var body: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            if collapsed {
                Button { ShellChrome.shared.toggleSidebar(layout) } label: { Image(systemName: "sidebar.left") }
                    .help("\(tr("Expand sidebar"))  ⌃⌘S").accessibilityLabel(tr("Expand sidebar"))
            }
        }
    }
}

/// Per-layout minimum widths: the sum of the columns that are on screen plus the narrowest detail that keeps toolbars
/// and titles unclipped. Settings' inner sidebar collapses to a menu below `SettingsScreen.compactBelow`, so the
/// detail minimum does not have to include it.
enum LayoutMetrics {
    static let detailMin: CGFloat = 620
    static let inspectorMin: CGFloat = 300

    static func minWidth(_ l: AppLayout, phoneHidden: Bool, collapsed: Bool) -> CGFloat {
        switch l {
        case .phoneTabs: return phoneHidden ? 700 : DD.Size.sidebarMin + 740
        case .classic: return (collapsed ? RailIcon.width : 224) + detailMin + (phoneHidden ? 0 : inspectorMin)
        case .grouped: return (collapsed ? RailIcon.width : 264) + detailMin
        case .minimal: return phoneHidden ? 720 : 330 + 760
        }
    }
}

/// Grows (never shrinks) the main window to a width, clamped to the screen's visible frame and kept on-screen.
@MainActor
enum WindowFit {
    private static var lastFit = Date.distantPast
    static var recentlyFitted: Bool { Date().timeIntervalSince(lastFit) < 1.2 }

    static func availableWidth() -> CGFloat {
        (WindowControl.mainWindow?.screen ?? NSScreen.main)?.visibleFrame.width ?? 1440
    }

    static func grow(to want: CGFloat) {
        guard let w = WindowControl.mainWindow, !w.styleMask.contains(.fullScreen), !w.isMiniaturized,
              let vis = (w.screen ?? NSScreen.main)?.visibleFrame else { return }
        var f = w.frame
        let target = min(want, vis.width)
        guard f.width < target - 0.5 else { return }
        f.size.width = target
        f.size.height = min(f.height, vis.height)
        if f.maxX > vis.maxX { f.origin.x = vis.maxX - f.width }
        if f.minX < vis.minX { f.origin.x = vis.minX }
        if f.minY < vis.minY { f.origin.y = vis.minY }
        if f.maxY > vis.maxY { f.origin.y = vis.maxY - f.height }
        lastFit = Date()
        let animate = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion && w.isVisible
        w.setFrame(f, display: true, animate: animate)
    }
}
