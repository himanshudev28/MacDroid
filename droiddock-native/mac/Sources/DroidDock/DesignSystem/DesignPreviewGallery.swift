import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// Shows every design-system component, light and dark side by side. Debug window only.
struct DesignPreviewGallery: View {
    enum Section: String, CaseIterable, Identifiable {
        case phone, lists, controls, states, native
        var id: String { rawValue }
        var title: String {
            switch self {
            case .phone: "Phone card"
            case .lists: "Rows"
            case .controls: "Controls"
            case .states: "States"
            case .native: "vs Native"
            }
        }
    }

    @Bindable var settings: AppearanceSettings
    @State private var section: Section

    @MainActor init(settings: AppearanceSettings? = nil, section: Section = .phone) {
        self.settings = settings ?? .shared
        _section = State(initialValue: section)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: DD.Space.md) {
                Text("Design Gallery").font(DD.Font.title2).foregroundStyle(DD.fg)
                Spacer()
                Picker("Section", selection: $section) {
                    ForEach(Section.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented).labelsHidden().frame(width: 420)
                Picker("Theme", selection: $settings.theme) {
                    ForEach(ThemeChoice.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented).labelsHidden().frame(width: 180)
            }
            .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.md)
            Divider()
            ScrollView {
                if section == .phone {
                    GalleryPhoneSection().padding(DD.Space.content)
                } else {
                    HStack(alignment: .top, spacing: 0) {
                        column.background(DD.ink).environment(\.colorScheme, .light)
                        column.background(DD.ink).environment(\.colorScheme, .dark)
                    }
                }
            }
        }
        .ddAppearance(settings)
    }

    private var column: some View {
        Group {
            switch section {
            case .phone: EmptyView()
            case .lists: GalleryListsSection()
            case .controls: GalleryControlsSection(settings: settings)
            case .states: GalleryStatesSection()
            case .native: GalleryNativeSection()
            }
        }
        .padding(DD.Space.content)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

// MARK: - Phone card states

private struct GalleryPhoneSection: View {
    @State private var dropTarget = false

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            HStack(alignment: .top, spacing: DD.Space.lg) {
                card("Connected · playing", Sample.playing)
                card("Connected · wallpaper", Sample.wallpaperOnly)
                card("Wallpaper · playing", Sample.wallpaperPlaying)
                card("Offline · wallpaper", Sample.offlineWallpaper)
                card("Pairing", DDPhoneCardModel(state: .pairing(qr: Sample.qr, code: "192.168.1.20 · 6996"), name: ""))
                card("Disconnected", DDPhoneCardModel(state: .disconnected, name: "Pixel 8 Pro"))
                card("Connecting", DDPhoneCardModel(state: .connecting, name: "Pixel 8 Pro"))
            }
            HStack(spacing: DD.Space.lg) {
                sidebarMock.environment(\.colorScheme, .light)
                sidebarMock.environment(\.colorScheme, .dark)
            }
        }
    }

    private func card(_ label: String, _ m: DDPhoneCardModel) -> some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            Text(label).font(DD.Font.label).foregroundStyle(DD.dim)
            DDPhoneCard(model: m, actions: Sample.actions)
                .frame(width: 264, height: 600)
        }
    }

    /// The card inside a sidebar column next to a menu-bar panel mock, to judge it in context.
    private var sidebarMock: some View {
        HStack(alignment: .top, spacing: DD.Space.lg) {
            VStack(spacing: 0) {
                DDPhoneCard(model: Sample.wallpaperOnly, actions: Sample.actions, isDropTarget: dropTarget)
                    .frame(height: 520)
                    .onTapGesture(count: 2) { dropTarget.toggle() }
            }
            .padding(DD.Space.md)
            .frame(width: 288)
            .background(DDVisualEffect(material: .sidebar, blending: .withinWindow))
            MenuBarPanelMock()
        }
        .padding(DD.Space.md)
        .background(DD.ink)
        .clipShape(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous))
    }
}

