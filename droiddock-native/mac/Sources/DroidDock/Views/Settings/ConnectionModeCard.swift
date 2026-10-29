import SwiftUI

/// Settings › Connection › "Bluetooth fallback" (docs/BLE-NEARBY-DESIGN.md §8).
///
/// - Persisted through the core config (`connectionMode`: `"network"` default | `"fallback"`, plus `bleAutoReturn`),
///   which also syncs the choice to the phone. Bluetooth is only a fallback, never a standalone mode, and is OFF
///   by default: no Bluetooth manager exists and no permission prompt appears until this is switched on here.
/// - Priority of links: USB > Wi-Fi > Bluetooth. With "Switch back automatically" on (default) the app returns to
///   Wi-Fi/USB by itself once it is stable; off keeps Bluetooth until the user taps "Use Wi-Fi now".
enum ConnectionMode {
    static let network = "network"
    static let fallback = "fallback"
}

struct ConnectionModeCard: View {
    @Environment(AppModel.self) private var model

    private var cfg: DroidConfig { model.config ?? DroidConfig() }
    private var ble: BleStatus { model.bleStatus }
    private var isOn: Binding<Bool> {
        Binding(get: { cfg.connectionMode == ConnectionMode.fallback },
                set: { model.setSetting("connectionMode", $0 ? ConnectionMode.fallback : ConnectionMode.network) })
    }
    private var autoReturn: Binding<Bool> {
        Binding(get: { cfg.bleAutoReturn }, set: { model.setSetting("bleAutoReturn", $0) })
    }

    var body: some View {
        DDSection(tr("Connection mode"), symbol: "point.3.connected.trianglepath.dotted",
                  footer: tr("Uses Bluetooth only when Wi-Fi to your phone is unavailable, and switches back automatically.")) {
            Toggle(isOn: isOn) {
                HStack(spacing: DD.Space.md) {
                    IconTile(symbol: "dot.radiowaves.left.and.right", tint: Color(nsColor: .systemBlue))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Bluetooth fallback"))
                        Text(tr("Keep notifications, clipboard and calls working over Bluetooth when Wi-Fi isn't available"))
                            .font(DD.Font.caption).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .toggleStyle(.switch)

            if isOn.wrappedValue {
                permissionRow
                statusRow
                DescribedToggle(tr("Switch back automatically"), isOn: autoReturn)
                switchRow
            }
        }
    }

    // MARK: Permission

    @ViewBuilder private var permissionRow: some View {
        switch ble.auth {
        case "denied", "restricted":
            HStack {
                Label(tr("Bluetooth is blocked for DroidDock"), systemImage: "exclamationmark.triangle")
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer(minLength: 0)
                Button(tr("Open Settings")) {
                    if let u = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") { NSWorkspace.shared.open(u) }
                }
            }
        case "notDetermined":
            HStack {
                Label(tr("Allow Bluetooth to finish setting up Wi-Fi fallback"), systemImage: "antenna.radiowaves.left.and.right")
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer(minLength: 0)
                Button(tr("Allow Bluetooth")) { BleService.shared.requestAuthorization() }
            }
        default:
            EmptyView()
        }
    }

    // MARK: Status

    private var statusRow: some View {
        LabeledContent(tr("Status")) {
            HStack(spacing: DD.Space.sm) {
                if let t = model.wifi.transport, model.wifi.connected {
                    StatusPill(text: t == "ble" ? tr("Bluetooth") : tr("Wi-Fi"), kind: .link)
                }
                Text(stateText).font(DD.Font.callout).foregroundStyle(DD.dim)
                if model.onBluetooth, let r = ble.rssi {
                    Text(verbatim: "\(r) dBm").font(DD.Font.callout).monospacedDigit().foregroundStyle(DD.dim)
                }
            }
        }
    }

    private var stateText: String {
        if ble.radio == "poweredOff" { return tr("Bluetooth is off") }
        if ble.radio == "unsupported" { return tr("Bluetooth isn't available on this Mac") }
        switch ble.state {
        case "connected": return tr("Bluetooth connected")
        case "authenticating": return tr("Connecting over Bluetooth…")
        case "scanning": return tr("Looking for your phone over Bluetooth…")
        case "standby": return tr("Ready, waiting for Wi-Fi to drop")
        default: return tr("Off")
        }
    }

    // MARK: Manual control

    @ViewBuilder private var switchRow: some View {
        if model.onBluetooth && ble.wifiAvailable {
            HStack {
                Label(cfg.bleAutoReturn ? tr("Wi-Fi is back") : tr("Wi-Fi available, switch now"), systemImage: "wifi")
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer(minLength: 0)
                Button(tr("Use Wi-Fi now")) { prefer("wifi") }
            }
        } else if model.wifi.connected && model.wifi.transport == "wifi" && ble.radio == "poweredOn" {
            HStack {
                Text(tr("Switch to Bluetooth to test the fallback")).font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer(minLength: 0)
                Button(tr("Use Bluetooth now")) { prefer("ble") }
            }
        }
        if ble.prefer != "auto" {
            HStack {
                Text(ble.prefer == "ble" ? tr("Bluetooth is forced") : tr("Wi-Fi is forced")).font(DD.Font.callout).foregroundStyle(DD.dim)
                Spacer(minLength: 0)
                Button(tr("Automatic")) { prefer("auto") }
            }
        }
    }

    private func prefer(_ t: String) {
        Task { try? await Core.shared.linkPrefer(t) }
    }
}
