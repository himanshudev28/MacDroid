import AppKit
import SwiftUI

// MARK: - Deterministic tint

/// Stable per-string hue (FNV-1a; never `hashValue`, which is randomised per launch).
enum DDTint {
    /// Apple system colours (dynamic: they brighten slightly in dark mode, deepen under Increase Contrast).
    /// For app-icon placeholders and symbol tiles; people get the grey monogram (`AvatarView`).
    static let palette: [Color] = [
        Color(nsColor: .systemBlue), Color(nsColor: .systemPurple), Color(nsColor: .systemPink),
        Color(nsColor: .systemOrange), Color(nsColor: .systemTeal), Color(nsColor: .systemGreen),
        Color(nsColor: .systemIndigo), Color(nsColor: .systemRed),
    ]
    static func color(for key: String) -> Color {
        var h: UInt32 = 2_166_136_261
        for b in key.utf8 { h = (h ^ UInt32(b)) &* 16_777_619 }
        return palette[Int(h % UInt32(palette.count))]
    }
}

// MARK: - Avatar

/// Contact / thread avatar: photo if given, else Apple's grey monogram (Contacts/Messages) with white initials.
struct AvatarView: View {
    var name: String
    var image: NSImage? = nil
    var size: CGFloat = DD.Size.avatar
    /// Optional SF Symbol instead of initials (e.g. "person.2.fill" for groups, "phone.fill" for numbers).
    var systemImage: String? = nil

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [Self.monogramTop, Self.monogramBottom], startPoint: .top, endPoint: .bottom)
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: size * 0.42, weight: .medium))
                        .foregroundStyle(.white)
                } else {
                    Text(Self.initials(name))
                        .font(.system(size: size * 0.42, weight: .medium, design: .rounded))
                        .foregroundStyle(.white)
                        .minimumScaleFactor(0.6)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    /// Monogram gradient: system grey, lightened toward the top (derived, so it follows the appearance).
    static let monogramTop = Color(nsColor: .ddMix(.systemGray, with: .white, light: 0.28, dark: 0.08))
    static let monogramBottom = Color(nsColor: .ddMix(.systemGray, with: .black, light: 0.04, dark: 0.22))

    static func initials(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        // Phone numbers / symbols → a person glyph would be better; caller can pass `systemImage`.
        let words = trimmed.split(whereSeparator: { $0.isWhitespace }).filter { $0.first?.isLetter == true }
        let letters = words.prefix(2).compactMap { $0.first.map(String.init) }
        if letters.isEmpty { return trimmed.first.map { String($0) } ?? "?" }
        return letters.joined().uppercased()
    }
}

// MARK: - App icon

/// Android app icon from PNG/JPEG data or base64 (cached), or a lettered placeholder.
struct AppIconView: View {
    var image: NSImage?
    var name: String
    var size: CGFloat = DD.Size.appTile

    init(image: NSImage?, name: String, size: CGFloat = DD.Size.appTile) {
        self.image = image
        self.name = name
        self.size = size
    }

    /// From raw bytes or base64 (decoded once, cached by key).
    init(data: Data?, cacheKey: String, name: String, size: CGFloat = DD.Size.appTile) {
        self.init(image: AppIconCache.image(data: data, key: cacheKey), name: name, size: size)
    }

