import AppKit
import SwiftUI

// MARK: - Toolbar tab bar

/// One tab of a `SegmentedTabBar`.
struct DDTab<ID: Hashable>: Identifiable {
    var id: ID
    var title: String
    var systemImage: String
    var badge: Int = 0
    /// Shown in the tooltip, e.g. "⌘1". The Go menu owns the real key equivalent.
    var shortcut: String?
}

/// Toolbar-centre view switcher drawn to the native macOS segmented-control metrics (track, white/raised knob,
/// 13-pt regular labels), plus what `Picker(.segmented)` can't do: SF Symbols with titles, count badges and ⌘1…⌘9.
/// Put it in `ToolbarItem(placement: .principal)`. `iconOnly` is for the minimal layout (tooltips carry titles).
/// If a switcher needs none of the extras, use `Picker(...).pickerStyle(.segmented)` instead.
struct SegmentedTabBar<ID: Hashable>: View {
    var tabs: [DDTab<ID>]
    @Binding var selection: ID
    var iconOnly: Bool = false
    /// Adds ⌘1…⌘n. Turn off if the window's Commands already define them.
    var numberShortcuts: Bool = true

    @Namespace private var ns
    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var scale
    @Environment(\.controlActiveState) private var activeState

    private static var height: CGFloat { 24 }
    private static var trackRadius: CGFloat { 7 }
    private static var inset: CGFloat { 2 }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(tabs.enumerated()), id: \.element.id) { index, tab in
                if index > 0 { divider(before: index) }
                segment(tab, index: index)
            }
        }
        .padding(Self.inset)
        .frame(height: Self.height)
        .fixedSize()   // never truncate a tab title; the toolbar gives the principal item its ideal width
        .background(RoundedRectangle(cornerRadius: Self.trackRadius, style: .continuous).fill(DD.panel3))
        // Click: the knob slides (matchedGeometry, Motion.select). Keyboard: instant (see `withDDPointerAnimation`).
        .accessibilityElement(children: .contain)
        .accessibilityLabel(tr("Sections"))
    }

    /// Native segmented controls draw a short divider between two unselected neighbours.
    @ViewBuilder private func divider(before index: Int) -> some View {
        let hidden = tabs[index].id == selection || tabs[index - 1].id == selection
        Rectangle().fill(DD.line)
            .frame(width: 1 / max(scale, 1), height: 12)
            .opacity(hidden ? 0 : 1)
    }

    @ViewBuilder private func segment(_ tab: DDTab<ID>, index: Int) -> some View {
        let selected = tab.id == selection
        let button = Button {
            // The knob glides on a click; ⌘-number and other key presses switch instantly.
            withDDPointerAnimation(Motion.select) { selection = tab.id }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 12, weight: .regular))
                    .symbolRenderingMode(.hierarchical)
                if !iconOnly {
                    Text(tab.title).font(.system(size: 13, weight: selected ? .medium : .regular)).lineLimit(1)
                }
                if tab.badge > 0 {
                    Text(verbatim: tab.badge > 99 ? "99+" : "\(tab.badge)")
                        .font(.system(size: 10, weight: .semibold)).monospacedDigit()
                        .foregroundStyle(DD.accentInk)
                        .padding(.horizontal, 5).frame(minWidth: 16, minHeight: 15)
                        .background(Capsule().fill(activeState == .inactive ? DD.faint : Color.accentColor))
                        .contentTransition(.numericText(value: Double(tab.badge)))
                }
            }
            .foregroundStyle(selected ? DD.fg : DD.fg.opacity(0.78))
            .padding(.horizontal, iconOnly ? 12 : 11)
            .frame(maxHeight: .infinity)
            .background {
                if selected {
                    let knob = RoundedRectangle(cornerRadius: Self.trackRadius - Self.inset, style: .continuous)
                    knob.fill(Color(nsColor: .controlColor))
                        .shadow(color: .black.opacity(scheme == .dark ? 0.25 : 0.12), radius: 0.5, y: 0.5)
                        .shadow(color: .black.opacity(scheme == .dark ? 0.0 : 0.06), radius: 2, y: 1)
                        .ddHairline(knob, scheme == .dark ? Color.white.opacity(0.06) : Color.black.opacity(0.04))
                        .matchedGeometryEffect(id: "knob", in: ns)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(tab.shortcut.map { "\(tab.title)  \($0)" } ?? tab.title)
        .accessibilityLabel(tab.badge > 0 ? "\(tab.title), \(tab.badge)" : tab.title)
        .accessibilityAddTraits(selected ? .isSelected : [])

        if numberShortcuts && index < 9 {
            button.keyboardShortcut(KeyEquivalent(Character("\(index + 1)")), modifiers: .command)
        } else {
            button
        }
    }
}

