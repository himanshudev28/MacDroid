import AppKit
import SwiftUI

// The phone card and its parts. Pure views over small value models: screen builders map AppModel →
// `DDPhoneCardModel` and pass closures. No knowledge of Core, AppModel or which shell hosts it.
// (`PhoneCard` in Views/Shell is the AppModel-aware wrapper; it should just render `DDPhoneCard`.)

// MARK: - Value models

enum DDTransport: Sendable, Equatable {
    case none, wifi, usb, wifiAndUsb, ble

    var symbol: String {
        switch self {
        case .none: "wifi.slash"
        case .wifi: "wifi"
        case .usb: "cable.connector"
        case .wifiAndUsb: "cable.connector"
        case .ble: "antenna.radiowaves.left.and.right"
        }
    }
    var title: String {
        switch self {
        case .none: tr("Offline")
        case .wifi: tr("Wi-Fi")
        case .usb: tr("USB")
        case .wifiAndUsb: tr("Wi-Fi + USB")
        case .ble: tr("Bluetooth")
        }
    }
}

/// Link grade. Mirrors core `LinkQuality.grade` (good | fair | weak | stalled).
enum LinkQualityLevel: Sendable, Equatable {
    case good, fair, weak, stalled

    init(grade: String) {
        switch grade {
        case "good": self = .good
        case "fair": self = .fair
        case "weak": self = .weak
        default: self = .stalled
        }
    }
    var bars: Int {
        switch self { case .good: 4; case .fair: 3; case .weak: 2; case .stalled: 1 }
    }
    var color: Color {
        switch self { case .good: DD.link; case .fair, .stalled: DD.warn; case .weak: DD.bad }
    }
    var textColor: Color {
        switch self { case .good: DD.linkText; case .fair, .stalled: DD.warnText; case .weak: DD.badText }
    }
    var title: String {
        switch self {
        case .good: tr("Good connection")
        case .fair: tr("Fair connection")
        case .weak: tr("Weak connection")
        case .stalled: tr("Weak connection")   // a quiet link is not a closed socket: only a socket close reads as disconnected
        }
    }
}

struct DDNowPlaying: Equatable {
    var title: String
    var artist: String?
    var app: String?
    var isPlaying: Bool
    /// Seconds. `duration == 0` hides the seek bar.
    var position: Double = 0
    var duration: Double = 0
    /// When `position` was sampled; the bar advances from here while playing.
    var positionAsOf: Date = .now
    var artwork: NSImage?
    /// Changes when the track changes (re-opens a collapsed player).
    var trackKey: String?
}

enum DDMediaCommand: Equatable, Sendable {
    case previous, playPause, next
    case seek(Double)
}

struct DDRecentApp: Identifiable, Equatable {
    var id: String          // package name
    var name: String
    var icon: NSImage?
}

struct DDQuickAction: Identifiable {
    var id: String
    var title: String
    var systemImage: String
    var shortcut: String? = nil      // display only, e.g. "⌘⇧M"
    var isOn: Bool = false
    var isEnabled: Bool = true
    var action: () -> Void
}

struct DDPhoneCardModel: Equatable {
    enum State: Equatable {
        case connected
        case connecting
        case disconnected
        /// Unpaired: show the pairing QR (rendered by the caller) and a short code.
        case pairing(qr: NSImage?, code: String?)
    }

    var state: State
    var name: String
    var battery: Int?
    var charging: Bool = false
    var transport: DDTransport = .none
    var link: LinkQualityLevel?
    var rttMs: Int?
    var nowPlaying: DDNowPlaying?
    var recentApps: [DDRecentApp] = []
    var wallpaper: NSImage?
    /// Optional tint (e.g. `wallpaper.ddAverageColor()`): fills the card behind the wallpaper while it decodes/letterboxes.
    var tint: Color?
    /// 0…1, nil hides the volume control.
    var volume: Double?
    var supportsLock: Bool = false
}

/// Everything the card can ask the app to do. All optional: a nil closure hides its control.
struct DDPhoneCardActions {
    var onPair: (() -> Void)?
    var onConnectionDetails: (() -> Void)?
    var onMedia: ((DDMediaCommand) -> Void)?
    var onLaunchApp: ((String) -> Void)?
    var onRemoveRecent: ((String) -> Void)?
    var onVolume: ((Double) -> Void)?
    var onLock: (() -> Void)?
    var quickActions: [DDQuickAction] = []

    init(onPair: (() -> Void)? = nil, onConnectionDetails: (() -> Void)? = nil,
         onMedia: ((DDMediaCommand) -> Void)? = nil, onLaunchApp: ((String) -> Void)? = nil,
         onRemoveRecent: ((String) -> Void)? = nil, onVolume: ((Double) -> Void)? = nil,
         onLock: (() -> Void)? = nil, quickActions: [DDQuickAction] = []) {
        self.onPair = onPair
        self.onConnectionDetails = onConnectionDetails
        self.onMedia = onMedia
        self.onLaunchApp = onLaunchApp
        self.onRemoveRecent = onRemoveRecent
        self.onVolume = onVolume
        self.onLock = onLock
        self.quickActions = quickActions
    }
}

