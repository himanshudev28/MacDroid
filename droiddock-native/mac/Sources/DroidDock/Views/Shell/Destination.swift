import SwiftUI

/// Every destination of the old Rail.tsx, shared by all shells (classic / grouped / phoneTabs / minimal).
enum Destination: String, CaseIterable, Identifiable, Hashable, Sendable {
    case dashboard, apps, devices, mirror, camera
    case notifications, messages, calls, contacts
    case files, photos, media, clipboard, transfers
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dashboard: tr("Dashboard")
        case .apps: tr("Apps")
        case .devices: tr("Devices")
        case .mirror: tr("Mirror")
        case .camera: tr("Camera")
        case .notifications: tr("Notifications")
        case .messages: tr("Messages")
        case .calls: tr("Calls")
        case .contacts: tr("Contacts")
        case .files: tr("Files")
        case .photos: tr("Photos")
        case .media: tr("Now playing")
        case .clipboard: tr("Clipboard")
        case .transfers: tr("Transfers")
        case .settings: tr("Settings")
        }
    }

    var symbol: String {
        switch self {
        case .dashboard: "house"
        case .apps: "square.grid.2x2"
        case .devices: "cable.connector"
        case .mirror: "rectangle.on.rectangle"
        case .camera: "camera"
        case .notifications: "bell"
        case .messages: "message"
        case .calls: "phone"
        case .contacts: "person.2"
        case .files: "folder"
        case .photos: "photo.on.rectangle"
        case .media: "music.note"
        case .clipboard: "doc.on.clipboard"
        case .transfers: "arrow.up.arrow.down"
        case .settings: "gearshape"
        }
    }

    /// ⌘<n> accelerator. One scheme in every layout (Go menu, tab tooltips): ⌘0 Dashboard, ⌘1…⌘7 the daily screens.
    var shortcutKey: Character? {
        switch self {
        case .dashboard: "0"
        case .notifications: "1"
        case .files: "2"
        case .apps: "3"
        case .clipboard: "4"
        case .mirror: "5"
        case .messages: "6"
        case .calls: "7"
        default: nil
        }
    }

    /// Tooltip text: the title plus its shortcut, if it has one.
    var helpText: String { shortcutKey.map { "\(title)  ⌘\($0)" } ?? title }

    /// The Go menu's list, identical in every layout: shortcut screens in key order, then the rest.
    static let goShortcuts: [Destination] = allCases.filter { $0.shortcutKey != nil }.sorted { $0.shortcutKey! < $1.shortcutKey! }
    static let goOthers: [Destination] = allCases.filter { $0.shortcutKey == nil && $0 != .settings }

    /// Rail.tsx groups (classic layout).
    static let classicGroups: [(title: String, items: [Destination])] = [
        ("", [.dashboard]),
        ("Connect", [.apps, .devices, .mirror, .camera]),
        ("Conversations", [.notifications, .messages, .calls, .contacts]),
        ("Library", [.files, .photos, .media, .clipboard, .transfers]),
    ]
}

/// `droiddock.layout`.
enum AppLayout: String, CaseIterable, Identifiable, Sendable {
    case phoneTabs, classic, grouped, minimal
    var id: String { rawValue }
    static let defaultsKey = "droiddock.layout"
    var title: String {
        switch self {
        case .phoneTabs: tr("Phone tabs")
        case .classic: tr("Classic")
        case .grouped: tr("Grouped")
        case .minimal: tr("Minimal")
        }
    }

    /// One line for the layout picker.
    var summary: String {
        switch self {
        case .phoneTabs: tr("Your phone on the left, five tabs along the top.")
        case .classic: tr("A sidebar with every screen. The phone sits in a side panel.")
        case .grouped: tr("A phone card above a sidebar grouped into Inbox, Files and Phone.")
        case .minimal: tr("The phone card with a few icon tabs. Everything else is under More.")
        }
    }
}