// MARK: - Toasts

enum DDToastKind: Sendable { case ok, bad, info }

struct DDToastItem: Identifiable, Equatable {
    var id: UUID = UUID()
    var kind: DDToastKind
    var text: String
    var actionTitle: String? = nil

    static func == (a: Self, b: Self) -> Bool { a.id == b.id && a.text == b.text }
}

/// A single HUD-style toast: material platter, status symbol, optional inline action, click to dismiss.
struct DDToast: View {
    var kind: DDToastKind
    var text: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil
    var onDismiss: (() -> Void)? = nil

    private var symbol: String {
        switch kind {
        case .ok: "checkmark.circle.fill"
        case .bad: "xmark.octagon.fill"
        case .info: "info.circle.fill"
        }
    }
    private var tint: Color {
        switch kind { case .ok: DD.ok; case .bad: DD.bad; case .info: DD.dim }
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 15)).foregroundStyle(tint)
                .symbolRenderingMode(.hierarchical)
            Text(text).font(DD.Font.bodyEmphasis).foregroundStyle(DD.fg).lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if let actionTitle, let onAction {
                Button(actionTitle, action: onAction)
                    .buttonStyle(.plain)
                    .font(DD.Font.headline)
                    .foregroundStyle(Color.accentColor)
                    .padding(.leading, 2)
            }
        }
        .padding(.leading, 12).padding(.trailing, 14)
        .padding(.vertical, 10)
        .glassBackground(radius: DD.Radius.xl, material: .regularMaterial, elevation: .floating)
        .contentShape(RoundedRectangle(cornerRadius: DD.Radius.xl, style: .continuous))
        .onTapGesture { onDismiss?() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityAction(named: tr("Dismiss")) { onDismiss?() }
    }
}

/// Bottom-centre toast stack. Host once per window: `.overlay(alignment: .bottom) { DDToastHost(...) }`.
/// The model owns lifetime (auto-dismiss 4 s; errors 6 s; pause while hovered is optional).
struct DDToastHost: View {
    var toasts: [DDToastItem]
    var onAction: ((DDToastItem) -> Void)? = nil
    var onDismiss: (DDToastItem) -> Void

    var body: some View {
        VStack(spacing: DD.Space.sm) {
            ForEach(toasts) { t in
                DDToast(kind: t.kind, text: t.text, actionTitle: t.actionTitle,
                        onAction: onAction.map { cb in { cb(t) } },
                        onDismiss: { onDismiss(t) })
                    .frame(maxWidth: 440)
                    .fixedSize(horizontal: false, vertical: true)
                    .ddTransition(.ddToast)
            }
        }
        .padding(.bottom, DD.Space.xl)
        .padding(.horizontal, DD.Space.lg)
        .ddAnimation(Motion.toast, value: toasts)
        .allowsHitTesting(!toasts.isEmpty)
        .accessibilityElement(children: .contain)
    }
}

// MARK: - Keyboard shortcut hint

/// Keycaps, e.g. `KeyboardShortcutHint("⌘", "K")`. For empty states and onboarding, not body copy.
/// (Menus and tooltips show shortcuts as plain glyphs: "⌘K".)
struct KeyboardShortcutHint: View {
    var keys: [String]
    init(_ keys: String...) { self.keys = keys }
    init(keys: [String]) { self.keys = keys }

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(keys.enumerated()), id: \.offset) { _, k in
                Text(k)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(DD.dim)
                    .frame(minWidth: 18, minHeight: 18)
                    .padding(.horizontal, k.count > 1 ? 4 : 0)
                    .background(RoundedRectangle(cornerRadius: DD.Radius.xs, style: .continuous).fill(DD.panel2))
                    .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.xs, style: .continuous))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(keys.joined(separator: " "))
    }
}

// MARK: - Count badge

/// Count capsule for group headers. `prominent` (unread/new) = accent capsule, white numerals, like a Dock badge
/// in the user's accent; otherwise a quiet grey capsule. Sidebar `List` rows should use `.badge(n)` instead.
struct CountBadge: View {
    var count: Int
    var prominent: Bool = false
    var body: some View {
        if count > 0 {
            Text(verbatim: count > 99 ? "99+" : "\(count)")
                .font(.system(size: 11, weight: prominent ? .semibold : .medium)).monospacedDigit()
                .foregroundStyle(prominent ? DD.accentInk : DD.dim)
                .padding(.horizontal, 6).frame(minWidth: 20, minHeight: 17)
                .background(Capsule().fill(prominent ? Color.accentColor : DD.panel2))
                .contentTransition(.numericText(value: Double(count)))
        }
    }
}
