import Foundation
import Observation

/// One optimistic outgoing message.
struct OutgoingSms: Identifiable, Equatable {
    enum State: Equatable { case sending, sent, failed(String) }
    let id = UUID()
    var address: String
    var text: String
    var date = Date()
    var state: State = .sending
}

/// One line of the conversation transcript.
enum ConversationRow: Identifiable, Equatable {
    case stamp(id: String, Date)
    case message(SmsMessage, tail: Bool)
    case outgoing(OutgoingSms, tail: Bool)

    var id: String {
        switch self {
        case .stamp(let id, _): "s-" + id
        case .message(let m, _): "m-\(m.id)"
        case .outgoing(let o, _): "o-" + o.id.uuidString
        }
    }
}

@Observable @MainActor
final class MessagesStore {
    enum Phase: Equatable { case idle, loading, loaded, failed(String) }

    private(set) var threads: [SmsThread] = []
    private(set) var threadsPhase: Phase = .idle
    private(set) var showSkeleton = false
    private(set) var messages: [SmsMessage] = []
    private(set) var messagesPhase: Phase = .idle
    private(set) var outbox: [OutgoingSms] = []
    private(set) var rows: [ConversationRow] = []

    // Paging + text search (phone caps `smspage` / `smssearch`; both false on an older phone).
    private(set) var caps = SmsCaps()
    private(set) var threadsMore = false
    private(set) var moreOlder = false
    private(set) var moreNewer = false
    private(set) var pagingThreads = false
    private(set) var pagingMessages = false
    private(set) var hits: [SmsHit] = []
    private(set) var hitsMore = false
    private(set) var searching = false
    private(set) var searchQuery = ""
    /// Row to scroll to after a search jump, and the message to flash.
    private(set) var scrollTarget: String?
    private(set) var highlightID: Int?
    private var searchGen = 0
    /// Bumped by every action that replaces the message window; a reply from an older one is dropped.
    private var loadGen = 0
    private var jumping = false
    /// Threads the phone has sent so far: the paging offset (a jump-added thread must not shift it).
    private var pagedThreads = 0
    private static let firstPage = 30

    var selectedID: Int?
    private(set) var compose: MessageTarget?
    var selected: SmsThread? { threads.first { $0.threadId == selectedID } }
    /// Address of the open conversation (thread or fresh compose).
    var address: String? { selected?.address ?? compose?.number }
    var title: String { selected.map { $0.name.isEmpty ? $0.address : $0.name } ?? compose?.name ?? "" }

    private var skeletonTask: Task<Void, Never>?

    // MARK: Loading