    init(base64: String?, cacheKey: String, name: String, size: CGFloat = DD.Size.appTile) {
        self.init(image: AppIconCache.image(base64: base64, key: cacheKey), name: name, size: size)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
        Group {
            if let image {
                // Legacy square icons get the same continuous rounded-square mask as the adaptive ones.
                Image(nsImage: image).resizable().interpolation(.high).scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(shape)
                    .ddHairline(shape)
            } else {
                ZStack {
                    LinearGradient(colors: [DDTint.color(for: name).opacity(0.75), DDTint.color(for: name)],
                                   startPoint: .top, endPoint: .bottom)
                    Text(name.first.map { String($0).uppercased() } ?? "?")
                        .font(.system(size: size * 0.44, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white)
                }
                .clipShape(shape)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Decoded-icon cache (NSCache is thread-safe; images are immutable once decoded).
enum AppIconCache {
    private static let cache = TrackedCache<NSString, NSImage>(countLimit: 400)

    static func image(data: Data?, key: String) -> NSImage? {
        if let hit = cache.object(forKey: key as NSString) { return hit }
        guard let data, let img = NSImage(data: data) else { return nil }
        cache.setObject(img, forKey: key as NSString, cost: Int(img.size.width * img.size.height * 4))
        return img
    }

    static func image(base64: String?, key: String) -> NSImage? {
        if let hit = cache.object(forKey: key as NSString) { return hit }
        guard let base64, let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else { return nil }
        return image(data: data, key: key)
    }
}

// MARK: - List row scaffold

/// The one row anatomy for notifications, threads, calls, files, clipboard items:
///
///   [leading 32] Title ················ meta
///                Subtitle (1–2 lines)    [trailing]
///
/// Inside a native `List` (selection-driven) pass `hoverHighlight: false` and let the List draw
/// selection. In a `ScrollView`/`LazyVStack` keep hover on.
struct ListRow<Leading: View, Trailing: View>: View {
    var title: String
    var subtitle: String?
    var meta: String?
    var subtitleLines: Int = 1
    var isUnread: Bool = false
    var isSelected: Bool = false
    var hoverHighlight: Bool = true
    /// Keep the trailing actions visible (dimmed until hovered) instead of revealing them on hover only.
    /// Use for rows whose actions are the point of the row (quick dial), so they are discoverable.
    var persistentActions: Bool = false
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing

    @State private var hovering = false
    @Environment(\.backgroundProminence) private var prominence
    @Environment(\.controlActiveState) private var activeState

    /// In hover mode, trailing actions appear on hover only (over the meta); in List mode they're inline.
    private var revealsTrailing: Bool { hoverHighlight && !persistentActions && Trailing.self != EmptyView.self }
    /// We draw the selection ourselves (hover mode) and the window is key.
    private var drawsSelection: Bool { hoverHighlight && isSelected }
    /// Text sits on an accent fill: our own selection in a key window, or a native List's focused selection.
    private var onAccent: Bool { (drawsSelection && activeState != .inactive) || prominence == .increased }
    private var primaryText: Color { onAccent ? DD.accentInk : DD.fg }
    private var secondaryText: Color { onAccent ? DD.accentInk.opacity(0.82) : DD.dim }

    init(title: String, subtitle: String? = nil, meta: String? = nil, subtitleLines: Int = 1,
         isUnread: Bool = false, isSelected: Bool = false, hoverHighlight: Bool = true,
         @ViewBuilder leading: () -> Leading, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.meta = meta
        self.subtitleLines = subtitleLines
        self.isUnread = isUnread
        self.isSelected = isSelected
        self.hoverHighlight = hoverHighlight
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: subtitle == nil ? .center : .top, spacing: 10) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: DD.Space.sm) {
                    Text(title)
                        .font(isUnread ? DD.Font.headline : DD.Font.bodyEmphasis)
                        .foregroundStyle(primaryText)
                        .lineLimit(1)
                    Spacer(minLength: DD.Space.xs)
                    if let meta {
                        Text(meta).font(DD.Font.caption).monospacedDigit()
                            .foregroundStyle(secondaryText)
                            .lineLimit(1)
                            .layoutPriority(1)
                            .opacity(revealsTrailing && hovering ? 0 : 1)
                    }
                }
                if let subtitle {
                    Text(subtitle).font(DD.Font.body).foregroundStyle(secondaryText)
                        .lineLimit(subtitleLines)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if !revealsTrailing { trailing.opacity(persistentActions && !hovering && !isSelected ? 0.55 : 1) }
        }
        // Hover-revealed actions take the timestamp's place instead of reserving dead space.
        .overlay(alignment: subtitle == nil ? .trailing : .topTrailing) {
            if revealsTrailing && hovering {
                trailing.transition(.opacity)
            }
        }
        // Unread: Mail-style accent dot in the leading gutter, centred on the title line.
        .overlay(alignment: .topLeading) {
            if isUnread {
                Circle().fill(onAccent ? DD.accentInk : Color.accentColor).frame(width: 8, height: 8)
                    .offset(x: -11, y: subtitle == nil ? 0 : 4.5)
                    .frame(maxHeight: subtitle == nil ? .infinity : nil)
                    .accessibilityHidden(true)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 10)
        .padding(.vertical, subtitle == nil ? 6 : 8)
        .frame(minHeight: subtitle == nil ? DD.Size.rowCompact : DD.Size.rowStandard)
        .background {
            if hoverHighlight {
                RoundedRectangle(cornerRadius: DD.Radius.sm, style: .continuous)
                    .fill(isSelected ? (activeState == .inactive ? DD.selectionInactive : DD.selection)
                                     : (hovering ? DD.hover : .clear))
            }
        }
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .ddAnimation(Motion.hover, value: hovering)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

extension ListRow {
    /// Keep the row's trailing actions visible at rest (see `persistentActions`).
    func persistentActions(_ on: Bool = true) -> ListRow {
        var copy = self
        copy.persistentActions = on
        return copy
    }
}

extension ListRow where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, meta: String? = nil, subtitleLines: Int = 1,
         isUnread: Bool = false, isSelected: Bool = false, hoverHighlight: Bool = true,
         @ViewBuilder leading: () -> Leading) {
        self.init(title: title, subtitle: subtitle, meta: meta, subtitleLines: subtitleLines,
                  isUnread: isUnread, isSelected: isSelected, hoverHighlight: hoverHighlight,
                  leading: leading, trailing: { EmptyView() })
    }
}

/// Square symbol tile used as a ListRow leading view for non-people rows (files, calls, system).
struct SymbolTile: View {
    var systemImage: String
    var tint: Color = DD.dim
    var size: CGFloat = DD.Size.avatar
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(tint.opacity(0.14))
            .overlay(Image(systemName: systemImage)
                .font(.system(size: size * 0.45, weight: .medium))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

// MARK: - Progress row (transfers)

enum DDTransferState: Equatable, Sendable {
    case queued, running, paused, done, failed(String)
}

struct ProgressRow<Trailing: View>: View {
    var name: String
    var systemImage: String           // file-type symbol, e.g. "doc.fill", "photo.fill"
    var incoming: Bool                // phone → Mac
    var fraction: Double              // 0…1
    var detail: String                // "12.4 MB of 80 MB · 3.2 MB/s · 20 s left" (caller formats)
    var state: DDTransferState
    @ViewBuilder var trailing: Trailing
    /// Set (via `celebrating(_:)`) for a row that has just finished: the checkmark gives one small bounce.
    private var celebrate = false

    init(name: String, systemImage: String, incoming: Bool, fraction: Double, detail: String,
         state: DDTransferState, @ViewBuilder trailing: () -> Trailing) {
        self.name = name
        self.systemImage = systemImage
        self.incoming = incoming
        self.fraction = fraction
        self.detail = detail
        self.state = state
        self.trailing = trailing()
    }

    private var tint: Color {
        switch state {
        case .done: DD.ok
        case .failed: DD.bad
        case .paused, .queued: DD.dim
        case .running: Color.accentColor
        }
    }

    var body: some View {
        HStack(spacing: DD.Space.md) {
            SymbolTile(systemImage: systemImage, tint: DD.dim, size: 36)
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: incoming ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, incoming ? DD.link : Color.accentColor)
                        .shadow(color: DD.shadow.opacity(0.18), radius: 1, y: 0.5)
                        .offset(x: 4, y: 4)
                }
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(name).font(DD.Font.bodyEmphasis).foregroundStyle(DD.fg)
                        .lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: DD.Space.sm)
                    stateLabel
                }
                if state == .running || state == .paused || state == .queued {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(DD.panel3)
                            Capsule().fill(tint)
                                .frame(width: max(4, geo.size.width * CGFloat(min(max(fraction, 0), 1))))
                        }
                    }
                    .frame(height: 4)
                    .ddAnimation(Motion.progress, value: fraction)
                }
                Text(failureText ?? detail).font(DD.Font.caption).monospacedDigit()
                    .foregroundStyle(failureText == nil ? DD.dim : DD.badText)
                    .lineLimit(1)
            }
            trailing
        }
        .padding(.horizontal, DD.Space.md)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityValue(state == .running ? "\(Int(fraction * 100))%" : "")
    }

    /// Mark this row as just-finished so its checkmark bounces once (chainable; the init stays unchanged).
    func celebrating(_ v: Bool) -> Self { var c = self; c.celebrate = v; return c }

    private var failureText: String? {
        if case .failed(let msg) = state { return msg }
        return nil
    }

    @ViewBuilder private var stateLabel: some View {
        switch state {
        case .running:
            Text(verbatim: "\(Int((fraction * 100).rounded()))%").font(DD.Font.label).monospacedDigit()
                .foregroundStyle(DD.dim).contentTransition(.numericText(value: fraction))
                .ddAnimation(Motion.progress, value: Int((fraction * 100).rounded()))
        case .queued: Text(tr("Waiting")).font(DD.Font.label).foregroundStyle(DD.dim)
        case .paused: Text(tr("Paused")).font(DD.Font.label).foregroundStyle(DD.dim)
        case .done: DoneCheck(celebrate: celebrate)
        case .failed: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(DD.bad).font(.system(size: 13))
        }
    }
}

