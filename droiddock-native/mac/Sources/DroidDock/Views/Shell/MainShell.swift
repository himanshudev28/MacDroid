import AppKit
import SwiftUI

/// The one split view behind all four window layouts. The layouts differ only in the columns, toolbar items and column
/// widths they put in it, so a layout switch swaps *content* and never rebuilds the `NavigationSplitView`.
///
/// Why one split view: swapping four different `NavigationSplitView`s (`.id(layout)`) left the window's `NSToolbar` bound to the
/// destroyed `NSSplitView` (a stale tracking separator in the title bar, no sidebar toggle), restored the previous layout's
/// column widths from the shared split-view autosave, and cost a full `NSSplitViewController` build per switch.
struct MainShell: View {
    let layout: AppLayout
    @Environment(\.layoutDirection) private var inheritedDirection
    @State private var chrome = ShellChrome.shared

    /// Phone tabs and Minimal: the phone is the sidebar, so the system sidebar toggle hides the phone. Classic and Grouped:
    /// the column never hides; the toggle collapses it to an icon rail.
    private var visibility: Binding<NavigationSplitViewVisibility> {
        let l = layout
        if ShellChrome.phoneIsSidebar(l) {
            return Binding(get: { chrome.effectivePhoneHidden(l) ? .detailOnly : .all },
                           set: { chrome.setPhoneHidden(l, $0 == .detailOnly) })
        }
        return Binding(get: { .all }, set: { if $0 == .detailOnly { chrome.toggleSidebar(l) } })
    }

    var body: some View {
        NavigationSplitView(columnVisibility: visibility) {
            switch layout {
            case .phoneTabs: PhoneTabsSidebar()
            case .classic: ClassicSidebar()
            case .grouped: GroupedSidebar()
            case .minimal: MinimalSidebar()
            }
        } detail: {
            switch layout {
            case .phoneTabs: PhoneTabsDetail()
            case .classic: ClassicDetail()
            case .grouped: GroupedDetail()
            case .minimal: MinimalDetail()
            }
        }
        // Keep the split view's chrome (sidebar side, toolbar zones) left-to-right in the tab layouts: with the system toolbar
        // still LTR, a mirrored split view pushes the principal tab bar into the `»` menu. Content columns mirror themselves.
        .environment(\.layoutDirection, ShellChrome.phoneIsSidebar(layout) ? .leftToRight : inheritedDirection)
        .onChange(of: layout) { _, new in SplitWidth.applyIdeal(new, chrome: chrome) }
    }
}

/// A split view keeps its divider where it was when only the columns' contents change, and `ideal` is honoured only when a
/// column is created. On a layout switch put the divider at the new layout's own ideal width.
@MainActor
enum SplitWidth {
    static func ideal(_ l: AppLayout) -> CGFloat {
        switch l {
        case .phoneTabs, .grouped, .minimal: LayoutMetrics.phoneIdeal
        case .classic: LayoutMetrics.listIdeal
        }
    }

    static func applyIdeal(_ l: AppLayout, chrome: ShellChrome) {
        guard !chrome.isCollapsed(l), !chrome.effectivePhoneHidden(l) || !ShellChrome.phoneIsSidebar(l),
              let root = WindowControl.mainWindow?.contentView?.superview, let split = outerSplit(in: root),
              let first = split.arrangedSubviews.first, !first.isHidden, first.frame.width > 1 else { return }
        split.setPosition(ideal(l), ofDividerAt: 0)
    }

    /// The window's outermost vertical split view (the sidebar | detail one; the inspector's is nested inside it).
    private static func outerSplit(in v: NSView) -> NSSplitView? {
        if let s = v as? NSSplitView, s.isVertical, s.arrangedSubviews.count >= 2 { return s }
        for sub in v.subviews { if let s = outerSplit(in: sub) { return s } }
        return nil
    }
}
