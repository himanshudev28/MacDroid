import Foundation

/// `get_config` / `config` event. Every field is decoded tolerantly (missing or wrong type ->
/// default) so a newer core never breaks the UI. Keys the core adds later are in `raw`.
struct DroidConfig: Codable, Sendable, Equatable {
    var token = ""
    var port = 48484
    var notifications = true
    var nativeNotifs = true
    var clipboardSync = true
    var deviceName: String?
    var deviceGuid: String?
    var tcpAddr: String?
    var autoReconnect = true
    var pausedUntil: Double?
    var photoSyncEnabled = false
    var photoSyncDest: String?
    var macFsEnabled = false
    var webdavWritable = false
    var quickShareEnabled = false
    var macFsRoots: [String] = []
    var encryptLink = false
    var remoteControl = false
    var macInfoSync = true
    var macMediaSync = true
    var macMediaBrowser = true
    var mutedApps: [String] = []
    var menubarText = "battery"          // none | battery | media | device
    var menubarBatteryStyle = "percent"  // percent | bar | both
    var menubarMaxLen = 20
    var menubarAlbumArt = "thumb"        // none | thumb | background
    var lowBatteryAlert = true
    var lowBatteryPct = 20
    var desktopDisplaySize = ""
    var desktopUiMode = "desktop"        // desktop | tablet | phone
    var desktopFlex = true
    var appWindowChrome = false
    var appWindowKeepAlive = false
    var mirrorCodec = "h264"             // h264 | h265
    var mirrorAudio = true
    var scrcpyUhid = false
    var scrcpyStayAwake = false
    var scrcpyTurnScreenOff = false
    var scrcpyAlwaysOnTop = false
    var pinnedApps: [String] = []
    var openAppsOnMac = false
    var defaultMirrorMode = "wifi"       // wifi | adb | desktop
    var mirrorBitrateMbps = 12
    var mirrorFps = 60
    var mirrorMaxSize = 0
    var widgetEnabled = false
    var autoCheckUpdates = true
    var lastUpdateCheck: Double = 0
    /// The complete payload, for keys not modelled above (e.g. `notifTiers`).
    var raw: [String: JSONValue] = [:]

    init() {}

    private enum CodingKeys: String, CodingKey {
        case token, port, notifications, nativeNotifs, clipboardSync, deviceName, deviceGuid, tcpAddr
        case autoReconnect, pausedUntil, photoSyncEnabled, photoSyncDest, macFsEnabled, webdavWritable
        case quickShareEnabled, macFsRoots, encryptLink, remoteControl, macInfoSync, macMediaSync
        case macMediaBrowser, mutedApps, menubarText, menubarBatteryStyle, menubarMaxLen, menubarAlbumArt
        case lowBatteryAlert, lowBatteryPct, desktopDisplaySize, desktopUiMode, desktopFlex
        case appWindowChrome, appWindowKeepAlive, mirrorCodec, mirrorAudio, scrcpyUhid, scrcpyStayAwake
        case scrcpyTurnScreenOff, scrcpyAlwaysOnTop, pinnedApps, openAppsOnMac, defaultMirrorMode
        case mirrorBitrateMbps, mirrorFps, mirrorMaxSize, widgetEnabled, autoCheckUpdates, lastUpdateCheck
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        token = c.get(.token, token); port = c.get(.port, port)
        notifications = c.get(.notifications, notifications); nativeNotifs = c.get(.nativeNotifs, nativeNotifs)
        clipboardSync = c.get(.clipboardSync, clipboardSync)
        deviceName = c.opt(.deviceName); deviceGuid = c.opt(.deviceGuid); tcpAddr = c.opt(.tcpAddr)
        autoReconnect = c.get(.autoReconnect, autoReconnect); pausedUntil = c.opt(.pausedUntil)
        photoSyncEnabled = c.get(.photoSyncEnabled, photoSyncEnabled); photoSyncDest = c.opt(.photoSyncDest)
        macFsEnabled = c.get(.macFsEnabled, macFsEnabled); webdavWritable = c.get(.webdavWritable, webdavWritable)
        quickShareEnabled = c.get(.quickShareEnabled, quickShareEnabled); macFsRoots = c.get(.macFsRoots, macFsRoots)
        encryptLink = c.get(.encryptLink, encryptLink); remoteControl = c.get(.remoteControl, remoteControl)
        macInfoSync = c.get(.macInfoSync, macInfoSync); macMediaSync = c.get(.macMediaSync, macMediaSync)
        macMediaBrowser = c.get(.macMediaBrowser, macMediaBrowser); mutedApps = c.get(.mutedApps, mutedApps)
        menubarText = c.get(.menubarText, menubarText); menubarBatteryStyle = c.get(.menubarBatteryStyle, menubarBatteryStyle)
        menubarMaxLen = c.get(.menubarMaxLen, menubarMaxLen); menubarAlbumArt = c.get(.menubarAlbumArt, menubarAlbumArt)
        lowBatteryAlert = c.get(.lowBatteryAlert, lowBatteryAlert); lowBatteryPct = c.get(.lowBatteryPct, lowBatteryPct)
        desktopDisplaySize = c.get(.desktopDisplaySize, desktopDisplaySize); desktopUiMode = c.get(.desktopUiMode, desktopUiMode)
        desktopFlex = c.get(.desktopFlex, desktopFlex); appWindowChrome = c.get(.appWindowChrome, appWindowChrome)
        appWindowKeepAlive = c.get(.appWindowKeepAlive, appWindowKeepAlive)
        mirrorCodec = c.get(.mirrorCodec, mirrorCodec); mirrorAudio = c.get(.mirrorAudio, mirrorAudio)
        scrcpyUhid = c.get(.scrcpyUhid, scrcpyUhid); scrcpyStayAwake = c.get(.scrcpyStayAwake, scrcpyStayAwake)
        scrcpyTurnScreenOff = c.get(.scrcpyTurnScreenOff, scrcpyTurnScreenOff)
        scrcpyAlwaysOnTop = c.get(.scrcpyAlwaysOnTop, scrcpyAlwaysOnTop)
        pinnedApps = c.get(.pinnedApps, pinnedApps); openAppsOnMac = c.get(.openAppsOnMac, openAppsOnMac)
        defaultMirrorMode = c.get(.defaultMirrorMode, defaultMirrorMode)
        mirrorBitrateMbps = c.get(.mirrorBitrateMbps, mirrorBitrateMbps); mirrorFps = c.get(.mirrorFps, mirrorFps)
        mirrorMaxSize = c.get(.mirrorMaxSize, mirrorMaxSize); widgetEnabled = c.get(.widgetEnabled, widgetEnabled)
        autoCheckUpdates = c.get(.autoCheckUpdates, autoCheckUpdates); lastUpdateCheck = c.get(.lastUpdateCheck, lastUpdateCheck)
        raw = (try? decoder.singleValueContainer().decode([String: JSONValue].self)) ?? [:]
    }
    // encode(to:) is synthesized from CodingKeys (raw is not encoded).
}

