import SwiftUI

/// Classic: a Finder/Mail-style three-pane window. The sidebar holds no phone card; the phone is one device row at the
/// top (like an iPhone under Finder's Locations), followed by foldable sections with live counts. The card itself lives
/// only in the trailing inspector, which the device row and the toolbar toggle open.
/// The split view itself is `MainShell`'s; this layout supplies its columns (`ClassicSidebar`, `ClassicDetail`).
struct ClassicSidebar: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    /// Folded section titles (English keys), `|`-separated. Per layout, so Grouped's folds don't leak in.
    @AppStorage("droiddock.classic.foldedSections") private var foldedRaw = ""

    private var collapsed: Bool { chrome.isCollapsed(.classic) }
    private static var titledGroups: [(title: String, items: [Destination])] { Destination.classicGroups.filter { !$0.title.isEmpty } }
    private static var untitled: [Destination] { Destination.classicGroups.filter { $0.title.isEmpty }.flatMap(\.items) }

    var body: some View {
        @Bindable var m = model
        List(selection: Binding(get: { m.selection },
                                set: { v in if let v { withDDPointerAnimation(Motion.screen) { model.selection = v } } })) {
            if collapsed {
                // Icon rail: the phone, the screens in one run, then Settings on its own so the gap matches the expanded list.
                Section { ClassicDeviceRow(compact: true) }
                Section { ForEach(Destination.classicGroups.flatMap(\.items)) { d in row(d) } }
                Section { row(.settings) }
            } else {
                Section { ClassicDeviceRow() }
                Section { ForEach(Self.untitled) { d in row(d) } }
                ForEach(Self.titledGroups, id: \.title) { group in
                    Section(isExpanded: expanded(group.title)) {
                        ForEach(group.items) { d in row(d) }
                    } header: {
                        header(group.title, items: group.items)
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
        // ⌘-shortcuts, the Go menu and the command bar can land on a screen inside a folded section: unfold it so the
        // selection is never hidden. Unanimated for keys (withDDPointerAnimation checks the event). `initial`: a selection
        // already inside a folded section when the sidebar appears (launch, layout switch) unfolds too.
        .onChange(of: model.selection, initial: true) { _, d in
            guard let g = Self.titledGroups.first(where: { $0.items.contains(d) }), folded.contains(g.title) else { return }
            withDDPointerAnimation(Motion.expand) { setFolded(g.title, false) }
        }
        .navigationSplitViewColumnWidth(min: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listMin,
                                        ideal: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listIdeal,
                                        max: collapsed ? LayoutMetrics.railWidth : LayoutMetrics.listMax)
    }

    // MARK: Rows

    @ViewBuilder private func row(_ d: Destination) -> some View {
        if collapsed {
            RailIcon(destination: d, badge: badge(d)).tag(d)
        } else if d == .transfers {
            // Finder-style: a live progress ring trails the row while something is moving, instead of a bare count.
            HStack(spacing: DD.Space.xs) {
                Label(d.title, systemImage: d.symbol)
                Spacer(minLength: 0)
                TransferProgressRing()
            }
            .tag(d).help(d.helpText)
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

    /// Section header. A folded section that hides a badge shows a small accent dot, so unread items aren't buried.
    private func header(_ title: String, items: [Destination]) -> some View {
        let hidden = folded.contains(title) && items.contains { badge($0) > 0 }
        return HStack(spacing: 6) {
            Text(tr(title))
            if hidden {
                Circle().fill(Color.accentColor).frame(width: 6, height: 6)
                    .ddTransition(.scale(scale: 0.5).combined(with: .opacity))
                    .accessibilityHidden(true)
            }
        }
        .ddAnimation(Motion.select, value: hidden)
    }

    // MARK: Folding

    private var folded: Set<String> { Set(foldedRaw.split(separator: "|").map(String.init)) }

    private func setFolded(_ title: String, _ on: Bool) {
        var s = folded
        if on { s.insert(title) } else { s.remove(title) }
        let raw = s.sorted().joined(separator: "|")
        if raw != foldedRaw { foldedRaw = raw }
    }

    private func expanded(_ title: String) -> Binding<Bool> {
        Binding(get: { !folded.contains(title) },
                set: { open in withDDPointerAnimation(Motion.expand) { setFolded(title, !open) } })
    }
}

// MARK: - Device row

/// The phone as one sidebar row: glyph with a status dot, name, and a status line (transport and battery when linked).
/// Click toggles the phone inspector; drop files on it to send them (like dropping on a device in Finder).
struct ClassicDeviceRow: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    @State private var hovering = false
    @State private var dropTargeted = false
    /// Icon-rail form: only the glyph and its status dot.
    var compact = false

    private enum Phase: Equatable { case connected, reconnecting, offline, unpaired }

    private var isPaired: Bool {
        (model.config?.deviceName?.isEmpty == false) || model.config?.deviceGuid != nil
    }
    private var phase: Phase {
        if model.isConnected { return .connected }
        if !isPaired { return .unpaired }
        return model.isReconnecting ? .reconnecting : .offline
    }
    private var panelOpen: Bool { !chrome.effectivePhoneHidden(.classic) }
    /// The screen is too narrow for list + detail + inspector, so the panel stays auto-hidden whatever is clicked.
    private var panelFits: Bool {
        LayoutMetrics.minWidth(.classic, phoneHidden: false, collapsed: chrome.isCollapsed(.classic)) <= WindowFit.availableWidth()
    }

    private var name: String {
        switch phase {
        case .unpaired: tr("Pair your phone")
        default: model.wifi.phoneName.flatMap { $0.isEmpty ? nil : $0 } ?? tr("Phone")
        }
    }
    private var status: String {
        switch phase {
        case .connected: model.pillTransport.title
        case .reconnecting: tr("Reconnecting…")
        case .offline: tr("Not connected")
        case .unpaired: tr("Waiting for phone")
        }
    }
    private var dotColor: Color {
        switch phase {
        case .connected: DD.ok
        case .reconnecting: DD.warn
        case .offline, .unpaired: DD.faint
        }
    }

    var body: some View {
        Button(action: activate) {
            if compact { railBody } else { fullBody }
        }
            .buttonStyle(PressableButtonStyle())
            .onHover { h in withDDAnimation(Motion.hover) { hovering = h } }
            .dropDestination(for: URL.self) { urls, _ in
                guard model.isConnected else { model.toast(.info, tr("Link your phone to send files")); return false }
                ShellActions.sendFiles(urls)
                Haptics.generic()
                return true
            } isTargeted: { t in withDDAnimation(Motion.hover) { dropTargeted = t } }
            .contextMenu {
                Button(panelOpen ? tr("Hide phone") : tr("Show phone")) { activate() }.disabled(!panelFits)
                if model.isConnected {
                    Divider()
                    Button(tr("Send File…")) { ShellActions.pickAndSend() }
                    Button(tr("Ring Phone")) { ShellActions.toggleRing() }
                    Button(tr("Take Screenshot")) { ShellActions.screenshot() }
                }
            }
            .ddAnimation(Motion.state, value: phase)
            .ddAnimation(Motion.hover, value: panelOpen)
            .help(dropTargeted ? tr("Drop to send to phone") : "\(tr("Phone panel"))  ⌥⌘S")
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
            .accessibilityAddTraits(panelOpen ? [.isButton, .isSelected] : .isButton)
            .listRowInsets(compact ? EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0) : nil)
    }

    private var fullBody: some View {
        HStack(spacing: 10) {
            glyph(size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(name).font(DD.Font.body).fontWeight(.semibold).foregroundStyle(DD.fg).lineLimit(1)
                    .contentTransition(.opacity)
                statusLine
            }
            Spacer(minLength: 0)
            // Panel affordance: shows on hover, and stays (accent) while the panel is open.
            Image(systemName: "sidebar.trailing")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(panelOpen ? Color.accentColor : DD.dim)
                .opacity(panelOpen || hovering ? 1 : 0)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 6).padding(.vertical, 5)
        .background(rowBackground(radius: 8))
        .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var railBody: some View {
        glyph(size: 28)
            .padding(2)
            .background(rowBackground(radius: 8))
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
    }

    private var statusLine: some View {
        HStack(spacing: 4) {
            if phase == .connected {
                Image(systemName: model.pillTransport.symbol).font(.system(size: 9, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
            }
            Text(status).contentTransition(.opacity)
            if phase == .connected, let b = model.batteryPercent {
                Text(verbatim: "·").foregroundStyle(DD.faint)
                if model.isCharging {
                    Image(systemName: "bolt.fill").font(.system(size: 8)).foregroundStyle(DD.okText)
                        .ddTransition(.scale(scale: 0.6).combined(with: .opacity))
                }
                Text(verbatim: "\(b)%").monospacedDigit()
                    .foregroundStyle(b <= 20 && !model.isCharging ? DD.badText : DD.dim)
                    .contentTransition(.numericText(value: Double(b)))
                    .ddAnimation(Motion.select, value: b)
            }
        }
        .font(DD.Font.caption).foregroundStyle(DD.dim).lineLimit(1)
        .ddAnimation(Motion.select, value: model.isCharging)
    }

    private func glyph(size: CGFloat) -> some View {
        Image(systemName: phase == .unpaired ? "qrcode" : "smartphone")
            .font(.system(size: size * 0.55, weight: .regular))
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(phase == .connected ? DD.fg : DD.dim)
            .contentTransition(.symbolEffect(.replace))
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(DD.panel2))
            .overlay(alignment: .bottomTrailing) {
                Circle().fill(dotColor).frame(width: 8, height: 8)
                    .overlay(Circle().strokeBorder(Color(nsColor: .windowBackgroundColor), lineWidth: 1.5))
                    .offset(x: 2, y: 2)
                    .accessibilityHidden(true)
            }
    }

    private func rowBackground(radius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return shape.fill(dropTargeted ? Color.accentColor.opacity(0.14) : (hovering ? DD.hover : .clear))
            .overlay(shape.strokeBorder(dropTargeted ? Color.accentColor : .clear, lineWidth: 2))
    }

    private var accessibilityText: String {
        var s = "\(name), \(status)"
        if phase == .connected, let b = model.batteryPercent {
            s += ", " + tr("Battery {n} percent", ["n": b]) + (model.isCharging ? ", " + tr("charging") : "")
        }
        return s
    }

    private func activate() {
        // A screen too narrow for the inspector keeps it auto-hidden; Dashboard then carries the phone instead.
        guard panelFits else { withDDPointerAnimation(Motion.screen) { model.selection = .dashboard }; return }
        chrome.setPhoneHidden(.classic, panelOpen)
    }
}

// MARK: - Detail

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