// MARK: - Card environment

private struct DDOnImageryKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    /// True inside a `DDPhoneCard` that is showing real imagery (wallpaper / album art): content is forced dark
    /// and platters use the thinnest material so the picture shows through, like the iPhone lock screen.
    var ddOnImagery: Bool {
        get { self[DDOnImageryKey.self] }
        set { self[DDOnImageryKey.self] = newValue }
    }
}

/// Control Center-style module platter: a material (thinner over imagery), a 1-px edge, accent fill when on.
/// Solid under Reduce Transparency. The single place on the card that chooses a material.
struct DDPlatter<S: InsettableShape>: ViewModifier {
    var shape: S
    var isOn: Bool = false
    var hovering: Bool = false
    @Environment(\.ddReduceTransparency) private var reduce
    @Environment(\.ddOnImagery) private var onImagery
    private static var quietFill: Color { Color(nsColor: .ddScaledAlpha(.controlColor, light: 0.88, dark: 0.42)) }

    func body(content: Content) -> some View {
        content
            .background {
                if isOn {
                    shape.fill(Color.accentColor)
                } else if reduce {
                    shape.fill(DD.ink)
                } else if onImagery {
                    // Frosted and slightly lifted, like lock-screen buttons over a wallpaper.
                    shape.fill(Material.ultraThinMaterial).overlay(shape.fill(Color.white.opacity(0.08)))
                } else {
                    // Widget platter: near-white in light, a lifted grey in dark (Control Center modules).
                    shape.fill(Self.quietFill)
                }
            }
            .overlay { if hovering && !isOn { shape.fill(DD.hover) } }
            .ddHairline(shape, isOn ? .clear : DD.line)
    }
}

extension View {
    func ddPlatter<S: InsettableShape>(_ shape: S, isOn: Bool = false, hovering: Bool = false) -> some View {
        modifier(DDPlatter(shape: shape, isOn: isOn, hovering: hovering))
    }
}

// MARK: - The card

/// The hero of three layouts. Fill the sidebar column width; it sizes its own height (440–720).
///
/// Two looks, both intentional:
/// - **Wallpaper** (the phone sent one): the wallpaper itself, gently dimmed top and bottom, content always light-on-dark,
///   controls on ultra-thin material platters (iPhone lock screen + Control Center). Offline/reconnecting blurs and dims
///   the wallpaper, like iPhone Mirroring when the phone is in use.
/// - **No wallpaper** (pairing, older phones): a quiet surface that follows the Mac's appearance, with the faintest wash
///   of the user's accent colour, and regular-material platters (a macOS widget).
struct DDPhoneCard: View {
    var model: DDPhoneCardModel
    var actions: DDPhoneCardActions = .init()
    /// True while a file drag hovers the card (caller owns `.dropDestination`).
    var isDropTarget: Bool = false

    @State private var playerOpen = true
    @Environment(\.colorScheme) private var outerScheme