/// Completion checkmark. Bounces once when it appears for a transfer that has just finished; otherwise static.
private struct DoneCheck: View {
    var celebrate: Bool
    @State private var landed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Image(systemName: "checkmark.circle.fill").foregroundStyle(DD.ok).font(.system(size: 13))
            .symbolEffect(.bounce, options: .nonRepeating, value: landed)
            .onAppear { if celebrate && !reduceMotion { landed = true } }
    }
}

extension ProgressRow where Trailing == EmptyView {
    init(name: String, systemImage: String, incoming: Bool, fraction: Double, detail: String,
         state: DDTransferState) {
        self.init(name: name, systemImage: systemImage, incoming: incoming, fraction: fraction,
                  detail: detail, state: state, trailing: { EmptyView() })
    }
}

// MARK: - App grid tile

struct AppTile: View {
    var name: String
    var icon: NSImage?
    var subtitle: String? = nil
    var action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                AppIconView(image: icon, name: name, size: DD.Size.appTile)
                    .shadow(color: DD.shadow.opacity(0.14), radius: 1.5, y: 0.5)
                Text(name).font(DD.Font.caption).foregroundStyle(DD.fg)
                    .lineLimit(2).multilineTextAlignment(.center)
                    .frame(height: 28, alignment: .top)
                if let subtitle {
                    Text(subtitle).font(DD.Font.footnote).foregroundStyle(DD.dim).lineLimit(1)
                }
            }
            .frame(width: 84)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous)
                .fill(hovering ? DD.hover : .clear))
            .contentShape(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous))
        }
        .buttonStyle(PressableButtonStyle())
        .onHover { hovering = $0 }
        .ddAnimation(Motion.hover, value: hovering)
        .help(name)
        .accessibilityLabel(name)
    }
}