private struct MenuBarPanelMock: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                BatteryRing(level: 82, size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Pixel 8 Pro").font(DD.Font.headline).foregroundStyle(DD.fg)
                    ConnectionPill(transport: .wifi, link: .good, rttMs: 18)
                        .scaleEffect(0.92, anchor: .leading)
                }
                Spacer()
                IconButton(systemImage: "macwindow", label: "Open DroidDock") {}
            }
            .padding(DD.Space.md)
            HStack(spacing: 6) {
                ForEach(["doc.on.clipboard", "arrow.up.doc", "bell.and.waves.left.and.right", "camera", "rectangle.on.rectangle"], id: \.self) { s in
                    IconButton(systemImage: s, label: s, size: 34) {}
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, DD.Space.sm).padding(.bottom, DD.Space.sm)
            Divider()
            MiniPlayer(track: Sample.track, style: .standard, onCommand: { _ in })
                .padding(.horizontal, 4).padding(.vertical, 2)
            Divider()
            VStack(spacing: 0) {
                ListRow(title: "WhatsApp · Sarah", subtitle: "Did you see the news?", meta: "2m", isUnread: true) {
                    AppIconView(image: nil, name: "WhatsApp", size: 28)
                }
                ListRow(title: "GitHub", subtitle: "Release v2.1.0 is available", meta: "1h") {
                    AppIconView(image: nil, name: "GitHub", size: 28)
                }
            }
            .padding(6)
            Divider()
            HStack {
                Text("Show all (6)").font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer()
                KeyboardShortcutHint("⌘", "Q")
            }
            .padding(DD.Space.md)
        }
        .frame(width: DD.Size.menuBarWidth)
        .glassBackground(radius: DD.Radius.lg, material: .bar, elevation: .floating)
    }
}

// MARK: - Rows

private struct GalleryListsSection: View {
    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            SectionHeader("Notifications") { CountBadge(count: 6, prominent: true) }
            Card(padding: 6) {
                VStack(spacing: 0) {
                    ListRow(title: "Sarah Connor", subtitle: "Did you see the news? I'm heading out at six, want a lift?",
                            meta: "09:41", subtitleLines: 2, isUnread: true) {
                        AppIconView(image: nil, name: "WhatsApp", size: 32)
                    } trailing: {
                        IconButton(systemImage: "xmark", label: "Dismiss", size: 22) {}
                    }
                    ListRow(title: "GitHub", subtitle: "droiddock/droiddock: Release v2.1.0 is available", meta: "08:12",
                            isSelected: true) {
                        AppIconView(image: nil, name: "GitHub", size: 32)
                    }
                    ListRow(title: "Bank", subtitle: "Your card ending 4421 was used", meta: "Yesterday") {
                        AppIconView(image: nil, name: "Monzo", size: 32)
                    }
                }
            }
            SectionHeader("Messages")
            Card(padding: 6) {
                VStack(spacing: 0) {
                    ListRow(title: "Mum", subtitle: "Call me when you land ❤️", meta: "12:04", isUnread: true) {
                        AvatarView(name: "Mum")
                    }
                    ListRow(title: "Alex Rivera", subtitle: "You: sounds good", meta: "Tue") {
                        AvatarView(name: "Alex Rivera")
                    }
                    ListRow(title: "+44 7700 900123", subtitle: "Your code is 482 113", meta: "Mon") {
                        AvatarView(name: "+44", systemImage: "number")
                    }
                }
            }
            SectionHeader("Calls")
            Card(padding: 6) {
                VStack(spacing: 0) {
                    ListRow(title: "Alex Rivera", subtitle: "Missed · mobile", meta: "10:02") {
                        SymbolTile(systemImage: "phone.arrow.down.left", tint: DD.bad)
                    } trailing: {
                        IconButton(systemImage: "phone.fill", label: "Call back", size: 24) {}
                    }
                    ListRow(title: "Mum", subtitle: "Outgoing · 12:31", meta: "Yesterday") {
                        SymbolTile(systemImage: "phone.arrow.up.right", tint: DD.dim)
                    }
                }
            }
            SectionHeader("Files & transfers")
            Card(padding: 0) {
                VStack(spacing: 0) {
                    ListRow(title: "IMG_2041.HEIC", subtitle: "3.2 MB · Today 09:12", hoverHighlight: true) {
                        SymbolTile(systemImage: "photo.fill", tint: DD.dim)
                    }.padding(6)
                    Divider().padding(.leading, 56)
                    ProgressRow(name: "Holiday video 2026.mp4", systemImage: "film.fill", incoming: true,
                                fraction: 0.62, detail: "496 MB of 800 MB · 32 MB/s · 9 s left", state: .running) {
                        IconButton(systemImage: "xmark.circle.fill", label: "Cancel", size: 22) {}
                    }
                    Divider().padding(.leading, 60)
                    ProgressRow(name: "Invoice-October.pdf", systemImage: "doc.richtext.fill", incoming: false,
                                fraction: 1, detail: "212 KB · sent to Pixel 8 Pro", state: .done)
                    Divider().padding(.leading, 60)
                    ProgressRow(name: "Backup.zip", systemImage: "doc.zipper", incoming: true, fraction: 0.2,
                                detail: "", state: .failed("Phone disconnected — 160 MB received"))
                }
            }
            SectionHeader("Apps")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 4)], spacing: 4) {
                ForEach(["Maps", "Spotify", "WhatsApp", "Camera", "Chrome", "Photos", "Gmail", "Uber"], id: \.self) {
                    AppTile(name: $0, icon: nil) {}
                }
            }
            SectionHeader("Loading")
            Card(padding: 0) { LoadingState(rows: 3) }
        }
    }
}