    private var showPlayer: Bool { model.state == .connected && model.nowPlaying != nil && playerOpen }
    private var onImagery: Bool { model.wallpaper != nil }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.phone, style: .continuous)
        content
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .frame(maxHeight: 720)
            // Backdrop as a background so arbitrary-size images can never drive the card's layout.
            .background {
                PhoneBackdrop(wallpaper: model.wallpaper,
                              artwork: showPlayer ? model.nowPlaying?.artwork : nil,
                              tint: model.tint,
                              dimmed: model.state != .connected)
            }
            .clipShape(shape)
            .ddHairline(shape)
            .overlay {
                if isDropTarget {
                    ZStack {
                        shape.fill(Color.accentColor.opacity(0.18))
                        shape.strokeBorder(Color.accentColor, lineWidth: 3)
                        Label(tr("Drop to send to phone"), systemImage: "arrow.down.doc")
                            .font(DD.Font.headline)
                            .foregroundStyle(DD.fg)
                            .padding(.horizontal, 14).frame(height: 32)
                            .ddPlatter(Capsule())
                    }
                    .transition(.opacity)
                }
            }
            .environment(\.ddOnImagery, onImagery)
            .environment(\.colorScheme, onImagery ? .dark : outerScheme)
            .ddAnimation(Motion.state, value: model.state)
            .ddAnimation(Motion.expand, value: showPlayer)
            .ddAnimation(Motion.content, value: isDropTarget)
            .onChange(of: model.nowPlaying?.trackKey) { _, _ in playerOpen = true }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(accessibilitySummary)
    }

    /// connected ↔ reconnecting ↔ offline ↔ pairing: cross-fade with a 2 % settle, never from nothing.
    private static var stateSwap: AnyTransition { .opacity.combined(with: .scale(scale: 0.98)) }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .connected:
            // Never scroll: when the column is short, shed the least important rows (recent apps, then the volume
            // button and the large clock) until the card fits.
            GeometryReader { g in connected(level: fitLevel(height: g.size.height)) }
                .ddTransition(Self.stateSwap)
        case .connecting: connecting.ddTransition(Self.stateSwap)
        case .disconnected: disconnected.ddTransition(Self.stateSwap)
        case .pairing(let qr, let code): pairing(qr: qr, code: code).ddTransition(Self.stateSwap)
        }
    }

    // Connected ────────────────────────────────────────────────────────────

    /// Which rows fit in `height` points (approximate heights of the rows, measured on the real card).
    private func fitLevel(height: CGFloat) -> Int {
        let player: CGFloat = showPlayer ? 160 : 0
        let apps: CGFloat = model.recentApps.isEmpty ? 0 : 56
        let base: CGFloat = 330 + player           // pill, name, clock, quick actions, status strip + gaps
        if height >= base + apps { return 0 }
        if height >= base { return 1 }
        if height >= base - 50 { return 2 }
        return 3
    }

    /// `level` 0 = everything; 1 = no recent apps; 2 = also compact clock, no volume; 3 = also tighter spacing.
    private func connected(level: Int) -> some View {
        VStack(spacing: 0) {
            ConnectionPill(transport: model.transport, link: model.link, rttMs: model.rttMs,
                           style: .onCard, action: actions.onConnectionDetails)
            Text(model.name)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(DD.fg)
                .lineLimit(1)
                .padding(.top, 10)

            Spacer(minLength: level >= 3 ? 2 : DD.Space.md)
            PhoneClock(compact: showPlayer || level >= 2)
            Spacer(minLength: level >= 3 ? 2 : DD.Space.md)

            VStack(spacing: level >= 3 ? 6 : 10) {
                if level == 0, !model.recentApps.isEmpty {
                    RecentAppsRow(apps: Array(model.recentApps.prefix(5)),
                                  onLaunch: actions.onLaunchApp, onRemove: actions.onRemoveRecent)
                        .padding(.bottom, 2)
                }
                if showPlayer, let np = model.nowPlaying {
                    MiniPlayer(track: np, style: .onCard, onCommand: actions.onMedia)
                        .ddTransition(.opacity.combined(with: .scale(scale: 0.97, anchor: .bottom)))
                }
                if !actions.quickActions.isEmpty {
                    QuickActionsRow(actions: actions.quickActions)
                }
                statusStrip(showVolume: level < 2)
            }
        }
    }

    private func statusStrip(showVolume: Bool) -> some View {
        HStack(spacing: 6) {
            if let b = model.battery {
                HStack(spacing: 6) {
                    BatteryGlyph(level: b, charging: model.charging)
                    Text(verbatim: "\(b)%")
                        .font(.system(size: 12, weight: .semibold, design: .rounded)).monospacedDigit()
                        .foregroundStyle(DD.fg)
                        .contentTransition(.numericText(value: Double(b)))
                        .ddAnimation(Motion.content, value: b)
                }
                .padding(.horizontal, 10).frame(height: 28)
                .ddPlatter(Capsule())
                .help(model.charging ? tr("{n}% — charging", ["n": b]) : "\(b)%")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(tr("Battery {n} percent", ["n": b]) + (model.charging ? ", " + tr("charging") : ""))
            }
            if showVolume, let vol = model.volume, let onVolume = actions.onVolume {
                VolumeButton(volume: vol, onChange: onVolume)
            }
            if model.supportsLock, let onLock = actions.onLock {
                OnCardCircleButton(systemImage: "lock.fill", label: tr("Lock phone screen"), size: 28, action: onLock)
            }
            if model.nowPlaying != nil {
                OnCardCircleButton(systemImage: playerOpen ? "chevron.down" : "music.note",
                                   label: playerOpen ? tr("Hide player") : tr("Show player"),
                                   size: 28) { playerOpen.toggle() }
            }
        }
    }

    // Other states ─────────────────────────────────────────────────────────

    private var connecting: some View {
        VStack(spacing: DD.Space.md) {
            Spacer()
            Image(systemName: "smartphone")
                .font(.system(size: 28, weight: .regular))
                .foregroundStyle(DD.dim)
                .frame(width: 76, height: 76)
                .ddPlatter(Circle())
            VStack(spacing: 6) {
                Text(model.name.isEmpty ? tr("Reconnecting…") : tr("Reconnecting to {name}…", ["name": model.name]))
                    .font(DD.Font.headline).foregroundStyle(DD.fg)
                    .multilineTextAlignment(.center)
                ProgressView().controlSize(.small)
            }
            Spacer()
        }
    }

    private var disconnected: some View {
        VStack(spacing: DD.Space.md) {
            Spacer()
            Image(systemName: "smartphone")
                .font(.system(size: 28, weight: .regular))
                .foregroundStyle(DD.dim)
                .frame(width: 76, height: 76)
                .ddPlatter(Circle())
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "wifi.slash")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(DD.dim)
                        .frame(width: 26, height: 26)
                        .ddPlatter(Circle())
                        .offset(x: 2, y: 2)
                }
                .padding(.bottom, DD.Space.xs)
            VStack(spacing: DD.Space.xs) {
                Text(model.name.isEmpty ? tr("Waiting for phone") : tr("{name} is offline", ["name": model.name]))
                    .font(DD.Font.title3).foregroundStyle(DD.fg)
                    .multilineTextAlignment(.center)
                Text(tr("Open DroidDock on your Android, on the same Wi-Fi as this Mac."))
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                    .multilineTextAlignment(.center).frame(maxWidth: 210)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let onPair = actions.onPair {
                Button(action: onPair) {
                    Label(tr("Pair a phone"), systemImage: "qrcode")
                }
                .buttonStyle(SecondaryButtonStyle())
                .padding(.top, DD.Space.xs)
            }
            Spacer()
        }
    }

    private func pairing(qr: NSImage?, code: String?) -> some View {
        VStack(spacing: DD.Space.md) {
            Spacer(minLength: DD.Space.sm)
            VStack(spacing: 6) {
                Text(tr("Pair your phone")).font(DD.Font.title2).foregroundStyle(DD.fg)
                Text(tr("In DroidDock on Android, tap Pair with Mac and scan this code."))
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                    .multilineTextAlignment(.center).lineLimit(4).frame(maxWidth: 210)
                    .fixedSize(horizontal: false, vertical: true)
            }
            let qrShape = RoundedRectangle(cornerRadius: 16, style: .continuous)
            ZStack {
                // Always white: phones scan dark-on-light.
                qrShape.fill(Color.white)
                if let qr {
                    Image(nsImage: qr).interpolation(.none).resizable().scaledToFit().padding(12)
                        .accessibilityLabel(tr("Pairing QR code"))
                } else {
                    ProgressView().controlSize(.small).environment(\.colorScheme, .light)
                }
            }
            .frame(width: 172, height: 172)
            .ddHairline(qrShape, DD.lineStrong)
            if let code {
                Text(verbatim: code)
                    .font(DD.Font.mono)
                    .foregroundStyle(DD.fg)
                    .textSelection(.enabled)
                    .padding(.horizontal, 10).frame(height: 24)
                    .ddPlatter(Capsule())
            }
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini)
                Text(tr("Looking for your phone…")).font(DD.Font.caption).foregroundStyle(DD.dim)
            }
            Spacer(minLength: DD.Space.sm)
        }
    }

    private var accessibilitySummary: String {
        switch model.state {
        case .connected:
            var parts = [model.name, tr("Connected over {t}", ["t": model.transport.title])]
            if let b = model.battery { parts.append(tr("Battery {n} percent", ["n": b])) }
            if let np = model.nowPlaying { parts.append(tr("Now playing {t}", ["t": np.title])) }
            return parts.joined(separator: ", ")
        case .connecting: return model.name.isEmpty ? tr("Reconnecting…") : tr("Reconnecting to {name}…", ["name": model.name])
        case .disconnected: return tr("No phone linked")
        case .pairing: return tr("Pair your phone")
        }
    }
}