// MARK: - Skeleton

/// Placeholder row with a soft shimmer. Static (no shimmer) under Reduce Motion.
struct SkeletonRow: View {
    var avatar: Bool = true
    var widthFraction: CGFloat = 0.6

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if avatar { Circle().fill(DD.panel3).frame(width: DD.Size.avatar, height: DD.Size.avatar) }
            VStack(alignment: .leading, spacing: 7) {
                GeometryReader { geo in
                    VStack(alignment: .leading, spacing: 7) {
                        Capsule().fill(DD.panel3).frame(width: geo.size.width * widthFraction * 0.6, height: 10)
                        Capsule().fill(DD.panel2).frame(width: geo.size.width * widthFraction, height: 8)
                    }
                }
                .frame(height: 25)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .modifier(Shimmer())
        .accessibilityHidden(true)
    }
}

/// A light band sweeping across. 1.4 s period; paused entirely under Reduce Motion.
struct Shimmer: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = -1
    /// Nothing on screen (window closed/occluded): don't keep a render loop alive.
    private var visible: Bool { UIActivity.shared.visible }

    private func start() {
        withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) { phase = 1 }
    }
    private func stop() {
        withAnimation(.linear(duration: 0.01)) { phase = -1 }   // replaces the repeating animation
    }

    func body(content: Content) -> some View {
        if reduceMotion {
            content.opacity(0.8)
        } else {
            content
                .overlay {
                    GeometryReader { geo in
                        LinearGradient(colors: [.clear, Color.white.opacity(0.28), .clear],
                                       startPoint: .leading, endPoint: .trailing)
                            .frame(width: geo.size.width * 0.5)
                            .offset(x: phase * geo.size.width * 1.5)
                            .blendMode(.plusLighter)
                    }
                    .mask(content)
                    .allowsHitTesting(false)
                }
                .onAppear { if visible { start() } }
                .onDisappear { stop() }
                .onChange(of: visible) { _, v in if v { start() } else { stop() } }
        }
    }
}
