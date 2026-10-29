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
    static let replyAction = "dd.reply.action"
    static let openAction = "dd.open.action"

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
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.replyCategory, actions: [open, reply], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.plainCategory, actions: [open], intentIdentifiers: [], options: []),
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
        Task {
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
        if let r = response as? UNTextInputNotificationResponse, r.actionIdentifier == Self.replyAction,
           let key = r.notification.request.content.userInfo["replyKey"] as? String {
            do { try await Core.shared.notifReply(key: key, text: r.userText) }
            catch { dlog("notif_reply failed: \(error)") }
        } else if response.actionIdentifier == Self.openAction {
            let key = response.notification.request.identifier
            await MainActor.run {
                let c = NotificationClickActions.shared
                if c.canOpenOnPhone(key: key) { c.open(key: key) } else { c.handleBannerClick(key: key) }
            }
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            let key = response.notification.request.identifier
            await MainActor.run { NotificationClickActions.shared.handleBannerClick(key: key) }
        }
    }
}
