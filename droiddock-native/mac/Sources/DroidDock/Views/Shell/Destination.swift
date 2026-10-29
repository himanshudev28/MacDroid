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

    /// ⌘<n> accelerator as in Rail.tsx (`key`).
    var shortcutKey: Character? {
        switch self {
        case .dashboard: "1"
        case .apps: "2"
        case .devices: "3"
        case .mirror: "4"
        case .camera: "5"
        case .notifications: "6"
        case .messages: "7"
        case .calls: "8"
        case .contacts: "9"
        default: nil
        }
    }

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
}
