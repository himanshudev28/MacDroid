import AppKit
import SwiftUI

/// Contacts: Starred then A–Z, search by name or number, message / call actions.
struct ContactsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var store = ContactsStore.shared
    @State private var reloadGate = ReloadGate()
    @State private var search = ""

    private var filtered: [Contact] { store.matching(search) }

    private struct Section: Identifiable { var id: String; var title: String; var items: [Contact] }

    private var sections: [Section] {
        var out: [Section] = []
        let starred = filtered.filter(\.starred)
        if !starred.isEmpty { out.append(Section(id: "★", title: tr("Starred"), items: starred)) }
        var by: [String: [Contact]] = [:]
        for c in filtered where !c.starred {
            let ch = c.name.first.map { String($0).folding(options: .diacriticInsensitive, locale: .current).uppercased() } ?? "#"
            by[ch.first?.isLetter == true ? ch : "#", default: []].append(c)
        }
        for k in by.keys.sorted(by: { $0 == "#" ? false : ($1 == "#" ? true : $0 < $1) }) {
            out.append(Section(id: k, title: k, items: by[k]!))
        }
        return out
    }

    var body: some View {
        Group {
            if !model.isConnected && store.contacts.isEmpty {
                EmptyState.disconnected(feature: tr("contacts")) { model.selection = .dashboard }
            } else {
                switch store.phase {
                case .idle, .loading:
                    if store.contacts.isEmpty { LoadingState(rows: 8) } else { list }
                case .failed(let msg):
                    if store.contacts.isEmpty {
                        EmptyState.failed(msg) { Task { await store.load(force: true) } }
                    } else { list }
                case .loaded:
                    if store.contacts.isEmpty {
                        EmptyState(systemImage: "person.crop.circle", title: tr("No contacts"),
                                   message: tr("Contacts saved on your phone appear here."))
                    } else if filtered.isEmpty {
                        NoResults(query: search)
                    } else { list }
                }
            }
        }
        .searchable(text: $search, prompt: tr("Search contacts"))
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { Task { await store.load(force: true) } } label: { Label(tr("Refresh"), systemImage: "arrow.clockwise") }
                    .keyboardShortcut("r", modifiers: .command).help(tr("Refresh  ⌘R"))
                    .disabled(!model.isConnected)
            }
        }
        .task(id: model.isConnected) { if model.isConnected, await reloadGate.ready() { await store.load() } }
    }

    private var list: some View {
        List {
            if !model.isConnected {
                OfflineStrip().listRowSeparator(.hidden).listRowInsets(EdgeInsets())
            }
            ForEach(sections) { s in
                SwiftUI.Section {
                    ForEach(s.items) { c in
                        ListRow(title: c.name, subtitle: c.number) {
                            AvatarView(name: c.name)
                        } trailing: {
                            HStack(spacing: 2) {
                                IconButton(systemImage: "message", label: tr("Message")) { message(c) }
                                IconButton(systemImage: "phone", label: tr("Call")) { call(c) }
                            }
                        }
                        .contextMenu {
                            Button(tr("Message")) { message(c) }
                            Button(tr("Call")) { call(c) }
                            Divider()
                            Button(tr("Copy Number")) {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(c.number, forType: .string)
                            }
                        }
                        .accessibilityAction(named: tr("Message")) { message(c) }
                        .accessibilityAction(named: tr("Call")) { call(c) }
                    }
                } header: {
                    Text(s.title).font(DD.Font.headline).foregroundStyle(DD.fg).textCase(nil)
                }
            }
        }
        .listStyle(.inset)
        .scrollContentBackground(.hidden)
        .opacity(model.isConnected ? 1 : 0.6)
        .disabled(!model.isConnected)
    }

    private func message(_ c: Contact) { InboxRouter.shared.openMessages(to: MessageTarget(number: c.number, name: c.name)) }
    private func call(_ c: Contact) {
        Task {
            do { try await Core.shared.actionCall(number: c.number); model.toast(.info, tr("Calling {name}…", ["name": c.name])) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }
}
