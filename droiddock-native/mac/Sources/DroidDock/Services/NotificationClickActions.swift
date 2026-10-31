import AppKit
import Observation
import SwiftUI

/// What clicking a phone app's banner does on the Mac. Stored per Android package in
/// UserDefaults `droiddock.notifClickActions` as JSON `{package: {kind: "app"|"url", value}}`.
struct ClickAction: Codable, Equatable, Sendable {
    var kind: String      // "app" (value = bundle id) | "url"
    var value: String
}

@Observable @MainActor
final class NotificationClickActions {
    static let shared = NotificationClickActions()
    static let defaultsKey = "droiddock.notifClickActions"

    private(set) var map: [String: ClickAction] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
           let m = try? JSONDecoder().decode([String: ClickAction].self, from: data) { map = m }
    }

    func set(_ action: ClickAction?, for pkg: String) {
        map[pkg] = action
        if let data = try? JSONEncoder().encode(map) { UserDefaults.standard.set(data, forKey: Self.defaultsKey) }
    }

    /// Phone package for a banner (the banner id is the phone notification key).
    func package(forNotificationKey key: String) -> String? {
        AppModel.shared.notifications.first { $0.key == key }?.pkg
    }

    /// Runs the mapped action. Returns false when nothing is mapped for this package.
    @discardableResult
    func perform(pkg: String?) -> Bool {
        guard let pkg, let a = map[pkg], !a.value.isEmpty else { return false }
        switch a.kind {
        case "app":
            guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: a.value) else {
                AppModel.shared.toast(.bad, tr("That Mac app is no longer installed")); return false
            }
            NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
        case "url":
            guard let url = URL(string: a.value) else { return false }
            NSWorkspace.shared.open(url)
        default: return false
        }
        return true
    }

    // MARK: Per-app "When clicked" choice

    static let modeKey = "droiddock.notifClickMode"
    static let windowTierKey = "droiddock.notifWindowTier"

    /// ADB serial when an app can get its own virtual-display window (ADB device + a scrcpy with virtual displays).
    var windowSerial: String? {
        let m = AppModel.shared
        guard let t = m.tools, t.scrcpy, t.caps.virtualDisplay else { return nil }
        return m.devices.first { $0.state == "device" }?.serial
    }

    /// "window" (default when ADB is available) | "phone" (default without) | "droiddock" | "none".
    func mode(for pkg: String?) -> String {
        let stored = pkg.flatMap { (UserDefaults.standard.dictionary(forKey: Self.modeKey) as? [String: String])?[$0] }
        if let stored, stored != "window" || windowSerial != nil { return stored }
        return windowSerial != nil ? "window" : "phone"
    }
    func setMode(_ mode: String, for pkg: String) {
        var m = (UserDefaults.standard.dictionary(forKey: Self.modeKey) as? [String: String]) ?? [:]
        m[pkg] = mode
        UserDefaults.standard.set(m, forKey: Self.modeKey)
    }

    /// Default-click handler used by the banners. Order: a Mac app/URL the user mapped, then the per-app choice
    /// (open on the phone in a pop-up by default, DroidDock's Notifications page, or nothing).
    func handleBannerClick(key: String) {
        if key == AppModel.allowPlainBannerID { AppModel.shared.blockedPlainBannerClicked(); return }   // the "Allow without encryption?" banner
        NotificationService.shared.remove(id: key)   // handled: drop its Notification Center copy, as macOS does
        guard NotifPrefs.openOnClick else { return }
        let pkg = package(forNotificationKey: key)
        if perform(pkg: pkg) { return }
        switch mode(for: pkg) {
        case "none": return
        case "droiddock": openNotificationsScreen()
        case "window" where canOpenOnPhone(key: key): openInOwnWindow(key: key)
        default:
            if canOpenOnPhone(key: key) { openOnPhone(key: key) } else { openNotificationsScreen() }
        }
    }

    private func openNotificationsScreen() {
        AppModel.shared.selection = .notifications
        WindowControl.showMain()
    }

    /// Whether an "Open" button makes sense: the phone is linked and still has this notification in our list.
    func canOpenOnPhone(key: String) -> Bool {
        let m = AppModel.shared
        return m.wifi.connected && m.notifications.contains { $0.key == key }
    }

    /// "Open" buttons (banner, menu bar, Notifications list, Notification Center): the app in its own Mac window on a
    /// virtual display whenever that is possible, so the phone's screen stays free. Only an app set to "Open on
    /// phone", or a Mac without ADB + a capable scrcpy, gets the whole-phone mirror.
    func open(key: String) {
        if windowSerial != nil, mode(for: package(forNotificationKey: key)) != "phone" { openInOwnWindow(key: key) }
        else { openOnPhone(key: key) }
    }

    /// Opens the notification's own screen on the phone, then shows the phone in the framed mirror window.
    func openOnPhone(key: String) {
        Task { @MainActor in await runOpenOnPhone(key: key) }
    }

    /// Opens the notification's app in its own Mac window on a virtual display, so the phone's screen stays free.
    /// Tier 1: the phone fires the tap action straight onto the display. Tier 2: it opened on the phone, so its task is
    /// moved. Tier 3: neither worked, so the app's launcher is started there and the user taps the chat. The working
    /// tier is remembered per phone.
    func openInOwnWindow(key: String) {
        Task { @MainActor in await runOpenInOwnWindow(key: key) }
    }

    private func runOpenInOwnWindow(key: String) async {
        let model = AppModel.shared
        guard let pkg = package(forNotificationKey: key), let serial = windowSerial else { await runOpenOnPhone(key: key); return }
        let name = model.notifications.first { $0.key == key }?.app ?? pkg
        let tierKey = "\(Self.windowTierKey).\(serial)"
        let cached = UserDefaults.standard.string(forKey: tierKey)
        do {
            let win = try await Core.shared.adbAppWindowOpen(serial: serial, package: pkg, title: name)
            var fired = false
            if model.wifi.supports("notifopen") {
                // A phone known to ignore the launch display gets a plain open, then the task is moved.
                let target = model.wifi.supports("notifopendisplay") && cached != "move" ? win.displayId : nil
                if let r = try? await Core.shared.notifOpen(key: key, displayId: target), r.ok {
                    fired = true
                    if r.locked == true { model.toast(.info, tr("Unlock your phone to see it")) }
                }
            }
            let placed = try await Core.shared.adbAppWindowPlace(serial: serial, package: pkg, displayId: win.displayId, fired: fired)
            if placed.tier != "launch" { UserDefaults.standard.set(placed.tier, forKey: tierKey) }
            model.toast(placed.tier == "launch" ? .info : .ok,
                        placed.tier == "launch" ? tr("Opened {app}: tap the chat", ["app": name])
                                                : tr("Opened {app} in its own window", ["app": name]))
        } catch {
            dlog("notif-open: own window failed, mirroring the phone instead: \(error)")
            await runOpenOnPhone(key: key)
        }
    }

    private func runOpenOnPhone(key: String) async {
        let model = AppModel.shared
        guard model.wifi.connected else { model.toast(.bad, tr("Your phone isn't connected")); return }
        let pkg = package(forNotificationKey: key)
        model.toast(.info, tr("Opening on your phone…"))
        if model.wifi.supports("notifopen") {
            do {
                let r = try await Core.shared.notifOpen(key: key)
                if !r.ok { model.toast(.bad, tr("Couldn't open — the notification is gone")); return }
                if r.locked == true { model.toast(.info, tr("Unlock your phone to see it")) }
            } catch {
                model.toast(.bad, tr("Couldn't open — the notification is gone")); return
            }
        } else if let pkg {
            // Older phone app: no tap action to fire, so launch the app itself.
            try? await Core.shared.appLaunch(pkg: pkg)
        }
        await showPhone()
    }

    /// The framed pop-up mirror. A live mirror is just raised. Otherwise USB/ADB embedded scrcpy (no approval prompt
    /// on the phone) when an ADB device and a capable scrcpy exist, else the Wi-Fi screen mirror (the phone may ask
    /// to approve capture). A whole-screen mirror is used rather than a per-app virtual display on purpose: the
    /// notification's deep link opens on the phone's main display, so a virtual display would not show the chat.
    private func showPhone() async {
        let model = AppModel.shared
        let session = MirrorSession.shared
        if session.isActive { session.bringToFront(); return }
        let tools = model.tools ?? ToolsStatus()
        if let serial = model.devices.first(where: { $0.state == "device" })?.serial, tools.scrcpy, tools.caps.virtualDisplay {
            session.markRequested(source: "screen")
            do { try await Core.shared.scrcpyEmbeddedStart(serial: serial); return }
            catch { dlog("notif-open: embedded scrcpy failed, trying Wi-Fi mirror: \(error)") }
        }
        if model.wifiFeaturesAvailable { await session.start(source: "screen") }
    }
}

