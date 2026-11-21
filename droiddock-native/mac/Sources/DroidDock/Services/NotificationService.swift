import Foundation
import AppKit
import Security
import UserNotifications

/// Shows the banners the core asks for (`native-notification`) and sends inline replies back.
/// UserNotifications needs a real .app bundle: every entry point guards on the bundle id.
final class NotificationService: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationService()
    static let replyCategory = "dd.reply"
    static let plainCategory = "dd.plain"
    static let otpReplyCategory = "dd.otp.reply"
    static let otpPlainCategory = "dd.otp.plain"
    static let copyAction = "dd.copy.action"
    static let replyAction = "dd.reply.action"
    static let openAction = "dd.open.action"
    static let quickShareCategory = "dd.quickshare"
    static let qsAcceptAction = "dd.qs.accept"
    static let qsDeclineAction = "dd.qs.decline"
    private static let qsPrefix = "dd-quickshare-"
    private let qsLock = NSLock()
    private var qsPending: Int?   // id of the request whose notification may still be answered
    private var qsDeclined: [String: Date] = [:]   // peer name -> last decline
    private var qsSeen: [String: Date] = [:]       // peer name -> last request that was shown

    private var available: Bool { Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app" }

    func configure() {
        #if DEBUG
        // QA: DD_TEST_BANNER=1 posts one banner at launch so the whole code path (auth, category, delegate) can be checked.
        if ProcessInfo.processInfo.environment["DD_TEST_BANNER"] == "1" {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(2))
                self.post(NativeNotification(id: "dd-test-banner", title: "DroidDock test banner",
                                             body: "If you can read this, banners work.", replyKey: nil, sound: false))
            }
        }
        #endif
        guard available else { dlog("notifications disabled: not running from a .app bundle"); return }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let reply = UNTextInputNotificationAction(
            identifier: Self.replyAction, title: tr("Reply"), options: [],
            textInputButtonTitle: tr("Send"), textInputPlaceholder: tr("Reply…"))
        let open = UNNotificationAction(identifier: Self.openAction, title: tr("Open"), options: [])
        let copy = UNNotificationAction(identifier: Self.copyAction, title: tr("Copy code"), options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.otpReplyCategory, actions: [copy, open, reply], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.otpPlainCategory, actions: [copy, open], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.replyCategory, actions: [open, reply], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.plainCategory, actions: [open], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.quickShareCategory, actions: [
                // Requests an unlocked device (macOS enforcement is unverified; the code on screen is the real check).
                UNNotificationAction(identifier: Self.qsAcceptAction, title: tr("Accept"), options: [.authenticationRequired]),
                UNNotificationAction(identifier: Self.qsDeclineAction, title: tr("Decline"), options: [.destructive]),
            ], intentIdentifiers: [], options: []),
        ])
        requestAuthorizationIfNeeded()
    }

    /// Asks macOS for permission whenever it has not been decided yet (first launch, or after a reset), and logs the
    /// effective settings so a "no banners" report can be diagnosed from the log.
    /// `then(banners, center)`: whether macOS will show a banner, and whether it will keep the notification in
    /// Notification Center.
    private func requestAuthorizationIfNeeded(then: (@Sendable (_ banners: Bool, _ center: Bool) -> Void)? = nil) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { s in
            dlog("notification settings: auth=\(s.authorizationStatus.rawValue) alertStyle=\(s.alertSetting.rawValue) "
                 + "style=\(s.alertStyle.rawValue) center=\(s.notificationCenterSetting.rawValue) sound=\(s.soundSetting.rawValue)")
            if s.authorizationStatus == .notDetermined {
                center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, err in
                    dlog("notification authorization: \(granted)\(err.map { " (\($0))" } ?? "")")
                    then?(granted, granted)
                }
            } else {
                let allowed = s.authorizationStatus != .denied
                if !allowed { dlog("notifications are DENIED in System Settings > Notifications > DroidDock") }
                else if s.alertStyle == .none { dlog("notification style is None: banners will not appear") }
                then?(allowed && s.alertStyle != .none, allowed && s.notificationCenterSetting != .disabled)
            }
        }
    }

    /// Ad-hoc signatures change with every build, and macOS keys notification permission to the signature: such a
    /// build can lose its banners without any error. They get our own panel instead.
    static let isAdHocSigned: Bool = {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(Bundle.main.bundleURL as CFURL, [], &code) == errSecSuccess, let code else { return false }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let d = info as? [String: Any], let flags = d[kSecCodeInfoFlags as String] as? UInt32 else { return false }
        return flags & SecCodeSignatureFlags.adhoc.rawValue != 0
    }()

    /// Core sends title = app name and body = "sender\ntext". Like Messages: sender as the title, app as the subtitle.
    static func split(_ n: NativeNotification) -> (title: String, subtitle: String?, body: String) {
        let parts = n.body.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        if parts.count == 2, !parts[0].isEmpty { return (parts[0], n.title, parts[1]) }
        return (n.title, nil, n.body)
    }

    /// The phone app's icon as a PNG file (cached per package). Waits at most ~300 ms for the package and the fetch,
    /// so a slow phone never delays the banner.
    func iconFile(forKey key: String) async -> URL? {
        let deadline = Date().addingTimeInterval(0.3)
        var pkg: String?
        while Date() < deadline {
            pkg = await MainActor.run { AppModel.shared.notifications.first { $0.key == key }?.pkg }
            if pkg != nil { break }
            try? await Task.sleep(for: .milliseconds(40))
        }
        guard let pkg, !pkg.contains("/") else { return nil }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("DroidDock/notif-icons")
        let file = dir.appendingPathComponent("\(pkg).png")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        let left = max(0.05, deadline.timeIntervalSinceNow)
        let fetched: String? = await withTaskGroup(of: String?.self) { g in
            g.addTask { try? await Core.shared.appIcon(pkg: pkg) }
            g.addTask { try? await Task.sleep(for: .seconds(left)); return nil }
            let r = await g.next() ?? nil; g.cancelAll(); return r
        }
        guard let url = fetched, let data = FileFormat.imageData(fromDataURL: url), let img = NSImage(data: data),
              let tiff = img.tiffRepresentation, let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
        else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? png.write(to: file)
        return file
    }

    /// Applies the per-app privacy option: the message text is replaced by a neutral line.
    static func masked(_ n: NativeNotification) -> NativeNotification {
        // Android hid the content itself (15+): say so rather than show a blank or a made-up line.
        if n.redacted == true, n.id != "call" { var m = n; m.body = tr("Content hidden by Android"); return m }
        guard n.hidePreview == true else { return n }
        var m = n
        m.body = tr("New notification")
        return m
    }

    /// Floating DroidDock banner. Only Sendable values cross the actor hop (the icon is loaded from its file there).
    /// With "Keep in Notification Center" on, a quiet copy also goes to macOS so it stays listed after the banner.
    private func showOwnBanner(_ n: NativeNotification, fileInCenter: Bool = NotifPrefs.keepInCenter) {
        Task {
            let url = await iconFile(forKey: n.id)
            await MainActor.run { BannerCenter.shared.show(n, iconURL: url) }
        }
        guard fileInCenter, available, n.id != "call" else { return }
        requestAuthorizationIfNeeded { _, center in
            guard center else { dlog("notification center copy skipped: Notification Center is off for DroidDock"); return }
            self.deliver(n, quiet: true)
        }
    }

    /// Hands the notification to macOS. `quiet` = passive and silent: it goes straight into Notification Center
    /// without a second banner or sound, because DroidDock's own banner is already on screen.
    private func deliver(_ n: NativeNotification, quiet: Bool) {
        let c = UNMutableNotificationContent()
        let parts = Self.split(n)
        c.title = parts.title
        if let sub = parts.subtitle { c.subtitle = sub }
        c.body = parts.body
        if quiet { c.interruptionLevel = .passive } else if n.sound ?? false { c.sound = .default }
        c.categoryIdentifier = n.replyKey != nil ? Self.replyCategory : Self.plainCategory
        if let k = n.replyKey { c.userInfo = ["replyKey": k] }
        if n.id != "call", n.redacted != true, OTPPrefs.showCopyAction, let code = OTPDetector.extract(title: n.title, text: n.body) {
            c.categoryIdentifier = n.replyKey != nil ? Self.otpReplyCategory : Self.otpPlainCategory
            c.userInfo["otp"] = code
        }
        Task {
            // Remembered on the notification itself: the phone's list may no longer have it when "Open" is pressed.
            // The phone's list can lag the post, so poll briefly (same budget as iconFile).
            var src: PhoneNotification?
            let deadline = Date().addingTimeInterval(0.4)
            repeat {
                src = await MainActor.run { AppModel.shared.notifications.first { $0.key == n.id } }
                if src != nil { break }
                try? await Task.sleep(for: .milliseconds(50))
            } while Date() < deadline
            if let p = src?.pkg { c.userInfo["pkg"] = p }
            if let a = src?.app { c.userInfo["app"] = a }
            if let f = await self.iconFile(forKey: n.id) {
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("dd-icon-\(UUID().uuidString).png")
                if (try? FileManager.default.copyItem(at: f, to: tmp)) != nil,
                   let a = try? UNNotificationAttachment(identifier: "icon", url: tmp, options: [UNNotificationAttachmentOptionsTypeHintKey: "public.png"]) {
                    c.attachments = [a]   // macOS moves the file into its own store when the request is added
                } else {
                    try? FileManager.default.removeItem(at: tmp)
                }
            }
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: n.id, content: c, trigger: nil)) {
                if let e = $0 { dlog("banner failed: \(e)") } else { dlog("\(quiet ? "notification center copy" : "banner") posted: \(n.title)") }
            }
        }
    }

    func post(_ original: NativeNotification) {
        let n = Self.masked(original)
        if n.id == "call" {
            // "Call alert": Pop-up = the floating call panel (no banner), Banner = a banner (panel only once answered), Off.
            guard NotifPrefs.callAlert == "banner" else { return }
        } else {
            switch NotifPrefs.bannerStyle {
            case "off": return
            case "droiddock":
                dlog("banner (own panel, chosen in Settings): \(n.title)")
                showOwnBanner(n)
                return
            default: break
            }
        }
        guard available, !Self.isAdHocSigned else {
            dlog("banner (own panel, \(available ? "ad-hoc signed" : "no bundle")): \(n.title)")
            showOwnBanner(n)
            return
        }
        // Authorization is re-checked on every post: if the first-launch prompt was missed or reset, this asks again
        // instead of silently dropping the banner.
        requestAuthorizationIfNeeded { banners, center in
            guard banners else {
                // Denied, or style "None": the system would swallow it. Show our own banner so it is never missed
                // (style "None" still lists it in Notification Center, so the quiet copy is filed there).
                dlog("banner (own panel, system banners off): \(n.title)")
                self.showOwnBanner(n, fileInCenter: false)
                if center, NotifPrefs.keepInCenter, n.id != "call" { self.deliver(n, quiet: true) }
                return
            }
            self.deliver(n, quiet: false)
        }
    }

    func remove(id: String) {
        guard available else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }

    // MARK: Delegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        // A quiet copy sits behind DroidDock's own banner: list it, never show it twice.
        notification.request.content.interruptionLevel == .passive ? [.list] : [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.notification.request.content.categoryIdentifier == Self.quickShareCategory {
            await handleQuickShare(response)
            return
        }
        if let r = response as? UNTextInputNotificationResponse, r.actionIdentifier == Self.replyAction,
           let key = r.notification.request.content.userInfo["replyKey"] as? String {
            do { try await Core.shared.notifReply(key: key, text: r.userText) }
            catch { dlog("notif_reply failed: \(error)") }
        } else if response.actionIdentifier == Self.copyAction {
            if let code = response.notification.request.content.userInfo["otp"] as? String {
                await MainActor.run { SecretPasteboard.copySecret(code) }
            }
        } else if response.actionIdentifier == Self.openAction {
            let key = response.notification.request.identifier
            let info = response.notification.request.content.userInfo
            let pkg = info["pkg"] as? String, app = info["app"] as? String
            await MainActor.run {
                NotificationClickActions.shared.explicitOpen(key: key, pkg: pkg, app: app)   // never silent
            }
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            let key = response.notification.request.identifier
            await MainActor.run { NotificationClickActions.shared.handleBannerClick(key: key) }
        }
    }
}

