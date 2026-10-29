import SwiftUI

/// Settings › Connection › "Server configuration": advertised network adapter, current address, port, and
/// "Save and restart the server" (core command `server_restart`: rebinds the listener and UDP discovery, re-advertises mDNS).
struct ServerConfigCard: View {
    @Environment(AppModel.self) private var model
    static let defaultPort = 48484

    @State private var adapters: [NetAdapter] = []
    @State private var iface = ""
    @State private var portText = ""
    @State private var busy = false
    @State private var loaded = false
    @State private var result: String?
    @FocusState private var portFocused: Bool

    private var savedPort: Int { model.config?.port ?? Self.defaultPort }
    private var savedIface: String { model.config?.serverIface ?? "" }
    private var portValue: Int? {
        guard let p = Int(portText.trimmingCharacters(in: .whitespaces)), (1024...65534).contains(p) else { return nil }
        return p
    }
    private var portInvalid: Bool { portValue == nil }
    private var changed: Bool { portValue != savedPort || iface != savedIface }
    private var portChanged: Bool { portValue != nil && portValue != savedPort }

    var body: some View {
        DDSection(tr("Server configuration"), symbol: "server.rack",
                  footer: tr("The adapter decides which address DroidDock advertises in the QR code and on Bonjour; the server itself accepts connections on every adapter.")) {
            Picker(selection: $iface) {
                Text(tr("Auto")).tag("")
                ForEach(adapters) { Text($0.title).tag($0.name) }
                if !iface.isEmpty, !adapters.contains(where: { $0.name == iface }) {
                    Text(tr("{name} (not connected)", ["name": iface])).tag(iface)
                }
            } label: {
                RowLabel(title: tr("Network adapter"), detail: tr("Which connection the phone should use to reach this Mac."),
                         symbol: "network", tint: Color(nsColor: .systemBlue))
            }
            LabeledContent {
                Text(verbatim: addressText).font(DD.Font.mono).foregroundStyle(DD.dim).textSelection(.enabled)
            } label: {
                RowLabel(title: tr("IP address"), symbol: "number", tint: Color(nsColor: .systemGray))
            }
            LabeledContent {
                HStack(spacing: DD.Space.sm) {
                    TextField("", text: $portText, prompt: Text(verbatim: String(Self.defaultPort)))
                        .labelsHidden().textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing)
                        .frame(width: 84).monospacedDigit().focused($portFocused)
                        .onSubmit(save)
                    if portText != String(Self.defaultPort) {
                        Button(tr("Default")) { portText = String(Self.defaultPort) }.buttonStyle(.borderless)
                    }
                }
            } label: {
                RowLabel(title: tr("Server port"),
                         detail: portInvalid ? tr("Use a number from 1024 to 65534.") : tr("Default {port}.", ["port": Self.defaultPort]),
                         symbol: "door.left.hand.open", tint: Color(nsColor: .systemIndigo))
            }
            if portInvalid {
                Label(tr("Use a number from 1024 to 65534."), systemImage: "exclamationmark.triangle.fill")
                    .font(DD.Font.caption).foregroundStyle(DD.warnText)
            } else if portChanged {
                Label(tr("The phone finds this Mac again by itself over Bonjour on most networks. If it doesn't, scan the new QR code once."),
                      systemImage: "info.circle").font(DD.Font.caption).foregroundStyle(DD.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            LabeledContent {
                Button(tr("Save and restart the server")) { save() }
                    .buttonStyle(PrimaryButtonStyle(compact: true))
                    .disabled(busy || portInvalid || !changed)
            } label: {
                if let result { Text(result).font(DD.Font.caption).foregroundStyle(DD.dim) }
            }
        }
        .onAppear { load() }
        .onChange(of: model.config?.port) { _, p in if !portFocused, let p { portText = String(p) } }
        .onChange(of: model.config?.serverIface) { _, v in if loaded { iface = v ?? "" } }
    }

    private var addressText: String {
        let ips = model.pairing?.ips ?? []
        return ips.isEmpty ? tr("No network") : ips.joined(separator: ", ")
    }

    private func load() {
        adapters = NetAdapters.active()
        if !loaded { iface = savedIface; portText = String(savedPort); loaded = true }
    }

    private func save() {
        guard let p = portValue, !busy else { return }
        busy = true; result = nil
        let ifc = iface
        Task {
            do {
                let r = try await Core.shared.serverRestart(port: p, iface: ifc)
                if let c = try? await Core.shared.getConfig() { model.applyConfig(c) }
                await model.refreshPairing()
                adapters = NetAdapters.active()
                result = tr("Server restarted on port {port}.", ["port": r.port])
                model.toast(.ok, tr("Server restarted on port {port}.", ["port": r.port]))
            } catch {
                result = error.localizedDescription
                model.toast(.bad, error.localizedDescription)
            }
            busy = false
        }
    }
}
