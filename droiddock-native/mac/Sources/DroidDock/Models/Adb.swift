import Foundation

struct ScrcpyCaps: Codable, Sendable, Equatable {
    var virtualDisplay = false, keepContent = false, flexDisplay = false, uhid = false
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        virtualDisplay = c.get(.virtualDisplay, false); keepContent = c.get(.keepContent, false)
        flexDisplay = c.get(.flexDisplay, false); uhid = c.get(.uhid, false)
    }
    private enum CodingKeys: String, CodingKey { case virtualDisplay, keepContent, flexDisplay, uhid }
}

struct ToolsStatus: Codable, Sendable, Equatable {
    var adb = false
    var scrcpy = false
    var brew = false
    var adbPath: String?
    var scrcpyPath: String?
    var scrcpyVersion: String?
    var caps = ScrcpyCaps()
    init() {}
    private enum CodingKeys: String, CodingKey { case adb, scrcpy, brew, adbPath, scrcpyPath, scrcpyVersion, caps }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        adb = c.get(.adb, false); scrcpy = c.get(.scrcpy, false); brew = c.get(.brew, false)
        adbPath = c.opt(.adbPath); scrcpyPath = c.opt(.scrcpyPath); scrcpyVersion = c.opt(.scrcpyVersion)
        caps = c.get(.caps, ScrcpyCaps())
    }
}

struct FreeformStatus: Codable, Sendable, Equatable {
    var sdk: Int?
    var supported: Bool
    var values: [String?]
    var enabled: Bool
}

/// `adb_app_window_open`: the virtual display backing an app's own Mac window.
struct AppWindowOpened: Codable, Sendable, Equatable {
    var displayId: Int
    var reused: Bool
}

/// `adb_app_window_place`: which tier got the app onto the virtual display ("notif" | "move" | "launch").
struct AppWindowPlaced: Codable, Sendable, Equatable {
    var tier: String
}

/// `appops get com.droiddock.app PROJECT_MEDIA`.
struct ProjectionStatus: Codable, Sendable, Equatable {
    var mode: String
    var allowed: Bool
}

struct AdbDevice: Codable, Sendable, Equatable, Identifiable {
    var id: String
    var serial: String
    var model: String
    var state: String
    var transport: String      // usb | wifi
    var usbSerial: String?
    var wifiSerial: String?
}

/// `adb_device_info`.
struct DeviceInfo: Codable, Sendable, Equatable {
    var model: String
    var android: String
    var sdk: String
    var battery: Double?
    var charging: Bool
}

struct Volume: Codable, Sendable, Equatable { var level: Double; var max: Double }

struct QrPairStatus: Codable, Sendable, Equatable {
    var state: String          // waiting | connecting | connected | error
    var text: String
    var addr: String?
}

struct AdbPairResult: Codable, Sendable, Equatable { var guid: String; var addr: String? }
struct AdbPairedInfo: Codable, Sendable, Equatable { var guid: String? }

struct WebdavStatus: Codable, Sendable, Equatable {
    var running: Bool
    var url: String?
    var mountPoint: String?
}
