import SwiftUI

/// "Focus": no sidebar. The screen owns the whole window; the phone is a compact status chip in the toolbar (its full
/// card opens in a popover), and the destinations sit in a floating dock at the bottom, like iPhone Mirroring's bar and
/// Tahoe's floating tab bars. Distinct from Phone tabs (phone sidebar + toolbar tabs) on purpose.
/// The split view itself is `MainShell`'s; Minimal keeps it detail-only.
enum MinimalShell {
    /// The dock, in daily-use order. Everything else is in the dock's More menu (and the Go menu).
    static let dock: [Destination] = [.dashboard, .notifications, .messages, .calls, .files, .photos, .apps, .mirror, .clipboard]
    static let overflow: [Destination] = Destination.allCases.filter { !dock.contains($0) }
}

/// Minimal's sidebar column is never shown (`MainShell` pins it to `.detailOnly`); this only removes the system toggle,
/// which would otherwise reveal an empty column.
struct MinimalSidebar: View {
    var body: some View {
        Color.clear
            .toolbar(removing: .sidebarToggle)
            .navigationSplitViewColumnWidth(min: 1, ideal: 1, max: 1)
    }
}

struct MinimalDetail: View {
    @Environment(AppModel.self) private var model
    private var textDirection: LayoutDirection { L10n.shared.isRTL ? .rightToLeft : .leftToRight }

    var body: some View {
        ScreenView(destination: model.selection)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Scroll views run under the dock and pad their content above it, so the glass floats over the screen.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                MinimalDock().padding(.bottom, DD.Space.md).padding(.top, DD.Space.xs)
            }
            .environment(\.layoutDirection, textDirection)
            .navigationTitle(model.selection.title)
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) { MinimalDeviceChip() }
            }
    }
}

// MARK: - Device chip

/// The phone at a glance in the toolbar: link dot, name, battery. Click (or ⌥⌘S) opens the full phone card.
struct MinimalDeviceChip: View {
    @Environment(AppModel.self) private var model
    @State private var chrome = ShellChrome.shared
    @State private var hovering = false
    /// When the popover last closed. A click on the chip first dismisses the transient popover (mouse-down), then runs
    /// the button action; without this the action would reopen it.
    @State private var closedAt = Date.distantPast

    private var dotColor: Color { model.isConnected ? DD.ok : Color.secondary.opacity(0.55) }

    var body: some View {
        @Bindable var c = chrome
        Button {
            if chrome.minimalPhoneOpen { chrome.minimalPhoneOpen = false }
            else if Date().timeIntervalSince(closedAt) > 0.3 { chrome.minimalPhoneOpen = true }
        } label: {
            HStack(spacing: DD.Space.sm) {
                Image(systemName: model.isConnected ? "iphone" : "iphone.slash")
                    .symbolRenderingMode(.hierarchical)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 14)
                Text(model.phoneName ?? tr("Not connected"))
                    .font(DD.Font.bodyEmphasis)
                    .lineLimit(1)
                    .contentTransition(.opacity)
                if model.isConnected, let b = model.batteryPercent {
                    Text("\(b)%")
                        .font(DD.Font.callout).monospacedDigit()
                        .foregroundStyle(b <= 20 && !model.isCharging ? DD.bad : (model.isCharging ? DD.ok : DD.dim))
                        .contentTransition(.numericText(value: Double(b)))
                        .ddAnimation(Motion.select, value: b)
                }
                Circle().fill(dotColor).frame(width: 7, height: 7)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, DD.Space.sm + 2)
            .frame(height: 26)
            .background(Capsule(style: .continuous).fill(Color.primary.opacity(chrome.minimalPhoneOpen ? 0.12 : (hovering ? 0.08 : 0))))
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(MinimalPressStyle())
        .onHover { h in withDDAnimation(Motion.hover) { hovering = h } }
        .ddAnimation(Motion.state, value: model.isConnected)
        .ddAnimation(Motion.hover, value: chrome.minimalPhoneOpen)
        .help("\(tr("Phone panel"))  ⌥⌘S")
        .accessibilityLabel(model.phoneName ?? tr("Not connected"))
        .accessibilityValue(model.batteryPercent.map { "\($0)%" } ?? "")
        .onChange(of: chrome.minimalPhoneOpen) { _, open in if !open { closedAt = Date() } }
        .popover(isPresented: $c.minimalPhoneOpen, arrowEdge: .bottom) {
            PhoneCard(actionSet: .minimal, onNavigate: { d in
                chrome.minimalPhoneOpen = false
                withDDPointerAnimation(Motion.screen) { model.selection = d }
            })
            .frame(width: LayoutMetrics.phoneIdeal, height: 580)
            .padding(DD.Space.xs + 2)   // concentric with the popover edge; a 12 pt frame read as a grey border
            .environment(model)
            .ddAppearance(AppearanceSettings.shared)
        }
    }
}

// MARK: - Dock

