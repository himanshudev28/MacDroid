import SwiftUI

/// AirSync-style: the phone IS the sidebar. The detail has five icon tabs (Notifications, Messages, Files, Apps,
/// Clipboard) plus a trailing "More" menu that lists every other destination, so nothing is out of reach.
/// The split view itself is `MainShell`'s; this layout supplies its columns (`MinimalSidebar`, `MinimalDetail`).
enum MinimalShell {

    static let tabs: [Destination] = [.notifications, .messages, .files, .apps, .clipboard]
    /// Everything not on the tab bar, in the Go menu's and the More menu's order.
    static let overflow: [Destination] = Destination.allCases.filter { !tabs.contains($0) }
}

struct MinimalSidebar: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        // The toolbar sidebar toggle hides the phone; a second "Hide phone" row was redundant.
        CardColumn { PhoneCard(actionSet: .minimal, onNavigate: { d in withDDPointerAnimation(Motion.screen) { model.selection = d } }) }
            .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
            .navigationSplitViewColumnWidth(min: LayoutMetrics.phoneMin, ideal: LayoutMetrics.phoneIdeal, max: LayoutMetrics.phoneMax)
    }
}

struct MinimalDetail: View {
    @Environment(AppModel.self) private var model
    private static var tabs: [Destination] { MinimalShell.tabs }
    private static var overflow: [Destination] { MinimalShell.overflow }
    private var current: Destination { model.selection }

    private var tabItems: [DDTab<Destination>] {
        Self.tabs.map { DDTab(id: $0, title: $0.title, systemImage: $0.symbol,
                              badge: $0 == .notifications ? model.notifications.count : 0,
                              shortcut: $0.shortcutKey.map { "⌘\($0)" }) }
    }

    private var textDirection: LayoutDirection { L10n.shared.isRTL ? .rightToLeft : .leftToRight }

    var body: some View {
        ScreenView(destination: current)
            .environment(\.layoutDirection, textDirection)
            .hidesWindowTitle(current.title)
            .toolbar {
                ToolbarItem(placement: .primaryAction) { ShellMoreMenu(destinations: Self.overflow) }
                ToolbarItem(placement: .principal) {
                    SegmentedTabBar(tabs: tabItems,
                                    selection: Binding(get: { current }, set: { v in withDDPointerAnimation(Motion.screen) { model.selection = v } }),
                                    iconOnly: true, numberShortcuts: false)
                        .fixedSize()
                }
            }
    }
}