// MARK: - Backdrop (wallpaper / album art / quiet surface)

private struct PhoneBackdrop: View {
    var wallpaper: NSImage?
    var artwork: NSImage?
    var tint: Color?
    var dimmed: Bool
    @Environment(\.ddReduceTransparency) private var reduceTransparency

    var body: some View {
        ZStack {
            if let wallpaper {
                (tint ?? Color.black)
                Image(nsImage: wallpaper).resizable().scaledToFill()
                    .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity)
                    .blur(radius: dimmed && !reduceTransparency ? 22 : 0, opaque: true)
                    .clipped()
                    .transition(.opacity)
                // Album art while the player is open: a blurred wash, like the lock screen's now-playing backdrop.
                if let artwork, !reduceTransparency {
                    Image(nsImage: artwork).resizable().scaledToFill()
                        .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity)
                        .scaleEffect(1.4).blur(radius: 40, opaque: true).saturation(1.1)
                        .clipped()
                        .transition(.opacity)
                }
                // Legibility: a soft top band (pill, clock) and a deeper bottom band (controls), nothing in between.
                LinearGradient(stops: [.init(color: .black.opacity(0.34), location: 0),
                                       .init(color: .black.opacity(0.06), location: 0.32),
                                       .init(color: .black.opacity(0.10), location: 0.58),
                                       .init(color: .black.opacity(0.46), location: 1)],
                               startPoint: .top, endPoint: .bottom)
                if dimmed { Color.black.opacity(0.28) }
            } else {
                QuietSurface()
            }
        }
        .ddAnimation(Motion.backdrop, value: artwork)
        .ddAnimation(Motion.backdrop, value: wallpaper)
        .ddAnimation(Motion.backdrop, value: dimmed)
        .accessibilityHidden(true)
    }
}

