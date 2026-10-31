import SwiftUI

/// Shell-agnostic screen factory. Screen builders replace the placeholder cases with real views
/// (each a self-contained `View` that never knows which shell hosts it).
struct ScreenView: View {
    @Environment(AppModel.self) private var model
    let destination: Destination
    var body: some View {
        screen
            // Identity per destination so a pointer-driven change (wrapped in `withDDPointerAnimation`) cross-fades
            // with a 4 pt settle; unanimated (keyboard) changes swap instantly.
            .id(destination)
            .ddTransition(.ddScreen)
    }

    /// Screens whose data travels only over the Wi-Fi socket (BLE fallback, docs/BLE-NEARBY-DESIGN.md §2).
    private var needsWifi: Bool {
        switch destination {
        case .apps, .mirror, .camera, .contacts, .files, .photos: model.isConnected && !model.wifiFeaturesAvailable
        default: false
        }
    }

    @ViewBuilder private var screen: some View {
        if needsWifi {
            ContentUnavailableView(tr("Needs Wi-Fi"), systemImage: "wifi.slash",
                                   description: Text(tr("Your phone is connected over Bluetooth. This comes back when Wi-Fi does.")))
        } else {
            realScreen
        }
    }

    @ViewBuilder private var realScreen: some View {
        switch destination {
        case .dashboard: DashboardScreen()
        case .apps: AppsScreen()
        case .devices: DevicesScreen()
        case .mirror: MirrorScreen()
        case .camera: CameraScreen()
        case .notifications: NotificationsScreen()
        case .messages: MessagesScreen()
        case .calls: CallsScreen()
        case .contacts: ContactsScreen()
        case .files: FilesScreen()
        case .photos: PhotosScreen()
        case .media: MediaScreen()
        case .clipboard: ClipboardScreen()
        case .transfers: TransfersScreen()
        case .settings: SettingsScreen()
        }
    }
}

struct PlaceholderScreen: View {
    @Environment(AppModel.self) private var model
    let destination: Destination
    var body: some View {
        EmptyState(
            systemImage: destination.symbol,
            title: destination.title,
            message: model.isConnected ? tr("This screen is being rebuilt in native SwiftUI.")
                                       : tr("Link your phone to use {name}.", ["name": destination.title]))
    }
}
