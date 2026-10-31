import SwiftUI

/// Flat sidebar list of every destination in the old Rail order. Phone status rides in the footer.
/// The split view itself is `MainShell`'s; this layout supplies its columns (`ClassicSidebar`, `ClassicDetail`).
struct ClassicSidebar: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var collapsed: Bool { chrome.isCollapsed(.classic) }

    var body: some View {
        @Bindable var m = model
        // List and footer are siblings (not a safeAreaInset): the inset left the last row ("Transfers") half under the footer.
        VStack(spacing: 0) {
            List(selection: Binding(get: { m.selection },
                           set: { v in if let v { withDDPointerAnimation(Motion.screen) { model.selection = v } } })) {
                if collapsed {
                    // Icon rail: the groups in one run, then Settings on its own so the gap matches the expanded list.
                    Section { ForEach(Destination.classicGroups.flatMap(\.items)) { d in row(d) } }
                    Section { row(.settings) }
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
        }
        .navigationSplitViewColumnWidth(min: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listMin,
                                        ideal: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listIdeal,
                                        max: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listMax)
    }

    @ViewBuilder private func row(_ d: Destination) -> some View {
        if collapsed {
            RailIcon(destination: d, badge: badge(d)).tag(d)
        } else {
            Label(d.title, systemImage: d.symbol).badge(badge(d)).tag(d)
                .help(d.helpText)
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
                Label(tr("Waiting for phone"), systemImage: "iphone.slash")
                    .font(.callout).foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, DD.Space.md)
        .frame(height: 36)
        .ddAnimation(Motion.state, value: model.isConnected)
    }
}

struct ClassicDetail: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var phonePanel: Binding<Bool> {
        Binding(get: { !chrome.effectivePhoneHidden(.classic) }, set: { chrome.setPhoneHidden(.classic, !$0) })
    }

    var body: some View {
        ScreenView(destination: model.selection)
            .inlineTitle(model.selection.title, subtitle: model.shellSubtitle)
            .toolbar {
                RailToggleItem(layout: .classic, collapsed: chrome.isCollapsed(.classic))
                PhonePanelToggleItem(layout: .classic)
            }
            .inspector(isPresented: phonePanel) {
                CardColumn(inInspector: true) { PhoneCard() }
                    .inspectorColumnWidth(min: LayoutMetrics.phoneMin, ideal: LayoutMetrics.phoneIdeal, max: LayoutMetrics.phoneMax)
            }
    }
}
