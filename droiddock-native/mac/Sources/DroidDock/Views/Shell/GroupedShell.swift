import SwiftUI

/// Grouped: a Reminders/Mail-style source list. A compact phone header row (it opens Home), a 2×2 grid of live "glance"
/// tiles for the inbox (Notifications, Messages, Calls, Transfers), then folding groups (Files, Phone) and Settings.
/// There is no big phone card: the Dashboard is the hero and the single owner of the pairing QR; the full card is one
/// click away in a popover from the header while a phone is linked.
/// The split view itself is `MainShell`'s; this layout supplies its columns (`GroupedSidebar`, `GroupedDetail`).
enum GroupedShell {
    static let tiles: [Destination] = [.notifications, .messages, .calls, .transfers]
    static let folds: [Fold] = [
        Fold(id: "files", title: "Files", items: [.files, .photos]),
        Fold(id: "phone", title: "Phone", items: [.apps, .mirror, .camera, .media, .clipboard, .contacts, .devices]),
    ]
    /// The collapsed rail: every destination in the expanded sidebar's order.
    static let railOrder: [Destination] = [.dashboard] + tiles + folds.flatMap(\.items) + [.settings]

    struct Fold: Identifiable {
        let id: String
        /// English key; `tr` at display time.
        let title: String
        let items: [Destination]
        var defaultsKey: String { "droiddock.grouped.expanded.\(id)" }
    }

    /// Filled glyph for a tile (white on its colour circle).
    static func tileSymbol(_ d: Destination) -> String {
        switch d {
        case .notifications: "bell.fill"
        case .messages: "message.fill"
        case .calls: "phone.fill"
        default: d.symbol
        }
    }

    /// System colour behind each tile's glyph (Reminders-style smart-list tiles). Never the accent: that means "selected".
    static func tileColor(_ d: Destination) -> Color {
        switch d {
        case .notifications: Color(nsColor: .systemRed)
        case .messages: Color(nsColor: .systemBlue)
        case .calls: Color(nsColor: .systemGreen)
        default: Color(nsColor: .systemOrange)
        }
    }
}

struct GroupedSidebar: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    /// Fold state per group, persisted (default: open).
    @State private var expanded: [String: Bool] = Dictionary(uniqueKeysWithValues: GroupedShell.folds.map {
        // `bool(forKey:)` also reads "NO"/"0" strings (argument domain); `as? Bool` treated those as "never set".
        ($0.id, UserDefaults.standard.object(forKey: $0.defaultsKey) == nil ? true : UserDefaults.standard.bool(forKey: $0.defaultsKey))
    })

    private var collapsed: Bool { chrome.isCollapsed(.grouped) }
    private var showHeader: Bool { !chrome.isPhoneHidden(.grouped) }

    private func select(_ d: Destination) {
        withDDPointerAnimation(Motion.screen) { model.selection = d }
    }

    var body: some View {
        @Bindable var m = model
        List(selection: Binding(get: { m.selection }, set: { v in if let v { select(v) } })) {
            if collapsed {
                // One flat section: headers and gaps would push the last icons (Settings) off a short window.
                Section {
                    ForEach(GroupedShell.railOrder) { d in RailIcon(destination: d, badge: badge(d)).tag(d) }
                }
            } else {
                Section {
                    if showHeader {
                        GroupedPhoneHeader().tag(Destination.dashboard)
                    } else {
                        Label(Destination.dashboard.title, systemImage: Destination.dashboard.symbol)
                            .tag(Destination.dashboard).help(Destination.dashboard.helpText)
                    }
                }
                Section {
                    // Not a tagged row: the tiles are their own selection, so the list never draws a second highlight.
                    GroupedGlanceGrid(selection: model.selection, onSelect: select)
                        .listRowInsets(EdgeInsets(top: DD.Space.xs, leading: 0, bottom: DD.Space.xs, trailing: 0))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                ForEach(GroupedShell.folds) { fold in
                    Section(isExpanded: expandedBinding(fold)) {
                        ForEach(fold.items) { d in
                            Label(d.title, systemImage: d.symbol).tag(d).help(d.helpText)
                        }
                    } header: {
                        GroupedFoldHeader(fold: fold, isExpanded: expanded[fold.id] ?? true)
                    }
                }
                Section {
                    Label(Destination.settings.title, systemImage: Destination.settings.symbol)
                        .tag(Destination.settings).help(Destination.settings.helpText)
                }
            }
        }
        .listStyle(.sidebar)
        // The rail can't hold the system toggle (it spills into the toolbar's `»` menu). The modifier only works on the
        // sidebar column's content, not on the split view or detail. The rail's own button and ⌃⌘S replace it.
        .toolbar(removing: collapsed ? .sidebarToggle : nil)
        .environment(\.sidebarRowSize, collapsed ? .small : .medium)
        // A destination inside a folded group (⌘5, Go menu, a card action) would select an invisible row: open its group.
        .onChange(of: model.selection, initial: true) { _, d in
            guard let fold = GroupedShell.folds.first(where: { $0.items.contains(d) }), expanded[fold.id] == false else { return }
            withDDPointerAnimation(Motion.expand) { setExpanded(fold, true) }
        }
        .navigationSplitViewColumnWidth(min: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneMin,
                                        ideal: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneIdeal,
                                        max: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.phoneMax)
    }

    private func expandedBinding(_ fold: GroupedShell.Fold) -> Binding<Bool> {
        Binding(get: { expanded[fold.id] ?? true },
                set: { v in withDDPointerAnimation(Motion.expand) { setExpanded(fold, v) } })
    }

    private func setExpanded(_ fold: GroupedShell.Fold, _ v: Bool) {
        guard expanded[fold.id] != v else { return }
        expanded[fold.id] = v
        UserDefaults.standard.set(v, forKey: fold.defaultsKey)
    }

    private func badge(_ d: Destination) -> Int {
        switch d {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: 0
        }
    }
}

