import Foundation

/// Typed wrappers over `Core.invoke`, one per export of the old bridge.ts (same names, same arg keys).
/// Removed (Swift-native now): get_appearance, window_theme_set, mirror_focus, mirror_set_on_top,
/// menubar_hide, open_main_window, autostart_get/set. Added: core_ping, mirror_closed, automation_open_url.
extension Core {
    // MARK: Core / config
    func corePing() async throws -> String { try await invoke("core_ping") }
    func getConfig() async throws -> DroidConfig { try await invoke("get_config") }
    @discardableResult
    func setSetting(_ key: String, _ value: Any) async throws -> DroidConfig {
        try await invoke("set_setting", ["key": key, "value": value])
    }
    func getPairingInfo() async throws -> PairingInfo { try await invoke("get_pairing_info") }
    func wifiStatus() async throws -> WifiStatus { try await invoke("wifi_status") }
    func pauseSet(until: Double?) async throws -> DroidConfig { try await invoke("pause_set", ["until": until.map { Int64($0.rounded()) } as Any]) }
    func widgetSet(show: Bool) async throws { try await invoke("widget_set", ["show": show]) }
    func automationOpenUrl(_ url: String) async throws { try await invoke("automation_open_url", ["url": url]) }

    // MARK: Notifications
    func notifReply(key: String, text: String) async throws { try await invoke("notif_reply", ["key": key, "text": text]) }
    func notifOpen(key: String, displayId: Int? = nil) async throws -> NotifOpenResult {
        var a: [String: Any] = ["key": key]
        if let displayId { a["displayId"] = Int64(displayId) }
        return try await invoke("notif_open", a)
    }
    func notifDismiss(key: String) async throws { try await invoke("notif_dismiss", ["key": key]) }
    func notifAction(key: String, index: Int) async throws { try await invoke("notif_action", ["key": key, "index": index]) }

    // MARK: Files
    func fsList(path: String) async throws -> [FsEntry] { try await invoke("fs_list", ["path": path]) }
    func fsDelete(path: String) async throws { try await invoke("fs_delete", ["path": path]) }
    func fsMkdir(path: String, name: String) async throws { let _: String = try await invoke("fs_mkdir", ["path": path, "name": name]) }
    func fsRename(path: String, newName: String) async throws { try await invoke("fs_rename", ["path": path, "newName": newName]) }
    func fsPull(path: String, name: String) async throws -> String { try await invoke("fs_pull", ["path": path, "name": name]) }
    func fsPush(localPath: String, dest: String) async throws { try await invoke("fs_push", ["localPath": localPath, "dest": dest]) }
    func fsCancel(transferId: Int) async throws { try await invoke("fs_cancel", ["transferId": transferId]) }
    func fsOpenInPlace(path: String) async throws { try await invoke("fs_open_in_place", ["path": path]) }
    func fsPendingSyncs() async throws -> [String] { try await invoke("fs_pending_syncs") }

    // MARK: Transfers
    func transfersList() async throws -> [TransferEntry] { try await invoke("transfers_list") }
    func transfersClear() async throws { try await invoke("transfers_clear") }
    func transferCancel(key: Int) async throws { try await invoke("transfer_cancel", ["key": key]) }
    func transferReveal(key: Int) async throws { try await invoke("transfer_reveal", ["key": key]) }
    func transferOpen(key: Int) async throws { try await invoke("transfer_open", ["key": key]) }

    // MARK: Photos
    func photosList(offset: Int, limit: Int) async throws -> [MediaItem] { try await invoke("photos_list", ["offset": offset, "limit": limit]) }
    func photoThumb(id: Int, kind: String) async throws -> String { try await invoke("photo_thumb", ["id": id, "kind": kind]) }
    func photoOpen(path: String, name: String) async throws { try await invoke("photo_open", ["path": path, "name": name]) }
    func photoSyncBackfill() async throws { try await invoke("photo_sync_backfill") }

    // MARK: Messages / contacts / calls
    func smsThreads() async throws -> [SmsThread] { try await invoke("sms_threads") }
    func smsMessages(threadId: Int) async throws -> SmsMessages { try await invoke("sms_messages", ["threadId": threadId]) }
    func smsSend(address: String, text: String) async throws { try await invoke("sms_send", ["address": address, "text": text]) }
    func contactsList() async throws -> [Contact] { try await invoke("contacts_list") }
    func actionCall(number: String) async throws { try await invoke("action_call", ["number": number]) }
    func callAction(_ action: String, on: Bool? = nil) async throws -> CallActionResult {
        try await invoke("call_action", ["action": action, "on": on as Any])
    }
    func ringPhone(on: Bool) async throws -> Bool { try await invoke("ring_phone", ["on": on]) }
    func clipboardPushNow() async throws { try await invoke("clipboard_push_now") }

