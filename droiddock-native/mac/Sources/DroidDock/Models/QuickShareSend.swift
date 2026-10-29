import Foundation

/// Payload shared by the `quickshare-send-*` events (fields vary per event).
struct QuickShareSendEvent: Decodable, Sendable {
    let transferId: Int
    let peer: String?
    let pin: String?
    let sent: Int64?
    let total: Int64?
    let error: String?
}

/// One outgoing Quick Share transfer, folded from its events.
struct QuickShareSend: Sendable, Identifiable, Equatable {
    enum State: Sendable, Equatable { case connecting, pin, waiting, sending, done, rejected, failed }
    let transferId: Int
    var state: State = .connecting
    var peer: String?
    var pin: String?
    var sent: Int64 = 0
    var total: Int64 = 0
    var error: String?
    var id: Int { transferId }
    var fraction: Double { total > 0 ? min(1, Double(sent) / Double(total)) : 0 }
    /// Still in progress (can be cancelled); everything else is history.
    var isLive: Bool { [.connecting, .pin, .waiting, .sending].contains(state) }
}

/// `phone-screenshot {path, via:"wifi"|"adb"}`.
struct PhoneScreenshot: Decodable, Sendable, Equatable {
    let path: String
    let via: String
}
