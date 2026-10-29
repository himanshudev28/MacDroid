import AppKit
import SwiftUI

/// Notifications, grouped by app (stack/expand), with inline reply, actions, per-app level and search.
/// Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct NotificationsScreen: View {
    @Environment(AppModel.self) private var model
    @AppStorage("droiddock.notifStacks") private var stacked = true
    @State private var search = ""
    @State private var filterApp: String?
    @State private var expanded: Set<String> = []
    @State private var replyingKey: String?
    @State private var replyText = ""
    @FocusState private var replyFocused: Bool

    private struct NotifGroup: Identifiable {
        var app: String
        var pkg: String?
        var items: [PhoneNotification]
        var id: String { app }
    }

    private var visible: [PhoneNotification] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        return model.notifications.filter { n in
            if let filterApp, (n.app ?? tr("App")) != filterApp { return false }
            if q.isEmpty { return true }
            return [n.app, n.title, n.text].contains { ($0 ?? "").lowercased().contains(q) }
        }
    }

    private var groups: [NotifGroup] {
        var order: [String] = []
        var by: [String: NotifGroup] = [:]
        for n in visible {
            let app = n.app ?? tr("App")
            if by[app] == nil { order.append(app); by[app] = NotifGroup(app: app, pkg: n.pkg, items: []) }
            by[app]!.items.append(n)
            if by[app]!.pkg == nil { by[app]!.pkg = n.pkg }
        }
        return order.compactMap { by[$0] }
    }

    private var appNames: [String] {
        var seen = Set<String>(), out: [String] = []
        for n in model.notifications { let a = n.app ?? tr("App"); if seen.insert(a).inserted { out.append(a) } }
        return out
    }

    var body: some View {
        Group {
            if model.notifications.isEmpty {
                if model.isConnected {
                    EmptyState(systemImage: "bell",
                               title: tr("Nothing new on your phone"),
                               message: (model.config?.nativeNotifs ?? true)
                                   ? tr("Phone notifications show up here, and as Mac banners you can reply to.")
                                   : tr("Phone notifications show up here. Turn on Mac banners in Settings."))
                } else {
                    EmptyState.disconnected(feature: tr("notifications")) { model.selection = .dashboard }
                }
            } else if visible.isEmpty {
                NoResults(query: search.isEmpty ? (filterApp ?? "") : search)
            } else {
                content
            }
        }
        .searchable(text: $search, prompt: tr("Search notifications"))
        .toolbar { toolbar }
        .ddAnimation(Motion.content, value: model.notifications.isEmpty)
    }

    // MARK: Content

    private var content: some View {
        List {
            if !model.isConnected {
                OfflineStrip().listRowSeparator(.hidden).listRowInsets(EdgeInsets())
            }
            if stacked {
                ForEach(groups) { g in groupSection(g) }
            } else {
                ForEach(visible) { n in rowBlock(n, showApp: true) }
            }
        }
        .listStyle(.inset)
        .scrollContentBackground(.hidden)
        .opacity(model.isConnected ? 1 : 0.6)
        .disabled(!model.isConnected)
        .ddAnimation(Motion.expand, value: stacked)
        .ddAnimation(Motion.expand, value: expanded)
        .ddAnimation(Motion.content, value: visible.map(\.key))   // new / removed notifications ease in and out
    }

    /// One app's notifications as a native List section: app header with count and Clear, the newest row, and
    /// "Show n more" to unfold the rest (Notification Center's stack).
    private func groupSection(_ g: NotifGroup) -> some View {
        let open = expanded.contains(g.app) || g.items.count == 1
        let shown = open ? g.items : Array(g.items.prefix(1))
        let hidden = g.items.count - shown.count
        let tier = NotifTier.current(for: g.pkg, config: model.config)
        return SwiftUI.Section {
            ForEach(shown) { n in
                rowBlock(n, showApp: false)
                    .transition(.opacity.combined(with: .offset(y: -4)))
            }
            if g.items.count > 1 {
                Button(open ? tr("Show less") : tr("Show {n} more", ["n": hidden])) { toggle(g.app) }
                    .buttonStyle(.borderless).font(DD.Font.caption).foregroundStyle(.secondary)
                    .contentTransition(.numericText())
                    .listRowSeparator(.hidden)
            }
        } header: {
            HStack(spacing: DD.Space.sm) {
                PhoneAppIcon(pkg: g.pkg, name: g.app, size: 16)
                Text(g.app).font(DD.Font.headline).foregroundStyle(DD.fg)
                CountBadge(count: g.items.count)
                if tier != .alert {
                    Image(systemName: tier.symbol).font(.system(size: 11)).foregroundStyle(.secondary)
                        .help(tier.title).accessibilityLabel(tier.title)
                }
                Spacer()
                Button { clear(g.items) } label: { Text(tr("Clear")).font(DD.Font.caption) }
                    .buttonStyle(.borderless).foregroundStyle(.secondary)
                    .help(tr("Clear {app}", ["app": g.app]))
            }
            .textCase(nil)
            .padding(.vertical, DD.Space.xs)
            .contextMenu { tierMenu(pkg: g.pkg, app: g.app) }
        }
    }

    // MARK: Row

    private func rowBlock(_ n: PhoneNotification, showApp: Bool) -> some View {
        let isCall = n.type == "call"
        let replying = replyingKey == n.key
        return VStack(spacing: 0) {
            ListRow(title: n.title ?? n.app ?? tr("Notification"),
                    subtitle: subtitle(n, showApp: showApp),
                    meta: InboxTime.short(n.time),
                    subtitleLines: 2) {
                if isCall { SymbolTile(systemImage: "phone.arrow.down.left", tint: DD.ok) }
                else { PhoneAppIcon(pkg: n.pkg, name: n.app ?? "?", size: DD.Size.avatar) }
            } trailing: {
                rowActions(n, isCall: isCall)
            }
            .contextMenu { rowMenu(n, isCall: isCall) }
            .accessibilityAction(named: tr("Dismiss")) { dismiss(n) }

            if let frac = progress(n) {
                ProgressView(value: frac).progressViewStyle(.linear).controlSize(.small)
                    .padding(.leading, 42).padding(.bottom, DD.Space.sm)
            } else if n.progressIndeterminate == true {
                ProgressView().progressViewStyle(.linear).controlSize(.small)
                    .padding(.leading, 42).padding(.bottom, DD.Space.sm)
            }
            if replying { replyField(n).transition(.opacity.combined(with: .offset(y: -4))) }
        }
        .ddAnimation(Motion.expand, value: replying)
    }

    private func subtitle(_ n: PhoneNotification, showApp: Bool) -> String? {
        let text = n.text ?? ""
        if showApp, let app = n.app, !app.isEmpty { return text.isEmpty ? app : "\(app) · \(text)" }
        return text.isEmpty ? nil : text
    }

    private func progress(_ n: PhoneNotification) -> Double? {
        guard let max = n.progressMax, max > 0 else { return nil }
        return Swift.min(1, Swift.max(0, (n.progress ?? 0) / max))
    }

    @ViewBuilder
    private func rowActions(_ n: PhoneNotification, isCall: Bool) -> some View {
        HStack(spacing: 2) {
            if isCall, let num = n.number ?? n.text {
                IconButton(systemImage: "phone.fill", label: tr("Call back")) { call(num) }
            }
            if !isCall, NotificationClickActions.shared.canOpenOnPhone(key: n.key) {
                Button(tr("Open")) { NotificationClickActions.shared.open(key: n.key) }
                    .buttonStyle(SecondaryButtonStyle(compact: true))
            }
            if n.replyable == true {
                Button { startReply(n) } label: { Label(tr("Reply"), systemImage: "arrowshape.turn.up.left") }
                    .buttonStyle(SecondaryButtonStyle(compact: true)).help(tr("Reply  ⏎"))
            }
            ForEach(Array((n.actions ?? []).prefix(2).enumerated()), id: \.offset) { i, label in
                Button(label) { act(n, i) }
                    .buttonStyle(SecondaryButtonStyle(compact: true))
            }
            IconButton(systemImage: "xmark", label: tr("Dismiss")) { dismiss(n) }
        }
        .padding(.leading, DD.Space.sm)
    }

    @ViewBuilder
    private func rowMenu(_ n: PhoneNotification, isCall: Bool) -> some View {
        Button(tr("Copy")) {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString([n.title, n.text].compactMap { $0 }.joined(separator: "\n"), forType: .string)
        }
        if n.type != "call", NotificationClickActions.shared.canOpenOnPhone(key: n.key) {
            Button(tr("Open on your phone")) { NotificationClickActions.shared.openOnPhone(key: n.key) }
        }
        if n.replyable == true { Button(tr("Reply")) { startReply(n) } }
        ForEach(Array((n.actions ?? []).enumerated()), id: \.offset) { i, label in
            Button(label) { act(n, i) }
        }
        Divider()
        Button(tr("Dismiss")) { dismiss(n) }
        tierMenu(pkg: n.pkg, app: n.app ?? tr("App"))
    }

    @ViewBuilder
    private func tierMenu(pkg: String?, app: String) -> some View {
        if let pkg {
            Menu(tr("Notification Level")) {
                Picker(tr("Notification Level"), selection: Binding(
                    get: { NotifTier.current(for: pkg, config: model.config) },
                    set: { NotifTier.set($0, for: pkg, config: model.config) })) {
                    ForEach(NotifTier.allCases) { t in Label("\(t.title): \(t.detail)", systemImage: t.symbol).tag(t) }
                }
                .pickerStyle(.inline).labelsHidden()
            }
            Button(tr("Open on Click…")) { model.selection = .settings }
        }
    }

    private func replyField(_ n: PhoneNotification) -> some View {
        HStack(spacing: DD.Space.sm) {
            TextField(tr("Reply to {name}…", ["name": n.title ?? n.app ?? ""]), text: $replyText)
                .textFieldStyle(.roundedBorder).controlSize(.small).focused($replyFocused)
                .onSubmit { sendReply(n) }
                .onExitCommand { cancelReply() }
            Button(tr("Send")) { sendReply(n) }
                .buttonStyle(PrimaryButtonStyle(compact: true))
                .disabled(replyText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(tr("Cancel")) { cancelReply() }.buttonStyle(SecondaryButtonStyle(compact: true))
        }
        .padding(.leading, 42).padding(.bottom, DD.Space.sm)
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            Button { withDDPointerAnimation(Motion.expand) { stacked.toggle() } } label: {
                Label(stacked ? tr("Show as One List") : tr("Group by App"),
                      systemImage: stacked ? "square.stack.3d.up.fill" : "square.stack.3d.up")
            }
            .help(stacked ? tr("Show as one list") : tr("Group by app"))
            .disabled(model.notifications.isEmpty)

            Menu {
                Picker(tr("App"), selection: $filterApp) {
                    Text(tr("All Apps")).tag(String?.none)
                    ForEach(appNames, id: \.self) { Text($0).tag(Optional($0)) }
                }
                .pickerStyle(.inline)
            } label: {
                Label(tr("Filter by App"), systemImage: filterApp == nil
                      ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
            }
            .help(tr("Filter by app"))
            .disabled(model.notifications.isEmpty)

            Button { clear(visible) } label: { Label(tr("Clear"), systemImage: "trash") }
                .keyboardShortcut(.delete, modifiers: .command)
                .help(tr("Clear all  ⌘⌫"))
                .disabled(visible.isEmpty)
        }
    }

    // MARK: Actions

    private func toggle(_ app: String) {
        withDDPointerAnimation(Motion.expand) {
            if expanded.contains(app) { expanded.remove(app) } else { expanded.insert(app) }
        }
    }

    private func dismiss(_ n: PhoneNotification) {
        withDDPointerAnimation(Motion.content) { model.dismissNotification(n.key) }
    }

    private func clear(_ items: [PhoneNotification]) {
        withDDPointerAnimation(Motion.content) { for n in items { model.dismissNotification(n.key) } }
        if filterApp != nil, !model.notifications.contains(where: { ($0.app ?? tr("App")) == filterApp }) { filterApp = nil }
    }

    private func startReply(_ n: PhoneNotification) {
        replyText = ""
        replyingKey = n.key
        Task { @MainActor in replyFocused = true }
    }
    private func cancelReply() { replyingKey = nil; replyText = "" }

    private func sendReply(_ n: PhoneNotification) {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        cancelReply()
        Task {
            do { try await Core.shared.notifReply(key: n.key, text: text); model.toast(.ok, tr("Reply sent")) }
            catch { model.toast(.bad, tr("Couldn't send the reply: {error}", ["error": error.localizedDescription])) }
        }
    }

    private func act(_ n: PhoneNotification, _ i: Int) {
        Task {
            do { try await Core.shared.notifAction(key: n.key, index: i) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }

    private func call(_ number: String) {
        Task {
            do { try await Core.shared.actionCall(number: number) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }
}
