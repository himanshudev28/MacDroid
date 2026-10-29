import Foundation
import Observation

/// Phone contacts, fetched once and shared by Contacts, the dialer and the new-message picker.
@Observable @MainActor
final class ContactsStore {
    static let shared = ContactsStore()

    enum Phase: Equatable { case idle, loading, loaded, failed(String) }
    private(set) var contacts: [Contact] = []
    private(set) var phase: Phase = .idle

    private init() {}

    func load(force: Bool = false) async {
        if phase == .loading { return }
        if phase == .loaded && !force { return }
        phase = .loading
        do {
            let list = try await Core.shared.contactsList()
            contacts = list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            phase = .loaded
        } catch {
            phase = .failed(Self.friendly(error))
        }
    }

    func reset() { contacts = []; phase = .idle }

    static func friendly(_ error: Error) -> String {
        let m = error.localizedDescription
        return m.isEmpty ? tr("The phone didn't answer. Check that it's nearby and try again.") : m
    }

    func matching(_ query: String) -> [Contact] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return contacts }
        let digits = q.filter(\.isNumber)
        return contacts.filter {
            $0.name.lowercased().contains(q) || (!digits.isEmpty && $0.number.filter(\.isNumber).contains(digits))
        }
    }

    func name(forNumber number: String) -> String? {
        let n = normalizedNumber(number)
        guard !n.isEmpty else { return nil }
        return contacts.first { normalizedNumber($0.number) == n }?.name
    }
}
