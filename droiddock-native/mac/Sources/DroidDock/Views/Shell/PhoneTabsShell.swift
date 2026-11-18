import SwiftUI

/// Default layout. Sidebar = the live phone card; detail = six toolbar tabs (Home first), each with an optional
/// sub-picker. The tabs are live: Inbox counts, Files shows a progress ring while transfers run, Screen a green dot while
/// streaming. Contacts, Devices, Now playing and Settings sit in a capsule pill under the card (and the Go menu).
/// The split view itself is `MainShell`'s; this layout supplies its columns (`PhoneTabsSidebar`, `PhoneTabsDetail`).
enum PhoneTabsShell {
    enum Tab: String, CaseIterable, Identifiable, Hashable {
        case home, inbox, files, apps, clipboard, screen
        var id: String { rawValue }
        var title: String {
            switch self {
            case .home: tr("Home")
            case .inbox: tr("Inbox")
            case .files: tr("Files")
            case .apps: tr("Apps")
            case .clipboard: tr("Clipboard")
            case .screen: tr("Screen")
            }
        }
        var symbol: String {
            switch self {
            case .home: "house"
            case .inbox: "tray"
            case .files: "folder"
            case .apps: "square.grid.2x2"
            case .clipboard: "doc.on.clipboard"
            case .screen: "rectangle.on.rectangle"
            }
        }
        var items: [Destination] {
            switch self {
            case .home: [.dashboard]
            case .inbox: [.notifications, .messages, .calls]
            case .files: [.files, .photos, .transfers]
            case .apps: [.apps]
            case .clipboard: [.clipboard]
            case .screen: [.mirror, .camera]
            }
        }
        static func containing(_ d: Destination) -> Tab? { allCases.first { $0.items.contains(d) } }
    }

    /// Not tabs: the capsule pill under the phone card, and the toolbar's More menu while the phone is hidden.
    static let extras: [Destination] = [.contacts, .devices, .media, .settings]
}

struct PhoneTabsSidebar: View {
    var body: some View {
        CardColumn { PhoneCard() }
            .safeAreaInset(edge: .bottom, spacing: 0) { PhoneTabsExtrasBar() }
            .environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)
            .navigationSplitViewColumnWidth(min: LayoutMetrics.phoneMin, ideal: LayoutMetrics.phoneIdeal, max: LayoutMetrics.phoneMax)
    }
}

/// The screens that aren't tabs (Contacts, Devices, Now playing, Settings), as a capsule pill under the card, in easy
/// reach at the bottom of the sidebar. It shares the tab bar's knob language: exactly one knob is on screen, either in the toolbar tabs or here, and it
/// glides between neighbours on a click (keys switch instantly). No "Hide phone" row: the phone IS the sidebar, so the
/// toolbar's sidebar toggle already does that (user feedback: two controls for one action). ⌥⌘S / View menu still work.
private struct PhoneTabsExtrasBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Namespace private var ns
    @State private var hovered: Destination?

    private static let track: CGFloat = 9, inset: CGFloat = 3

    var body: some View {
        HStack(spacing: 0) {
            ForEach(PhoneTabsShell.extras) { d in item(d) }
        }
        .padding(Self.inset)
        .background(Capsule(style: .continuous).fill(DD.panel2))
        .overlay(Capsule(style: .continuous).strokeBorder(DD.line.opacity(0.6), lineWidth: 0.5))
        .padding(.horizontal, LayoutMetrics.cardInset).padding(.top, DD.Space.xs).padding(.bottom, DD.Space.sm)
        .accessibilityElement(children: .contain)
    }

    private func item(_ d: Destination) -> some View {
        let on = model.selection == d
        let knob = Capsule(style: .continuous)
        return Button { withDDPointerAnimation(Motion.screen) { model.selection = d } } label: {
            Image(systemName: d.symbol)
                .font(.system(size: 14, weight: .regular))
                .symbolRenderingMode(.hierarchical)
                .symbolVariant(on ? .fill : .none)
                .contentTransition(.symbolEffect(.replace))
                .foregroundStyle(on ? Color.accentColor : (hovered == d ? DD.fg : DD.dim))
                .frame(maxWidth: .infinity).frame(height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle())
        // The knob sits OUTSIDE the button style: PressableButtonStyle springs a scaleEffect on press, and a
        // matchedGeometryEffect source under an animating scale changes its frame on every frame of that spring. The
        // window then re-ran Update Constraints synchronously, ~500 times inside one display cycle, until AppKit threw
        // (the 3.7.0 crash on contacts → devices and other pill clicks; reproduced with DD_PASS_WATCH=1 + `click:`).
        .background {
            if on {
                knob.fill(Color(nsColor: .controlColor))
                    .shadow(color: .black.opacity(scheme == .dark ? 0.25 : 0.12), radius: 0.5, y: 0.5)
                    .matchedGeometryEffect(id: "extrasKnob", in: ns)
            } else if hovered == d {
                knob.fill(DD.hover)
            }
        }
        .onHover { h in
            withDDAnimation(Motion.hover) {
                if h { hovered = d } else if hovered == d { hovered = nil }
            }
        }
        .help(d.helpText)
        .accessibilityLabel(d.title)
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
    }
}

