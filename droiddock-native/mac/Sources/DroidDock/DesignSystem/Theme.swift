import AppKit
import Observation
import SwiftUI

// MARK: - Colour helpers

extension Color {
    /// A colour that resolves per-appearance (light/dark) at draw time.
    /// Legacy helper: **do not use for UI chrome.** Every DD token is an Apple semantic colour; this only exists so
    /// older call sites keep compiling while the screen sweep replaces them.
    init(light: UInt32, dark: UInt32, lightAlpha: Double = 1, darkAlpha: Double = 1) {
        let ns = NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let hex = isDark ? dark : light
            let a = isDark ? darkAlpha : lightAlpha
            return NSColor(
                srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: a)
        }
        self.init(nsColor: ns)
    }

    /// Legacy helper (see above). Prefer `Color(nsColor: .systemBlue)` etc.
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

extension NSColor {
    /// A darker variant of a system colour for *words* in light mode (system green/orange are ~2:1 on white),
    /// unchanged in dark mode. Derived at draw time from the system colour, so it still follows Increase Contrast.
    static func ddReadable(_ base: NSColor, darken: CGFloat) -> NSColor {
        ddMix(base, with: .black, light: darken, dark: 0)
    }

    /// `base` with its alpha scaled per appearance (resolved at draw time).
    static func ddScaledAlpha(_ base: NSColor, light: CGFloat, dark: CGFloat) -> NSColor {
        NSColor(name: nil) { appearance in
            var resolved = base
            appearance.performAsCurrentDrawingAppearance {
                resolved = base.usingColorSpace(.sRGB) ?? base
            }
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return resolved.withAlphaComponent(resolved.alphaComponent * (isDark ? dark : light))
        }
    }

    /// `base` blended toward `other` by a per-appearance fraction, resolved at draw time (follows the appearance and
    /// Increase Contrast because `base` is re-resolved for each appearance).
    static func ddMix(_ base: NSColor, with other: NSColor, light: CGFloat, dark: CGFloat) -> NSColor {
        NSColor(name: nil) { appearance in
            var resolved = base
            appearance.performAsCurrentDrawingAppearance {
                resolved = base.usingColorSpace(.sRGB) ?? base
            }
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let f = isDark ? dark : light
            return f == 0 ? resolved : (resolved.blended(withFraction: f, of: other) ?? resolved)
        }
    }
}

// MARK: - Tokens: Apple semantic colours (Apple-native revision, 2026-10-29)
//
// Every colour is an AppKit semantic/system colour, so light, dark, Increase Contrast, the user's accent colour and
// window (in)activity all come from macOS. No hex values, no warm tints, no hand-rolled light/dark pairs (the only
// derived colours are the `*Text` status variants, because Apple has no readable green/orange for text on white).
//
// Resolved values (macOS 15, sRGB): label 85 %, secondaryLabel 50 % (light) / 55 % (dark), tertiaryLabel 26 %,
// separator 10 %; fills are black/white at 9.8 % (system) → 7.8 → 4.7 → 2.7 → 0.8 % (quinary).

/// Design tokens. Never hardcode colours/sizes in screens; use these.
enum DD {
    // Surfaces ───────────────────────────────────────────────────────────────
    /// The window background. **Screens should not paint it** — let the window/split-view/sidebar background show.
    /// Kept for the rare surface that must be opaque (a badge ring cut-out, a snapshot backdrop).
    static let ink    = Color(nsColor: .windowBackgroundColor)
    /// Grouped-content container (System Settings section box): a faint fill over whatever is behind it.
    /// Calibrated against a native `Form(.grouped)` section on macOS 15 (≈ 1.3 % black / 1.6 % white).
    static let panel  = Color(nsColor: .quaternarySystemFill).opacity(0.55)
    /// Inset / chip / quiet-selected fill (a field well, a tag, a keycap).
    static let panel2 = Color(nsColor: .tertiarySystemFill)
    /// Pressed / strongest neutral fill.
    static let panel3 = Color(nsColor: .secondarySystemFill)
    /// Content-area background for a white "document" well (lists, text, tables): white in light, near-black in dark.
    static let content = Color(nsColor: .controlBackgroundColor)
    /// Hairline separator (Apple's `separatorColor`). Use at 1 px (`DD.Size.hairline`).
    static let line   = Color(nsColor: .separatorColor)
    /// Edge of a grouped container: what a native grouped `Form` section draws (fainter than `line` in light mode,
    /// about the same in dark). Derived from `separatorColor`.
    static let containerEdge = Color(nsColor: .ddScaledAlpha(.separatorColor, light: 0.20, dark: 0.60))
    /// Stronger separator for Increase Contrast and outlined controls.
    static let lineStrong = Color(nsColor: .tertiaryLabelColor)
    /// Faintest wash (barely there): inactive track, skeleton base.
    static let glow   = Color(nsColor: .quinarySystemFill)
    /// Hover wash for custom rows/buttons (Apple toolbar-button hover weight).
    static let hover  = Color(nsColor: .tertiarySystemFill)
    /// Modal scrim behind a custom overlay.
    static let scrim  = Color(nsColor: .shadowColor).opacity(0.28)
    /// Deprecated: lit top rims are gone (they read as a web "glass" effect, not macOS). Always clear.
    static let sheen  = Color.clear
    /// Shadow ink: neutral, from the system.
    static let shadow = Color(nsColor: .shadowColor)