// MARK: - Phone header

/// One compact row for the phone (Find My / Music account row): wallpaper thumbnail, name, one status line. Selecting it
/// opens Home. While linked, a trailing info button opens the full phone card in a popover. It never shows the QR.
private struct GroupedPhoneHeader: View {
    @Environment(AppModel.self) private var model
    @State private var shell = ShellState.shared
    @State private var showCard = false

    private var isPaired: Bool { (model.config?.deviceName?.isEmpty == false) || model.config?.deviceGuid != nil }

    private enum Phase: Equatable { case linked, connecting, offline, unpaired }
    private var phase: Phase {
        if model.isConnected { return .linked }
        if !isPaired { return .unpaired }
        return model.isReconnecting ? .connecting : .offline
    }

    private var title: String {
        switch phase {
        case .linked: model.phoneName ?? tr("Phone")
        case .unpaired: tr("Pair your phone")
        case .connecting, .offline: model.wifi.phoneName ?? tr("Phone")
        }
    }

    var body: some View {
        HStack(spacing: DD.Space.sm + 2) {
            thumbnail
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(DD.Font.headline).lineLimit(1).truncationMode(.middle)
                status.font(DD.Font.caption).foregroundStyle(.secondary).lineLimit(1)
                    .id(phase).ddTransition(.ddRise)
            }
            Spacer(minLength: 0)
            if phase == .linked {
                Button { showCard.toggle() } label: {
                    Image(systemName: "info.circle").font(.system(size: 14)).foregroundStyle(.secondary)
                        .frame(width: 22, height: 22).contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .help(tr("Phone panel"))
                .accessibilityLabel(tr("Phone panel"))
                .popover(isPresented: $showCard, arrowEdge: .trailing) {
                    // Same host as Minimal's popover: the popover is its own window, so pass the model and appearance in.
                    PhoneCard()
                        .frame(width: LayoutMetrics.phoneIdeal, height: 580)
                        .padding(DD.Space.xs + 2)   // concentric with the popover edge
                        .environment(model)
                        .ddAppearance(AppearanceSettings.shared)
                }
                .transition(.opacity)
            }
        }
        .padding(.vertical, DD.Space.xs)
        .ddAnimation(Motion.state, value: phase)
        .contentShape(Rectangle())
        .help(Destination.dashboard.helpText)
        .accessibilityElement(children: .combine)
        .accessibilityHint(Destination.dashboard.title)
        .onChange(of: model.isConnected) { _, live in if !live { showCard = false } }
    }

