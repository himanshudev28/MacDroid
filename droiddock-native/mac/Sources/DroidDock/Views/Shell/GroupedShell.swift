import SwiftUI

/// A compact phone card on top, then the destinations in four short groups (Inbox, Files, Phone, Mac).
struct GroupedShell: View {
    @Environment(AppModel.self) private var model
    @State private var pairSheet = false
    @State private var chrome = ShellChrome.shared

    private var collapsed: Bool { chrome.isCollapsed(.grouped) }

    private var visibility: Binding<NavigationSplitViewVisibility> {
        Binding(get: { .all }, set: { if $0 == .detailOnly { chrome.toggleSidebar(.grouped) } })
    }

    private static let groups: [(String, [Destination])] = [
        ("Inbox", [.notifications, .messages, .calls]),
        ("Files", [.files, .photos, .transfers]),
        ("Phone", [.apps, .mirror, .camera, .media, .clipboard, .contacts, .devices]),
        ("Mac", [.dashboard, .settings]),
    ]

    var body: some View {
        @Bindable var m = model
        NavigationSplitView(columnVisibility: visibility) {
            VStack(spacing: 0) {
                if !collapsed && !chrome.isPhoneHidden(.grouped) {
                    // Pinned above the list (not a row of it) so the phone summary never scrolls away; only the
                    // destinations below can scroll, and only in a window too short for all of them.
                    CompactPhoneCard(onPair: { pairSheet = true })
                        .padding(.horizontal, DD.Space.sm).padding(.top, DD.Space.sm).padding(.bottom, DD.Space.xs)
                }
                List(selection: Binding(get: { m.selection },
                           set: { v in if let v { withDDPointerAnimation(Motion.screen) { model.selection = v } } })) {
                    ForEach(Self.groups, id: \.0) { title, items in
                        Section(collapsed ? "" : tr(title)) {
                            ForEach(items) { d in
                                if collapsed {
                                    RailIcon(destination: d, badge: badge(d))
                                } else {
                                    Label(d.title, systemImage: d.symbol).badge(badge(d)).tag(d)
                                }
                            }
                        }
                    }
                }
                .listStyle(.sidebar)
                .scrollIndicators(.never)
                // The rail can't hold the system toggle (it spills into the toolbar's `»` menu). The modifier only works on the
                // sidebar column's content, not on the split view or detail. The rail's own button and ⌃⌘S replace it.
                .toolbar(removing: collapsed ? .sidebarToggle : nil)
                SidebarChromeBar(layout: .grouped, iconOnly: collapsed, inline: true)
            }
            .navigationSplitViewColumnWidth(min: collapsed ? RailIcon.width : 264, ideal: collapsed ? RailIcon.width : 280, max: collapsed ? RailIcon.width : 320)
        } detail: {
            ScreenView(destination: model.selection)
                .navigationTitle(model.selection.title)
                .toolbar { RailToggleItem(layout: .grouped, collapsed: collapsed) }
        }
        .sheet(isPresented: $pairSheet) {
            VStack(spacing: DD.Space.lg) {
                PhoneCard().frame(width: 300)
                Button(tr("Done")) { pairSheet = false }
                    .buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
            }
            .padding(DD.Space.xl)
            .environment(model)
            .onChange(of: model.isConnected) { _, c in if c { pairSheet = false } }
        }
    }

    private func badge(_ d: Destination) -> Int {
        switch d {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: 0
        }
    }
}

