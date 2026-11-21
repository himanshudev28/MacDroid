import Foundation

struct MirrorStarted: Codable, Sendable, Equatable {
    var width: Int
    var height: Int
    var codec: String          // avc1.PPCCLL | hvc1... | h265
    var source: String         // screen | camera
    var facing: String?        // front | back
    /// Camera only, optional (older phones omit all three): the lenses the phone can switch between, the active
    /// one, and who takes stills ("phone" = full-resolution on the phone, "frame" = the Mac grabs the picture).
    var lenses: [MirrorLens]?
    var lens: String?
    var photo: String?
    /// Camera only: degrees clockwise to turn the received frame upright (0/90/180/270).
    var rotation: Int?
    /// Camera zoom: supported ratio range [min, max] and the current ratio. Absent = no zoom control.
    var zoomRange: [Double]?
    var zoom: Double?
}
/// One selectable camera lens. `id` is opaque to the Mac ("0@0.5", "adb:2@1"); `label` is "0.5×", "1×", "Front".
struct MirrorLens: Codable, Sendable, Equatable, Identifiable {
    var id: String
    var facing: String         // back | front | external
    var label: String
    var zoom: Double?
}
/// `camera-photo-saved {path}` / `camera-photo-error {error}` / `camera-lens-failed {lens,error}` / `camera-switching {lens}`.
struct CameraEventPayload: Codable, Sendable, Equatable {
    var path: String?; var error: String?; var lens: String?; var rotation: Int?; var ratio: Double?
}
struct ErrorPayload: Codable, Sendable, Equatable { var error: String?; var id: Int? }
struct AudioStarted: Codable, Sendable, Equatable {
    var sampleRate: Double; var channels: Int; var format: String
}

struct QuickShareFile: Codable, Sendable, Equatable { var name: String; var size: Double; var mime: String }
struct QuickShareRequest: Codable, Sendable, Equatable, Identifiable {
    var id: Int; var peer: String; var pin: String; var files: [QuickShareFile]
}
struct QuickShareProgress: Codable, Sendable, Equatable { var received: Double; var total: Double }
struct QuickShareReceived: Codable, Sendable, Equatable { var paths: [String]; var id: Int? }
struct QuickShareRejected: Codable, Sendable, Equatable { var id: Int? }

struct UpdateInfo: Codable, Sendable, Equatable {
    var version: String
    var notes: String
    var currentVersion: String
    /// Release page, for the "Open release page" fallback.
    var htmlUrl: String?
    /// The release carries a signed zip the app can install itself.
    var installAvailable: Bool?
}
/// `update-progress`: phase is downloading | verifying | installing | restarting.
struct UpdateProgress: Codable, Sendable, Equatable {
    var phase: String?; var percent: Double?; var downloaded: Double?; var total: Double?
}
/// `update_install` result: `restart` (new app in place, quit now) or `open` (show `url`).
struct UpdateInstallOutcome: Codable, Sendable, Equatable { var action: String; var url: String? }

/// `automation` event: `droiddock://` routes forwarded by the core.
struct AutomationEvent: Codable, Sendable, Equatable {
    var action: String         // mirror | mirror-adb | desktop | app | clipboard-push | commandbar
    var pkg: String?
    /// State-changing URL routes: the Mac asks before acting (any web page can fire a `droiddock://` link).
    var confirm: Bool?
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
    /// Android hid the content of this notification: show "Content hidden by Android", never look for a code.
    var redacted: Bool?
}

struct MenubarTitle: Codable, Sendable, Equatable { var title: String? }
struct MirrorWindowOp: Codable, Sendable, Equatable { var op: String }   // open | close
struct Toast: Identifiable, Equatable, Sendable {
    enum Kind: Sendable { case ok, bad, info }
    let id = UUID()
    var kind: Kind
    var text: String
}
