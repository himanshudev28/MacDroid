import AppKit
import SwiftUI

// MARK: - Hairline

extension View {
    /// A true 1-pixel border (0.5 pt on Retina), the weight Apple uses for container and popover edges.
    func ddHairline<S: InsettableShape>(_ shape: S, _ color: Color = DD.line) -> some View {
        modifier(HairlineBorder(shape: shape, color: color))
    }
}

private struct HairlineBorder<S: InsettableShape>: ViewModifier {
    let shape: S
    let color: Color
    @Environment(\.displayScale) private var scale
    @Environment(\.colorSchemeContrast) private var contrast
    func body(content: Content) -> some View {
        content.overlay {
            shape.strokeBorder(contrast == .increased ? DD.lineStrong : color,
                               lineWidth: contrast == .increased ? 1 : 1 / max(scale, 1))
                .allowsHitTesting(false)
        }
    }
}

// MARK: - Materials
//
// ┌───────────────────────────────────────────────────────────────────────────────────────┐
// │ LIQUID GLASS SEAM — the ONE place to adopt macOS 26 `glassEffect` later.                │
// │ The dev SDK is macOS 15.2, so `if #available(macOS 26, *) { content.glassEffect(...) }` │
// │ does not compile yet. When the SDK moves to 26: branch inside `GlassBackground.body`   │
// │ and `DDPlatter`, keep the material path as the fallback.                               │
// └───────────────────────────────────────────────────────────────────────────────────────┘
//
// Apple uses materials for things that FLOAT over content: popovers, HUDs, toasts, overlays, the menu-bar panel,
// sidebars. Content containers (cards, settings groups) are a faint fill, not glass. Never stack glass on glass.

struct GlassBackground: ViewModifier {
    var radius: CGFloat = DD.Radius.xl
    var material: Material = .regularMaterial
    var elevation: DD.Elevation = .flat
    @Environment(\.ddReduceTransparency) private var reduce

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .background {
                if reduce {
                    shape.fill(DD.ink)
                } else {
                    shape.fill(material)
                }
            }
            .clipShape(shape)
            .ddHairline(shape)
            .ddElevation(elevation)
    }
}

extension View {
    /// Native material + 1-px edge (+ optional shadow for floating things). Solid window background when
    /// transparency is reduced. For floating chrome only: toasts, overlays, popover-like panels, HUDs.
    func glassBackground(radius: CGFloat = DD.Radius.xl, material: Material = .regularMaterial,
                         elevation: DD.Elevation = .flat) -> some View {
        modifier(GlassBackground(radius: radius, material: material, elevation: elevation))
    }

    /// AppKit window material behind a borderless window/panel's content (call overlay, status widget, HUD).
    /// `.hudWindow` for dark HUDs, `.popover` for popover-like panels, `.menu` for menu-like panels,
    /// `.sidebar` for a custom sidebar column. Blends with what is behind the WINDOW.
    func windowMaterial(_ material: NSVisualEffectView.Material = .popover, radius: CGFloat = DD.Radius.xl) -> some View {
        background(DDVisualEffect(material: material, blending: .behindWindow, radius: radius))
    }
}

/// `NSVisualEffectView` for the cases SwiftUI `Material` can't express (behind-window blending, HUD/popover/menu).
struct DDVisualEffect: NSViewRepresentable {
    var material: NSVisualEffectView.Material
    var blending: NSVisualEffectView.BlendingMode = .behindWindow
    var radius: CGFloat = 0
    var emphasized = false

    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.state = .followsWindowActiveState
        update(v)
        return v
    }

    func updateNSView(_ v: NSVisualEffectView, context: Context) { update(v) }

    private func update(_ v: NSVisualEffectView) {
        v.material = material
        v.blendingMode = blending
        v.isEmphasized = emphasized
        v.wantsLayer = true
        v.layer?.cornerRadius = radius
        v.layer?.cornerCurve = .continuous
        v.layer?.masksToBounds = radius > 0
    }
}

// MARK: - Card (grouped container)

/// A System Settings-style grouped container: faint fill, 10-pt continuous corners, 1-px edge, no shadow.
/// Use it to group related rows inside a pane. Don't nest cards; don't put cards inside a `Form` (use `Section`).
struct Card<Content: View>: View {
    var padding: CGFloat = DD.Space.lg
    var raised: Bool = false
    @ViewBuilder var content: Content

    init(padding: CGFloat = DD.Space.lg, raised: Bool = false, @ViewBuilder content: () -> Content) {
        self.padding = padding
        self.raised = raised
        self.content = content()
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { shape.fill(raised ? DD.panel2 : DD.panel) }
            .clipShape(shape)
            .ddHairline(shape, DD.containerEdge)
    }
}

