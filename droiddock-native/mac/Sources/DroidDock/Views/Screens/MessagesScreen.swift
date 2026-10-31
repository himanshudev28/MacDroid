import AppKit
import SwiftUI

/// Messages: thread list + conversation. Shell-agnostic (ARCHITECTURE.md §K).
struct MessagesScreen: View {
    @Environment(AppModel.self) private var model
    @State private var store = MessagesStore()
    @State private var router = InboxRouter.shared
    @State private var search = ""
    @State private var showNew = false

    private struct LoadKey: Hashable { var rev: Int; var connected: Bool }

    private var shown: [SmsThread] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return store.threads }
        return store.threads.filter { [$0.name, $0.address, $0.snippet].contains { $0.lowercased().contains(q) } }
    }

    var body: some View {
        Group {
            if !model.isConnected && store.threads.isEmpty {
                EmptyState.disconnected(feature: tr("messages"))
            } else {
                HSplitView {
                    threadList.frame(minWidth: 230, idealWidth: 280, maxWidth: 360)
                    ConversationPane(store: store, search: $search).frame(minWidth: 320)
                }
                .opacity(model.isConnected ? 1 : 0.6)
                .disabled(!model.isConnected)
            }
        }
        .searchable(text: $search, prompt: tr("Search messages"))
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { showNew = true } label: { Label(tr("New Message"), systemImage: "square.and.pencil") }
                    .keyboardShortcut("n", modifiers: .command)
                    .help(tr("New message  ⌘N"))
                    .disabled(!model.isConnected)
            }
        }
        .sheet(isPresented: $showNew) {
            NewMessageSheet { target in store.open(target) }
        }
        .task(id: LoadKey(rev: model.smsRevision, connected: model.isConnected)) {
            guard model.isConnected else { return }
            await store.loadThreads()
            await store.loadMessages()
        }
        .onChange(of: router.messageTarget, initial: true) { _, t in
            guard let t else { return }
            router.messageTarget = nil
            store.open(t)
            if store.selectedID != nil { Task { await store.loadMessages() } }
        }
    }

    // MARK: Thread list

    @ViewBuilder
    private var threadList: some View {
        VStack(spacing: 0) {
            if !model.isConnected { OfflineStrip() }
            switch store.threadsPhase {
            case .loading where store.showSkeleton:
                LoadingState(rows: 7)
            case .loading, .idle:
                Color.clear
            case .failed(let msg):
                EmptyState.failed(msg) { Task { await store.loadThreads() } }
            case .loaded:
                if store.threads.isEmpty && store.compose == nil {
                    EmptyState(systemImage: "message", title: tr("No conversations"),
                               message: tr("Text messages from your phone appear here."),
                               actionTitle: tr("New Message")) { showNew = true }
                } else if shown.isEmpty && store.compose == nil {
                    NoResults(query: search)
                } else {
                    list
                }
            }
        }
        // Skeleton → list: a short content fade instead of a pop.
        .ddAnimation(Motion.content, value: store.threadsPhase)
    }

    private var list: some View {
        List(selection: Binding(get: { store.selectedID }, set: { store.select($0) })) {
            if let c = store.compose {
                ListRow(title: c.name, subtitle: tr("New message"), hoverHighlight: false) {
                    AvatarView(name: c.name, systemImage: isNumberOnly(c.name) ? "number" : nil)
                }
                .listRowSeparator(.hidden)
                .transition(.opacity)
            }
            ForEach(shown) { t in
                ListRow(title: t.name.isEmpty ? t.address : t.name, subtitle: t.snippet,
                        meta: InboxTime.short(t.date), subtitleLines: 2, hoverHighlight: false) {
                    AvatarView(name: t.name.isEmpty ? t.address : t.name,
                               systemImage: isNumberOnly(t.name.isEmpty ? t.address : t.name) ? "number" : nil)
                }
                .tag(t.threadId)
                .listRowSeparator(.hidden)
                .contextMenu {
                    Button(tr("Call")) { Task { await model.run(tr("Couldn't place the call")) { try await Core.shared.actionCall(number: t.address) } } }
                    Button(tr("Copy Number")) {
                        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(t.address, forType: .string)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
    }
}

// MARK: - Conversation

private struct ConversationPane: View {
    @Environment(AppModel.self) private var model
    var store: MessagesStore
    @Binding var search: String
    @State private var draft = ""
    @FocusState private var composeFocused: Bool

    var body: some View {
        Group {
            if store.address == nil {
                EmptyState(systemImage: "bubble.left.and.bubble.right", title: tr("Select a conversation"),
                           message: tr("Pick a thread, or start a new message with ⌘N."))
            } else {
                transcript
            }
        }
        .onChange(of: store.address) { _, _ in composeFocused = true }
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 2) {
                    switch store.messagesPhase {
                    case .loading:
                        ProgressView().controlSize(.small).padding(.top, 60)
                    case .failed(let msg):
                        EmptyState.failed(msg) { Task { await store.loadMessages() } }.frame(height: 320)
                    default:
                        if store.rows.isEmpty {
                            Text(tr("No messages yet. Say hello."))
                                .font(DD.Font.callout).foregroundStyle(DD.dim).padding(.top, 60)
                        }
                        ForEach(store.rows) { row in
                            rowView(row).id(row.id)
                        }
                    }
                }
                .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.md)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: store.rows.last?.id) { _, id in
                guard let id else { return }
                withDDAnimation(Motion.content) { proxy.scrollTo(id, anchor: .bottom) }
            }
            .onChange(of: store.selectedID) { _, _ in
                if let id = store.rows.last?.id { proxy.scrollTo(id, anchor: .bottom) }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
    }

    private var header: some View {
        HStack(spacing: DD.Space.md) {
            AvatarView(name: store.title, size: 28, systemImage: isNumberOnly(store.title) ? "number" : nil)
            VStack(alignment: .leading, spacing: 0) {
                Text(store.title).font(DD.Font.headline).foregroundStyle(DD.fg).lineLimit(1)
                if let a = store.address, a != store.title {
                    Text(a).font(DD.Font.caption).foregroundStyle(DD.dim).lineLimit(1)
                }
            }
            Spacer()
            IconButton(systemImage: "phone", label: tr("Call")) {
                if let a = store.address { Task { await model.run(tr("Couldn't place the call")) { try await Core.shared.actionCall(number: a) } } }
            }
        }
        .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.sm)
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: DD.Space.sm) {
            HStack(alignment: .bottom, spacing: DD.Space.sm) {
                TextField(tr("Message"), text: $draft, axis: .vertical)
                    .textFieldStyle(.plain).font(DD.Font.body)
                    .lineLimit(1...5)
                    .focused($composeFocused)
                    .onKeyPress(.return, phases: .down) { press in
                        if press.modifiers.contains(.shift) { draft += "\n"; return .handled }
                        sendDraft(); return .handled
                    }
                Button(action: sendDraft) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 22))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(canSend ? DD.accentInk : DD.faint, canSend ? Color.accentColor : DD.panel2)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .help(tr("Send  ⏎"))
                .accessibilityLabel(tr("Send"))
            }
            .padding(.leading, DD.Space.md).padding(.trailing, 4).padding(.vertical, 4)
            .background(Capsule(style: .continuous).fill(DD.content))
            .ddHairline(Capsule(style: .continuous))
        }
        .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.sm)
    }

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && model.isConnected }

    private func sendDraft() {
        guard canSend else { return }
        let text = draft
        draft = ""
        Task { await store.send(text) }
    }

    @ViewBuilder
    private func rowView(_ row: ConversationRow) -> some View {
        switch row {
        case .stamp(_, let date):
            Text(InboxTime.stamp(date)).font(DD.Font.footnote).foregroundStyle(DD.dim)
                .padding(.top, DD.Space.md).padding(.bottom, DD.Space.xs)
        case .message(let m, let tail):
            Bubble(text: m.body, outgoing: m.out, tail: tail, date: Date(timeIntervalSince1970: m.date / 1000))
                .padding(.bottom, tail ? 6 : 0)
        case .outgoing(let o, let tail):
            VStack(alignment: .trailing, spacing: 2) {
                Bubble(text: o.text, outgoing: true, tail: tail, date: o.date)
                    .opacity(o.state == .sending ? 0.6 : 1)
                switch o.state {
                case .sending:
                    Text(tr("Sending…")).font(DD.Font.footnote).foregroundStyle(DD.dim)
                case .sent:
                    EmptyView()
                case .failed(let err):
                    HStack(spacing: DD.Space.xs) {
                        Image(systemName: "exclamationmark.circle.fill").foregroundStyle(DD.bad)
                        Text(tr("Not delivered")).foregroundStyle(DD.badText)
                        Button(tr("Retry")) { Task { await store.retry(o.id) } }.buttonStyle(.borderless).foregroundStyle(Color.accentColor)
                        Button(tr("Delete")) { store.discard(o.id) }.buttonStyle(.borderless).foregroundStyle(DD.dim)
                    }
                    .font(DD.Font.footnote).help(err)
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.bottom, tail ? 6 : 0)
        }
    }
}

