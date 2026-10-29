import SwiftUI

/// Devices screen (ADB / scrcpy). Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct DevicesScreen: View {
    @Environment(AppModel.self) private var model
    @State private var selected: String?
    @State private var freshTools: ToolsStatus?
    @State private var reconnecting = false
    @State private var confirmForget = false
    @State private var paired = false
    @State private var showPair = false

    private var tools: ToolsStatus? { freshTools ?? model.tools }
    private var activeSerial: String? {
        if let s = selected, model.devices.contains(where: { $0.serial == s }) { return s }
        return (model.devices.first { $0.state == "device" } ?? model.devices.first)?.serial
    }

    var body: some View {
        Form {
            devicesSection
            ToolsCard(tools: tools) { freshTools = $0 }
            phoneAppSection
            wirelessSection
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .sheet(isPresented: $showPair) { WirelessPairSheet() }
        .onChange(of: model.tools) { _, _ in freshTools = nil }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { reconnect() } label: { Label(tr("Reconnect"), systemImage: "arrow.clockwise") }
                    .keyboardShortcut("r", modifiers: .command)
                    .help(tr("Reconnect saved phone  ⌘R")).disabled(reconnecting)
            }
        }
        .task { paired = ((try? await Core.shared.adbPairedInfo())?.guid) != nil }
        .confirmationDialog(tr("Forget this phone?"), isPresented: $confirmForget, titleVisibility: .visible) {
            Button(tr("Forget Phone"), role: .destructive) { forget() }
        } message: { Text(tr("DroidDock will stop reconnecting to it wirelessly. You can pair it again any time.")) }
    }

    // MARK: Devices

    private var devicesSection: some View {
        Section {
            if model.devices.isEmpty {
                ContentUnavailableView {
                    Label(tr("No device connected"), systemImage: "cable.connector").symbolRenderingMode(.hierarchical)
                } description: {
                    Text(tr("Plug in your phone with a USB cable and allow USB debugging, or pair it wirelessly below."))
                } actions: {
                    HStack(spacing: DD.Space.sm) {
                        Button { showPair = true } label: { Label(tr("Pair Wirelessly…"), systemImage: "qrcode") }
                            .buttonStyle(PrimaryButtonStyle(compact: true))
                            .help(tr("Pair over Wi-Fi with a QR code or pairing code"))
                        Button { reconnect() } label: { Label(tr("Reconnect"), systemImage: "arrow.clockwise") }
                            .buttonStyle(SecondaryButtonStyle(compact: true)).disabled(reconnecting)
                    }
                }
                .frame(maxWidth: .infinity)
                .transition(.opacity)
            } else {
                ForEach(model.devices) { d in
                    DeviceCard(device: d, tools: tools ?? ToolsStatus(), isSelected: d.serial == activeSerial,
                               showsSelection: model.devices.count > 1,
                               appInfo: (model.devices.count == 1 || d.serial == activeSerial) ? model.deviceInfo : nil) {
                        selected = d.serial
                    }
                    .transition(.ddRise)
                }
            }
        } header: {
            HStack {
                Text(tr("ADB Devices"))
                Spacer()
                if !model.devices.isEmpty { CountBadge(count: model.devices.count) }
            }
        }
        .ddAnimation(Motion.content, value: model.devices.map(\.serial))
    }

    // MARK: Phone app link

    private var phoneAppSection: some View {
        Section(tr("Phone App")) {
            HStack(spacing: DD.Space.md) {
                Image(systemName: model.isConnected ? "wifi" : "wifi.slash").font(.system(size: 16))
                    .symbolRenderingMode(.hierarchical).foregroundStyle(.secondary).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.isConnected ? (model.phoneName ?? tr("Phone")) : tr("Phone app not linked"))
                        .font(DD.Font.body)
                    Text(model.isConnected ? tr("Linked over Wi-Fi") : tr("Install DroidDock on your phone, then pair."))
                        .font(DD.Font.callout).foregroundStyle(.secondary)
                }
                Spacer()
                if model.isConnected { StatusPill(text: tr("Linked"), kind: .link) }
                else {
                    Button(tr("Pair")) { model.selection = .dashboard }.buttonStyle(SecondaryButtonStyle(compact: true))
                }
            }
        }
    }

    // MARK: Wireless

    private var wirelessSection: some View {
        Section {
            actionRow("qrcode", tr("Pair with QR code"), tr("Android 11+ · Wireless debugging")) { showPair = true }
            actionRow("arrow.clockwise", reconnecting ? tr("Reconnecting…") : tr("Reconnect saved phone"),
                      tr("Tries the last wireless address again")) { reconnect() }
            if paired {
                actionRow("xmark.circle", tr("Forget phone"), tr("Stop reconnecting wirelessly"), destructive: true) { confirmForget = true }
            }
        } header: {
            HStack {
                Text(tr("Wireless"))
                Spacer()
                if let q = model.qrPair, q.state != "error" {
                    StatusPill(text: q.text, kind: q.state == "connected" ? .ok : .accent)
                }
            }
        }
    }

    private func actionRow(_ symbol: String, _ title: String, _ hint: String, destructive: Bool = false,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: DD.Space.md) {
                Image(systemName: symbol).font(.system(size: 16)).symbolRenderingMode(.hierarchical)
                    .foregroundStyle(destructive ? DD.bad : Color.secondary).frame(width: 24)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(DD.Font.body).foregroundStyle(destructive ? DD.badText : DD.fg)
                    Text(hint).font(DD.Font.callout).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(.tertiary)
                    .flipsForRightToLeftLayoutDirection(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Actions

    private func reconnect() {
        guard !reconnecting else { return }
        reconnecting = true
        Task {
            do { let r = try await Core.shared.adbReconnectNow(); model.toast(.info, r) }
            catch { model.toast(.bad, error.localizedDescription) }
            reconnecting = false
        }
    }

    private func forget() {
        Task {
            do { try await Core.shared.adbUnpair(); paired = false; model.toast(.ok, tr("Phone forgotten")) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }
}