/// `get_pairing_info`.
struct PairingInfo: Codable, Sendable, Equatable {
    var host: String
    var port: Int
    var token: String
    var ips: [String]
    /// SHA-256 of the Mac's TLS public key (base64url); the phone pins it. Absent from older cores.
    var fp: String?

    /// Same shape as pairing.ts `pairingUrl` (the QR payload).
    var url: String {
        let name = host.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-_.!~*'()"))) ?? host
        return "droiddock://pair?v=1&name=\(name)&ips=\(ips.joined(separator: ","))&port=\(port)&token=\(token)\(fpSuffix)"
    }
    private var fpSuffix: String {
        guard let fp, !fp.isEmpty else { return "" }
        return "&fp=\(fp)"
    }
}

/// Per-app notification options (`notifAppSettings[pkg]`); defaults match the core's `AppNotif::default`.
struct AppNotifSettings: Codable, Sendable, Equatable {
    var sound = false
    var preview = true
    var priority = "phone"          // phone | always | never
    var syncDismissals = true

    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sound = c.get(.sound, sound); preview = c.get(.preview, preview)
        priority = c.get(.priority, priority); syncDismissals = c.get(.syncDismissals, syncDismissals)
    }
    private enum CodingKeys: String, CodingKey { case sound, preview, priority, syncDismissals }
}

extension DroidConfig {
    func appNotif(_ pkg: String) -> AppNotifSettings {
        guard let v = raw["notifAppSettings"]?.objectValue?[pkg],
              let d = try? JSONEncoder().encode(v),
              let s = try? JSONDecoder().decode(AppNotifSettings.self, from: d) else { return AppNotifSettings() }
        return s
    }
    var syncNotifDismissals: Bool { raw["syncNotifDismissals"]?.boolValue ?? true }
    /// BLE fallback: `"network"` (default) | `"fallback"`.
    var connectionMode: String { raw["connectionMode"]?.stringValue == "fallback" ? "fallback" : "network" }
    var bleAutoReturn: Bool { raw["bleAutoReturn"]?.boolValue ?? true }
    /// BSD name of the advertised adapter; empty = Auto.
    var serverIface: String { raw["serverIface"]?.stringValue ?? "" }
}