// MARK: - Controls

private struct GalleryControlsSection: View {
    @Bindable var settings: AppearanceSettings
    @State private var tab = 0
    @State private var tab2 = 0
    @State private var toggleA = true

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            SectionHeader("Toolbar tabs")
            SegmentedTabBar(tabs: [
                DDTab(id: 0, title: "Inbox", systemImage: "tray", badge: 6),
                DDTab(id: 1, title: "Files", systemImage: "folder"),
                DDTab(id: 2, title: "Apps", systemImage: "square.grid.2x2"),
                DDTab(id: 3, title: "Clipboard", systemImage: "doc.on.clipboard"),
                DDTab(id: 4, title: "Screen", systemImage: "rectangle.on.rectangle"),
            ], selection: $tab, numberShortcuts: false)
            SegmentedTabBar(tabs: [
                DDTab(id: 0, title: "Notifications", systemImage: "bell", badge: 3),
                DDTab(id: 1, title: "Apps", systemImage: "square.grid.2x2"),
                DDTab(id: 2, title: "Settings", systemImage: "gearshape"),
            ], selection: $tab2, iconOnly: true, numberShortcuts: false)

            SectionHeader("Buttons")
            HStack(spacing: DD.Space.sm) {
                Button("Send file") {}.buttonStyle(PrimaryButtonStyle())
                Button("Cancel") {}.buttonStyle(SecondaryButtonStyle())
                Button("Disconnect") {}.buttonStyle(DestructiveButtonStyle())
                Button("Off") {}.buttonStyle(PrimaryButtonStyle()).disabled(true)
            }
            HStack(spacing: DD.Space.xs) {
                IconButton(systemImage: "arrow.clockwise", label: "Refresh") {}
                IconButton(systemImage: "square.stack", label: "Stack", isOn: true) {}
                IconButton(systemImage: "trash", label: "Clear") {}
                Spacer()
                HStack(spacing: DD.Space.md) {
                    KeyboardShortcutHint("⌘", "⌫")
                    KeyboardShortcutHint("⌘", "⇧", "M")
                }
            }

            SectionHeader("Status")
            FlowRow {
                StatusPill(text: "Linked over Wi-Fi", kind: .link)
                StatusPill(text: "Ready", kind: .ok)
                StatusPill(text: "Slow link", kind: .warn)
                StatusPill(text: "Offline", kind: .bad)
                StatusPill(text: "Beta", kind: .accent, systemImage: "sparkles")
            }
            HStack(spacing: DD.Space.md) {
                ConnectionPill(transport: .wifi, link: .good, rttMs: 18, action: {})
                ConnectionPill(transport: .usb, link: .fair)
                LinkQualityBadge(level: .weak, rttMs: 240, showsLabel: true)
            }
            HStack(spacing: DD.Space.lg) {
                BatteryRing(level: 82, size: 34)
                BatteryRing(level: 24, size: 34)
                BatteryRing(level: 9, size: 34)
                BatteryRing(level: 56, charging: true, size: 34)
                BatteryIndicator(level: 82)
                BatteryIndicator(level: 24, charging: true)
            }

            SectionHeader("Now playing")
            Card(padding: 4) { MiniPlayer(track: Sample.track, style: .standard, onCommand: { _ in }) }

            SettingsSection("Settings rows", footer: "Footer text explains the group.") {
                SettingsRow("Theme", description: "Follow macOS or force light/dark", systemImage: "circle.lefthalf.filled") {
                    Picker("Theme", selection: $settings.theme) {
                        ForEach(ThemeChoice.allCases) { Text($0.title).tag($0) }
                    }.pickerStyle(.segmented).labelsHidden().frame(width: 180)
                }
                SettingsRow("Sync notifications", description: "Mirror phone notifications to the Mac", systemImage: "bell") {
                    Toggle("", isOn: $toggleA).labelsHidden().toggleStyle(.switch).controlSize(.small)
                }
            }

            SectionHeader("Toasts")
            VStack(alignment: .leading, spacing: DD.Space.sm) {
                DDToast(kind: .ok, text: "Sent 3 photos to Pixel 8 Pro", actionTitle: "Show", onAction: {})
                DDToast(kind: .bad, text: "Couldn't reach the phone")
                DDToast(kind: .info, text: "Copied from phone")
            }
        }
    }
}

