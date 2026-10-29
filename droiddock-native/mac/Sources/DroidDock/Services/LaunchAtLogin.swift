import Foundation
import ServiceManagement

enum LaunchAtLogin {
    private static var available: Bool { Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app" }
    static var isEnabled: Bool { available && SMAppService.mainApp.status == .enabled }
    /// Registered but the user still has to approve it in System Settings > Login Items.
    static var needsApproval: Bool { available && SMAppService.mainApp.status == .requiresApproval }
    static func set(_ on: Bool) throws {
        guard available else { return }
        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
    }
}
