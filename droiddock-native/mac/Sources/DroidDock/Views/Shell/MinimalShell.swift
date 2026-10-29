import SwiftUI

/// AirSync-style: the phone IS the sidebar. The detail has five icon tabs (Notifications, Messages, Files, Apps,
/// Settings) plus a trailing "More" menu that lists every other destination, so nothing is out of reach.
struct MinimalShell: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var visibility: Binding<NavigationSplitViewVisibility> {
        Binding(get: { chrome.effectivePhoneHidden(.minimal) ? .detailOnly : .all },
                set: { chrome.setPhoneHidden(.minimal, $0 == .detailOnly) })
    }

    static let tabs: [Destination] = [.notifications, .messages, .files, .apps, .settings]
    /// Everything not on the tab bar, in the Go menu's and the More menu's order.
    static let overflow: [Destination] = Destination.allCases.filter { !tabs.contains($0) }

    private var current: Destination { model.selection }
    private var inOverflow: Bool { Self.overflow.contains(current) }

    private var tabItems: [DDTab<Destination>] {
        Self.tabs.map { DDTab(id: $0, title: $0.title, systemImage: $0.symbol,
                              badge: $0 == .notifications ? model.notifications.count : 0) }
    }

    private var textDirection: LayoutDirection { L10n.shared.isRTL ? .rightToLeft : .leftToRight }

    var body: some View {
        NavigationSplitView(columnVisibility: visibility) {
            // The toolbar sidebar toggle hides the phone; a second "Hide phone" row was redundant.
            CardColumn { PhoneCard(actionSet: .minimal, onNavigate: { d in withDDPointerAnimation(Motion.screen) { model.selection = d } }) }
            .environment(\.layoutDirection, textDirection)
            .navigationSplitViewColumnWidth(min: 330, ideal: 340, max: 380)
        } detail: {
            ScreenView(destination: current)
                .environment(\.layoutDirection, textDirection)
                .navigationTitle("")
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        HStack(spacing: DD.Space.xs) {
                            SegmentedTabBar(tabs: tabItems,
                                            selection: Binding(get: { current }, set: { v in withDDPointerAnimation(Motion.screen) { model.selection = v } }),
                                            iconOnly: true, numberShortcuts: false)
                            moreMenu
                        }
                        .fixedSize()
                    }
                }
        }
        // Split-view chrome stays left-to-right (a mirrored one pushes the tab bar into the toolbar's `»` menu); columns mirror themselves.
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Trailing "More": every destination that has no tab. Shows the open one's icon (tinted) when it is one of them.
    private var moreMenu: some View {
        Menu {
            ForEach(Self.overflow) { d in
                Button { withDDPointerAnimation(Motion.screen) { model.selection = d } } label: {
                    Label(d.title, systemImage: d.symbol)
                }
            }
        } label: {
            Image(systemName: inOverflow ? current.symbol : "ellipsis")
                .font(.system(size: 12, weight: .regular))
                .foregroundStyle(inOverflow ? Color.accentColor : Color.secondary)
                .frame(width: 28, height: 24)
        }
        .menuStyle(.borderlessButton).menuIndicator(.hidden)
        .fixedSize()
        .help(inOverflow ? current.title : tr("More"))
        .accessibilityLabel(tr("More"))
    }
}