/// The no-wallpaper surface: the content background with a whisper of the user's accent from the top,
/// so it reads as a device (not a form) in both appearances. Graphite accent → pure neutral.
private struct QuietSurface: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        ZStack {
            Color(nsColor: .controlBackgroundColor)
            LinearGradient(colors: [Color.accentColor.opacity(scheme == .dark ? 0.13 : 0.12),
                                    Color.accentColor.opacity(scheme == .dark ? 0.02 : 0.03)],
                           startPoint: .top, endPoint: .bottom)
            RadialGradient(colors: [Color.accentColor.opacity(scheme == .dark ? 0.12 : 0.10), .clear],
                           center: UnitPoint(x: 0.5, y: 0.0), startRadius: 0, endRadius: 320)
        }
    }
}

// MARK: - Clock

/// Lock-screen arrangement: the date above, the time below in SF Pro Rounded.
struct PhoneClock: View {
    var compact = false
    @Environment(\.ddOnImagery) private var onImagery
    private static let time = Date.FormatStyle.dateTime.hour(.defaultDigits(amPM: .omitted)).minute()
    private static let date = Date.FormatStyle.dateTime.weekday(.wide).month(.wide).day()

    var body: some View {
        TimelineView(.everyMinute) { ctx in
            VStack(spacing: compact ? -2 : -4) {
                Text(ctx.date, format: Self.date)
                    .font(.system(size: compact ? 12 : 15, weight: .semibold))
                    // Over a photo the 80% date vanished on bright wallpapers (seen on a real phone):
                    // full strength there, softened only on plain backgrounds.
                    .foregroundStyle(onImagery ? DD.fg : DD.fg.opacity(0.8))
                Text(ctx.date, format: Self.time)
                    .font(compact ? DD.Font.clockCompact : DD.Font.clock)
                    .monospacedDigit()
                    .tracking(compact ? -0.5 : -1)
                    .foregroundStyle(DD.fg)
                    .contentTransition(.numericText())
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            // Lock-screen legibility on wallpapers: a wide, faint shadow — invisible on dark areas,
            // enough edge on bright ones. Static (no animation), so it costs nothing per frame.
            .shadow(color: .black.opacity(onImagery ? 0.35 : 0), radius: onImagery ? 8 : 0, y: onImagery ? 1 : 0)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Connection pill

/// Link status capsule: green LED + transport + signal bars (+ latency). `.onCard` sits on a card platter;
/// `.standard` is a quiet grey capsule for lists, the menu-bar panel and the classic sidebar footer.
struct ConnectionPill: View {
    enum Style { case onCard, standard }
    var transport: DDTransport
    var link: LinkQualityLevel?
    var rttMs: Int?
    var style: Style = .standard
    var action: (() -> Void)?

    @State private var hovering = false

    private var live: Bool { transport != .none }

    var body: some View {
        let content = HStack(spacing: 5) {
            Circle().fill(live ? DD.link : DD.faint)
                .frame(width: 7, height: 7)
            Image(systemName: transport.symbol).font(.system(size: 10, weight: .semibold))
            Text(transport.title).font(.system(size: 11, weight: .medium))
            if let link, transport != .none {
                LinkQualityBadge(level: link, rttMs: nil)
            }
            if let rttMs, transport != .none {
                Text(verbatim: "\(rttMs) ms").font(.system(size: 11)).monospacedDigit()
                    .foregroundStyle(DD.dim)
            }
        }
        .foregroundStyle(DD.fg)
        .padding(.horizontal, 10)
        .frame(height: 24)
        .modifier(PillBackground(style: style, hovering: hovering && action != nil))
        .contentShape(Capsule())

        Group {
            if let action {
                Button(action: action) { content }
                    .buttonStyle(PressableButtonStyle())
                    .onHover { hovering = $0 }
                    .help(tr("Connection details"))
            } else {
                content
            }
        }
        .ddAnimation(Motion.hover, value: hovering)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var accessibilityText: String {
        guard transport != .none else { return tr("Offline") }
        var s = tr("Connected over {t}", ["t": transport.title])
        if let link { s += ", " + link.title }
        if let rttMs { s += ", \(rttMs) ms" }
        return s
    }
}

private struct PillBackground: ViewModifier {
    var style: ConnectionPill.Style
    var hovering: Bool
    func body(content: Content) -> some View {
        switch style {
        case .onCard: content.ddPlatter(Capsule(), hovering: hovering)
        case .standard: content.background(Capsule().fill(hovering ? DD.panel3 : DD.panel2))
        }
    }
}

// MARK: - Link quality (signal bars)

struct LinkQualityBadge: View {
    var level: LinkQualityLevel
    var rttMs: Int?
    /// Ignored (kept for source compatibility): colours are semantic and follow the surrounding appearance.
    var onCard: Bool = false
    var showsLabel: Bool = false

    var body: some View {
        HStack(spacing: 5) {
            HStack(alignment: .bottom, spacing: 1.5) {
                ForEach(0..<4, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1, style: .continuous)
                        .fill(i < level.bars ? level.color : DD.faint.opacity(0.7))
                        .frame(width: 2.5, height: CGFloat(4 + i * 2))
                }
            }
            .frame(height: 10, alignment: .bottom)
            if showsLabel {
                Text(level.title).font(DD.Font.label).foregroundStyle(DD.fg)
            }
            if let rttMs {
                Text(verbatim: "\(rttMs) ms").font(DD.Font.caption).monospacedDigit()
                    .foregroundStyle(DD.dim)
            }
        }
        .ddAnimation(Motion.select, value: level)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(level.title + (rttMs.map { ", \($0) ms" } ?? ""))
        .help(level.title + (rttMs.map { " · \($0) ms" } ?? ""))
    }
}

// MARK: - Battery

/// Apple's battery glyph, drawn so the fill tracks the real percentage (SF battery symbols only have 5 steps).
/// Label-coloured fill; green while charging; red at 20 % and below.
struct BatteryGlyph: View {
    var level: Int
    var charging: Bool
    /// Ignored (kept for source compatibility): colours are semantic and follow the surrounding appearance.
    var onCard: Bool = true

    var body: some View {
        let frac = CGFloat(min(100, max(0, level))) / 100
        let fill: Color = charging ? DD.ok : (level <= 20 ? DD.bad : DD.fg)
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 3.5, style: .continuous)
                .strokeBorder(DD.fg.opacity(0.4), lineWidth: 1)
                .frame(width: 24, height: 12)
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(fill)
                .frame(width: max(2, 20 * frac), height: 8)
                .padding(.leading, 2)
            if charging {
                Image(systemName: "bolt.fill").font(.system(size: 8, weight: .black))
                    .foregroundStyle(DD.fg)
                    .shadow(color: DD.ink.opacity(0.6), radius: 0.5)
                    .frame(width: 24, height: 12)
            }
        }
        .overlay(alignment: .trailing) {
            RoundedRectangle(cornerRadius: 1).fill(DD.fg.opacity(0.4))
                .frame(width: 1.5, height: 4).offset(x: 2.5)
        }
        .padding(.trailing, 2.5)
        .accessibilityHidden(true)
    }
}

/// Circular battery gauge (Batteries-widget style) for the menu bar panel header and dashboards.
/// Colour only carries news: green while charging, red at 20 % and below, otherwise the label colour.
struct BatteryRing: View {
    var level: Int
    var charging: Bool = false
    var size: CGFloat = 28
    var lineWidth: CGFloat = 3

    private var color: Color { charging ? DD.ok : (level <= 20 ? DD.bad : DD.fg) }

    var body: some View {
        ZStack {
            Circle().stroke(DD.fg.opacity(0.12), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: CGFloat(min(100, max(0, level))) / 100)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if charging {
                Image(systemName: "bolt.fill").font(.system(size: size * 0.36, weight: .semibold)).foregroundStyle(DD.fg)
            } else if size >= 28 {
                Text(verbatim: "\(level)").font(.system(size: size * 0.34, weight: .semibold, design: .rounded))
                    .monospacedDigit().foregroundStyle(DD.fg)
                    .contentTransition(.numericText(value: Double(level)))
            }
        }
        .frame(width: size, height: size)
        .ddAnimation(Motion.expand, value: level)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Battery {n} percent", ["n": level]) + (charging ? ", " + tr("charging") : ""))
    }
}

// MARK: - Mini player

/// Now playing. `.onCard`: a lock-screen-style platter (art, title, scrubber, large plain transport glyphs).
/// `.standard`: a compact row for the menu-bar panel (no platter; the panel is the surface).
struct MiniPlayer: View {
    enum Style { case onCard, standard }
    var track: DDNowPlaying
    var style: Style = .standard
    var onCommand: ((DDMediaCommand) -> Void)?

    @State private var scrub: Double?    // non-nil while dragging

    var body: some View {
        VStack(spacing: style == .onCard ? 8 : 6) {
            HStack(spacing: 10) {
                artwork
                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title.isEmpty ? tr("Unknown track") : track.title)
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(DD.fg).lineLimit(1)
                    Text(track.artist ?? track.app ?? "")
                        .font(.system(size: 12)).foregroundStyle(DD.dim).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if style == .standard { controls }
            }
            // Track change: title, artist and artwork cross-fade instead of snapping.
            .ddAnimation(Motion.content, value: track.trackKey)
            if track.duration > 0, track.position <= track.duration + 1 {
                TimelineView(.periodic(from: .now, by: track.isPlaying ? UIActivity.shared.period(1) : 3600)) { ctx in   // idle when paused
                    SeekBar(position: scrub ?? livePosition(at: ctx.date), duration: track.duration,
                            onCard: style == .onCard,
                            onScrub: { scrub = $0 },
                            onCommit: { v in scrub = nil; onCommand?(.seek(v)) })
                }
            }
            if style == .onCard { controls.padding(.top, -2) }
        }
        .padding(style == .onCard ? 12 : 10)
        .modifier(PlayerPlatter(onCard: style == .onCard))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(tr("Now playing {t}", ["t": track.title]))
    }

    private func livePosition(at date: Date) -> Double {
        guard track.isPlaying else { return track.position }
        return min(track.duration, track.position + date.timeIntervalSince(track.positionAsOf))
    }

    private var artwork: some View {
        let side: CGFloat = style == .onCard ? 44 : 38
        let shape = RoundedRectangle(cornerRadius: style == .onCard ? 9 : 7, style: .continuous)
        return Group {
            if let art = track.artwork {
                Image(nsImage: art).resizable().scaledToFill()
            } else {
                ZStack {
                    DD.panel3
                    Image(systemName: "music.note").font(.system(size: 16, weight: .medium)).foregroundStyle(DD.dim)
                }
            }
        }
        .frame(width: side, height: side)
        .clipShape(shape)
        .ddHairline(shape)
        .accessibilityHidden(true)
    }

    private var controls: some View {
        HStack(spacing: style == .onCard ? 26 : 2) {
            transport("backward.fill", tr("Previous"), .previous, glyph: style == .onCard ? 17 : 13)
            transport(track.isPlaying ? "pause.fill" : "play.fill", track.isPlaying ? tr("Pause") : tr("Play"),
                      .playPause, glyph: style == .onCard ? 24 : 15)
            transport("forward.fill", tr("Next"), .next, glyph: style == .onCard ? 17 : 13)
        }
    }

    private func transport(_ symbol: String, _ label: String, _ cmd: DDMediaCommand, glyph: CGFloat) -> some View {
        Button { onCommand?(cmd) } label: {
            Image(systemName: symbol)
                .font(.system(size: glyph))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: glyph + 14, height: glyph + 14)
        }
        .buttonStyle(TransportButtonStyle())
        .disabled(onCommand == nil)
        .help(label)
        .accessibilityLabel(label)
    }
}