// MARK: - States

private struct GalleryStatesSection: View {
    var body: some View {
        VStack(spacing: DD.Space.lg) {
            Card(padding: 0) {
                EmptyState(systemImage: "tray", title: "Nothing new on your phone",
                           message: "Notifications from your phone appear here as they arrive.")
                    .frame(height: 240)
            }
            Card(padding: 0) { EmptyState.disconnected(feature: "messages", onPair: {}).frame(height: 260) }
            Card(padding: 0) { EmptyState.failed("The phone stopped answering after 10 s.", retry: {}).frame(height: 260) }
            Text("Floating material — popover/HUD/toast surface")
                .font(DD.Font.body).foregroundStyle(DD.fg)
                .padding(DD.Space.lg).frame(maxWidth: .infinity)
                .glassBackground(elevation: .raised)
            Card(raised: true) {
                HStack {
                    Text("Raised card").font(DD.Font.headline).foregroundStyle(DD.fg)
                    Spacer()
                    Text("192.168.1.42:8731").font(DD.Font.mono).foregroundStyle(DD.dim)
                }
            }
        }
    }
}

// MARK: - Native reference (calibration: our components next to the system ones)

private struct GalleryNativeSection: View {
    @State private var seg = 0
    @State private var tab = 0
    @State private var on = true
    @State private var pick = 1
    @State private var sel: Int? = 1

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            Text("Segmented: native Picker, then SegmentedTabBar").font(DD.Font.caption).foregroundStyle(DD.dim)
            Picker("", selection: $seg) {
                Text("Inbox").tag(0); Text("Files").tag(1); Text("Apps").tag(2); Text("Clipboard").tag(3)
            }
            .pickerStyle(.segmented).labelsHidden().fixedSize()
            SegmentedTabBar(tabs: [
                DDTab(id: 0, title: "Inbox", systemImage: "tray", badge: 0),
                DDTab(id: 1, title: "Files", systemImage: "folder"),
                DDTab(id: 2, title: "Apps", systemImage: "square.grid.2x2"),
                DDTab(id: 3, title: "Clipboard", systemImage: "doc.on.clipboard"),
            ], selection: $tab, numberShortcuts: false)

            Text("Buttons: native, then DD styles").font(DD.Font.caption).foregroundStyle(DD.dim)
            HStack {
                Button("Send file") {}.buttonStyle(.borderedProminent)
                Button("Cancel") {}.buttonStyle(.bordered)
                Button("Delete", role: .destructive) {}
            }
            HStack {
                Button("Send file") {}.buttonStyle(PrimaryButtonStyle())
                Button("Cancel") {}.buttonStyle(SecondaryButtonStyle())
                Button("Delete") {}.buttonStyle(DestructiveButtonStyle())
            }

            Text("Grouped: native Form, then SettingsSection").font(DD.Font.caption).foregroundStyle(DD.dim)
            Form {
                Section("Notifications") {
                    Toggle("Sync notifications", isOn: $on)
                    Picker("Sound", selection: $pick) { Text("Default").tag(1); Text("None").tag(2) }
                    LabeledContent("Device", value: "Pixel 8 Pro")
                }
            }
            .formStyle(.grouped).scrollContentBackground(.hidden).scrollDisabled(true)
            .frame(height: 190).padding(.horizontal, -20)
            SettingsSection("Notifications") {
                SettingsRow("Sync notifications") {
                    Toggle("", isOn: $on).labelsHidden().toggleStyle(.switch).controlSize(.mini)
                }
                SettingsRow("Sound") {
                    Picker("", selection: $pick) { Text("Default").tag(1); Text("None").tag(2) }.labelsHidden().fixedSize()
                }
                SettingsRow("Device") { Text("Pixel 8 Pro").foregroundStyle(DD.dim) }
            }

            Text("List: native inset List, then ListRow").font(DD.Font.caption).foregroundStyle(DD.dim)
            List(selection: $sel) {
                Label("Inbox", systemImage: "tray").tag(1)
                Label("Files", systemImage: "folder").tag(2)
            }
            .listStyle(.inset).frame(height: 70)
            VStack(spacing: 0) {
                ListRow(title: "Inbox", isSelected: true) { Image(systemName: "tray").foregroundStyle(Color.accentColor) }
                ListRow(title: "Files") { Image(systemName: "folder").foregroundStyle(Color.accentColor) }
            }

