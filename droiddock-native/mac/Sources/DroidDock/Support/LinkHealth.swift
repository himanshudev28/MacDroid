import AppKit
import Foundation

/// `tool_versions` reply: this core's version plus what `adb`/`scrcpy` say when asked.
struct ToolVersions: Codable, Sendable, Equatable {
    var coreVersion: String = ""
    var adbVersion: String?
    var scrcpyVersion: String?
    var phoneAppVersion: String?
}

/// A diagnosed, link-level problem with an optional one-click fix.
struct LinkIssue: Identifiable {
    let id: String
    let severity: String            // error | warn | info
    let title: String
    let detail: String
    var fixLabel: String?
    var fix: (@MainActor () async -> Void)?
}

/// Everything the link-health view and the support report read, gathered once.
struct LinkSnapshot {
    var tools = ToolsStatus()
    var versions = ToolVersions()
    var items: [HealthItem] = []
    /// False until the first tool probe lands, so "adb not found" is never claimed from defaults.
    var loaded = false
}

@MainActor
enum LinkHealth {
    /// `versions` can be passed from a previous snapshot: asking adb for its version spawns a process.
    static func snapshot(items: [HealthItem], versions: ToolVersions? = nil) async -> LinkSnapshot {
        var s = LinkSnapshot(items: items)
        if let t = try? await Core.shared.adbTools() { s.tools = t; s.loaded = true }
        if let versions, !versions.coreVersion.isEmpty { s.versions = versions }
        else if let v = try? await Core.shared.toolVersions() { s.versions = v }
        return s
    }

    // MARK: Words for the link

    static func transportName(_ m: AppModel) -> String {
        guard m.wifi.connected else { return tr("Not connected") }
        switch m.wifi.transport {
        case "ble": return tr("Bluetooth")
        case "usb": return tr("USB cable")
        default: return tr("Wi-Fi")
        }
    }

    static func securityName(_ m: AppModel) -> String {
        switch m.wifi.security {
        case "tls": return tr("Encrypted (TLS)")
        case "aes": return tr("Encrypted (older sealed link)")
        case "ble": return tr("Encrypted (Bluetooth)")
        case "plain": return tr("Not encrypted")
        default: return "–"
        }
    }

    static func gradeName(_ q: LinkQuality?) -> String {
        switch q?.grade {
        case "good": return tr("Good")
        case "fair": return tr("Fair")
        case "weak": return tr("Weak")
        case "stalled": return tr("Stalled")
        default: return "–"
        }
    }

    static func rttText(_ q: LinkQuality?) -> String {
        guard let ms = q?.rttMs else { return "–" }
        return "\(Int(ms.rounded())) ms"
    }

    // MARK: Failure causes

    static func issues(model m: AppModel, snap: LinkSnapshot) -> [LinkIssue] {
        var out: [LinkIssue] = []
        let install: @MainActor () async -> Void = { try? await Core.shared.adbScrcpyInstall() }
        let cfgBle = m.bleStatus

        if !m.wifi.connected {
            if m.isReconnecting {
                out.append(.init(id: "reconnecting", severity: "info", title: tr("Reconnecting to the phone"),
                                 detail: tr("The phone dials back in by itself. Give it a few seconds, and keep both on the same network.")))
            } else {
                out.append(.init(id: "no-link", severity: "warn", title: tr("No phone is linked"),
                                 detail: tr("Open DroidDock on the phone and keep it on the same Wi-Fi as this Mac, or plug it in by cable. If it never connects, pair again from the Connection settings.")))
            }
        }
        if m.wifi.connected, m.wifi.security == "plain" {
            out.append(.init(id: "plain", severity: "warn", title: tr("This link is not encrypted"),
                             detail: tr("Update DroidDock on the phone so it can use an encrypted link.")))
        }
        if m.wifi.connected, m.wifi.transport == "ble" {
            out.append(.init(id: "ble-only", severity: "warn", title: tr("Connected over Bluetooth only"),
                             detail: tr("Files, photos and mirroring need Wi-Fi. Join the same network as the Mac to get them back.")))
        }
        if m.wifi.connected, let g = m.linkQuality?.grade, (g == "weak" || g == "stalled"), m.wifi.transport != "usb" {
            out.append(.init(id: "weak-link", severity: "warn", title: tr("The link is slow"),
                             detail: tr("Move closer to the router, switch the phone to 5 GHz Wi-Fi, or use the cable.")))
        }
        if cfgBle.auth == "denied" || cfgBle.auth == "restricted" {
            out.append(.init(id: "ble-auth", severity: "warn", title: tr("Bluetooth access is off for DroidDock"),
                             detail: tr("Without it the Bluetooth fallback can't find the phone."),
                             fixLabel: tr("Open Settings"),
                             fix: { open("x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") }))
        } else if cfgBle.pairingStale == true, cfgBle.state != "connected", cfgBle.radio == "poweredOn" {
            out.append(.init(id: "ble-pairing", severity: "warn", title: tr("Bluetooth pairing is out of date"),
                             detail: tr("The phone no longer has this Mac's Bluetooth keys, so the Bluetooth fallback can't connect. In Bluetooth settings, remove the phone, then pair it again."),
                             fixLabel: tr("Open Settings"),
                             fix: { open("x-apple.systempreferences:com.apple.BluetoothSettings") }))
        } else if cfgBle.radio == "poweredOff" {
            out.append(.init(id: "ble-off", severity: "info", title: tr("Bluetooth is turned off on this Mac"),
                             detail: tr("The Bluetooth fallback is unavailable while it's off."),
                             fixLabel: tr("Open Settings"),
                             fix: { open("x-apple.systempreferences:com.apple.BluetoothSettings") }))
        }
        if snap.loaded, !snap.tools.adb {
            out.append(.init(id: "no-adb", severity: "info", title: tr("adb was not found"),
                             detail: tr("Mirroring, the second screen and cable features need Android platform tools."),
                             fixLabel: snap.tools.brew ? tr("Install") : nil,
                             fix: snap.tools.brew ? install : nil))
        }
        if snap.loaded, !snap.tools.scrcpy {
            out.append(.init(id: "no-scrcpy", severity: "info", title: tr("scrcpy was not found"),
                             detail: tr("Mirroring needs scrcpy."),
                             fixLabel: snap.tools.brew ? tr("Install") : nil,
                             fix: snap.tools.brew ? install : nil))
        }
        return out
    }

