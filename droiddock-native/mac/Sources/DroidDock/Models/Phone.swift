import Foundation

/// `wifi_status` / `wifi-status`.
struct WifiStatus: Codable, Sendable, Equatable {
    var connected: Bool = false
    var phoneName: String?
    var caps: [String]?
    /// `wifi` | `ble` | nil (BLE fallback; additive).
    var transport: String?
    /// `tls` | `aes` | `ble` | `plain` | `none` (additive); changes when the same phone swaps link type.
    var security: String?
    func supports(_ cap: String) -> Bool { connected && (caps ?? []).contains(cap) }
}

/// The last phone the core blocked for connecting without encryption (kept in memory only).
struct BlockedPlain: Equatable, Sendable {
    var deviceId: String
    var name: String
}

/// `{kind, text}` from `wifi-event`.
struct WifiEvent: Codable, Sendable, Equatable {
    var kind: String   // ok | bad | info
    var text: String
    /// Additive: English template + `{name}`-style params, so the core's own messages can be localized.
    var key: String?
    var params: [String: String]?
    /// Set with `action == "allow-plain"`: the blocked phone's id, for `tls_allow_plain`.
    var deviceId: String?
    var action: String?
    /// What to show: the localized template when the core sent a key, else its English `text`.
    var display: String { key.map { tr($0, params ?? [:]) } ?? text }
}

/// bridge.ts `Notif` (named to avoid Foundation.Notification).
struct PhoneNotification: Codable, Sendable, Equatable, Identifiable {
    var key: String
    var app: String?
    var title: String?
    var text: String?
    var replyable: Bool?
    var time: Double?
    var type: String?          // "call" for incoming-call cards
    var pkg: String?
    var priority: String?      // max | high | default | low | min
    var ongoing: Bool?
    var progress: Double?
    var progressMax: Double?
    var progressIndeterminate: Bool?
    var actions: [String]?
    var number: String?
    var name: String?
    var id: String { key }
}

/// bridge.ts `IncomingCall` (`call` event).
struct IncomingCall: Codable, Sendable, Equatable {
    var state: String          // ringing | active | idle (ADB: RINGING/ACTIVE/IDLE, dialing)
    var number: String?
    var name: String?
    var key: String?
    var canAnswer: Bool?
    var canEnd: Bool?
    var canAudio: Bool?
    var muted: Bool?
    var speaker: Bool?
    /// Internet calls (WhatsApp…): the app it came through.
    var app: String?
    var voip: Bool?
    /// Phones send "" for an unknown name or number: treat it as missing.
    var callerName: String? { name.flatMap { $0.isEmpty ? nil : $0 } }
    var callerNumber: String? { number.flatMap { $0.isEmpty ? nil : $0 } }
}

struct CallActionResult: Codable, Sendable, Equatable {
    var ok: Bool
    var muted: Bool?
    var speaker: Bool?
    var error: String?
}

struct CallState: Codable, Sendable, Equatable { var state: String; var serial: String? }

struct MediaState: Codable, Sendable, Equatable {
    var active: Bool = false
    var title: String?
    var artist: String?
    var app: String?
    var playing: Bool?
    var vol: Double?
    var volMax: Double?
    var pos: Double?
    var dur: Double?
    var trackKey: String?
    /// Base64 JPEG; only on a track change.
    var art: String?
    /// True when the payload carried an `art` key at all (null => "no art, drop the cache").
    var artKeyPresent = false

    private enum CodingKeys: String, CodingKey { case active, title, artist, app, playing, vol, volMax, pos, dur, trackKey, art }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active = c.get(.active, false); title = c.opt(.title); artist = c.opt(.artist); app = c.opt(.app)
        playing = c.opt(.playing); vol = c.opt(.vol); volMax = c.opt(.volMax); pos = c.opt(.pos); dur = c.opt(.dur)
        trackKey = c.opt(.trackKey); art = c.opt(.art); artKeyPresent = c.contains(.art)
    }
}

/// `device-info` pushed by the phone over the Wi-Fi link.
struct AppDeviceInfo: Codable, Sendable, Equatable {
    var model: String?
    var android: String?
    var battery: Double?
    var charging: Bool?
}

/// `ble-status` event / `ble_status` command (docs/BLE-NEARBY-DESIGN.md §8).
struct BleStatus: Codable, Sendable, Equatable {
    var mode: String = "network"
    var radio: String = "unknown"       // unknown|resetting|unsupported|unauthorized|poweredOff|poweredOn
    var auth: String = "notDetermined"  // notDetermined|restricted|denied|allowed
    var state: String = "off"           // off|standby|scanning|connecting|authenticating|connected
    var rssi: Int?
    var autoReturn: Bool = true
    var prefer: String = "auto"         // auto|wifi|ble
    var wifiAvailable: Bool = false
    var bleLive: Bool = false
}

struct LinkQuality: Codable, Sendable, Equatable {
    var rttMs: Double?
    var grade: String          // good | fair | weak | stalled
}

struct HealthItem: Codable, Sendable, Equatable, Identifiable {
    var id: String
    var ok: Bool
    var severity: String       // error | warn | info
    var title: String
    var detail: String
    var fix: String?
    var side: String           // mac | phone
}

struct SmsThread: Codable, Sendable, Equatable, Identifiable {
    var threadId: Int; var address: String; var name: String; var snippet: String; var date: Double
    var id: Int { threadId }
}
struct SmsMessage: Codable, Sendable, Equatable, Identifiable {
    var id: Int; var body: String; var date: Double; var out: Bool
}
struct SmsMessages: Codable, Sendable { var messages: [SmsMessage]; var address: String }
struct Contact: Codable, Sendable, Equatable, Identifiable {
    var name: String; var number: String; var starred: Bool
    var id: String { number + name }
}
struct PhoneApp: Codable, Sendable, Equatable, Identifiable {
    var pkg: String; var label: String
    var id: String { pkg }
}
struct FsEntry: Codable, Sendable, Equatable, Identifiable {
    var name: String; var dir: Bool; var size: Double; var modified: Double
    var id: String { name }
}
struct MediaItem: Codable, Sendable, Equatable, Identifiable {
    var id: Int; var name: String; var date: Double; var size: Double; var path: String
    var kind: String           // image | video
    var duration: Double?
}
struct EditSync: Codable, Sendable, Equatable {
    var localPath: String; var phonePath: String
    var status: String         // syncing | synced | pending
    var error: String?
}
struct PhotoSyncProgress: Codable, Sendable, Equatable {
    var done: Double; var total: Double; var name: String?; var error: String?
}
