import SwiftUI

/// The phone card on top, then the destinations in short groups (Inbox, Files, Phone).
/// The split view itself is `MainShell`'s; this layout supplies its columns (`GroupedSidebar`, `GroupedDetail`).
struct GroupedSidebar: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var collapsed: Bool { chrome.isCollapsed(.grouped) }

    private static let groups: [(String, [Destination])] = [
        ("", [.dashboard]),
        ("Inbox", [.notifications, .messages, .calls]),
        ("Files", [.files, .photos, .transfers]),
        ("Phone", [.apps, .mirror, .camera, .media, .clipboard, .contacts, .devices]),
        ("", [.settings]),
    ]

    var body: some View {
        @Bindable var m = model
        let showCard = !collapsed && !chrome.isPhoneHidden(.grouped)
        GeometryReader { g in
            // A taller card while it shows the pairing QR or an offline state, so nothing is clipped.
            let live = model.isConnected
            let cardH = live ? min(300, max(244, g.size.height * 0.40)) : min(360, max(300, g.size.height * 0.52))
            VStack(spacing: 0) {
                if showCard {
                    // Pinned above the list (not a row of it) so the phone summary never scrolls away; only the
                    // destinations below can scroll, and only in a window too short for all of them.
                    PhoneCard()
                        .frame(height: cardH)
                        .padding(.horizontal, LayoutMetrics.cardInset).padding(.top, DD.Space.xs).padding(.bottom, DD.Space.sm)
                }
                List(selection: Binding(get: { m.selection },
                           set: { v in if let v { withDDPointerAnimation(Motion.screen) { model.selection = v } } })) {
                    if collapsed {
                        // One flat section: the headers and group gaps would push the last icons (Settings) off a short window.
                        Section {
                            ForEach(Self.groups.flatMap(\.1)) { d in
                                RailIcon(destination: d, badge: badge(d)).tag(d)
                            }
                        }
                    } else {
                        ForEach(Array(Self.groups.enumerated()), id: \.offset) { _, group in
                            Section(group.0.isEmpty ? "" : tr(group.0)) {
                                ForEach(group.1) { d in
                                    Label(d.title, systemImage: d.symbol).badge(badge(d)).tag(d).help(d.helpText)
                                }
                            }
                        }
                    }
                }
                .listStyle(.sidebar)
                // The rail can't hold the system toggle (it spills into the toolbar's `»` menu). The modifier only works on the
                // sidebar column's content, not on the split view or detail. The rail's own button and ⌃⌘S replace it.
                .toolbar(removing: collapsed ? .sidebarToggle : nil)
                .environment(\.sidebarRowSize, collapsed ? .small : .medium)
            }
        }
        .navigationSplitViewColumnWidth(min: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneMin,
                                        ideal: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneIdeal,
                                        max: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneMax)
    }

    private func badge(_ d: Destination) -> Int {
        switch d {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: 0
        }
    }
}

struct GroupedDetail: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    var body: some View {
        ScreenView(destination: model.selection)
            .inlineTitle(model.selection.title, subtitle: model.shellSubtitle)
            .toolbar {
                RailToggleItem(layout: .grouped, collapsed: chrome.isCollapsed(.grouped))
                PhonePanelToggleItem(layout: .grouped)
            }
    }
}
