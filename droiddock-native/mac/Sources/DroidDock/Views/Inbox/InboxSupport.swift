import AppKit
import Observation
import SwiftUI

// MARK: - Time formatting

enum InboxTime {
    /// "now", "5m", "3h", "Yesterday", "Mon", "12 Oct": the compact meta column.
    static func short(_ ms: Double?) -> String? {
        guard let ms, ms > 0 else { return nil }
        let date = Date(timeIntervalSince1970: ms / 1000)
        let secs = Date().timeIntervalSince(date)
        if secs < 60 { return tr("now") }
        if secs < 3600 { return "\(Int(secs / 60))m" }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "\(Int(secs / 3600))h" }
        if cal.isDateInYesterday(date) { return tr("Yesterday") }
        if secs < 6 * 86_400 { return date.formatted(.dateTime.weekday(.abbreviated)) }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }

    /// Centre stamp between message clusters: "Today 10:41", "Yesterday 18:02", "Mon 09:15", "12 Oct, 14:30".
    static func stamp(_ date: Date) -> String {
        let time = date.formatted(date: .omitted, time: .shortened)
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "\(tr("Today")) \(time)" }
        if cal.isDateInYesterday(date) { return "\(tr("Yesterday")) \(time)" }
        if Date().timeIntervalSince(date) < 6 * 86_400 {
            return "\(date.formatted(.dateTime.weekday(.wide))) \(time)"
        }
        return date.formatted(.dateTime.day().month(.abbreviated).year()) + ", " + time
    }

    static func clock(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

// MARK: - Cross-screen routing

struct MessageTarget: Equatable, Hashable, Sendable {
    var number: String
    var name: String
}

/// Lets Contacts / Calls / the call overlay open a conversation in Messages without the screens knowing each other.
@Observable @MainActor
final class InboxRouter {
    static let shared = InboxRouter()
    var messageTarget: MessageTarget?
    private init() {}

    func openMessages(to target: MessageTarget) {
        messageTarget = target
        AppModel.shared.selection = .messages
        WindowControl.showMain()
    }
}

/// Last 10 digits: "+1 (415) 555-0134" and "4155550134" match.
func normalizedNumber(_ s: String) -> String { String(s.filter(\.isNumber).suffix(10)) }
func isNumberOnly(_ s: String) -> Bool {
    let t = s.trimmingCharacters(in: .whitespaces)
    return !t.isEmpty && t.allSatisfy { $0.isNumber || "+-() ".contains($0) }
}

// MARK: - Notification levels (#15)

enum NotifTier: String, CaseIterable, Identifiable {
    case alert, silent, muted
    var id: String { rawValue }
    var title: String {
        switch self {
        case .alert: tr("Alert")
        case .silent: tr("Silent")
        case .muted: tr("Muted")
        }
    }
    var symbol: String {
        switch self {
        case .alert: "bell.badge"
        case .silent: "bell"
        case .muted: "bell.slash"
        }
    }
    var detail: String {
        switch self {
        case .alert: tr("Banner and sound on your Mac")
        case .silent: tr("Banner without sound")
        case .muted: tr("Only listed here")
        }
    }

    /// Current level for a package: `notifTiers[pkg]`, else the legacy `mutedApps` list, else Alert.
    @MainActor static func current(for pkg: String?, config: DroidConfig?) -> NotifTier {
        guard let pkg, let config else { return .alert }
        if let s = config.raw["notifTiers"]?[pkg]?.stringValue, let t = NotifTier(rawValue: s) { return t }
        return config.mutedApps.contains(pkg) ? .muted : .alert
    }

    @MainActor static func set(_ tier: NotifTier, for pkg: String, config: DroidConfig?) {
        var tiers: [String: String] = [:]
        for p in config?.mutedApps ?? [] { tiers[p] = "muted" }
        for (k, v) in config?.raw["notifTiers"]?.objectValue ?? [:] { if let s = v.stringValue { tiers[k] = s } }
        tiers[pkg] = tier.rawValue
        AppModel.shared.setSetting("notifTiers", tiers)
    }
}

// Phone app icons: `PhoneAppIcon` / `PhoneIconStore` live in DesignSystem/PhoneAppIcon.swift.

// MARK: - Shared bits

/// Slim row shown above cached content when the phone is away.
struct OfflineStrip: View {
    var body: some View {
        HStack(spacing: DD.Space.sm) {
            StatusPill(text: tr("Offline"), kind: .bad, systemImage: "wifi.slash")
            Text(tr("Showing what was here when the phone left."))
                .font(DD.Font.caption).foregroundStyle(DD.dim)
            Spacer()
        }
        .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.sm)
    }
}

/// Searches that matched nothing.
struct NoResults: View {
    var query: String
    var body: some View {
        EmptyState(systemImage: "magnifyingglass", title: tr("No results"),
                   message: tr("Nothing matches “{query}”.", ["query": query]))
    }
}

/// True once `active` has stayed true for 0.3 s: loading UI that never flashes for fast loads (UI-GUIDE §9).
struct DelayedFlag: ViewModifier {
    var active: Bool
    @Binding var visible: Bool
    func body(content: Content) -> some View {
        content.task(id: active) {
            if !active { visible = false; return }
            try? await Task.sleep(for: .milliseconds(300), tolerance: .milliseconds(100))
            if !Task.isCancelled { visible = true }
        }
    }
}
extension View {
    func delayedFlag(_ active: Bool, into visible: Binding<Bool>) -> some View {
        modifier(DelayedFlag(active: active, visible: visible))
    }
}