    // Text ───────────────────────────────────────────────────────────────────
    static let fg    = Color(nsColor: .labelColor)
    static let dim   = Color(nsColor: .secondaryLabelColor)
    /// Placeholder, disabled, decoration. Not for words people need to read.
    static let faint = Color(nsColor: .tertiaryLabelColor)
    static let quaternary = Color(nsColor: .quaternaryLabelColor)

    // Accent ─────────────────────────────────────────────────────────────────
    /// The user's macOS accent colour (System Settings › Appearance). Selection and the one primary action only.
    static var accent: Color { .accentColor }
    /// Deprecated alias: the app no longer has its own accent. Resolves to the system accent.
    static var accentAmber: Color { .accentColor }
    /// Deprecated alias (see `accentAmber`).
    static var accentAmberHover: Color { .accentColor }
    /// Text/glyph on an accent fill (white, as on a prominent button or selected row).
    static let accentInk = Color(nsColor: .alternateSelectedControlTextColor)
    /// Selected row/content background (accent-based; Finder/Mail selection).
    static let selection = Color(nsColor: .selectedContentBackgroundColor)
    /// Selection when the list or window is not focused (grey).
    static let selectionInactive = Color(nsColor: .unemphasizedSelectedContentBackgroundColor)

    // Status ─────────────────────────────────────────────────────────────────
    /// Android-connected / live status (system green). Fills and glyphs: LED dot, link glyph.
    static let link     = Color(nsColor: .systemGreen)
    /// Link status in *words* (darker green in light mode so it reads on white).
    static let linkText = Color(nsColor: .ddReadable(.systemGreen, darken: 0.42))
    static let ok   = Color(nsColor: .systemGreen)
    static let warn = Color(nsColor: .systemOrange)
    static let bad  = Color(nsColor: .systemRed)
    /// Status in words (light mode darkened for 4.5:1 on white; dark mode = the system colour).
    static let okText   = Color(nsColor: .ddReadable(.systemGreen, darken: 0.42))
    static let warnText = Color(nsColor: .ddReadable(.systemOrange, darken: 0.40))
    static let badText  = Color(nsColor: .ddReadable(.systemRed, darken: 0.12))

    /// Foreground styles for content drawn ON dark imagery (phone wallpaper, mirrored video, album-art wash).
    /// Always white-on-dark regardless of theme. Inside `DDPhoneCard` the card picks these automatically.
    enum OnCard {
        static let primary   = Color.white
        static let secondary = Color.white.opacity(0.72)   // ≥ 4.5:1 over the card's dimming
        static let tertiary  = Color.white.opacity(0.48)   // decoration only
        static let fill      = Color.white.opacity(0.14)   // control platter (when a material can't be used)
        static let fillHover = Color.white.opacity(0.22)
        static let fillActive = Color.white.opacity(0.30)  // toggled-on control
        static let stroke    = Color.white.opacity(0.10)
        static let strokeActive = Color.white.opacity(0.22)
    }

    // Spacing (4-pt base, 8-pt rhythm) ──────────────────────────────────────────
    enum Space {
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        /// Detail-pane content inset (leading/trailing) — HIG-style 20 pt margins.
        static let content: CGFloat = 20
    }