/// ~230 pt phone summary for the grouped layout: wallpaper (or aurora), status, clock, quick actions, a one-line player.
struct CompactPhoneCard: View {
    @Environment(AppModel.self) private var model
    var onPair: () -> Void
    @State private var shell = ShellState.shared
    @State private var dropTargeted = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.xl + 4, style: .continuous)
        Group {
            if model.isConnected { connected } else { offline }
        }
        .padding(DD.Space.md)
        .frame(maxWidth: .infinity)
        .background { backdrop }
        .clipShape(shape)
        .overlay(shape.strokeBorder(DD.OnCard.stroke, lineWidth: 1))
        .overlay {
            if dropTargeted {
                ZStack {
                    shape.fill(Color.black.opacity(0.45))
                    shape.inset(by: 5).strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
                    Label(tr("Drop to send to phone"), systemImage: "arrow.down.doc")
                        .font(DD.Font.headline).foregroundStyle(DD.OnCard.primary)
                }.transition(.opacity)
            }
        }
        .environment(\.colorScheme, .dark)
        .dropDestination(for: URL.self) { urls, _ in
            guard model.isConnected else { return false }
            ShellActions.sendFiles(urls); return true
        } isTargeted: { dropTargeted = $0 }
        .ddAnimation(Motion.state, value: model.isConnected)
        .ddAnimation(Motion.content, value: dropTargeted)
    }

    private var isPaired: Bool { model.config?.deviceGuid != nil }
    private var np: DDNowPlaying? { shell.nowPlaying(model) }

    private var connected: some View {
        VStack(spacing: DD.Space.sm) {
            HStack {
                ConnectionPill(transport: model.pillTransport, link: model.linkQuality.map { LinkQualityLevel(grade: $0.grade) },
                               rttMs: nil, style: .onCard)
                Spacer(minLength: 0)
                if let b = model.batteryPercent {
                    HStack(spacing: 5) {
                        BatteryGlyph(level: b, charging: model.isCharging)
                        Text(verbatim: "\(b)%").font(DD.Font.label).monospacedDigit()
                            .foregroundStyle(DD.OnCard.primary)
                            .contentTransition(.numericText(value: Double(b)))
                    }
                    .padding(.horizontal, 9).frame(height: 24)
                    .background(Capsule().fill(DD.OnCard.fill))
                    .overlay(Capsule().strokeBorder(DD.OnCard.stroke, lineWidth: 1))
                }
            }
            VStack(spacing: DD.Space.sm) {
                Text(model.phoneName ?? "").font(DD.Font.label)
                    .foregroundStyle(DD.OnCard.secondary).lineLimit(1)
                    .padding(.horizontal, 9).frame(height: 20)
                    .background(Capsule().fill(DD.OnCard.fill))
                PhoneClock(compact: true)
            }
            .padding(.vertical, DD.Space.xs)
            if let np {
                HStack(spacing: DD.Space.sm) {
                    Image(systemName: "music.note").font(DD.Font.label).foregroundStyle(DD.OnCard.secondary)
                    Text(np.artist.map { "\(np.title) · \($0)" } ?? np.title)
                        .font(DD.Font.callout).foregroundStyle(DD.OnCard.primary).lineLimit(1)
                    Spacer(minLength: 0)
                    OnCardCircleButton(systemImage: np.isPlaying ? "pause.fill" : "play.fill",
                                       label: np.isPlaying ? tr("Pause") : tr("Play"), size: 24) {
                        ShellActions.media(.playPause)
                    }
                }
                .padding(.leading, 10).padding(.trailing, 4).frame(height: 32)
                .background(Capsule().fill(DD.OnCard.fill))
                .overlay(Capsule().strokeBorder(DD.OnCard.stroke, lineWidth: 1))
                .transition(.opacity)
            }
            QuickActionsRow(actions: Array(PhoneCard.quickActions(.standard, model: model, shell: shell,
                                                                   onNavigate: { model.selection = $0 }).prefix(5)))
        }
    }

    private var offline: some View {
        VStack(spacing: DD.Space.sm) {
            Image(systemName: "iphone.gen3").font(.system(size: 30))
                .foregroundStyle(DD.OnCard.secondary)
            Text(model.wifi.phoneName.map { tr("{name} is offline", ["name": $0]) } ?? (isPaired ? tr("Waiting for phone") : tr("No phone linked")))
                .font(DD.Font.title3).foregroundStyle(DD.OnCard.primary).lineLimit(1)
            Button(action: onPair) { Label(tr("Pair a phone"), systemImage: "qrcode") }
                .buttonStyle(.bordered).controlSize(.regular)
        }
        .frame(minHeight: 150)
    }

    @ViewBuilder private var backdrop: some View {
        let tint = shell.tint ?? Color.accentColor
        ZStack {
            LinearGradient(colors: [tint.opacity(0.85), Color.accentColor.opacity(0.45), Color.black.opacity(0.9)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            if let w = shell.wallpaper, model.isConnected {
                GeometryReader { g in
                    Image(nsImage: w).resizable().scaledToFill().frame(width: g.size.width, height: g.size.height).clipped()
                }
            }
            LinearGradient(stops: [.init(color: .black.opacity(0.50), location: 0),
                                   .init(color: .black.opacity(0.20), location: 0.45),
                                   .init(color: .black.opacity(0.62), location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
    }
}