private struct PlayerPlatter: ViewModifier {
    var onCard: Bool
    func body(content: Content) -> some View {
        if onCard { content.ddPlatter(RoundedRectangle(cornerRadius: 18, style: .continuous)) } else { content }
    }
}

/// Plain glyph that dims while pressed and shows a faint circle on hover (Control Center transport buttons).
private struct TransportButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        TransportBody(label: configuration.label, pressed: configuration.isPressed)
    }
}

private struct TransportBody<L: View>: View {
    let label: L
    let pressed: Bool
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        label
            .foregroundStyle(DD.fg)
            .background(Circle().fill(hovering ? DD.hover : .clear))
            .contentShape(Circle())
            .scaleEffect(pressed && !reduceMotion ? 0.9 : 1)
            .opacity(enabled ? (pressed ? 0.6 : 1) : 0.35)
            .onHover { hovering = $0 && enabled }
            .animation(Motion.press, value: pressed)
            .animation(Motion.hover, value: hovering)
    }
}

/// Lock-screen scrubber: a thin bar that thickens on hover/drag (no thumb), haptic ticks at the ends.
struct SeekBar: View {
    var position: Double
    var duration: Double
    /// Ignored (kept for source compatibility): colours are semantic and follow the surrounding appearance.
    var onCard: Bool = false
    var onScrub: (Double) -> Void
    var onCommit: (Double) -> Void