// MARK: - Section header

/// Group header above a card or list group: 13-pt semibold in the primary label colour, like a grouped `Form`
/// section header. Optional trailing accessory (count badge, "Show all").
struct SectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    init(_ title: String, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(DD.Font.headline).foregroundStyle(DD.fg)
            Spacer(minLength: DD.Space.sm)
            trailing
        }
        .accessibilityAddTraits(.isHeader)
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String) { self.init(title) { EmptyView() } }
}

// MARK: - Settings section / row

/// Grouped settings outside a `Form` (sheets, popovers). Matches `Form { Section }.formStyle(.grouped)` metrics.
struct SettingsSection<Content: View>: View {
    let title: String?
    var footer: String?
    @ViewBuilder var content: Content

    init(_ title: String? = nil, footer: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footer
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Header and footer align with the row text (10 pt in), as in a grouped Form.
            if let title { SectionHeader(title).padding(.horizontal, 10) }
            let shape = RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous)
            VStack(spacing: 0) { content }
                // Every row draws its separator on top; hide the first one under the container's top edge.
                .padding(.top, -1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background { shape.fill(DD.panel) }
                .clipShape(shape)
                .ddHairline(shape, DD.containerEdge)
            if let footer {
                Text(footer).font(DD.Font.caption).foregroundStyle(DD.dim)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 10)
            }
        }
    }
}

struct SettingsRow<Trailing: View>: View {
    let label: String
    var description: String?
    var systemImage: String?
    @ViewBuilder var trailing: Trailing
    @Environment(\.displayScale) private var scale

    init(_ label: String, description: String? = nil, systemImage: String? = nil,
         @ViewBuilder trailing: () -> Trailing) {
        self.label = label
        self.description = description
        self.systemImage = systemImage
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .center, spacing: DD.Space.sm) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: 13))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(DD.dim).frame(width: 20)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(DD.Font.body).foregroundStyle(DD.fg)
                if let description {
                    Text(description).font(DD.Font.caption).foregroundStyle(DD.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: DD.Space.md)
            trailing
        }
        .padding(.horizontal, 10)
        .padding(.vertical, description == nil ? 7 : 8)
        .frame(minHeight: 37)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Inset separator on top of every row (the section hides the first). Starts at the text, like Apple's.
        .overlay(alignment: .top) {
            Rectangle().fill(DD.line).frame(height: 1 / max(scale, 1))
                .padding(.leading, systemImage == nil ? 10 : 38).padding(.trailing, 10)
        }
        .accessibilityElement(children: .contain)
    }
}

// MARK: - Status pill

enum StatusKind: Sendable {
    case neutral, ok, warn, bad, link, accent
}

/// Apple-style status tag: a neutral capsule, a coloured dot (or glyph) and the word in the label colour.
/// Colour never carries the meaning alone; the word does.
struct StatusPill: View {
    let text: String
    var kind: StatusKind = .neutral
    var systemImage: String?

    private var dot: Color {
        switch kind {
        case .neutral: DD.faint
        case .ok: DD.ok
        case .warn: DD.warn
        case .bad: DD.bad
        case .link: DD.link
        case .accent: .accentColor
        }
    }

    var body: some View {
        HStack(spacing: 5) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: 9.5, weight: .semibold))
                    .foregroundStyle(dot)
            } else {
                Circle().fill(dot).frame(width: 7, height: 7)
            }
            Text(text).font(DD.Font.label).foregroundStyle(DD.fg.opacity(0.9)).lineLimit(1)
        }
        .padding(.horizontal, 8)
        .frame(height: 20)
        .background(Capsule().fill(DD.panel2))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Battery

/// Inline battery: the glyph Apple draws in the menu bar plus the percentage.
struct BatteryIndicator: View {
    let level: Int          // 0...100
    var charging: Bool = false

    var body: some View {
        HStack(spacing: 5) {
            BatteryGlyph(level: level, charging: charging, onCard: false)
            Text(verbatim: "\(level)%").font(DD.Font.caption).monospacedDigit().foregroundStyle(DD.dim)
                .contentTransition(.numericText(value: Double(level)))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Battery {n} percent", ["n": level]) + (charging ? ", " + tr("charging") : ""))
    }
}

// MARK: - Empty / error / disconnected states

/// Built on the system `ContentUnavailableView`: secondary symbol, title, one sentence, at most one action.
struct EmptyState: View {
    let systemImage: String
    let title: String
    var message: String?
    var actionTitle: String?
    var action: (() -> Void)?
    var tint: Color? = nil

