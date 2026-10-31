import SwiftUI

/// Default layout. Sidebar = the live phone card; detail = five toolbar tabs, each with an optional sub-picker.
/// Home, Contacts, Devices, Now playing and Settings live in a slim footer under the card (and the Go menu).
/// The split view itself is `MainShell`'s; this layout supplies its columns (`PhoneTabsSidebar`, `PhoneTabsDetail`).
enum PhoneTabsShell {
    enum Tab: String, CaseIterable, Identifiable, Hashable {
        case inbox, files, apps, clipboard, screen
        var id: String { rawValue }
        var title: String {
            switch self {
            case .inbox: tr("Inbox")
            case .files: tr("Files")
            case .apps: tr("Apps")
            case .clipboard: tr("Clipboard")
            case .screen: tr("Screen")
            }
        }
        var symbol: String {
            switch self {
            case .inbox: "tray"
            case .files: "folder"
            case .apps: "square.grid.2x2"
            case .clipboard: "doc.on.clipboard"
            case .screen: "rectangle.on.rectangle"
            }
        }
        var items: [Destination] {
            switch self {
            case .inbox: [.notifications, .messages, .calls]
            case .files: [.files, .photos, .transfers]
            case .apps: [.apps]
            case .clipboard: [.clipboard]
            case .screen: [.mirror, .camera]
            }
        }
        static func containing(_ d: Destination) -> Tab? { allCases.first { $0.items.contains(d) } }
    }

    static let extras: [Destination] = [.dashboard, .contacts, .devices, .media, .settings]
}

struct PhoneTabsSidebar: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        CardColumn { PhoneCard() }
            .safeAreaInset(edge: .bottom, spacing: 0) { footer }
            .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
            .navigationSplitViewColumnWidth(min: LayoutMetrics.phoneMin, ideal: LayoutMetrics.phoneIdeal, max: LayoutMetrics.phoneMax)
    }

    private var footer: some View {
        HStack(spacing: DD.Space.xs) {
            ForEach(PhoneTabsShell.extras) { d in
                IconButton(systemImage: d.symbol, label: d.helpText, size: DD.Size.iconButton, isOn: model.selection == d) {
                    withDDPointerAnimation(Motion.screen) { model.selection = d }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, LayoutMetrics.cardInset).padding(.vertical, DD.Space.sm)
        // No "Hide phone" row here: the phone IS the sidebar, so the toolbar's sidebar toggle already
        // does exactly that (user feedback: two controls for one action). ⌥⌘S / View menu still work.
    }
}

struct PhoneTabsDetail: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    private var lastSub: [PhoneTabsShell.Tab: Destination] { chrome.lastSub }
    typealias Tab = PhoneTabsShell.Tab
    private var tab: Tab? { Tab.containing(model.selection) }


    private var tabBinding: Binding<Tab?> {
        Binding(get: { tab },
                set: { if let t = $0 { withDDPointerAnimation(Motion.screen) { model.selection = lastSub[t] ?? t.items[0] } } })
    }

    private var tabs: [DDTab<Tab?>] {
        Tab.allCases.map { t in
            DDTab(id: Optional(t), title: t.title, systemImage: t.symbol, badge: t == .inbox ? model.notifications.count : 0,
                  shortcut: t.items[0].shortcutKey.map { "⌘\($0)" })
        }
    }

    @State private var detailWidth: CGFloat = 700   // small until measured: icons can't overflow into the `»` menu, labels can
    private var textDirection: LayoutDirection { L10n.shared.isRTL ? .rightToLeft : .leftToRight }

    var body: some View {
        content
            .environment(\.layoutDirection, textDirection)
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { detailWidth = $0 }
            .hidesWindowTitle(model.selection.title)
            .toolbar {
                ToolbarItem(placement: .primaryAction) { ShellMoreMenu(destinations: PhoneTabsShell.extras) }
                ToolbarItem(placement: .principal) {
                    // Labelled tabs need ~360 pt plus the screen's own toolbar items (search, actions); below that the system
                    // pushes everything into the `»` overflow menu, so drop to icons (titles stay in the tooltips).
                    SegmentedTabBar(tabs: tabs, selection: tabBinding, iconOnly: detailWidth < 820, numberShortcuts: false).fixedSize()
                }
            }
            .onChange(of: model.selection, initial: true) { _, new in
                if let t = Tab.containing(new) { chrome.lastSub[t] = new }
            }
    }

    @ViewBuilder private var content: some View {
        switch tab {
        case .inbox: InboxHost()
        case .files: FilesHost()
        case .screen: ScreenHost()
        default: ScreenView(destination: model.selection)
        }
    }
}

/// Sidebar content that hosts the phone card as one row of a native `List(.sidebar)`, so the system sidebar material
/// shows around it. The card always takes exactly the height the column has (capped at 720 pt) and sheds rows to fit
/// (see `DDPhoneCard`), so there is nothing to scroll.
struct CardColumn<Content: View>: View {
    /// `true` inside an `.inspector`, which supplies its own material; a plain padded column keeps the card inside it.
    var inInspector = false
    @ViewBuilder var content: Content
    var body: some View {
        GeometryReader { g in
            if inInspector {
                content
                    .frame(height: max(0, min(720, g.size.height - LayoutMetrics.cardInset * 2)))
                    .padding(LayoutMetrics.cardInset)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                List {
                    content
                        .frame(height: max(0, min(720, g.size.height - DD.Space.md - DD.Space.xs * 2)))
                        // The sidebar list adds ~16 pt of its own margin per side (measured); pull the card out to `cardInset`.
                        .padding(.horizontal, LayoutMetrics.cardInset - 16)
                        .listRowInsets(EdgeInsets(top: DD.Space.xs, leading: 0, bottom: DD.Space.xs, trailing: 0))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                .listStyle(.sidebar)
                .scrollDisabled(true)
                .scrollIndicators(.never)
            }
        }
    }
}