    @State private var hovering = false
    @State private var dragging = false

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                let frac = duration > 0 ? CGFloat(min(max(position / duration, 0), 1)) : 0
                let active = hovering || dragging
                let h: CGFloat = active ? 7 : 4
                ZStack(alignment: .leading) {
                    Capsule().fill(DD.fg.opacity(0.16))
                    Capsule().fill(DD.fg.opacity(active ? 0.9 : 0.65))
                        .frame(width: max(h, geo.size.width * frac))
                }
                .frame(height: h)
                .clipShape(Capsule())
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if !dragging { dragging = true; Haptics.alignment() }
                        guard geo.size.width > 0, duration.isFinite, duration > 0 else { return }
                        let v = Double(min(max(g.location.x / geo.size.width, 0), 1)) * duration
                        if v <= 0 || v >= duration { Haptics.alignment() }
                        onScrub(v)
                    }
                    .onEnded { g in
                        dragging = false
                        guard geo.size.width > 0, duration.isFinite, duration > 0 else { return }
                        let v = Double(min(max(g.location.x / geo.size.width, 0), 1)) * duration
                        Haptics.levelChange()
                        onCommit(v)
                    })
            }
            .frame(height: 12)
            .onHover { hovering = $0 }
            .ddAnimation(Motion.hover, value: hovering || dragging)
            HStack {
                Text(Self.fmt(position))
                Spacer()
                // Unknown length (duration 0, or shorter than the position the phone reports): no remaining time.
                if duration > 0, position <= duration + 1 { Text("-" + Self.fmt(max(0, duration - position))) }
            }
            .font(.system(size: 11, weight: .medium, design: .rounded)).monospacedDigit()
            .foregroundStyle(DD.dim)
        }
        .accessibilityElement()
        .accessibilityLabel(tr("Seek"))
        .accessibilityValue(Self.fmt(position) + " / " + Self.fmt(duration))
        .accessibilityAdjustableAction { dir in
            let step = 10.0
            onCommit(dir == .increment ? min(duration, position + step) : max(0, position - step))
        }
    }

    static func fmt(_ s: Double) -> String {
        let t = Int(s.rounded(.down))
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
                         : String(format: "%d:%02d", t / 60, t % 60)
    }
}

