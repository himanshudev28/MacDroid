import SwiftUI

/// Pane router shared by the Settings window and the Classic layout's Settings destination.
struct SettingsPaneView: View {
    let pane: SettingsPane
    var body: some View {
        switch pane {
        case .general: GeneralSettingsPane()
        case .appearance: AppearanceSettingsPane()
        case .connection: ConnectionSettingsPane()
        case .notifications: NotificationsSettingsPane()
        case .mirroring: MirroringSettingsPane()
        case .files: FilesSettingsPane()
        case .privacy: PrivacySettingsPane()
        case .shortcuts: ShortcutsSettingsPane()
        case .health: HealthSettingsPane()
        case .about: AboutSettingsPane()
        }
    }
}

struct SidebarLabel: View {
    let pane: SettingsPane
    var body: some View {
        Label {
            Text(pane.title)
        } icon: {
            Image(systemName: pane.symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(pane.tint.gradient))
                .accessibilityHidden(true)
        }
    }
}

/// The `Settings { }` scene (⌘,): sidebar of panes + grouped-form detail.
struct SettingsRoot: View {
    @AppStorage(SettingsPane.defaultsKey) private var paneRaw = SettingsPane.general.rawValue
    private var pane: SettingsPane { SettingsPane(rawValue: paneRaw) ?? .general }

    var body: some View {
        NavigationSplitView(columnVisibility: .constant(.all)) {
            List(SettingsPane.allCases, selection: Binding(get: { pane }, set: { paneRaw = ($0 ?? .general).rawValue })) { p in
                SidebarLabel(pane: p).tag(p)
            }
            .listStyle(.sidebar)
            .safeAreaInset(edge: .top, spacing: 0) { MyMacCard().padding(.horizontal, DD.Space.md).padding(.top, DD.Space.sm).padding(.bottom, DD.Space.xs) }
            .navigationSplitViewColumnWidth(min: 200, ideal: 216, max: 250)
        } detail: {
            SettingsPaneView(pane: pane)
                .id(pane)
                .navigationTitle(pane.title)
        }
        .navigationSplitViewStyle(.balanced)
        .toolbar(removing: .sidebarToggle)
        .liquidWindowBackground()
        .frame(minWidth: 740, idealWidth: 800, minHeight: 540, idealHeight: 620)
    }
}