    // MARK: Health / media remote
    func healthCheck() async throws -> [HealthItem] { try await invoke("health_check") }
    func healthFix(id: String) async throws -> String { try await invoke("health_fix", ["id": id]) }
    func mediaCmd(_ cmd: String, value: Double = 0) async throws { try await invoke("media_cmd", ["cmd": cmd, "value": Int64(value.rounded())]) }   // the core takes an i64; a fractional JSON number is rejected
    func mediaState() async throws -> MediaState? { try await invoke("media_state") }

    // MARK: Wallpaper / apps
    func wallpaperGet() async throws -> String { try await invoke("wallpaper_get") }
    func appsList() async throws -> [PhoneApp] { try await invoke("apps_list") }
    func appIcon(pkg: String) async throws -> String { try await invoke("app_icon", ["pkg": pkg]) }
    func appLaunch(pkg: String) async throws { try await invoke("app_launch", ["pkg": pkg]) }

    // MARK: Mirror / audio
    func mirrorPopout(source: String) async throws { try await invoke("mirror_popout", ["source": source]) }
    func mirrorStop() async throws { try await invoke("mirror_stop") }
    func mirrorInput(_ msg: [String: Any]) async throws { try await invoke("mirror_input", ["msg": msg]) }
    /// Call once the mirror window + decoder are ready; frames flow afterwards. Returns a pending `mirror-started`.
    func mirrorAttach() async throws -> MirrorStarted? { try await invoke("mirror_attach") }
    func mirrorClosed() async throws { try await invoke("mirror_closed") }
    func mirrorSetHevcSupported(_ supported: Bool) async throws { try await invoke("mirror_set_hevc_supported", ["supported": supported]) }
    func mirrorSaveCapture(_ data: Data, ext: String) async throws -> String {
        try await invokeBytes("mirror_save_capture", ["ext": ext], body: data)
    }
    func audioAttach() async throws -> AudioStarted? { try await invoke("audio_attach") }

    // MARK: Quick Share
    func quickshareSetEnabled(on: Bool) async throws -> Bool { try await invoke("quickshare_set_enabled", ["on": on]) }
    func quickshareStatus() async throws -> Bool { try await invoke("quickshare_status") }
    func quickshareRespond(id: Int, accept: Bool) async throws { try await invoke("quickshare_respond", ["id": id, "accept": accept]) }

