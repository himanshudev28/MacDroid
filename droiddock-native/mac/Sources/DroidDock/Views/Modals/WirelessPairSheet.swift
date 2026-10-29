import SwiftUI

/// Android 11+ Wireless Debugging pairing: scan a QR or type the code the phone shows.
struct WirelessPairSheet: View {
    @Environment(\.dismiss) private var dismiss
    private enum Mode: String, CaseIterable, Identifiable { case qr, code; var id: String { rawValue } }
    @State private var mode: Mode = .qr

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            VStack(alignment: .leading, spacing: 3) {
                Text(tr("Pair wirelessly")).font(DD.Font.title2)
                Text(tr("Cable-free ADB over Wi-Fi. Android 11 or newer.")).font(DD.Font.callout).foregroundStyle(DD.dim)
            }
            Picker("", selection: $mode) {
                Label(tr("Pair via QR"), systemImage: "qrcode").tag(Mode.qr)
                Label(tr("Enter code"), systemImage: "key").tag(Mode.code)
            }
            .pickerStyle(.segmented).labelsHidden()

            Group {
                switch mode {
                case .qr: PairQRTab(onDone: { dismiss() })
                case .code: PairCodeTab(onDone: { dismiss() })
                }
            }
            .frame(maxWidth: .infinity, minHeight: 300, alignment: .top)

            HStack {
                Spacer()
                Button(tr("Cancel")) { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(DD.Space.xl)
        .frame(width: 540)
        .ddAppearance(AppearanceSettings.shared)
    }
}

private struct PairQRTab: View {
    @Environment(AppModel.self) private var model
    let onDone: () -> Void
    @State private var payload = ""
    @State private var status = QrPairStatus(state: "waiting", text: tr("Waiting for scan…"), addr: nil)

    private static func rand(_ n: Int) -> String {
        let a = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        return String((0..<n).map { _ in a.randomElement()! })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            VStack(alignment: .leading, spacing: 6) {
                step(1, tr("On the phone open Settings › Developer options › Wireless debugging."))
                step(2, tr("Tap “Pair device with QR code” and scan this code."))
                step(3, tr("Stay on the Wireless debugging screen until it connects."))
            }
            HStack {
                Spacer()
                if payload.isEmpty { RoundedRectangle(cornerRadius: DD.Radius.md).fill(DD.panel3).frame(width: 176, height: 176) }
                else { SettingsQRCode(text: payload) }
                Spacer()
            }
            HStack(spacing: DD.Space.sm) {
                Spacer()
                switch status.state {
                case "connected": Image(systemName: "checkmark.circle.fill").symbolRenderingMode(.hierarchical).foregroundStyle(DD.ok)
                case "error": Image(systemName: "exclamationmark.triangle.fill").symbolRenderingMode(.hierarchical).foregroundStyle(DD.bad)
                default: ProgressView().controlSize(.small)
                }
                Text(status.text).font(DD.Font.callout).foregroundStyle(status.state == "error" ? DD.badText : DD.fg)
                if let a = status.addr { Text(a).font(DD.Font.monoSmall).foregroundStyle(DD.dim) }
                Spacer()
            }
        }
        .task {
            let name = "droiddock-" + Self.rand(6), pw = Self.rand(10)
            payload = "WIFI:T:ADB;S:\(name);P:\(pw);;"
            try? await Core.shared.adbQrPairStart(serviceName: name, password: pw)
        }
        .onChange(of: model.qrPair) { _, s in
            guard let s else { return }
            status = s
            if s.state == "connected" {
                model.toast(.ok, tr("Paired over Wi-Fi"))
                Task { try? await Task.sleep(for: .seconds(1.1)); onDone() }
            }
        }
        .onDisappear { Task { try? await Core.shared.adbQrPairCancel() } }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: DD.Space.sm) {
            Text("\(n)").font(DD.Font.label).foregroundStyle(DD.dim).frame(width: 16, height: 16)
                .background(Circle().fill(DD.panel3))
            Text(text).font(DD.Font.callout).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct PairCodeTab: View {
    @Environment(AppModel.self) private var model
    let onDone: () -> Void
    @State private var hostPort = ""
    @State private var code = ""
    @State private var busy = false
    @FocusState private var focus: Field?
    private enum Field { case host, code }

    private var hostOK: Bool {
        hostPort.trimmingCharacters(in: .whitespaces).range(of: #"^(\d{1,3}\.){3}\d{1,3}:\d{2,5}$"#, options: .regularExpression) != nil
    }
    private var codeOK: Bool { code.count == 6 && code.allSatisfy(\.isNumber) }

    var body: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            Text(tr("On the phone open Wireless debugging › Pair device with pairing code, then type the address and 6-digit code it shows."))
                .font(DD.Font.callout).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
            Form {
                TextField(tr("Pairing address"), text: $hostPort, prompt: Text("192.168.0.100:41234"))
                    .focused($focus, equals: .host).font(DD.Font.mono)
                TextField(tr("Pairing code"), text: Binding(get: { code }, set: { code = String($0.filter(\.isNumber).prefix(6)) }), prompt: Text("123456"))
                    .focused($focus, equals: .code).font(DD.Font.mono).onSubmit(submit)
            }
            .formStyle(.grouped).scrollContentBackground(.hidden).scrollDisabled(true)
            .frame(height: 110)
            Label(tr("Use the port from the pairing dialog. It differs from the main port on the Wireless debugging screen."),
                  systemImage: "exclamationmark.circle")
                .font(DD.Font.caption).foregroundStyle(DD.dim)
            HStack {
                Spacer()
                Button(busy ? tr("Pairing…") : tr("Pair and connect"), action: submit)
                    .buttonStyle(PrimaryButtonStyle()).disabled(!hostOK || !codeOK || busy)
            }
        }
        .onAppear { focus = .host }
    }

    private func submit() {
        guard hostOK, codeOK, !busy else { return }
        busy = true
        Task {
            do {
                let r = try await Core.shared.adbPairWireless(hostPort: hostPort.trimmingCharacters(in: .whitespaces), code: code)
                model.toast(.ok, r.addr.map { tr("Paired and connected over Wi-Fi: {addr}", ["addr": $0]) } ?? tr("Paired. Reconnecting over Wi-Fi…"))
                onDone()
            } catch { model.toast(.bad, error.localizedDescription) }
            busy = false
        }
    }
}