// MARK: - Quick actions / recent apps / on-card buttons

struct QuickActionsRow: View {
    var actions: [DDQuickAction]
    var body: some View {
        // Six platters need ~280 pt at the usual gap; tighten the gap before the row can spill out of a 300-pt column.
        ViewThatFits(in: .horizontal) {
            row(spacing: 10)
            row(spacing: 5)
        }
        .frame(maxWidth: .infinity)
    }

    private func row(spacing: CGFloat) -> some View {
        HStack(spacing: spacing) {
            ForEach(actions) { a in
                OnCardCircleButton(systemImage: a.systemImage,
                                   label: a.shortcut.map { "\(a.title)  \($0)" } ?? a.title,
                                   size: 38, isOn: a.isOn, action: a.action)
                    .disabled(!a.isEnabled)
                    .accessibilityLabel(a.title)
            }
        }
    }
}

/// Control Center-style round module: symbol on a material platter; accent fill + white glyph when on.
struct OnCardCircleButton: View {
    var systemImage: String
    var label: String
    var size: CGFloat = 38
    var isOn: Bool = false
    var action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size * 0.40, weight: .medium))
                .symbolRenderingMode(.hierarchical)
                .symbolEffect(.bounce, options: .nonRepeating, value: reduceMotion ? false : isOn)   // toggle feedback
                .frame(width: size, height: size)
        }
        .buttonStyle(CircleModuleStyle(isOn: isOn))
        .ddAnimation(Motion.select, value: isOn)
        .help(label)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

private struct CircleModuleStyle: ButtonStyle {
    var isOn: Bool
    func makeBody(configuration: Configuration) -> some View {
        CircleModuleBody(label: configuration.label, pressed: configuration.isPressed, isOn: isOn)
    }
}

private struct CircleModuleBody<L: View>: View {
    let label: L
    let pressed: Bool
    let isOn: Bool
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        label
            .foregroundStyle(isOn ? DD.accentInk : DD.fg)
            .ddPlatter(Circle(), isOn: isOn, hovering: hovering)
            .contentShape(Circle())
            .scaleEffect(pressed && !reduceMotion ? 0.92 : 1)
            .brightness(pressed ? -0.06 : 0)
            .opacity(enabled ? 1 : 0.4)
            .onHover { hovering = $0 && enabled }
            .animation(Motion.press, value: pressed)
            .animation(Motion.hover, value: hovering)
    }
}

struct RecentAppsRow: View {
    var apps: [DDRecentApp]
    var onLaunch: ((String) -> Void)?
    var onRemove: ((String) -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            ForEach(apps) { app in
                Button { onLaunch?(app.id) } label: {
                    PhoneAppIcon(pkg: app.id, name: app.name, size: 32)
                        .shadow(color: DD.shadow.opacity(0.2), radius: 2, y: 1)
                }
                .buttonStyle(PressableButtonStyle(scale: 0.9))
                .help(tr("Open {app} on phone", ["app": app.name]))
                .accessibilityLabel(tr("Open {app} on phone", ["app": app.name]))
                .contextMenu {
                    if let onLaunch { Button(tr("Open on Phone")) { onLaunch(app.id) } }
                    if let onRemove { Button(tr("Remove from Recents")) { onRemove(app.id) } }
                }
            }
        }
    }
}

private struct VolumeButton: View {
    var volume: Double
    var onChange: (Double) -> Void
    @State private var open = false
    @State private var value: Double = 0

    var body: some View {
        OnCardCircleButton(systemImage: volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill",
                           label: tr("Phone volume"), size: 28, isOn: open) {
            value = volume
            open.toggle()
        }
        .popover(isPresented: $open, arrowEdge: .top) {
            HStack(spacing: 10) {
                Image(systemName: "speaker.fill").foregroundStyle(DD.dim)
                Slider(value: $value, in: 0...1) { editing in
                    if !editing { onChange(value) }
                }
                .frame(width: 160)
                .onChange(of: value) { old, new in
                    // Detent ticks at the ends; live-apply while dragging.
                    if (new == 0 || new == 1) && old != new { Haptics.alignment() }
                    onChange(new)
                }
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(DD.dim)
            }
            .font(.system(size: 11))
            .padding(DD.Space.md)
        }
    }
}

// MARK: - Image helpers

extension NSImage {
    /// Average colour (1×1 downsample). Call once per wallpaper change, off the hot path.
    func ddAverageColor() -> Color? {
        guard let cg = cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        var px = [UInt8](repeating: 0, count: 4)
        let ok: Bool = px.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: 1, height: 1, bitsPerComponent: 8,
                                      bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return true
        }
        guard ok else { return nil }
        return Color(.sRGB, red: Double(px[0]) / 255, green: Double(px[1]) / 255, blue: Double(px[2]) / 255)
    }
}