/// The phone's answer to `notif-open`.
struct NotifOpenResult: Codable, Sendable, Equatable {
    var ok: Bool
    var locked: Bool?
}

// MARK: - Editor

struct NotificationClickActionsEditor: View {
    private let store = NotificationClickActions.shared
    @Environment(AppModel.self) private var model
    @State private var pkg = ""
    @State private var kind = "app"
    @State private var urlText = ""

    /// Distinct phone apps seen in the notification history, label by package.
    private var phoneApps: [(pkg: String, label: String)] {
        var seen: [String: String] = [:]
        for n in model.notifications { if let p = n.pkg, seen[p] == nil { seen[p] = n.app ?? p } }
        for p in store.map.keys where seen[p] == nil { seen[p] = p }
        return seen.map { ($0.key, $0.value) }.sorted { $0.1.localizedCaseInsensitiveCompare($1.1) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(store.map.keys.sorted(), id: \.self) { p in
                if let a = store.map[p] {
                    SettingsRow(label(for: p), description: describe(a), systemImage: a.kind == "app" ? "app" : "link") {
                        IconButton(systemImage: "trash", label: tr("Remove")) { store.set(nil, for: p) }
                    }
                }
            }
            SettingsRow(tr("Add a rule"), description: phoneApps.isEmpty
                        ? tr("Phone apps appear here after they send a notification.") : nil) {
                HStack(spacing: DD.Space.sm) {
                    Picker(tr("Phone app"), selection: $pkg) {
                        Text(tr("Choose app")).tag("")
                        ForEach(phoneApps, id: \.pkg) { Text($0.label).tag($0.pkg) }
                    }.labelsHidden().frame(width: 150)
                    Picker(tr("Action"), selection: $kind) {
                        Text(tr("Mac app")).tag("app")
                        Text(tr("URL")).tag("url")
                    }.labelsHidden().pickerStyle(.segmented).frame(width: 130)
                }
            }
            SettingsRow("") {
                HStack(spacing: DD.Space.sm) {
                    if kind == "url" {
                        TextField("https://…", text: $urlText).textFieldStyle(.roundedBorder).frame(width: 220)
                        Button(tr("Add")) { add(.init(kind: "url", value: urlText.trimmingCharacters(in: .whitespaces))) }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(pkg.isEmpty || URL(string: urlText).flatMap(\.scheme) == nil)
                    } else {
                        Button(tr("Choose Mac app…")) { chooseApp() }
                            .buttonStyle(SecondaryButtonStyle()).disabled(pkg.isEmpty)
                    }
                }
            }
        }
    }

    private func label(for p: String) -> String { phoneApps.first { $0.pkg == p }?.label ?? p }

    private func describe(_ a: ClickAction) -> String {
        if a.kind == "app" {
            let name = NSWorkspace.shared.urlForApplication(withBundleIdentifier: a.value)
                .map { FileManager.default.displayName(atPath: $0.path) } ?? a.value
            return tr("Opens {name}", ["name": name])
        }
        return tr("Opens {name}", ["name": a.value])
    }

    private func add(_ a: ClickAction) {
        guard !pkg.isEmpty else { return }
        store.set(a, for: pkg)
        urlText = ""
    }

    private func chooseApp() {
        let panel = NSOpenPanel()
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.prompt = tr("Choose")
        guard panel.runModal() == .OK, let url = panel.url, let id = Bundle(url: url)?.bundleIdentifier else { return }
        add(.init(kind: "app", value: id))
    }
}
