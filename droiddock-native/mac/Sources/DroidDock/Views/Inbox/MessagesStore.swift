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
            let t = try await Core.shared.smsThreads().sorted { $0.date > $1.date }
            threads = t
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
        if messages.isEmpty { messagesPhase = .loading }
        do {
            let r = try await Core.shared.smsMessages(threadId: id)
            guard selectedID == id else { return }
            messages = r.messages.sorted { $0.date < $1.date }
            messagesPhase = .loaded
            outbox.removeAll { $0.state == .sent }
        } catch {
            guard selectedID == id else { return }
            if messages.isEmpty { messagesPhase = .failed(ContactsStore.friendly(error)) }
        }
        rebuild()
    }

    func select(_ id: Int?) {
        guard id != selectedID else { return }
        selectedID = id
        compose = nil
        messages = []
        messagesPhase = id == nil ? .idle : .loading
        rebuild()
    }

    func open(_ target: MessageTarget) {
        let n = normalizedNumber(target.number)
        if let t = threads.first(where: { normalizedNumber($0.address) == n }) {
            select(t.threadId)
        } else {
            selectedID = nil
            compose = target
            messages = []
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