/// Floating dock of destinations. The selection is a soft pill that slides between icons (matched geometry); hovering
/// an icon washes it and floats its name above, like the Dock; a click gives a one-shot symbol bounce.
struct MinimalDock: View {
    @Environment(AppModel.self) private var model
    @Environment(\.ddReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Namespace private var pill
    @State private var hovered: Destination?
    @State private var bounce: [Destination: Int] = [:]
    @State private var moreHover = false

    private var moreSelected: Bool { MinimalShell.overflow.contains(model.selection) }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(MinimalShell.dock) { d in item(d) }
            Divider().frame(height: 20).padding(.horizontal, DD.Space.xs)
            more
        }
        .padding(5)
        .background {
            Capsule(style: .continuous)
                .fill(reduceTransparency ? AnyShapeStyle(Color(nsColor: .windowBackgroundColor)) : AnyShapeStyle(.regularMaterial))
        }
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(Color.primary.opacity(contrast == .increased ? 0.35 : 0.10), lineWidth: 0.5)
        }
        // A floating thing: the only shadow in the window.
        .shadow(color: .black.opacity(0.16), radius: 14, y: 6)
        .shadow(color: .black.opacity(0.06), radius: 2, y: 1)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(tr("Go"))
    }

    private func badge(_ d: Destination) -> Int {
        switch d {
        case .notifications: model.notifications.count
        case .transfers: model.activeTransferCount
        default: 0
        }
    }

    @ViewBuilder private func selectionPill(_ on: Bool) -> some View {
        if on {
            Capsule(style: .continuous)
                .fill(Color.accentColor.opacity(0.16))
                .matchedGeometryEffect(id: "pill", in: pill)
        }
    }

    private func item(_ d: Destination) -> some View {
        let selected = model.selection == d
        let isHovered = hovered == d
        let count = badge(d)
        return Button {
            withDDPointerAnimation(Motion.screen) { model.selection = d }
            if Motion.isPointerEvent { bounce[d, default: 0] += 1 }
        } label: {
            Image(systemName: d.symbol)
                .symbolVariant(selected ? .fill : .none)
                .symbolRenderingMode(.hierarchical)
                .font(.system(size: 16, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? Color.accentColor : (isHovered ? DD.fg : DD.dim))
                .symbolEffect(.bounce.down, options: .speed(1.4), value: bounce[d, default: 0])
                .frame(width: 40, height: 34)
                .background {
                    ZStack {
                        if isHovered && !selected { Capsule(style: .continuous).fill(Color.primary.opacity(0.07)) }
                        selectionPill(selected)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if count > 0 {
                        Text(count > 99 ? "99+" : "\(count)")
                            .font(.system(size: 9, weight: .bold)).monospacedDigit()
                            .foregroundStyle(.white)
                            .contentTransition(.numericText(value: Double(count)))
                            .padding(.horizontal, 4).frame(minWidth: 15, minHeight: 15)
                            .background(Capsule().fill(DD.bad))
                            .offset(x: 2, y: -1)
                            .ddTransition(.scale(scale: 0.6).combined(with: .opacity))
                            .accessibilityHidden(true)
                    }
                }
                .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(MinimalPressStyle())
        .onHover { h in withDDAnimation(Motion.hover) { hovered = h ? d : (hovered == d ? nil : hovered) } }
        .overlay(alignment: .top) {
            if isHovered { MinimalDockLabel(text: d.title).offset(y: -38).allowsHitTesting(false) }
        }
        .ddAnimation(Motion.content, value: count)
        .accessibilityLabel(count > 0 ? "\(d.title), \(count)" : d.title)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
        .help(d.shortcutKey.map { "\(d.title)  ⌘\($0)" } ?? d.title)
    }

    private var more: some View {
        ZStack {
            Capsule(style: .continuous).fill(Color.primary.opacity(moreHover && !moreSelected ? 0.07 : 0))
            selectionPill(moreSelected)
            Menu {
                ForEach(MinimalShell.overflow) { d in
                    Button { withDDPointerAnimation(Motion.screen) { model.selection = d } } label: {
                        if let k = d.shortcutKey { Label("\(d.title)  ⌘\(k)", systemImage: d.symbol) }
                        else { Label(d.title, systemImage: d.symbol) }
                    }
                }
            } label: {
                Image(systemName: moreSelected ? model.selection.symbol : "ellipsis")
                    .symbolVariant(moreSelected ? .fill : .none)
                    .contentTransition(.symbolEffect(.replace))
            }
            .menuStyle(.borderlessButton).menuIndicator(.hidden)
            .tint(moreSelected ? Color.accentColor : DD.dim)
            .foregroundStyle(moreSelected ? Color.accentColor : DD.dim)
            .fixedSize()
        }
        .frame(width: 40, height: 34)
        .contentShape(Capsule(style: .continuous))
        .onHover { h in withDDAnimation(Motion.hover) { moreHover = h } }
        .overlay(alignment: .top) {
            if moreHover { MinimalDockLabel(text: moreSelected ? model.selection.title : tr("More")).offset(y: -38).allowsHitTesting(false) }
        }
        .help(tr("More"))
        .accessibilityLabel(tr("More"))
    }
}

/// The Dock-style name tag that floats above a hovered icon.
private struct MinimalDockLabel: View {
    let text: String
    @Environment(\.ddReduceTransparency) private var reduceTransparency
    var body: some View {
        Text(text)
            .font(DD.Font.callout.weight(.medium))
            .foregroundStyle(DD.fg)
            .lineLimit(1).fixedSize()
            .padding(.horizontal, DD.Space.sm).padding(.vertical, 3)
            .background {
                RoundedRectangle(cornerRadius: DD.Radius.sm, style: .continuous)
                    .fill(reduceTransparency ? AnyShapeStyle(Color(nsColor: .windowBackgroundColor)) : AnyShapeStyle(.thickMaterial))
            }
            .overlay(RoundedRectangle(cornerRadius: DD.Radius.sm, style: .continuous).strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5))
            .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
            .ddTransition(.ddRise)
            .accessibilityHidden(true)
    }
}

/// Press feedback on press-down: scale 0.94 for the small dock targets (0.97 reads as nothing at 34 pt), no bounce.
private struct MinimalPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.7 : 1)
            .ddAnimation(Motion.press, value: configuration.isPressed)
    }
}