// MARK: Incoming Quick Share request

/// The request sheet lives on the main window, which is usually closed in a menu-bar app, and the sender is blocked
/// until we answer. So the request also goes out as a notification; if macOS cannot show one, the window opens instead.
extension NotificationService {
    func quickShareRequested(_ r: QuickShareRequest) {
        qsLock.lock()
        qsPending = r.id
        let repeatRequest = qsSeen[r.peer].map { Date().timeIntervalSince($0) < 60 } ?? false
        qsSeen[r.peer] = Date()
        qsLock.unlock()
        let openWindow: @Sendable () -> Void = { Task { @MainActor in WindowControl.showMain() } }
        guard available else { openWindow(); return }
        let total = r.files.reduce(0) { $0 + max(0, $1.size) }
        let c = UNMutableNotificationContent()
        c.title = tn("{peer} wants to send {n} file", "{peer} wants to send {n} files", r.files.count, ["peer": r.peer])
        c.body = tr("Code {pin}. Accept only if it matches the sending device.", ["pin": r.pin])
            + (total > 0 ? " " + ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file) : "")
        c.categoryIdentifier = Self.quickShareCategory
        if !repeatRequest { c.interruptionLevel = .timeSensitive; c.sound = .default }
        c.userInfo = ["qsId": r.id]
        requestAuthorizationIfNeeded { banners, _ in
            self.qsLock.lock(); let stillPending = self.qsPending == r.id; self.qsLock.unlock()
            guard stillPending else { return }   // answered or ended while authorization was being checked
            guard banners else { dlog("quickshare request: system banners off, opening the window"); openWindow(); return }
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: Self.qsPrefix + "\(r.id)", content: c, trigger: nil)) {
                if let e = $0 { dlog("quickshare notification failed: \(e)"); openWindow() }
            }
        }
    }

    /// The request was answered or ended by any path: drop its notification so a stale Accept cannot fire.
    func quickShareResolved(id: Int? = nil) {
        qsLock.lock()
        let pending = qsPending
        if id == nil || id == pending { qsPending = nil }
        qsLock.unlock()
        guard available, let gone = id ?? pending else { return }
        let ident = [Self.qsPrefix + "\(gone)"]
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ident)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ident)
    }

    private func claimQuickShare(id: Int) -> Bool {
        qsLock.lock(); defer { qsLock.unlock() }
        guard qsPending == id else { return false }
        qsPending = nil
        return true
    }

    func noteDeclined(peer: String) { qsLock.lock(); qsDeclined[peer] = Date(); qsLock.unlock() }

    /// The same device asked again within 10 s of being declined: drop it quietly.
    func isRepeatAfterDecline(peer: String) -> Bool {
        qsLock.lock(); defer { qsLock.unlock() }
        return qsDeclined[peer].map { Date().timeIntervalSince($0) < 10 } ?? false
    }

    fileprivate func handleQuickShare(_ response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo["qsId"] as? Int else { return }
        let act = response.actionIdentifier
        if act == UNNotificationDefaultActionIdentifier || act == Self.openAction {
            await MainActor.run { WindowControl.showMain() }   // the sheet answers it
            return
        }
        guard act == Self.qsAcceptAction || act == Self.qsDeclineAction else { return }
        let live = claimQuickShare(id: id)
        guard live else { dlog("quickshare: ignoring answer for finished request \(id)"); return }
        let accept = act == Self.qsAcceptAction
        if !accept { noteDeclined(peer: await MainActor.run { AppModel.shared.quickShare?.peer } ?? "") }
        quickShareResolved(id: id)
        do {
            try await Core.shared.quickshareRespond(id: id, accept: accept)
            await MainActor.run {
                let m = AppModel.shared
                guard m.quickShare?.id == id else { return }
                if accept { m.quickShareAcceptedId = id } else { m.quickShareEnded(id: id) }   // accepted: the sheet shows progress
            }
        } catch {
            await MainActor.run {
                AppModel.shared.quickShareEnded(id: id)
                AppModel.shared.toast(.bad, tr("Quick Share request expired"))
            }
        }
    }
}
