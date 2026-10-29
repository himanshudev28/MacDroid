import Foundation

/// `transfers` event / `transfers_list` row (transfer_log.rs).
struct TransferEntry: Codable, Sendable, Equatable, Identifiable {
    var key: Int
    var name: String
    var dir: String            // toPhone | toMac
    var source: String         // files | share | macFiles | quickShare | photoSync | finder
    var unit: String           // bytes | items
    var total: Double
    var done: Double
    var status: String         // active | done | failed | cancelled
    var error: String?
    var cancelledBy: String?   // mac | phone
    var startedMs: Double
    var updatedMs: Double
    var endedMs: Double?
    var speed: Double
    var etaSecs: Double?
    var path: String?
    var cancellable: Bool
    var id: Int { key }
    var isActive: Bool { status == "active" }
}

/// `transfer-progress` (files tab + phone-initiated pushes).
struct TransferProgress: Codable, Sendable, Equatable {
    var transferId: Int
    var name: String
    var sent: Double
    var total: Double
    var dir: String            // push | pull | phone
    var done: Bool
    var error: String?
}