    func loadThreads() async {
        if threads.isEmpty {
            threadsPhase = .loading
            skeletonTask?.cancel()
            skeletonTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(300), tolerance: .milliseconds(100))
                if !Task.isCancelled, self?.threadsPhase == .loading { self?.showSkeleton = true }
            }
        }
        do {
            caps = (try? await Core.shared.smsCaps()) ?? SmsCaps()
            // Refresh everything already paged in, so a new message doesn't collapse the list to page one.
            let page: SmsThreadPage
            if caps.paging {
                page = try await Core.shared.smsThreadsPage(offset: 0, limit: min(max(Self.firstPage, threads.count), 500))
            } else {
                page = SmsThreadPage(threads: try await Core.shared.smsThreads(), more: false)
            }
            var t = page.threads.sorted { $0.date > $1.date }
            // A conversation opened from a search hit may be older than the loaded pages: keep it.
            if let sel = selectedID, !t.contains(where: { $0.threadId == sel }),
               let keep = threads.first(where: { $0.threadId == sel }) { t.append(keep) }
            threads = t
            pagedThreads = page.threads.count
            threadsMore = page.more
            threadsPhase = .loaded
            #if DEBUG
            // Dev/QA: DD_OPEN_FIRST_THREAD=1 opens the newest conversation on launch.
            if selectedID == nil, compose == nil, ProcessInfo.processInfo.environment["DD_OPEN_FIRST_THREAD"] != nil,
               let first = t.first { selectedID = first.threadId }
            #endif
            // A compose target that now has a thread becomes that thread.
            if let c = compose, let m = t.first(where: { normalizedNumber($0.address) == normalizedNumber(c.number) }) {
                compose = nil; selectedID = m.threadId
            }
        } catch {
            if threads.isEmpty { threadsPhase = .failed(ContactsStore.friendly(error)) }
        }
        showSkeleton = false
        skeletonTask?.cancel()
    }

    func loadMessages() async {
        guard let id = selectedID else { messages = []; messagesPhase = .idle; rebuild(); return }
        // Reading history from a search jump: don't yank the window back to the newest messages.
        if moreNewer && messagesPhase == .loaded { return }
        if jumping { return }
        loadGen += 1
        let gen = loadGen
        if messages.isEmpty { messagesPhase = .loading }
        do {
            // Keep any older pages already scrolled in.
            let page = try await Core.shared.smsMessagesPage(
                threadId: id, limit: caps.paging ? min(max(60, messages.count), 500) : nil)
            guard selectedID == id, gen == loadGen else { return }
            messages = page.messages.sorted { $0.date < $1.date }
            moreOlder = page.moreOlder
            moreNewer = false
            messagesPhase = .loaded
            outbox.removeAll { $0.state == .sent }
        } catch {
            guard selectedID == id, gen == loadGen else { return }
            if messages.isEmpty { messagesPhase = .failed(ContactsStore.friendly(error)) }
        }
        rebuild()
    }

    /// Older threads, when the user reaches the end of the list.
    func loadMoreThreads() async {
        guard threadsMore, !pagingThreads, caps.paging else { return }
        pagingThreads = true
        defer { pagingThreads = false }
        guard let page = try? await Core.shared.smsThreadsPage(offset: pagedThreads, limit: Self.firstPage) else { return }
        let known = Set(threads.map(\.threadId))
        threads += page.threads.filter { !known.contains($0.threadId) }
        threads.sort { $0.date > $1.date }
        pagedThreads += page.threads.count
        threadsMore = page.more
    }

    /// Older messages in the open conversation. Returns the id of the row that was first, so the view can
    /// keep the reader's place when the new rows are prepended.
    @discardableResult
    func loadOlder() async -> String? {
        guard moreOlder, !pagingMessages, messagesPhase == .loaded, let id = selectedID,
              let oldest = messages.first else { return nil }
        pagingMessages = true
        defer { pagingMessages = false }
        guard let page = try? await Core.shared.smsMessagesPage(threadId: id, before: oldest, limit: 60),
              selectedID == id else { return nil }
        let known = Set(messages.map(\.id))
        messages = (page.messages.filter { !known.contains($0.id) } + messages).sorted { $0.date < $1.date }
        moreOlder = page.moreOlder
        rebuild()
        return "m-\(oldest.id)"
    }

    /// Newer messages, only while reading a window that ended before the newest message (after a search jump).
    func loadNewer() async {
        guard moreNewer, !pagingMessages, messagesPhase == .loaded, let id = selectedID,
              let newest = messages.last else { return }
        pagingMessages = true
        defer { pagingMessages = false }
        guard let page = try? await Core.shared.smsMessagesPage(threadId: id, after: newest, limit: 60),
              selectedID == id else { return }
        let known = Set(messages.map(\.id))
        messages = (messages + page.messages.filter { !known.contains($0.id) }).sorted { $0.date < $1.date }
        moreNewer = page.moreNewer
        rebuild()
    }

    // MARK: Search in message text

    /// Debounced by the caller. An empty or 1-character query clears the hits.
    func search(_ raw: String) async {
        let q = raw.trimmingCharacters(in: .whitespaces)
        searchGen += 1
        let gen = searchGen
        searchQuery = q
        guard caps.search, q.count >= 2 else { hits = []; hitsMore = false; searching = false; return }
        searching = true
        defer { if gen == searchGen { searching = false } }
        guard let page = try? await Core.shared.smsSearch(query: q, offset: 0), gen == searchGen else { return }
        hits = page.results
        hitsMore = page.more
    }

    func loadMoreHits() async {
        guard hitsMore, !searching, caps.search else { return }
        let gen = searchGen, q = searchQuery
        searching = true
        defer { if gen == searchGen { searching = false } }
        guard let page = try? await Core.shared.smsSearch(query: q, offset: hits.count), gen == searchGen else { return }
        let known = Set(hits.map(\.id))
        hits += page.results.filter { !known.contains($0.id) }
        hitsMore = page.more
    }

    /// Open the conversation a hit belongs to, centred on that message.
    func jump(to hit: SmsHit) async {
        if !threads.contains(where: { $0.threadId == hit.threadId }) {
            threads.append(SmsThread(threadId: hit.threadId, address: hit.address, name: hit.name,
                                     snippet: hit.body, date: hit.date))
            threads.sort { $0.date > $1.date }
        }
        selectedID = hit.threadId
        compose = nil
        messages = []
        moreOlder = false; moreNewer = false
        messagesPhase = .loading
        loadGen += 1
        let gen = loadGen
        jumping = true
        rebuild()
        defer { if gen == loadGen { jumping = false } }
        do {
            let page = try await Core.shared.smsMessagesPage(threadId: hit.threadId, around: hit.id, limit: 60)
            guard selectedID == hit.threadId, gen == loadGen else { return }
            messages = page.messages.sorted { $0.date < $1.date }
            moreOlder = page.moreOlder
            moreNewer = page.moreNewer
            messagesPhase = .loaded
            rebuild()
            if messages.contains(where: { $0.id == hit.id }) {
                scrollTarget = "m-\(hit.id)"
                highlightID = hit.id
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(2))
                    if self?.highlightID == hit.id { self?.highlightID = nil }
                }
            }
        } catch {
            guard selectedID == hit.threadId, gen == loadGen else { return }
            messagesPhase = .failed(ContactsStore.friendly(error))
        }
    }

    func clearScrollTarget() { scrollTarget = nil }

    func select(_ id: Int?) {
        guard id != selectedID else { return }
        loadGen += 1; jumping = false
        selectedID = id
        compose = nil
        messages = []
        moreOlder = false; moreNewer = false
        messagesPhase = id == nil ? .idle : .loading
        rebuild()
    }

    func open(_ target: MessageTarget) {
        let n = normalizedNumber(target.number)
        if let t = threads.first(where: { normalizedNumber($0.address) == n }) {
            select(t.threadId)
        } else {
            loadGen += 1; jumping = false
            selectedID = nil
            compose = target
            messages = []
            moreOlder = false; moreNewer = false
            messagesPhase = .loaded
            rebuild()
        }
    }

    // MARK: Sending

    func send(_ raw: String) async {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let address else { return }
        let out = OutgoingSms(address: address, text: text)
        outbox.append(out)
        rebuild()
        await deliver(out.id)
    }

    func retry(_ id: UUID) async {
        guard let i = outbox.firstIndex(where: { $0.id == id }) else { return }
        outbox[i].state = .sending
        rebuild()
        await deliver(id)
    }

    func discard(_ id: UUID) { outbox.removeAll { $0.id == id }; rebuild() }

    private func deliver(_ id: UUID) async {
        guard let i = outbox.firstIndex(where: { $0.id == id }) else { return }
        let item = outbox[i]
        do {
            try await Core.shared.smsSend(address: item.address, text: item.text)
            if let j = outbox.firstIndex(where: { $0.id == id }) { outbox[j].state = .sent }
            rebuild()
            moreNewer = false   // sending returns a search-jump window to the live end
            await loadThreads()
            await loadMessages()
        } catch {
            if let j = outbox.firstIndex(where: { $0.id == id }) {
                outbox[j].state = .failed(ContactsStore.friendly(error))
            }
            rebuild()
        }
    }

    // MARK: Transcript

    private func rebuild() {
        let addr = address.map(normalizedNumber)
        let pending = outbox.filter { addr != nil && normalizedNumber($0.address) == addr }
        var out: [ConversationRow] = []
        var last: Date?
        func stampIfNeeded(_ d: Date, _ key: String) {
            if last == nil || d.timeIntervalSince(last!) > 15 * 60 { out.append(.stamp(id: key, d)) }
            last = d
        }
        for (i, m) in messages.enumerated() {
            let d = Date(timeIntervalSince1970: m.date / 1000)
            stampIfNeeded(d, "\(m.id)")
            let next = i + 1 < messages.count ? messages[i + 1] : nil
            let sameSideNext: Bool
            if let next { sameSideNext = next.out == m.out && (next.date - m.date) <= 15 * 60_000 }
            else { sameSideNext = !pending.isEmpty && m.out }
            out.append(.message(m, tail: !sameSideNext))
        }
        for (i, o) in pending.enumerated() {
            stampIfNeeded(o.date, o.id.uuidString)
            out.append(.outgoing(o, tail: i == pending.count - 1))
        }
        rows = out
    }
}