    /// 32 pt rounded square: the phone's wallpaper while linked, otherwise a state glyph.
    private var thumbnail: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous)
        return ZStack {
            if phase == .linked, let w = shell.wallpaper {
                Image(nsImage: w).resizable().scaledToFill()
                    .transition(.opacity)
            } else {
                shape.fill(DD.panel2)
                Image(systemName: glyph)
                    .font(.system(size: 15, weight: .medium))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .contentTransition(.symbolEffect(.replace))
            }
        }
        .frame(width: DD.Size.avatar, height: DD.Size.avatar)
        .clipShape(shape)
        .overlay(shape.strokeBorder(DD.containerEdge, lineWidth: 1))
        .overlay(alignment: .bottomTrailing) {
            // Link dot on the thumbnail's corner: green while linked, nothing otherwise (the status line says why).
            if phase == .linked {
                Circle().fill(DD.link).frame(width: 9, height: 9)
                    .overlay(Circle().strokeBorder(Color(nsColor: .windowBackgroundColor), lineWidth: 1.5))
                    .offset(x: 2, y: 2)
                    .ddTransition(.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .accessibilityHidden(true)
    }

    private var glyph: String {
        switch phase {
        case .linked: "iphone"
        case .connecting: "iphone.radiowaves.left.and.right"
        case .offline: "iphone.slash"
        case .unpaired: "qrcode"
        }
    }

    @ViewBuilder private var status: some View {
        switch phase {
        case .linked:
            HStack(spacing: DD.Space.xs) {
                if let b = model.batteryPercent {
                    Text(verbatim: "\(b)%").monospacedDigit()
                        .contentTransition(.numericText(value: Double(b)))
                        .ddAnimation(Motion.content, value: b)
                    if model.isCharging { Image(systemName: "bolt.fill").imageScale(.small) }
                    Text(verbatim: "·")
                }
                Text(model.pillTransport.title)
            }
        case .connecting: Text(tr("Reconnecting…"))
        case .offline: Text(tr("Not connected"))
        case .unpaired: Text(tr("Not connected"))
        }
    }
}

// MARK: - Glance tiles

/// Reminders-style smart tiles: glyph on a system-coloured circle, a live count, the title. The selected tile fills with
/// the user's accent (grey in an inactive window) and the fill glides between tiles.
private struct GroupedGlanceGrid: View {
    let selection: Destination
    let onSelect: (Destination) -> Void
    @Namespace private var ns

    var body: some View {
        Grid(horizontalSpacing: DD.Space.sm, verticalSpacing: DD.Space.sm) {
            GridRow { tile(0); tile(1) }
            GridRow { tile(2); tile(3) }
        }
        .padding(.horizontal, LayoutMetrics.cardInset - 16 + DD.Space.xs)
    }

    private func tile(_ i: Int) -> some View {
        let d = GroupedShell.tiles[i]
        return GroupedGlanceTile(destination: d, isSelected: selection == d, ns: ns) { onSelect(d) }
    }
}

private struct GroupedGlanceTile: View {
    let destination: Destination
    let isSelected: Bool
    let ns: Namespace.ID
    let action: () -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.controlActiveState) private var activeState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false
    /// Bumped only when the count rises, so the glyph bounces for news and never for a dismissal.
    @State private var bumps = 0

    private var count: Int? {
        switch destination {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: nil
        }
    }
    private var callLive: Bool { destination == .calls && model.activeCall != nil }
    private var keyWindow: Bool { activeState == .key || activeState == .active }
    /// Text on the selected fill: white on the accent, the label colour on the inactive grey.
    private var onFill: Bool { isSelected && keyWindow }
    private var radius: CGFloat { DD.Radius.lg }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: DD.Space.sm) {
                HStack(alignment: .top) {
                    glyph
                    Spacer(minLength: DD.Space.xs)
                    trailing
                }
                Text(destination.title)
                    .font(DD.Font.bodyEmphasis)
                    .foregroundStyle(onFill ? DD.accentInk : DD.dim)
                    .lineLimit(1).minimumScaleFactor(0.85)
            }
            .padding(.horizontal, DD.Space.sm + 2).padding(.vertical, DD.Space.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { background }
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        }
        .buttonStyle(PressableButtonStyle())
        .onHover { hovering = $0 }
        .ddAnimation(Motion.hover, value: hovering)
        // No implicit animation on `isSelected`: the Go menu's ⌘0–⌘7 changes selection without a pointer transaction and
        // must stay instant. Clicks animate through `withDDPointerAnimation` at the call site.
        .onChange(of: count ?? 0) { old, new in if new > old { bumps += 1 } }
        .help(destination.helpText)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    private var glyph: some View {
        Circle()
            .fill(onFill ? Color.white : GroupedShell.tileColor(destination))
            .frame(width: 26, height: 26)
            .overlay {
                Image(systemName: GroupedShell.tileSymbol(destination))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(onFill ? GroupedShell.tileColor(destination) : Color.white)
                    .symbolEffect(.bounce, options: .nonRepeating, value: reduceMotion ? 0 : bumps)
            }
    }

    @ViewBuilder private var trailing: some View {
        if let count {
            Text(verbatim: "\(count)")
                .font(.system(size: 20, weight: .semibold, design: .rounded)).monospacedDigit()
                .foregroundStyle(onFill ? DD.accentInk : (count > 0 ? DD.fg : DD.faint))
                .contentTransition(.numericText(value: Double(count)))
                .ddAnimation(Motion.content, value: count)
        } else if callLive {
            Text(tr("Live"))
                .font(DD.Font.label)
                .foregroundStyle(onFill ? DD.accentInk : DD.okText)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(onFill ? Color.white.opacity(0.22) : DD.ok.opacity(0.14)))
                .ddTransition(.opacity.combined(with: .scale(scale: 0.96)))
        }
    }

    @ViewBuilder private var background: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        ZStack {
            shape.fill(hovering && !isSelected ? DD.panel3 : DD.panel2)
            if isSelected {
                // The fill glides between tiles; under Reduce Motion it just cross-fades in place.
                if reduceMotion {
                    shape.fill(keyWindow ? DD.selection : DD.selectionInactive).transition(.opacity)
                } else {
                    shape.fill(keyWindow ? DD.selection : DD.selectionInactive)
                        .matchedGeometryEffect(id: "groupedTileSelection", in: ns)
                        .transition(.opacity)
                }
            }
        }
    }

    private var accessibilityText: String {
        var s = destination.title
        if let count { s += ", \(count)" }
        if callLive { s += ", " + tr("Live") }
        return s
    }
}

