import AppKit
import SwiftUI

/// What the bar shows. One instance, owned by the controller; the view reads it, the controller writes it.
@Observable @MainActor
final class CommandBarState {
    var query = ""
    var items: [CBItem] = []
    var selection = 0
    var connected = false
    /// The row whose first ↩ armed it (needs a second ↩). Typing or moving the selection clears it.
    var armedID: String?
    var rtl = false
    var reduceTransparency = false
    /// Bumped by the controller on every open so the field re-focuses and clears even if the text did not change.
    var openCount = 0
    @ObservationIgnored weak var field: NSTextField?
    @ObservationIgnored var onQueryChange: ((String) -> Void)?
    @ObservationIgnored var onActivate: ((Int) -> Void)?

    func disabled(_ item: CBItem) -> Bool { item.needsLink && !connected }
    func secondaryDisabled(_ item: CBItem) -> Bool { item.secondary.needsLink && !connected }
}

enum CBMetrics {
    static let width: CGFloat = 640
    static let fieldHeight: CGFloat = 58
    static let rowHeight: CGFloat = 48
    static let listPadding: CGFloat = 6
    static let radius: CGFloat = 16
    static func height(rows: Int) -> CGFloat {
        rows == 0 ? fieldHeight : fieldHeight + 1 + CGFloat(rows) * rowHeight + listPadding * 2
    }
    static var maxHeight: CGFloat { height(rows: CBSearch.maxRows) }
}

struct CommandBarView: View {
    let state: CommandBarState

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: DD.Space.md) {
                Image(systemName: "magnifyingglass").font(.system(size: 18, weight: .regular)).foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                CBSearchField(state: state)
            }
            .padding(.horizontal, DD.Space.lg)
            .frame(height: CBMetrics.fieldHeight)

            if !state.items.isEmpty {
                Divider()
                VStack(spacing: 0) {
                    ForEach(Array(state.items.enumerated()), id: \.element.id) { i, item in
                        CBRow(item: item, selected: i == state.selection, disabled: state.disabled(item),
                              secondaryDisabled: state.secondaryDisabled(item), armed: state.armedID == item.id,
                              onActivate: { state.selection = i; state.onActivate?(i) })
                            .contentShape(Rectangle())
                            .onTapGesture { state.selection = i; state.onActivate?(i) }
                    }
                }
                .padding(CBMetrics.listPadding)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(tr("Results"))
            }
        }
        .frame(width: CBMetrics.width, alignment: .top)
        .frame(maxHeight: .infinity, alignment: .top)
        .environment(\.layoutDirection, state.rtl ? .rightToLeft : .leftToRight)
    }
}

private struct CBRow: View {
    let item: CBItem
    let selected: Bool
    let disabled: Bool
    let secondaryDisabled: Bool
    let armed: Bool
    let onActivate: () -> Void

    /// Readable on whatever accent the user picked (the system's own selected-row text colour).
    private static let onAccent = Color(nsColor: .alternateSelectedControlTextColor)

    var body: some View {
        HStack(spacing: DD.Space.md) {
            Image(systemName: item.symbol)
                .font(.system(size: 15))
                .foregroundStyle(selected ? Self.onAccent : Color.secondary)
                .frame(width: 28, height: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(item.title).font(.system(size: 14, weight: .medium)).lineLimit(1)
                    .foregroundStyle(selected ? Self.onAccent : Color.primary)
                let sub = disabled ? tr("Connect your phone first") : (armed ? item.armSubtitle : item.subtitle)
                if !sub.isEmpty {
                    Text(sub).font(.system(size: 11)).lineLimit(1)
                        .foregroundStyle(selected ? Self.onAccent.opacity(0.8) : Color.secondary)
                }
            }
            Spacer(minLength: DD.Space.sm)
            if selected {
                if item.secondary != .none, !secondaryDisabled, !item.secondaryTitle.isEmpty {
                    Text("⌘↩ " + item.secondaryTitle).font(.system(size: 11)).foregroundStyle(Self.onAccent.opacity(0.75)).lineLimit(1)
                }
                if item.primary != .none, !disabled {
                    Text("↩").font(.system(size: 13, weight: .medium)).foregroundStyle(Self.onAccent.opacity(0.9))
                }
            }
        }
        .padding(.horizontal, DD.Space.sm)
        .frame(height: CBMetrics.rowHeight)
        .background {
            if selected { RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(Color.accentColor) }
        }
        .opacity(disabled ? 0.5 : 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.title)
        .accessibilityValue(disabled ? tr("Connect your phone first") : (armed ? item.armSubtitle : item.subtitle))
        .accessibilityAction { onActivate() }
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// The big search field: an AppKit field so the panel can focus it synchronously on open (no SwiftUI focus hop).
private struct CBSearchField: NSViewRepresentable {
    let state: CommandBarState

    func makeNSView(context: Context) -> NSTextField {
        let f = NSTextField()
        f.isBordered = false
        f.drawsBackground = false
        f.focusRingType = .none
        f.font = .systemFont(ofSize: 22, weight: .light)
        f.placeholderString = tr("Search contacts, apps and phone actions")
        f.cell?.usesSingleLineMode = true
        f.cell?.isScrollable = true
        f.lineBreakMode = .byTruncatingTail
        f.delegate = context.coordinator
        f.setAccessibilityLabel(tr("Command bar"))
        f.setAccessibilityRole(.textField)
        state.field = f
        DispatchQueue.main.async { f.window?.makeFirstResponder(f) }
        return f
    }

    func updateNSView(_ f: NSTextField, context: Context) {
        _ = state.openCount   // a new open re-syncs the field
        if f.stringValue != state.query {
            f.stringValue = state.query
            f.currentEditor()?.selectedRange = NSRange(location: (state.query as NSString).length, length: 0)
        }
        f.userInterfaceLayoutDirection = state.rtl ? .rightToLeft : .leftToRight
        let ph = tr("Search contacts, apps and phone actions")
        if f.placeholderString != ph { f.placeholderString = ph }
    }

    func makeCoordinator() -> Coordinator { Coordinator(state) }

    @MainActor final class Coordinator: NSObject, NSTextFieldDelegate {
        let state: CommandBarState
        init(_ s: CommandBarState) { state = s }
        func controlTextDidChange(_ n: Notification) {
            guard let f = n.object as? NSTextField else { return }
            state.query = f.stringValue
            state.onQueryChange?(f.stringValue)
        }
    }
}
