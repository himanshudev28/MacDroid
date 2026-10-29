import Foundation
import Observation
import SwiftUI

/// Single source of truth for UI state. Consumes `Core.shared.events` in ONE task (order preserved).
@Observable @MainActor
final class AppModel {
    static let shared = AppModel()

    // Core lifecycle
    private(set) var coreReady = false
    private(set) var coreError: String?
    private(set) var appVersion = "dev"

    // Config / pairing
    private(set) var config: DroidConfig?
    private(set) var pairing: PairingInfo?

    // Link
    private(set) var wifi = WifiStatus()
    private(set) var deviceInfo: AppDeviceInfo?
    private(set) var linkQuality: LinkQuality?
    private(set) var bleStatus = BleStatus()
    /// Which link carries traffic: `.wifi` or `.ble` (the pill label).
    var pillTransport: DDTransport { wifi.transport == "ble" ? .ble : .wifi }
    var onBluetooth: Bool { wifi.connected && wifi.transport == "ble" }
    /// Files, photos, mirror, camera, Mac Files, wallpaper... need the Wi-Fi socket (BLE fallback §2).
    var wifiFeaturesAvailable: Bool { wifi.connected && (wifi.transport != "ble" || bleStatus.wifiAvailable) }
    private(set) var lastWifiEvent: WifiEvent?
    var isConnected: Bool { wifi.connected }
    /// While set (and not connected) the phone card says "Reconnecting…". Armed at launch and whenever an
    /// established link drops; the phone is the one dialling in, so this is a bounded grace period, not a promise.
    private var reconnectGraceUntil: Date?
    private var graceTask: Task<Void, Never>?
    private var graceTick = 0
    var isReconnecting: Bool {
        _ = graceTick
        guard !wifi.connected, let c = config, c.autoReconnect, !isPaused,
              c.deviceName?.isEmpty == false || c.deviceGuid != nil,
              let until = reconnectGraceUntil else { return false }
        return until > Date()
    }
    #if DEBUG
    private static let traceLink = ProcessInfo.processInfo.environment["DD_TRACE_LINK"] != nil
    #endif
    private func armReconnectGrace(seconds: Double = 45) {
        reconnectGraceUntil = Date().addingTimeInterval(seconds)
        graceTask?.cancel()
        graceTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds), tolerance: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.graceTick += 1
        }
    }
    var phoneName: String? { wifi.connected ? wifi.phoneName : nil }  // config.deviceName is THIS Mac's name, never the phone's
    var batteryPercent: Int? { deviceInfo?.battery.map { Int($0.rounded()) } }
    var isCharging: Bool { deviceInfo?.charging ?? false }
    var isPaused: Bool {
        guard let until = config?.pausedUntil, until > 0 else { return false }
        return until > Date().timeIntervalSince1970 * 1000
    }

    // Phone data
    private(set) var notifications: [PhoneNotification] = []
    private(set) var activeCall: IncomingCall?
    private(set) var media: MediaState?
    private(set) var mediaArt: String?
    private(set) var transfers: [TransferEntry] = []
    private(set) var transferProgress: [Int: TransferProgress] = [:]
    private(set) var editSync: [String: EditSync] = [:]
    private(set) var photoSync: PhotoSyncProgress?
    /// Bumped on `sms-changed`; screens `.task(id:)` on it to reload.
    private(set) var smsRevision = 0
    /// Stored (not recomputed per read); kept in step by `setTransfers`.
    private(set) var activeTransferCount = 0
    /// Stored so `MenuBarLabel` doesn't rebuild on every notification, only when the badge flips.
    private(set) var hasNotifications = false

    // ADB
    private(set) var devices: [AdbDevice] = []
    private(set) var tools: ToolsStatus?
    private(set) var qrPair: QrPairStatus?
    private(set) var callState: CallState?

    // Mirror / audio (WP4 consumes these)
    private(set) var mirror: MirrorStarted?
    private(set) var mirrorError: String?
    private(set) var audio: AudioStarted?
    private(set) var audioError: String?
    /// "open" | "close" requests from the core; WP4's MirrorWindowController observes this via `onMirrorWindow`.
    var onMirrorWindow: (@MainActor (String) -> Void)?
    var onMirrorStarted: (@MainActor (MirrorStarted) -> Void)?
    var onMirrorStopped: (@MainActor () -> Void)?
    var onMirrorError: (@MainActor (String?) -> Void)?
    func clearAudioError() { if audioError != nil { audioError = nil } }
    /// The phone cannot act on taps / keys (accessibility service off) and no adb route exists.
    var onMirrorControlUnavailable: (@MainActor () -> Void)?
    var onAudioStarted: (@MainActor (AudioStarted) -> Void)?
    var onAudioStopped: (@MainActor () -> Void)?

    // Quick Share
    var quickShare: QuickShareRequest?
    private(set) var quickShareProgress: QuickShareProgress?
    private(set) var quickShareReceived: [String] = []
    /// Quick Share *send* (Mac → nearby device). Devices appear while discovery runs.
    private(set) var quickShareDevices: [QuickShareDevice] = []
    /// Outgoing sends keyed by transfer id.
    private(set) var quickShareSends: [Int: QuickShareSend] = [:]
    /// Drops outgoing Quick Share sends that are no longer running (Transfers › Clear History).
    func clearFinishedQuickShareSends() {
        let live = quickShareSends.filter { $0.value.isLive }
        if live.count != quickShareSends.count { quickShareSends = live }
    }
    /// Last phone screenshot saved (`phone-screenshot`).
    private(set) var lastScreenshot: PhoneScreenshot?

    // Updates
    private(set) var updateInfo: UpdateInfo?
    private(set) var updateProgress: UpdateProgress?

    // Shell
    private(set) var menubarTitle: String?
    private(set) var toasts: [Toast] = []
    /// DEBUG builds only: `DD_START_DEST=<destination>` overrides the first screen (QA).
    #if DEBUG
    var selection: Destination = Destination(rawValue: ProcessInfo.processInfo.environment["DD_START_DEST"] ?? "") ?? .dashboard
    #else
    var selection: Destination = .dashboard
    #endif
    private(set) var lastAutomation: AutomationEvent?
    /// Hook for the screen that handles `droiddock://` routes (mirror, desktop, app, clipboard-push).
    var onAutomation: (@MainActor (AutomationEvent) -> Void)?

    private let decoder = JSONDecoder()
    private var eventTask: Task<Void, Never>?
    private var pendingURLs: [String] = []

    private init() {}

    // MARK: Start

    func start() {
        guard eventTask == nil else { return }
        // Consumer first: AsyncStream buffers anything emitted during start.
        eventTask = Task { [weak self] in
            for await ev in Core.shared.events {
                guard let self else { return }
                self.handle(ev)
            }
        }
        do {
            BleService.shared.install()   // sinks only: creates no CBCentralManager, so no Bluetooth prompt
            try Core.shared.start()
            coreReady = true
        } catch {
            coreError = error.localizedDescription
            dlog("core start failed: \(error)")
            return
        }
        Task { await bootstrap() }
    }

    private func bootstrap() async {
        let core = Core.shared
        if let pong = try? await core.corePing() { dlog("core_ping -> \(pong)") }
        if let c = try? await core.getConfig() { set(\.config, c) }
        if let p = try? await core.getPairingInfo() { pairing = p }
        if let w = try? await core.wifiStatus() { set(\.wifi, w) }
        if !wifi.connected { armReconnectGrace(seconds: 30) }
        if let v = try? await core.appVersion() { appVersion = v }
        if let t = try? await core.adbTools() { set(\.tools, t) }
        if let d = try? await core.adbDevices() { set(\.devices, d) }
        if let tr = try? await core.transfersList() { setTransfers(tr) }
        if let m = try? await core.mediaState() { applyMedia(m) }
        for url in pendingURLs { try? await core.automationOpenUrl(url) }
        pendingURLs.removeAll()
        #if DEBUG
        if let c = config { dlog("config loaded: port \(c.port)") }
        // QA: `DD_FAKE_CALL=ringing|active` shows the call banner a few seconds after launch (the fake phone has no `--ring`).
        if let st = ProcessInfo.processInfo.environment["DD_FAKE_CALL"], !st.isEmpty {
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(4))
                self?.set(\.activeCall, IncomingCall(state: st, number: "+14155550134", name: "Ada Lovelace", key: "dbg",
                                                      canAnswer: st == "ringing", canEnd: true, canAudio: true, muted: false, speaker: false))
            }
        }
        #endif
    }

    #if DEBUG
    /// QA only (probe step `fake:on`): pretend a phone is connected so connected visuals can be screenshotted.
    private(set) var debugFake = false
    func debugFakeNotifications() {
        let apps = [("com.whatsapp", "WhatsApp"), ("com.google.android.gm", "Gmail"), ("org.telegram.messenger", "Telegram"),
                    ("com.spotify.music", "Spotify"), ("com.instagram.android", "Instagram"), ("net.one97.paytm", "Paytm")]
        for (i, a) in apps.enumerated() {
            putNotification(PhoneNotification(key: "fake-\(i)", app: a.1, title: "Ada", text: "Hello \(i)", replyable: false,
                                              time: Date().timeIntervalSince1970 * 1000, type: nil, pkg: a.0))
        }
    }

    /// QA (probe step `callstate:<ringing|active|idle>`): a fake phone call.
    func debugSetCall(_ st: String) {
        if st == "idle" { set(\.activeCall, nil); return }
        set(\.activeCall, IncomingCall(state: st, number: "+14155550134", name: "Ada Lovelace", key: "dbg",
                                       canAnswer: st == "ringing", canEnd: true, canAudio: true, muted: false, speaker: false))
    }

    func debugFakeConnected(rtt: Double? = nil) {
        debugFake = true
        set(\.wifi, WifiStatus(connected: true, phoneName: "Pixel 8 Pro", caps: []))
        set(\.deviceInfo, AppDeviceInfo(model: "Pixel 8 Pro", android: "15", battery: 82, charging: false))
        set(\.linkQuality, LinkQuality(rttMs: rtt, grade: "good"))
    }

    /// QA only (probe step `fake:content`): notifications with package names, plus a playing track with artwork.
    /// `bad` reproduces the old "-0:00" track (position beyond the reported duration).
    func debugFakeContent(badDuration: Bool = false) {
        let now = Date().timeIntervalSince1970 * 1000
        func n(_ i: Int, _ app: String, _ pkg: String, _ title: String, _ text: String, reply: Bool, ago: Double) -> PhoneNotification {
            PhoneNotification(key: "dbg-\(i)", app: app, title: title, text: text, replyable: reply, time: now - ago * 1000, pkg: pkg)
        }
        setNotifications([
            n(1, "Messages", "com.fake.messages", "Ada Lovelace", "Are we still on for lunch tomorrow? I found a great place near the office.", reply: true, ago: 120),
            n(2, "Paytm", "com.fake.paytm", "Payment received", "You received 10 rupees from Charles", reply: false, ago: 1500),
            n(3, "Gmail", "com.fake.gmail", "Invoice #4821", "Your monthly statement is ready to view", reply: false, ago: 7200),
            n(4, "Photos", "com.fake.photos", "New memory", "A trip from one year ago", reply: false, ago: 90000),
        ])
        var m = MediaState()
        m.active = true; m.title = "Midnight City"; m.artist = "M83"; m.app = "Spotify"; m.playing = true
        m.pos = badDuration ? 146_000 : 61_000; m.dur = badDuration ? 0 : 243_000; m.trackKey = "dbg-track"
        if !badDuration {
            let img = NSImage(size: NSSize(width: 300, height: 300), flipped: false) { r in
                NSGradient(colors: [.systemIndigo, .systemPink, .systemOrange])?.draw(in: r, angle: 45)
                return true
            }
            if let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
               let jpg = rep.representation(using: .jpeg, properties: [:]) {
                m.art = jpg.base64EncodedString(); m.artKeyPresent = true
            }
        }
        applyMedia(m)
    }
    #endif

    /// `droiddock://` URL from the OS (queued until the core is up).
    func openURL(_ url: String) {
        guard coreReady else { pendingURLs.append(url); return }
        Task { try? await Core.shared.automationOpenUrl(url) }
    }

    func applyConfig(_ c: DroidConfig) { set(\.config, c) }

    func refreshPairing() async {
        if let p = try? await Core.shared.getPairingInfo() { pairing = p }
    }

    // MARK: Settings (optimistic-free: the core echoes `config`)

    private let settingsLane = SerialLane()
    func setSetting(_ key: String, _ value: Any) {
        // Serial: two quick toggles must apply (and echo their config) in the order they were made.
        nonisolated(unsafe) let v = value
        settingsLane.submit { [weak self] in
            guard let self else { return }
            do {
                let c = try await Core.shared.setSetting(key, v)
                await MainActor.run { self.config = c }
            } catch {
                await MainActor.run { self.toast(.bad, error.localizedDescription) }
            }
        }
    }

    // MARK: Toasts

    func toast(_ kind: Toast.Kind, _ text: String) {
        let t = Toast(kind: kind, text: text)
        toasts.append(t)
        if toasts.count > 3 { toasts.removeFirst() }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(kind == .bad ? 6 : 4), tolerance: .seconds(1))
            self?.toasts.removeAll { $0.id == t.id }
        }
    }
    func dismissToast(_ id: UUID) { toasts.removeAll { $0.id == id } }

    /// Dismissing here also dismisses on the phone, unless "Sync notification dismissals" (global or for this app) is off.
    func dismissNotification(_ key: String) {
        let pkg = notifications.first { $0.key == key }?.pkg
        setNotifications(notifications.filter { $0.key != key })
        guard syncsDismissals(pkg: pkg) else { return }
        Task { try? await Core.shared.notifDismiss(key: key) }
    }

    func syncsDismissals(pkg: String?) -> Bool {
        guard let c = config else { return true }
        return c.syncNotifDismissals && (pkg.map { c.appNotif($0).syncDismissals } ?? true)
    }

    /// Writes one app's notification options through the core (which echoes the new config).
    func setAppNotif(_ pkg: String, _ s: AppNotifSettings) {
        Task { [weak self] in
            do { let c = try await Core.shared.setNotifAppSettings(pkg: pkg, settings: s); self?.set(\.config, c) }
            catch { self?.toast(.bad, error.localizedDescription) }
        }
    }

    func clearUpdateInfo() { updateInfo = nil }

    // MARK: Events

    private func decode<T: Decodable>(_ type: T.Type, _ ev: CoreEvent) -> T? {
        do { return try decoder.decode(T.self, from: ev.payload) }
        catch { dlog("event \(ev.name): decode \(T.self) failed: \(error)"); return nil }
    }

    /// `@Observable` setters fire on every write, equal or not: periodic events that rarely change go through here.
    private func set<T: Equatable>(_ kp: ReferenceWritableKeyPath<AppModel, T>, _ v: T) {
        if self[keyPath: kp] != v { self[keyPath: kp] = v }
    }

    private func setTransfers(_ t: [TransferEntry]) {
        set(\.transfers, t)
        set(\.activeTransferCount, t.reduce(0) { $0 + ($1.isActive ? 1 : 0) })
    }

    private func setNotifications(_ n: [PhoneNotification]) {
        notifications = n
        set(\.hasNotifications, !n.isEmpty)
    }

    private func dropCallCards() {
        if notifications.contains(where: { $0.type == "call" }) { setNotifications(notifications.filter { $0.type != "call" }) }
    }

    private func putNotification(_ n: PhoneNotification) {
        if let p = n.pkg, n.type != "call" { KnownNotifApps.record(pkg: p, name: n.app) }
        var list = notifications
        list.removeAll { $0.key == n.key }
        list.insert(n, at: 0)
        if list.count > 100 { list.removeLast(list.count - 100) }
        setNotifications(list)
    }

    private func handle(_ ev: CoreEvent) {
        #if DEBUG
        if Self.traceLink, ev.name == "wifi-status" || ev.name == "link-quality" {
            FileHandle.standardError.write(Data("[trace \(Date().timeIntervalSince1970)] \(ev.name)\n".utf8))
        }
        #endif
        switch ev.name {
        case "config":
            if let c = decode(DroidConfig.self, ev) { set(\.config, c) }
        case "wifi-status":
            guard let s = decode(WifiStatus.self, ev) else { return }
            let wasConnected = wifi.connected
            set(\.wifi, s)
            if !s.connected {
                if wasConnected { armReconnectGrace() }
                set(\.media, nil); set(\.mediaArt, nil); set(\.activeCall, nil); set(\.deviceInfo, nil); set(\.linkQuality, nil)
            } else if !wasConnected {
                dlog("phone connected: \(s.phoneName ?? "?")")
            }
        case "ble-status":
            if let b = decode(BleStatus.self, ev) { set(\.bleStatus, b) }
        case "wifi-event":
            set(\.lastWifiEvent, decode(WifiEvent.self, ev))
        case "device-info":
            if let i = decode(AppDeviceInfo.self, ev) { set(\.deviceInfo, i) }
        case "link-quality":
            if var q = decode(LinkQuality.self, ev) {
                q.rttMs = q.rttMs.map { ($0 / 5).rounded() * 5 }   // 5 ms buckets: jitter must not publish
                set(\.linkQuality, q)
            } else { set(\.linkQuality, nil) }
        case "notification":
            guard let n = decode(PhoneNotification.self, ev) else { return }
            putNotification(n)
        case "notification-removed":
            struct K: Decodable { let key: String }
            if let k = decode(K.self, ev) {
                // Phone-side dismissal: keep the Mac's copy when dismissal sync is off for that app.
                if !syncsDismissals(pkg: notifications.first { $0.key == k.key }?.pkg) { return }
                setNotifications(notifications.filter { $0.key != k.key })
                NotificationService.shared.remove(id: k.key)
            }
        case "call":
            if let c = decode(IncomingCall.self, ev) { applyCall(c) }
        case "call-state":
            guard let s = decode(CallState.self, ev) else { return }
            set(\.callState, s)
            if s.state == "IDLE" {
                set(\.activeCall, nil)
                NotificationService.shared.remove(id: "call")   // the "Incoming call" banner must not outlive the call
            }
        case "vd-window":
            if let w = decode(VDWindowInfo.self, ev) { VirtualDisplayBars.shared.add(w) }
        case "vd-window-closed":
            if let c = decode(VDWindowClosed.self, ev) { VirtualDisplayBars.shared.remove(pid: c.pid) }
        case "mac-hud":
            if let h = decode(SystemHUD.Payload.self, ev) { SystemHUD.shared.show(h) }
        case "media":
            if let m = decode(MediaState.self, ev) { applyMedia(m) }
        case "sms-changed":
            smsRevision += 1
        case "transfers":
            if let t = decode([TransferEntry].self, ev) { setTransfers(t) }
        case "transfer-progress":
            guard let p = decode(TransferProgress.self, ev) else { return }
            if p.done { transferProgress[p.transferId] = nil }
            else if transferProgress[p.transferId] != p { transferProgress[p.transferId] = p }
            if p.dir == "phone", p.done {
                if let e = p.error { toast(.bad, "\(p.name): \(e)") }
                else { toast(.ok, tr("{name} saved to Downloads", ["name": p.name])) }
            }
        case "edit-sync":
            guard let e = decode(EditSync.self, ev) else { return }
            editSync[e.phonePath] = e.status == "synced" ? nil : e
            if e.status == "synced" { toast(.ok, tr("Synced back to phone")) }
            else if e.status == "pending", let err = e.error { toast(.bad, tr("Will retry sync — {error}", ["error": err])) }
        case "photosync-progress":
            set(\.photoSync, decode(PhotoSyncProgress.self, ev))
        case "devices":
            if let d = decode([AdbDevice].self, ev) { set(\.devices, d) }
        case "tools":
            if let t = decode(ToolsStatus.self, ev) { set(\.tools, t) }
        case "adb-qr-status":
            set(\.qrPair, decode(QrPairStatus.self, ev))
        case "quickshare-request":
            guard let r = decode(QuickShareRequest.self, ev) else { return }
            if quickShare != nil {   // already prompting: decline the newcomer
                Task { try? await Core.shared.quickshareRespond(id: r.id, accept: false) }
            } else { quickShare = r; quickShareProgress = nil }
        case "quickshare-progress":
            set(\.quickShareProgress, decode(QuickShareProgress.self, ev))
        case "quickshare-received":
            if let r = decode(QuickShareReceived.self, ev) {
                quickShareReceived = r.paths; quickShare = nil; quickShareProgress = nil
                toast(.ok, tn("Received {n} file", "Received {n} files", r.paths.count))
            }
        case "quickshare-error":
            if let e = decode(ErrorPayload.self, ev) {
                quickShare = nil; quickShareProgress = nil
                toast(.bad, e.error ?? tr("Quick Share failed"))
            }
        case "quickshare-devices":
            if let d = decode([QuickShareDevice].self, ev) { set(\.quickShareDevices, d) }
        case "quickshare-send-pin", "quickshare-send-waiting", "quickshare-send-progress",
             "quickshare-send-done", "quickshare-send-rejected", "quickshare-send-error":
            guard let p = decode(QuickShareSendEvent.self, ev) else { return }
            var s = quickShareSends[p.transferId] ?? QuickShareSend(transferId: p.transferId)
            switch ev.name {
            case "quickshare-send-pin": s.state = .pin; s.peer = p.peer; s.pin = p.pin
            case "quickshare-send-waiting": s.state = .waiting
            case "quickshare-send-progress": s.state = .sending; s.sent = p.sent ?? s.sent; s.total = p.total ?? s.total
            case "quickshare-send-done": s.state = .done; toast(.ok, tr("Sent with Quick Share"))
            case "quickshare-send-rejected": s.state = .rejected; toast(.bad, tr("Declined on the other device"))
            default: s.state = .failed; s.error = p.error; toast(.bad, p.error ?? tr("Quick Share failed"))
            }
            quickShareSends[p.transferId] = s
        case "phone-screenshot":
            if let s = decode(PhoneScreenshot.self, ev) {
                lastScreenshot = s
                toast(.ok, tr("Screenshot saved to Downloads"))
            }
        case "mirror-started":
            if let m = decode(MirrorStarted.self, ev) { mirror = m; mirrorError = nil; onMirrorStarted?(m) }
        case "mirror-stopped":
            mirror = nil; onMirrorStopped?()
        case "mirror-error":
            mirrorError = decode(ErrorPayload.self, ev)?.error
            onMirrorError?(mirrorError)
        case "mirror-control":
            onMirrorControlUnavailable?()
        case "mirror-window":
            if let op = decode(MirrorWindowOp.self, ev) { onMirrorWindow?(op.op) }
        case "audio-started":
            if let a = decode(AudioStarted.self, ev) { audio = a; audioError = nil; onAudioStarted?(a) }
        case "audio-stopped":
            audio = nil; onAudioStopped?()
        case "audio-error":
            audioError = decode(ErrorPayload.self, ev)?.error
            if let e = audioError { toast(.bad, "Phone audio: \(e)") }
        case "update-available":
            updateInfo = decode(UpdateInfo.self, ev)
        case "update-progress":
            updateProgress = decode(UpdateProgress.self, ev)
        case "automation":
            if let a = decode(AutomationEvent.self, ev) {
                lastAutomation = a
                dlog("automation: \(a.action)")
                onAutomation?(a)
            }
        case "menubar-title":
            menubarTitle = decode(MenubarTitle.self, ev)?.title
        case "native-notification":
            if let n = decode(NativeNotification.self, ev) { NotificationService.shared.post(n) }
        default:
            dlog("unhandled event: \(ev.name)")
        }
    }

    /// Mirrors App.tsx: ringing adds a call card, active swaps controls, idle clears.
    private func applyCall(_ c: IncomingCall) {
        switch c.state {
        case "ringing":
            let item = PhoneNotification(
                key: c.key ?? "call-incoming", app: c.app ?? "Phone",
                title: c.callerName ?? c.callerNumber ?? tr("Unknown caller"),
                text: c.app.map { tr("{app} call", ["app": $0]) }
                    ?? ((c.callerNumber != nil && c.callerName != nil) ? c.callerNumber : tr("Incoming call on your phone")),
                replyable: false, time: Date().timeIntervalSince1970 * 1000, type: "call")
            putNotification(item)
            set(\.activeCall, c)
        case "active", "dialing":
            // Answered: the call module/pop-up takes over, so the "Incoming call" card goes.
            dropCallCards()
            set(\.activeCall, c)
        default:
            // Ended. A call that was only ever ringing was missed: keep one card saying so; otherwise drop it.
            if let cur = activeCall, cur.state == "ringing" {
                let key = cur.key ?? "call-incoming"
                if var card = notifications.first(where: { $0.key == key && $0.type == "call" }) {
                    card.text = tr("Missed call")
                    putNotification(card)
                }
            } else {
                dropCallCards()
            }
            if let cur = activeCall, ["ringing", "dialing", "active"].contains(cur.state) { set(\.activeCall, nil) }
        }
    }

    /// `art` rides only on track changes: keep the cached image unless the payload says otherwise.
    private func applyMedia(_ m: MediaState?) {
        guard let m else { media = nil; mediaArt = nil; return }
        if m.artKeyPresent { set(\.mediaArt, m.art) }
        if !m.active { set(\.mediaArt, nil) }
        set(\.media, m)
    }
}