// MARK: - Fold header

/// Group header. While folded it keeps hidden state visible: a dot when something inside is live (mirroring, music).
private struct GroupedFoldHeader: View {
    let fold: GroupedShell.Fold
    let isExpanded: Bool
    @Environment(AppModel.self) private var model

    private var liveInside: Bool {
        fold.items.contains { d in
            switch d {
            case .mirror: model.mirror != nil
            case .media: model.media?.active == true && model.media?.playing == true
            default: false
            }
        }
    }

    var body: some View {
        HStack(spacing: DD.Space.xs + 2) {
            Text(tr(fold.title))
            if !isExpanded && liveInside {
                Circle().fill(Color.accentColor).frame(width: 6, height: 6)
                    .ddTransition(.scale(scale: 0.6).combined(with: .opacity))
                    .accessibilityLabel(tr("Live"))
            }
        }
        .ddAnimation(Motion.state, value: !isExpanded && liveInside)
    }
}

// MARK: - Detail

struct GroupedDetail: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared

    private var collapsed: Bool { chrome.isCollapsed(.grouped) }

    var body: some View {
        ScreenView(destination: model.selection)
            .inlineTitle(model.selection.title, subtitle: model.shellSubtitle)
            .toolbar {
                RailToggleItem(layout: .grouped, collapsed: collapsed)
                // The rail has no phone header to hide, so the toggle would do nothing there: shown only when expanded.
                ToolbarItem(placement: .primaryAction) {
                    if !collapsed {
                        Toggle(isOn: Binding(get: { !chrome.isPhoneHidden(.grouped) }, set: { chrome.setPhoneHidden(.grouped, !$0) })) {
                            Image(systemName: "iphone")
                        }
                        .toggleStyle(.button)
                        .help("\(tr("Phone panel"))  ⌥⌘S")
                        .accessibilityLabel(tr("Phone panel"))
                    }
                }
            }
    }
}
