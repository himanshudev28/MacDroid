import SwiftUI

/// Flat sidebar list of every destination in the old Rail order. Phone status rides in the footer.
struct ClassicShell: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var collapsed: Bool { chrome.isCollapsed(.classic) }

    /// The system toolbar toggle drives the same collapse state (the column itself never hides).
    private var visibility: Binding<NavigationSplitViewVisibility> {
        Binding(get: { .all }, set: { if $0 == .detailOnly { chrome.toggleSidebar(.classic) } })
    }

    private var phonePanel: Binding<Bool> {
        Binding(get: { !chrome.effectivePhoneHidden(.classic) }, set: { chrome.setPhoneHidden(.classic, !$0) })
    }

    var body: some View {
        @Bindable var m = model
        NavigationSplitView(columnVisibility: visibility) {
            // List and footer are siblings (not a safeAreaInset): the inset left the last row ("Transfers") half under the footer.
            VStack(spacing: 0) {
            List(selection: Binding(get: { m.selection },
                           set: { v in if let v { withDDPointerAnimation(Motion.screen) { model.selection = v } } })) {
                if collapsed {
                    // Icon rail: one compact section so all 15 destinations fit without scrolling
                    // (group gaps pushed the last item under the footer on shorter windows).
                    ForEach(Destination.classicGroups.flatMap(\.items) + [.settings]) { d in row(d) }
                } else {
                    ForEach(Destination.classicGroups, id: \.title) { group in
                        Section(group.title.isEmpty ? "" : tr(group.title)) {
                            ForEach(group.items) { d in row(d) }
                        }
                    }
                    Section { row(.settings) }
                }
            }
            .listStyle(.sidebar)
            // The rail can't hold the system toggle (it spills into the toolbar's `»` menu), so it is removed and the detail's
            // toolbar carries its own (RailToggleItem). That item also keeps the unified toolbar, so the rail starts below
            // the title bar instead of under the traffic lights.
            .toolbar(removing: collapsed ? .sidebarToggle : nil)
            .environment(\.sidebarRowSize, collapsed ? .small : .medium)
            footer
            SidebarChromeBar(layout: .classic, iconOnly: collapsed, inline: true)
            }
            .navigationSplitViewColumnWidth(min: collapsed ? RailIcon.width : 200, ideal: collapsed ? RailIcon.width : 224, max: collapsed ? RailIcon.width : 280)
        } detail: {
            ScreenView(destination: model.selection)
                .navigationTitle(model.selection.title)
                .toolbar { RailToggleItem(layout: .classic, collapsed: collapsed) }
                .inspector(isPresented: phonePanel) {
                    CardColumn(inInspector: true) { PhoneCard() }
                        .inspectorColumnWidth(min: LayoutMetrics.inspectorMin, ideal: 320, max: 360)
                }
        }
    }

    @ViewBuilder private func row(_ d: Destination) -> some View {
        if collapsed {
            RailIcon(destination: d, badge: badge(d)).tag(d)
                } else {
            Label(d.title, systemImage: d.symbol).badge(badge(d)).tag(d)
        }
    }

    private func badge(_ d: Destination) -> Int {
        switch d {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: 0
        }
    }

    private var footer: some View {
        HStack(spacing: DD.Space.sm) {
            if collapsed {
                Circle().fill(model.isConnected ? DD.ok : Color.secondary.opacity(0.5)).frame(width: 8, height: 8)
                    .frame(maxWidth: .infinity)
                    .help(model.isConnected ? (model.phoneName ?? "") : tr("Waiting for phone"))
            } else if model.isConnected {
                // No latency here: in a 200 pt sidebar it squeezed the label to "… …" (seen live);
                // the dashboard tile and the phone card already show it.
                ConnectionPill(transport: model.pillTransport, link: model.linkQuality.map { LinkQualityLevel(grade: $0.grade) },
                               rttMs: nil, style: .standard)
                    .fixedSize()
                Spacer(minLength: 0)
                if let b = model.batteryPercent { BatteryIndicator(level: b, charging: model.isCharging) }
            } else {
                StatusPill(text: tr("Waiting for phone"), kind: .neutral)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, DD.Space.md).padding(.vertical, DD.Space.sm)
        .background(alignment: .top) { Divider() }
        .ddAnimation(Motion.state, value: model.isConnected)
    }
}
