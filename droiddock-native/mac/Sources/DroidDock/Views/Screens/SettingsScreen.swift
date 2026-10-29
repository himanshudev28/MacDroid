import SwiftUI

/// Settings screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
/// Used as a sidebar destination in the Classic layout; renders the same panes as the ⌘, window.
struct SettingsScreen: View {
    @AppStorage(SettingsPane.defaultsKey) private var paneRaw = SettingsPane.general.rawValue
    private var pane: SettingsPane { SettingsPane(rawValue: paneRaw) ?? .general }

    /// Below this width the pane list no longer fits beside the pane itself, so it becomes a menu header.
    static let compactBelow: CGFloat = 660
    @State private var width: CGFloat = 1000

    private var selection: Binding<SettingsPane> {
        Binding(get: { pane }, set: { paneRaw = $0.rawValue })
    }

    var body: some View {
        Group {
            if width < Self.compactBelow {
                VStack(spacing: 0) {
                    HStack(spacing: DD.Space.md) {
                        MyMacCard(compact: true).frame(width: 52)
                        Picker(tr("Section"), selection: selection) {
                            ForEach(SettingsPane.allCases) { Label($0.title, systemImage: $0.symbol).tag($0) }
                        }
                        .pickerStyle(.menu).labelsHidden().fixedSize()
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.sm)
                    Divider()
                    SettingsPaneView(pane: pane).id(pane)
                }
            } else {
                HStack(spacing: 0) {
                    List(SettingsPane.allCases, selection: Binding(get: { pane }, set: { paneRaw = ($0 ?? .general).rawValue })) { p in
                        SidebarLabel(pane: p).tag(p)
                    }
                    .listStyle(.sidebar)
                    .safeAreaInset(edge: .top, spacing: 0) { MyMacCard().padding(.horizontal, DD.Space.md).padding(.top, DD.Space.sm).padding(.bottom, DD.Space.xs) }
                    .frame(width: 200)
                    Divider()
                    SettingsPaneView(pane: pane).id(pane)
                }
            }
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
    }
}