            Text("Empty state (ContentUnavailableView)").font(DD.Font.caption).foregroundStyle(DD.dim)
            EmptyState(systemImage: "tray", title: "Nothing new on your phone",
                       message: "Notifications appear here as they arrive.", actionTitle: "Refresh", action: {})
                .frame(height: 200)
        }
    }
}

/// Simple wrapping row for pills.
private struct FlowRow<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: DD.Space.sm) { content }
            VStack(alignment: .leading, spacing: DD.Space.sm) { content }
        }
    }
}

// MARK: - Sample data

@MainActor
private enum Sample {
    static let wallpaper: NSImage = gradientImage(size: 600, colors: [
        NSColor(srgbRed: 0.18, green: 0.22, blue: 0.48, alpha: 1),
        NSColor(srgbRed: 0.55, green: 0.30, blue: 0.55, alpha: 1),
        NSColor(srgbRed: 0.95, green: 0.55, blue: 0.35, alpha: 1),
    ])
    static let art: NSImage = gradientImage(size: 300, colors: [
        NSColor(srgbRed: 0.10, green: 0.55, blue: 0.55, alpha: 1),
        NSColor(srgbRed: 0.95, green: 0.80, blue: 0.30, alpha: 1),
    ])

    static let track = DDNowPlaying(title: "Midnight City", artist: "M83", app: "Spotify", isPlaying: true,
                                    position: 74, duration: 243, positionAsOf: .now, artwork: art, trackKey: "m83")

    static let apps = ["Maps", "Spotify", "WhatsApp", "Camera", "Chrome"].map {
        DDRecentApp(id: "com.example.\($0.lowercased())", name: $0, icon: nil)
    }

    static var playing: DDPhoneCardModel {
        DDPhoneCardModel(state: .connected, name: "Pixel 8 Pro", battery: 82, charging: false, transport: .wifi,
                         link: .good, rttMs: 18, nowPlaying: track, recentApps: apps, wallpaper: nil, tint: nil,
                         volume: 0.6, supportsLock: true)
    }
    static var wallpaperOnly: DDPhoneCardModel {
        DDPhoneCardModel(state: .connected, name: "Pixel 8 Pro", battery: 24, charging: true, transport: .wifiAndUsb,
                         link: .fair, rttMs: 64, nowPlaying: nil, recentApps: apps, wallpaper: wallpaper,
                         tint: wallpaper.ddAverageColor(), volume: 0.4, supportsLock: true)
    }

    static var wallpaperPlaying: DDPhoneCardModel {
        var m = wallpaperOnly
        m.nowPlaying = track
        m.battery = 82
        m.charging = false
        m.link = .good
        m.rttMs = 18
        m.transport = .wifi
        return m
    }
    static var offlineWallpaper: DDPhoneCardModel {
        var m = wallpaperOnly
        m.state = .disconnected
        return m
    }

    static var actions: DDPhoneCardActions {
        DDPhoneCardActions(
            onPair: {}, onConnectionDetails: {}, onMedia: { _ in }, onLaunchApp: { _ in }, onRemoveRecent: { _ in },
            onVolume: { _ in }, onLock: {},
            quickActions: [
                DDQuickAction(id: "mirror", title: "Mirror", systemImage: "rectangle.on.rectangle", shortcut: "⌘⇧M", action: {}),
                DDQuickAction(id: "send", title: "Send file", systemImage: "arrow.up.doc", action: {}),
                DDQuickAction(id: "files", title: "Files", systemImage: "folder", action: {}),
                DDQuickAction(id: "clip", title: "Clipboard", systemImage: "doc.on.clipboard", isOn: true, action: {}),
                DDQuickAction(id: "msg", title: "Messages", systemImage: "message", action: {}),
            ])
    }

    static let qr: NSImage? = {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data("droiddock://pair?host=192.168.1.20&port=6996&token=abc123".utf8)
        guard let out = f.outputImage else { return nil }
        let rep = NSCIImageRep(ciImage: out)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }()

    static func gradientImage(size: CGFloat, colors: [NSColor]) -> NSImage {
        NSImage(size: NSSize(width: size, height: size * 1.6), flipped: false) { rect in
            NSGradient(colors: colors)?.draw(in: rect, angle: -70)
            NSColor.white.withAlphaComponent(0.12).setFill()
            NSBezierPath(ovalIn: NSRect(x: rect.width * 0.4, y: rect.height * 0.55, width: rect.width * 0.8, height: rect.width * 0.8)).fill()
            NSColor.black.withAlphaComponent(0.15).setFill()
            NSBezierPath(ovalIn: NSRect(x: -rect.width * 0.3, y: rect.height * 0.05, width: rect.width * 0.9, height: rect.width * 0.9)).fill()
            return true
        }
    }
}