    // Corner radii (always `.continuous`) — macOS 14/15 metrics ─────────────────
    enum Radius {
        static let xs: CGFloat = 4    // keycaps, tiny chips
        static let sm: CGFloat = 6    // buttons, fields, list-row selection (macOS inset list)
        static let md: CGFloat = 8    // tiles, thumbnails
        static let lg: CGFloat = 10   // grouped containers (System Settings sections), cards
        static let xl: CGFloat = 14   // popover-like panels, toasts, overlays
        static let phone: CGFloat = 28 // the phone card (device-silhouette radius)
        /// Concentric radius for something inset by `inset` inside a container of radius `outer`.
        static func concentric(_ outer: CGFloat, inset: CGFloat) -> CGFloat { max(outer - inset, 4) }
    }

    // Fixed sizes that recur across screens ──────────────────────────────────────
    enum Size {
        static let sidebarMin: CGFloat = 260
        static let sidebarIdeal: CGFloat = 288
        static let sidebarMax: CGFloat = 340
        static let menuBarWidth: CGFloat = 340      // MenuBarExtra(.window) panel
        static let rowCompact: CGFloat = 28         // one-line row (sidebar/settings-like)
        static let rowStandard: CGFloat = 44        // two-line row (title + subtitle)
        static let rowTall: CGFloat = 56            // two-line row with 36-pt avatar/icon
        static let avatar: CGFloat = 32
        static let appTile: CGFloat = 52            // app grid icon
        static let iconButton: CGFloat = 28
        static let toolbarSymbol: CGFloat = 15
        static let hairline: CGFloat = 1
    }

    // Typography: the macOS text styles (SF Pro). Body 13, Callout 12, Subheadline 11, Footnote 10.
    // Rounded is reserved for numerals on the phone card (clock, battery) and big numbers, like Apple's widgets.
    enum Font {
        static let largeTitle = SwiftUI.Font.largeTitle.weight(.bold)        // 26
        static let title      = SwiftUI.Font.title.weight(.semibold)         // 22
        static let title2     = SwiftUI.Font.title2.weight(.semibold)        // 17
        static let title3     = SwiftUI.Font.title3.weight(.semibold)        // 15
        static let headline   = SwiftUI.Font.headline                        // 13 bold
        static let body       = SwiftUI.Font.body                            // 13
        static let bodyEmphasis = SwiftUI.Font.body.weight(.medium)
        static let callout    = SwiftUI.Font.callout                         // 12
        static let label      = SwiftUI.Font.subheadline.weight(.medium)     // 11
        static let caption    = SwiftUI.Font.subheadline                     // 11
        static let footnote   = SwiftUI.Font.footnote                        // 10
        static let mono       = SwiftUI.Font.system(.callout, design: .monospaced)
        static let monoSmall  = SwiftUI.Font.system(.subheadline, design: .monospaced)
        static let bigNumber  = SwiftUI.Font.system(size: 34, weight: .semibold, design: .rounded)
        /// Phone-card clock: SF Pro Rounded, like the iPhone lock screen's rounded option.
        static let clock      = SwiftUI.Font.system(size: 64, weight: .semibold, design: .rounded)
        static let clockCompact = SwiftUI.Font.system(size: 42, weight: .semibold, design: .rounded)
    }

    /// Shadows: only for things that float (popovers, toasts, overlays, the dragged item). Content never casts one.
    enum Elevation {
        case flat, card, raised, floating
        var color: Color { DD.shadow.opacity(opacity) }
        var opacity: Double {
            switch self { case .flat, .card: 0; case .raised: 0.10; case .floating: 0.18 }
        }
        var radius: CGFloat {
            switch self { case .flat, .card: 0; case .raised: 3; case .floating: 16 }
        }
        var y: CGFloat {
            switch self { case .flat, .card: 0; case .raised: 1; case .floating: 6 }
        }
    }
}

extension View {
    /// Apply a DD elevation shadow (no-op for `.flat`/`.card`).
    @ViewBuilder func ddElevation(_ e: DD.Elevation) -> some View {
        if e.opacity == 0 { self } else { shadow(color: e.color, radius: e.radius, x: 0, y: e.y) }
    }
}

// MARK: - Appearance settings

enum ThemeChoice: String, CaseIterable, Identifiable, Sendable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// Retired. The app follows the macOS accent colour; this type remains only so older Settings code compiles.
/// Both cases resolve to the system accent. Remove the picker that shows it.
enum AccentChoice: String, CaseIterable, Identifiable, Sendable {
    case amber, system
    var id: String { rawValue }
    var title: String { self == .amber ? "Amber" : "System" }
}