    // MARK: ADB / scrcpy
    func scrcpyEmbeddedStart(serial: String) async throws { try await invoke("scrcpy_embedded_start", ["serial": serial]) }
    func scrcpyEmbeddedStop() async throws { try await invoke("scrcpy_embedded_stop") }
    func scrcpyEmbeddedRunning() async throws -> Bool { try await invoke("scrcpy_embedded_running") }
    func adbTools() async throws -> ToolsStatus { try await invoke("adb_tools") }
    func adbScrcpyInstall() async throws { try await invoke("adb_scrcpy_install") }
    func adbDevices() async throws -> [AdbDevice] { try await invoke("adb_devices") }
    func adbDeviceInfo(serial: String) async throws -> DeviceInfo { try await invoke("adb_device_info", ["serial": serial]) }
    func adbGoWireless(serial: String) async throws -> String { try await invoke("adb_go_wireless", ["serial": serial]) }
    func adbPairWireless(hostPort: String, code: String) async throws -> AdbPairResult {
        try await invoke("adb_pair_wireless", ["hostPort": hostPort, "code": code])
    }
    func adbUnpair() async throws { try await invoke("adb_unpair") }
    func adbPairedInfo() async throws -> AdbPairedInfo { try await invoke("adb_paired_info") }
    func adbReconnectNow() async throws -> String { try await invoke("adb_reconnect_now") }
    func adbQrPairStart(serviceName: String, password: String) async throws {
        try await invoke("adb_qr_pair_start", ["serviceName": serviceName, "password": password])
    }
    func adbQrPairCancel() async throws { try await invoke("adb_qr_pair_cancel") }
    func adbCamera(serial: String) async throws { try await invoke("adb_camera", ["serial": serial]) }
    func adbMirror(serial: String) async throws { try await invoke("adb_mirror", ["serial": serial]) }
    func adbDesktop(serial: String, size: String? = nil) async throws {
        try await invoke("adb_desktop", ["serial": serial, "size": (size?.isEmpty == false ? size! : NSNull()) as Any])
    }
    func adbMirrorApp(serial: String, package: String, newDisplay: Bool) async throws {
        try await invoke("adb_mirror_app", ["serial": serial, "package": package, "newDisplay": newDisplay])
    }
    func adbAppWindowOpen(serial: String, package: String, title: String) async throws -> AppWindowOpened {
        try await invoke("adb_app_window_open", ["serial": serial, "package": package, "title": title])
    }
    func adbSecondScreen(serial: String) async throws -> AppWindowOpened {
        try await invoke("adb_second_screen", ["serial": serial])
    }
    func adbVdKey(serial: String, displayId: Int, key: String, package: String? = nil) async throws {
        try await invoke("adb_vd_key", ["serial": serial, "displayId": Int64(displayId), "key": key, "package": (package ?? NSNull()) as Any])
    }
    func adbVdTasks(serial: String, displayId: Int) async throws -> [VDTask] {
        try await invoke("adb_vd_tasks", ["serial": serial, "displayId": Int64(displayId)])
    }
    func adbVdSwitch(serial: String, displayId: Int, package: String) async throws {
        try await invoke("adb_vd_switch", ["serial": serial, "displayId": Int64(displayId), "package": package])
    }
    func adbAppWindowPlace(serial: String, package: String, displayId: Int, fired: Bool) async throws -> AppWindowPlaced {
        try await invoke("adb_app_window_place", ["serial": serial, "package": package, "displayId": Int64(displayId), "fired": fired])
    }
    func adbProjectionStatus() async throws -> ProjectionStatus { try await invoke("adb_projection_status") }
    func adbProjectionGrant(allow: Bool) async throws -> ProjectionStatus { try await invoke("adb_projection_grant", ["allow": allow]) }
    func adbScreenshot(serial: String) async throws -> String { try await invoke("adb_screenshot", ["serial": serial]) }
    func adbVolumeGet() async throws -> Volume { try await invoke("adb_volume_get") }
    func adbVolumeSet(level: Double, currentLevel: Double) async throws -> String {
        try await invoke("adb_volume_set", ["level": Int64(level.rounded()), "currentLevel": Int64(currentLevel.rounded())])
    }
    func adbCallEnd() async throws { try await invoke("adb_call_end") }
    func adbCallSpeaker() async throws { try await invoke("adb_call_speaker") }
    func adbCallMute() async throws { try await invoke("adb_call_mute") }
    func adbCallDtmf(digit: String) async throws { try await invoke("adb_call_dtmf", ["digit": digit]) }
    func adbCallStartPolling(serial: String? = nil) async throws { try await invoke("adb_call_start_polling", ["serial": serial as Any]) }
    func adbFreeformStatus() async throws -> FreeformStatus { try await invoke("adb_freeform_status") }
    func adbFreeformEnable() async throws -> FreeformStatus { try await invoke("adb_freeform_enable") }
    func adbFreeformRevert() async throws -> FreeformStatus { try await invoke("adb_freeform_revert") }

    // MARK: WebDAV / crash logs
    func webdavStart() async throws -> WebdavStatus { try await invoke("webdav_start") }
    func webdavStop() async throws -> WebdavStatus { try await invoke("webdav_stop") }
    func webdavStatus() async throws -> WebdavStatus { try await invoke("webdav_status") }
    func crashLogCount() async throws -> Int { try await invoke("crash_log_count") }
    func crashLogsReveal() async throws { try await invoke("crash_logs_reveal") }
    func crashLogsClear() async throws -> Int { try await invoke("crash_logs_clear") }

    // MARK: Accessibility / Spaces
    func accessibilityTrusted() async throws -> Bool { try await invoke("accessibility_trusted") }
    func openAccessibilitySettings() async throws { try await invoke("open_accessibility_settings") }
    func accessibilityReset() async throws -> Bool { try await invoke("accessibility_reset") }
    func spacesBindingActive() async throws -> Bool { try await invoke("spaces_binding_active") }
    func spacesBindingClear() async throws -> Bool { try await invoke("spaces_binding_clear") }

    // MARK: Updates
    func appVersion() async throws -> String { try await invoke("app_version") }
    func updateCheck() async throws -> UpdateInfo? { try await invoke("update_check") }
    /// Returns the release page URL (Swift opens it); no auto-install until Sparkle.
    func updateInstall() async throws -> String? { try await invoke("update_install") }
}

