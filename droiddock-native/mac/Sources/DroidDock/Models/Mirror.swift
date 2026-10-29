import Foundation

struct MirrorStarted: Codable, Sendable, Equatable {
    var width: Int
    var height: Int
    var codec: String          // avc1.PPCCLL | hvc1... | h265
    var source: String         // screen | camera
    var facing: String?        // front | back
}
struct ErrorPayload: Codable, Sendable, Equatable { var error: String? }
struct AudioStarted: Codable, Sendable, Equatable {
    var sampleRate: Double; var channels: Int; var format: String
}

struct QuickShareFile: Codable, Sendable, Equatable { var name: String; var size: Double; var mime: String }
struct QuickShareRequest: Codable, Sendable, Equatable, Identifiable {
    var id: Int; var peer: String; var pin: String; var files: [QuickShareFile]
}
struct QuickShareProgress: Codable, Sendable, Equatable { var received: Double; var total: Double }
struct QuickShareReceived: Codable, Sendable, Equatable { var paths: [String] }

struct UpdateInfo: Codable, Sendable, Equatable {
    var version: String
    var notes: String
    var currentVersion: String
}
struct UpdateProgress: Codable, Sendable, Equatable { var downloaded: Double; var total: Double? }

/// `automation` event: `droiddock://` routes forwarded by the core.
struct AutomationEvent: Codable, Sendable, Equatable {
    var action: String         // mirror | mirror-adb | desktop | app | clipboard-push
    var pkg: String?
}

/// `native-notification` (core -> Swift banner request).
struct NativeNotification: Codable, Sendable, Equatable {
    var id: String
    var title: String
    var body: String
    var replyKey: String?
    var sound: Bool?
    /// Per-app privacy option: show a neutral line instead of the message text.
    var hidePreview: Bool?
}

struct MenubarTitle: Codable, Sendable, Equatable { var title: String? }
struct MirrorWindowOp: Codable, Sendable, Equatable { var op: String }   // open | close
struct Toast: Identifiable, Equatable, Sendable {
    enum Kind: Sendable { case ok, bad, info }
    let id = UUID()
    var kind: Kind
    var text: String
}