    private static func open(_ url: String) {
        if let u = URL(string: url) { NSWorkspace.shared.open(u) }
    }

    // MARK: Support report

    /// Plain text for a bug report. By construction it holds only versions, states and
    /// already-redacted event lines: no pairing token, TLS pin, notification or clipboard
    /// text, phone numbers, message bodies, and not even the phone's name.
    static func report(model m: AppModel, snap: LinkSnapshot) -> String {
        let info = Bundle.main.infoDictionary
        let build = (info?["CFBundleVersion"] as? String) ?? "?"
        var l: [String] = []
        l.append("DroidDock diagnostics")
        l.append("App: \(m.appVersion) (build \(build))")
        l.append("Core: \(snap.versions.coreVersion.isEmpty ? "?" : snap.versions.coreVersion)")
        l.append("macOS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        l.append("")
        l.append("Link")
        l.append("- State: \(m.wifi.connected ? "connected" : (m.isReconnecting ? "reconnecting" : "not connected"))")
        l.append("- Transport: \(m.wifi.connected ? (m.wifi.transport ?? "wifi") : "none")")
        l.append("- Security: \(m.wifi.security ?? "none")")
        l.append("- Round trip: \(rttText(m.linkQuality)) (grade \(m.linkQuality?.grade ?? "n/a"))")
        l.append("- Listening port: \(m.config?.port ?? 0)")
        l.append("- Bluetooth: radio \(m.bleStatus.radio), access \(m.bleStatus.auth), state \(m.bleStatus.state)")
        if let d = m.deviceInfo, m.wifi.connected {
            l.append("- Phone Android: \(d.android ?? "?")")
        }
        l.append("- Phone app version: \(snap.versions.phoneAppVersion ?? "not reported by the phone")")
        l.append("- Phone capabilities: \((m.wifi.caps ?? []).sorted().joined(separator: ", "))")
        l.append("")
        l.append("Tools")
        l.append("- adb: \(snap.tools.adb ? (snap.versions.adbVersion ?? "found") : "not found")")
        l.append("- scrcpy: \(snap.tools.scrcpy ? (snap.versions.scrcpyVersion ?? snap.tools.scrcpyVersion ?? "found") : "not found")")
        l.append("")
        l.append("Health items")
        if snap.items.isEmpty { l.append("- (not checked)") }
        for i in snap.items {
            l.append("- [\(i.ok ? "ok" : i.severity)] \(i.side): \(i.id)")
        }
        let issues = issues(model: m, snap: snap)
        if !issues.isEmpty {
            l.append("")
            l.append("Flagged")
            for i in issues { l.append("- [\(i.severity)] \(i.id)") }
        }
        l.append("")
        l.append("Recent log")
        let log = DiagLog.shared.recent()
        if log.isEmpty { l.append("- (empty)") }
        l.append(contentsOf: log)
        // Structured fields are versions and states only; log lines were redacted when written.
        return l.joined(separator: "\n")
    }

    static func copyReport(model m: AppModel, items: [HealthItem]) async {
        let checked = items.isEmpty ? ((try? await Core.shared.healthCheck()) ?? []) : items
        let snap = await snapshot(items: checked)
        let text = report(model: m, snap: snap)
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        m.toast(.ok, tr("Diagnostics copied"))
    }
}
