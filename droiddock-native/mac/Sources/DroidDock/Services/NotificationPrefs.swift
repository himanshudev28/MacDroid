import Foundation
import AppKit

/// Mac-side notification preferences (UserDefaults). Per-app options live in the core config (`notifAppSettings`).
enum NotifPrefs {
    /// System notifications style: "default" (macOS banner, own panel as the fallback) | "droiddock" (always the
    /// floating DroidDock banner) | "off" (nothing pops up; the Notifications list still fills).
    static let bannerStyleKey = "droiddock.notif.bannerStyle"
    /// Where a ringing call is announced: "popup" (floating call panel) | "banner" (notification banner) | "off".
    static let callAlertKey = "droiddock.notif.callAlert"
    /// Play a ringtone on the Mac while the phone rings.
    static let callRingKey = "droiddock.notif.callRing"
    /// Clicking a banner opens DroidDock (or the app mapped to that phone app).
    static let openOnClickKey = "droiddock.notif.openOnClick"
    /// When DroidDock shows its own floating banner, also file a quiet copy in macOS Notification Center.
    static let keepInCenterKey = "droiddock.notif.keepInCenter"

    static var bannerStyle: String { UserDefaults.standard.string(forKey: bannerStyleKey) ?? "default" }
    static var callAlert: String { UserDefaults.standard.string(forKey: callAlertKey) ?? "popup" }
    static var callRing: Bool { UserDefaults.standard.bool(forKey: callRingKey) }
    static var openOnClick: Bool { UserDefaults.standard.object(forKey: openOnClickKey) as? Bool ?? true }
    /// How long a banner stays on screen (seconds). Hovering or typing a reply pauses it.
    static let bannerSecondsKey = "droiddock.notif.bannerSeconds"
    static var bannerSeconds: Int { (UserDefaults.standard.object(forKey: bannerSecondsKey) as? Int).map { min(max($0, 2), 30) } ?? 5 }
    static var keepInCenter: Bool { UserDefaults.standard.object(forKey: keepInCenterKey) as? Bool ?? true }
}

/// Every phone app that has ever sent a notification (package -> label), so the per-app list survives restarts.
enum KnownNotifApps {
    static let key = "droiddock.notif.knownApps"
    static func all() -> [String: String] { UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:] }
    static func record(pkg: String, name: String?) {
        var m = all()
        let label = (name?.isEmpty == false) ? name! : (m[pkg] ?? pkg)
        guard m[pkg] != label else { return }
        m[pkg] = label
        UserDefaults.standard.set(m, forKey: key)
    }
}

/// Plays the Mac-side ringtone while the phone rings (looping system sound, stopped when the call state changes).
@MainActor
final class CallRinger {
    static let shared = CallRinger()
    private var sound: NSSound?
    func start() {
        guard sound == nil, let s = NSSound(named: "Ping") ?? NSSound(named: "Glass") else { return }
        s.loops = true
        s.play()
        sound = s
    }
    func stop() { sound?.stop(); sound = nil }
}