/// User-facing look settings, persisted in UserDefaults under `droiddock.appearance.*`.
@MainActor @Observable
final class AppearanceSettings {
    static let shared = AppearanceSettings()

    private enum Key {
        static let theme = "droiddock.appearance.theme"
        static let reduceTransparency = "droiddock.appearance.reduceTransparency"
    }
    /// Liquid opacity (0 = fully see-through glass, 1 = solid window). UserDefaults key `droiddock.liquidOpacity`.
    static let liquidOpacityKey = "droiddock.liquidOpacity"
    static let liquidOpacityDefault = 0.8

    var theme: ThemeChoice {
        didSet { defaults.set(theme.rawValue, forKey: Key.theme) }
    }
    /// Retired: ignored (always the system accent) and no longer persisted. Kept so old call sites compile.
    var accent: AccentChoice = .system
    /// The app's own preference (Settings toggle).
    var reduceTransparencyPreference: Bool {
        didSet { defaults.set(reduceTransparencyPreference, forKey: Key.reduceTransparency) }
    }
    /// How opaque the window background is, 0...1 (Settings › Appearance › Liquid opacity).
    var liquidOpacity: Double {
        didSet { defaults.set(min(1, max(0, liquidOpacity)), forKey: Self.liquidOpacityKey) }
    }
    /// Mirrors the macOS accessibility setting; refreshed on system notification.
    private(set) var systemReduceTransparency: Bool

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var observer: (any NSObjectProtocol)?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = ThemeChoice(rawValue: defaults.string(forKey: Key.theme) ?? "") ?? .system
        reduceTransparencyPreference = defaults.bool(forKey: Key.reduceTransparency)
        liquidOpacity = (defaults.object(forKey: Self.liquidOpacityKey) as? Double).map { min(1, max(0, $0)) } ?? Self.liquidOpacityDefault
        systemReduceTransparency = NSWorkspace.shared.accessibilityDisplayShouldReduceTransparency
        // `droiddock.appearance.accent` (the old amber choice) is no longer read.
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.systemReduceTransparency = NSWorkspace.shared.accessibilityDisplayShouldReduceTransparency
            }
        }
    }

    /// True when either the app preference or macOS accessibility asks for solid surfaces.
    var reduceTransparency: Bool { reduceTransparencyPreference || systemReduceTransparency }

    /// Opacity actually used for the window background: solid whenever Reduce Transparency (app toggle or macOS) is on.
    var effectiveGlassOpacity: Double { reduceTransparency ? 1 : liquidOpacity }

    /// Always the macOS accent colour.
    var accentColor: Color { .accentColor }
    var accentInkColor: Color { DD.accentInk }
}

// MARK: - Environment plumbing

private struct DDAccentKey: EnvironmentKey { static let defaultValue: Color = .accentColor }
private struct DDAccentInkKey: EnvironmentKey { static let defaultValue: Color = DD.accentInk }
private struct DDReduceTransparencyKey: EnvironmentKey { static let defaultValue: Bool = false }

extension EnvironmentValues {
    /// The interactive accent: always `Color.accentColor` now. Kept so existing `@Environment(\.ddAccent)` compiles.
    var ddAccent: Color {
        get { self[DDAccentKey.self] }
        set { self[DDAccentKey.self] = newValue }
    }
    var ddAccentInk: Color {
        get { self[DDAccentInkKey.self] }
        set { self[DDAccentInkKey.self] = newValue }
    }
    var ddReduceTransparency: Bool {
        get { self[DDReduceTransparencyKey.self] }
        set { self[DDReduceTransparencyKey.self] = newValue }
    }
}

private struct DDAppearanceModifier: ViewModifier {
    let settings: AppearanceSettings
    func body(content: Content) -> some View {
        // No `.tint`: controls take the user's accent (and graphite/multicolour) straight from macOS.
        content
            .environment(settings)
            .environment(\.ddReduceTransparency, settings.reduceTransparency)
            .preferredColorScheme(settings.theme.colorScheme)
    }
}

extension View {
    /// Apply once at the root of every window: theme and reduce transparency.
    func ddAppearance(_ settings: AppearanceSettings) -> some View {
        modifier(DDAppearanceModifier(settings: settings))
    }
}