    var body: some View {
        ContentUnavailableView {
            Label {
                Text(title)
            } icon: {
                Image(systemName: systemImage)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(tint ?? DD.dim)
            }
        } description: {
            if let message { Text(message) }
        } actions: {
            if let actionTitle, let action {
                Button(actionTitle, action: action)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension EmptyState {
    /// Standard "phone not linked" recipe for any screen that needs the phone.
    static func disconnected(feature: String, onPair: (() -> Void)? = nil) -> EmptyState {
        EmptyState(systemImage: "smartphone",
                   title: tr("Phone not linked"),
                   message: tr("Link your phone to see {feature} here.", ["feature": feature]),
                   actionTitle: onPair == nil ? nil : tr("Pair a phone"),
                   action: onPair)
    }

    /// Standard error recipe: what failed, plain words, one way forward.
    static func failed(_ message: String, retry: (() -> Void)? = nil) -> EmptyState {
        EmptyState(systemImage: "exclamationmark.triangle",
                   title: tr("Something went wrong"),
                   message: message,
                   actionTitle: retry == nil ? nil : tr("Try Again"),
                   action: retry)
    }
}

/// Loading placeholder for a whole pane: N skeleton rows (shimmer respects Reduce Motion).
struct LoadingState: View {
    var rows: Int = 6
    var avatar: Bool = true
    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { i in
                SkeletonRow(avatar: avatar, widthFraction: [0.62, 0.48, 0.71, 0.55, 0.66, 0.4][i % 6])
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DD.Space.sm)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("Loading"))
        .ddTransition(.opacity.animation(Motion.exit))   // skeleton leaves quickly; the content fades in behind it
    }
}

// MARK: - Buttons

/// Borderless symbol button with the macOS toolbar look: secondary glyph, hover/pressed fill, accent when on.
/// Prefer a plain `Button { } label: { Label(…) }` inside `.toolbar` (the system draws it); use this in content.
struct IconButton: View {
    let systemImage: String
    let label: String          // accessibility label + tooltip
    var size: CGFloat = DD.Size.iconButton
    var isOn: Bool = false     // toggled state (e.g. "stacked" view on)
    var action: () -> Void

    init(systemImage: String, label: String, size: CGFloat = DD.Size.iconButton, isOn: Bool = false,
         action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.label = label
        self.size = size
        self.isOn = isOn
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: max(11, size * 0.5), weight: .regular))
                .symbolRenderingMode(.hierarchical)
                .frame(width: size, height: size)
        }
        .buttonStyle(DDIconButtonStyle(isOn: isOn))
        .help(label)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// Toolbar-like press/hover treatment for a symbol label.
struct DDIconButtonStyle: ButtonStyle {
    var isOn = false
    func makeBody(configuration: Configuration) -> some View {
        IconButtonBody(label: configuration.label, isPressed: configuration.isPressed, isOn: isOn)
    }
}

private struct IconButtonBody<Label: View>: View {
    let label: Label
    let isPressed: Bool
    let isOn: Bool
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.sm, style: .continuous)
        label
            .foregroundStyle(isOn ? Color.accentColor : (hovering ? DD.fg : DD.dim))
            .background(shape.fill(isPressed ? DD.panel3 : (hovering ? DD.hover : .clear)))
            .contentShape(shape)
            .opacity(enabled ? 1 : 0.35)
            .onHover { hovering = $0 && enabled }
            .animation(Motion.hover, value: hovering)
    }
}

/// The one primary action of a view: the native prominent push button (user's accent colour).
/// `compact` = small control size. A thin wrapper so call sites read the same as before.
struct PrimaryButtonStyle: PrimitiveButtonStyle {
    var compact: Bool = false
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration)
            .buttonStyle(.borderedProminent)
            .modifier(CompactControl(compact: compact))
    }
}

/// Ordinary actions: the native bordered push button.
struct SecondaryButtonStyle: PrimitiveButtonStyle {
    var compact: Bool = false
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration)
            .buttonStyle(.bordered)
            .modifier(CompactControl(compact: compact))
    }
}

/// Destructive confirmation (Disconnect, Delete): native push button with a red label, as macOS alerts do.
/// Prefer `.confirmationDialog` + `Button(role: .destructive)` first.
struct DestructiveButtonStyle: PrimitiveButtonStyle {
    var compact: Bool = false
    func makeBody(configuration: Configuration) -> some View {
        Button(role: configuration.role ?? .destructive, action: configuration.trigger) {
            configuration.label.foregroundStyle(DD.badText)
        }
        .buttonStyle(.bordered)
        .modifier(CompactControl(compact: compact))
    }
}

private struct CompactControl: ViewModifier {
    let compact: Bool
    func body(content: Content) -> some View {
        if compact { content.controlSize(.small) } else { content }
    }
}