private struct Bubble: View {
    var text: String
    var outgoing: Bool
    var tail: Bool
    var date: Date
    /// A message that arrived (or was sent) a moment ago eases in; history loaded with the thread does not.
    @State private var shown: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(text: String, outgoing: Bool, tail: Bool, date: Date) {
        self.text = text; self.outgoing = outgoing; self.tail = tail; self.date = date
        _shown = State(initialValue: abs(date.timeIntervalSinceNow) > 4)
    }

    var body: some View {
        HStack(spacing: 0) {
            if outgoing { Spacer(minLength: 72) }
            Text(text)
                .font(DD.Font.body)
                .foregroundStyle(outgoing ? DD.accentInk : DD.fg)
                .textSelection(.enabled)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(
                    UnevenRoundedRectangle(
                        topLeadingRadius: 17, bottomLeadingRadius: (!outgoing && tail) ? 5 : 17,
                        bottomTrailingRadius: (outgoing && tail) ? 5 : 17, topTrailingRadius: 17,
                        style: .continuous)
                    .fill(outgoing ? Color.accentColor : DD.panel3))
                .help(date.formatted(date: .abbreviated, time: .shortened))
            if !outgoing { Spacer(minLength: 72) }
        }
        .opacity(shown ? 1 : 0)
        .scaleEffect(shown || reduceMotion ? 1 : 0.96, anchor: outgoing ? .bottomTrailing : .bottomLeading)
        .offset(y: shown || reduceMotion ? 0 : 6)
        .onAppear { if !shown { withDDAnimation(Motion.select) { shown = true } } }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(outgoing ? tr("You") : tr("Them")): \(text)")
    }
}