struct PhoneTabsDetail: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    @State private var mirror = MirrorSession.shared
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
                  shortcut: t.items[0].shortcutKey.map { "⌘\($0)" }, accessory: accessory(t))
        }
    }

    /// Live marks on the tabs. Gated on cheap stored state so an idle tab carries no accessory (and no gap); the mark
    /// itself reads the fast-changing values (progress), so ticks don't rebuild the toolbar.
    private func accessory(_ t: Tab) -> AnyView? {
        switch t {
        case .files where model.activeTransferCount > 0: AnyView(TransferProgressRing(diameter: 11, animated: false))
        case .screen where mirror.isLive: AnyView(MirrorLiveDot(animated: false))
        default: nil
        }
    }

    /// Width the live accessories add to the labelled tab bar (see the `iconOnly` threshold).
    private var accessoryReserve: CGFloat {
        CGFloat((model.activeTransferCount > 0 ? 1 : 0) + (mirror.isLive ? 1 : 0)) * 22
    }

    /// Icons until measured (icons can't overflow into the `»` menu, labels can). Hysteresis + async: the bar's own width
    /// change must never flip this back inside the same layout pass.
    @State private var iconOnly = true
    private var textDirection: LayoutDirection { L10n.shared.isRTL ? .rightToLeft : .leftToRight }
    /// The extras live in the sidebar footer; the toolbar's More menu only stands in for it while the phone is hidden.

    var body: some View {
        content
            .environment(\.layoutDirection, textDirection)
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { w in
                let limit = 900 + accessoryReserve   // six labelled tabs
                let want = iconOnly ? w < limit + 40 : w < limit
                guard want != iconOnly else { return }
                DispatchQueue.main.async { if iconOnly != want { iconOnly = want } }
            }
            .hidesWindowTitle(model.selection.title)
            .toolbar {
                // Only while the phone (and with it the pill) is hidden; otherwise the pill already holds these.
                if chrome.effectivePhoneHidden(.phoneTabs) {
                    ToolbarItem(placement: .primaryAction) { ShellMoreMenu(destinations: PhoneTabsShell.extras) }
                }
                ToolbarItem(placement: .principal) {
                    // Labelled tabs need ~360 pt plus the screen's own toolbar items (search, actions); below that the system
                    // pushes everything into the `»` overflow menu, so drop to icons (titles stay in the tooltips).
                    // Each live accessory (ring, dot) widens its segment by about 20 pt: reserve it, so starting a transfer or a
                    // stream in a borderline window drops to icons instead of pushing the tabs into `»`.
                    SegmentedTabBar(tabs: tabs, selection: tabBinding, iconOnly: iconOnly,
                                    numberShortcuts: false).fixedSize()
                }
            }
            .onChange(of: model.selection, initial: true) { _, new in
                if let t = Tab.containing(new) { chrome.lastSub[t] = new }
            }
    }

    /// Each tab's host keeps its own identity, so a click cross-fades with a 4 pt settle (Motion.screen via the binding);
    /// a key press swaps instantly (withDDPointerAnimation drops the animation).
    @ViewBuilder private var content: some View {
        Group {
            switch tab {
            case .inbox: InboxHost()
            case .files: FilesHost()
            case .screen: ScreenHost()
            default: ScreenView(destination: model.selection)
            }
        }
        // No per-tab `.id` + transition here: during the fade both tabs' screens were live and both contributed toolbar
        // items, re-laying out the NSToolbar every frame (crash on tab switch, 3.7.0). ScreenView keeps its own
        // per-destination cross-fade, as in 3.6.2.
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