// MARK: WP8/WP9 additions (notification tiers, Wi-Fi screenshot, Quick Share send)
// Events: `phone-screenshot {path, via:"wifi"|"adb"}`, `quickshare-devices [QuickShareDevice]`,
// `quickshare-send-pin {transferId, peer, pin}`, `quickshare-send-waiting {transferId}`,
// `quickshare-send-progress {transferId, sent, total}`, `quickshare-send-done {transferId}`,
// `quickshare-send-rejected {transferId}`, `quickshare-send-error {transferId, error}`.
struct QuickShareDevice: Codable, Sendable, Identifiable, Equatable {
    let id: String
    let name: String
    /// "phone" | "tablet" | "laptop" | "unknown"
    let type: String
    let host: String
    let port: Int
}

struct ServerRestartResult: Decodable, Sendable { let port: Int; let iface: String; let ips: [String] }

extension Core {
    func getNotifAppSettings() async throws -> [String: AppNotifSettings] { try await invoke("get_notif_app_settings") }
    /// Replaces one app's options (nil = back to defaults). Returns the new config.
    @discardableResult
    func setNotifAppSettings(pkg: String, settings: AppNotifSettings?) async throws -> DroidConfig {
        var args: [String: Any] = ["pkg": pkg]
        if let s = settings {
            args["settings"] = ["sound": s.sound, "preview": s.preview, "priority": s.priority, "syncDismissals": s.syncDismissals]
        }
        return try await invoke("set_notif_app_settings", args)
    }
    /// Saves port / advertised adapter ("" = Auto) and restarts the listener, discovery and the mDNS advert.
    func serverRestart(port: Int?, iface: String?) async throws -> ServerRestartResult {
        var args: [String: Any] = [:]
        if let port { args["port"] = port }
        if let iface { args["iface"] = iface }
        return try await invoke("server_restart", args)
    }
    /// `tiers`: package -> "alert" | "silent" | "muted". Keeps `mutedApps` in step.
    func setNotifTiers(_ tiers: [String: String]) async throws -> DroidConfig { try await setSetting("notifTiers", tiers) }
    /// Saves a screenshot to Downloads (Wi-Fi if the phone has the `screenshot` cap, else ADB) and returns the path.
    func phoneScreenshot() async throws -> String { try await invoke("phone_screenshot") }
    func quickshareDiscoverStart() async throws { try await invoke("quickshare_discover_start") }
    func quickshareDiscoverStop() async throws { try await invoke("quickshare_discover_stop") }
    func quickshareDevices() async throws -> [QuickShareDevice] { try await invoke("quickshare_devices") }
    /// Returns the transfer id used by the `quickshare-send-*` events.
    func quickshareSend(deviceId: String, paths: [String]) async throws -> Int { try await invoke("quickshare_send", ["deviceId": deviceId, "paths": paths]) }
    func quickshareSendCancel(transferId: Int) async throws { try await invoke("quickshare_send_cancel", ["transferId": transferId]) }
}

// MARK: Swift optimisation pass
extension Core {
    /// `app_set_active {active}`: true while a window, the menu-bar panel or the widget is visible; false when the app
    /// is menu-bar-only/occluded (the core slows link-quality probes and Mac-media polling). Errors are ignored by callers.
    func setAppActive(_ active: Bool) async throws { try await invoke("app_set_active", ["active": active]) }
}

// MARK: Storage
struct CacheInfo: Decodable, Sendable {
    var editCacheBytes: Int64 = 0
    var editCacheFiles: Int = 0
    var pendingSyncs: Int = 0
    var toolsBytes: Int64 = 0
    var logsBytes: Int64 = 0
}
struct CacheClearResult: Decodable, Sendable {
    var freedBytes: Int64 = 0
    var keptPending: Int = 0
}
extension Core {
    func cacheInfo() async throws -> CacheInfo { try await invoke("cache_info") }
    /// `kinds`: "editCache" and/or "tools". Pending (unsynced) edits are never deleted. "tools" throws while ADB is in use.
    func cacheClear(kinds: [String]) async throws -> CacheClearResult { try await invoke("cache_clear", ["kinds": kinds]) }
}

// MARK: - BLE fallback (docs/BLE-NEARBY-DESIGN.md §8)
extension Core {
    func bleStatus() async throws -> BleStatus { try await invoke("ble_status") }
    /// Force the transport: "wifi", "ble" or "auto" (hand control back). Mirrored to the phone as `link-prefer`.
    func linkPrefer(_ transport: String) async throws { try await invoke("link_prefer", ["transport": transport]) }
}