// MARK: - New message sheet

private struct NewMessageSheet: View {
    var onPick: (MessageTarget) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var store = ContactsStore.shared
    @State private var to = ""
    @FocusState private var focused: Bool

    private var matches: [Contact] { Array(store.matching(to).prefix(60)) }
    private var typedNumber: String? {
        let t = to.trimmingCharacters(in: .whitespaces)
        return t.filter(\.isNumber).count >= 3 && isNumberOnly(t) ? t : nil
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: DD.Space.sm) {
                Text(tr("To:")).font(DD.Font.body).foregroundStyle(DD.dim)
                TextField(tr("Name or number"), text: $to)
                    .textFieldStyle(.plain).font(DD.Font.body).focused($focused)
                    .onSubmit { if let n = typedNumber { pick(MessageTarget(number: n, name: n)) } else if let c = matches.first { pick(c) } }
            }
            .padding(.horizontal, DD.Space.lg).padding(.vertical, DD.Space.md)
            Divider()
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let n = typedNumber {
                        Button { pick(MessageTarget(number: n, name: n)) } label: {
                            ListRow(title: tr("Send to {number}", ["number": n]), hoverHighlight: true) {
                                AvatarView(name: n, systemImage: "number")
                            }
                        }.buttonStyle(.plain)
                    }
                    switch store.phase {
                    case .loading, .idle:
                        if matches.isEmpty { ProgressView().controlSize(.small).padding(DD.Space.xl) }
                    case .failed(let m):
                        Text(m).font(DD.Font.callout).foregroundStyle(DD.dim).padding(DD.Space.lg)
                    case .loaded:
                        if matches.isEmpty && typedNumber == nil {
                            Text(tr("No matching contacts")).font(DD.Font.callout).foregroundStyle(DD.dim).padding(DD.Space.xl)
                        }
                    }
                    ForEach(matches) { c in
                        Button { pick(c) } label: {
                            ListRow(title: c.name, subtitle: c.number) { AvatarView(name: c.name) }
                        }.buttonStyle(.plain)
                    }
                }
                .padding(DD.Space.sm)
            }
            Divider()
            HStack {
                Spacer()
                Button(tr("Cancel")) { dismiss() }.buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
            }
            .padding(DD.Space.md)
        }
        .frame(width: 520, height: 460)
        .onAppear { focused = true }
        .task { await store.load() }
    }

    private func pick(_ c: Contact) { pick(MessageTarget(number: c.number, name: c.name)) }
    private func pick(_ t: MessageTarget) { onPick(t); dismiss() }
}
