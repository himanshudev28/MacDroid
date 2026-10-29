import SwiftUI
import CoreImage.CIFilterBuiltins

/// Crisp QR image (always dark-on-white so phones can scan it in either theme).
struct SettingsQRCode: View {
    let text: String
    var size: CGFloat = 156
    var body: some View {
        if let img = Self.make(text) {
            Image(nsImage: img).interpolation(.none).resizable()
                .frame(width: size, height: size)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(Color.white))
                .accessibilityLabel(tr("Pairing QR code"))
        }
    }
    static func make(_ text: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8); f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let colored = out.applyingFilter("CIFalseColor", parameters: [
            "inputColor0": CIColor(red: 0.11, green: 0.11, blue: 0.13), "inputColor1": CIColor(red: 0.98, green: 0.976, blue: 0.97)])
        let rep = NSCIImageRep(ciImage: colored)
        let img = NSImage(size: rep.size); img.addRepresentation(rep); return img
    }
}

// MARK: - Connection

struct ConnectionSettingsPane: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @FocusState private var nameFocused: Bool
    @State private var showPair = false
    @State private var busy = false

    var body: some View {
        let c = model.config ?? DroidConfig()
        SettingsForm {
            ConnectionModeCard()

            DDSection(tr("Pair your phone")) {
                HStack(alignment: .center, spacing: DD.Space.xl) {
                    LockedQRView {
                        if let p = model.pairing { SettingsQRCode(text: p.url) }
                        else { ProgressView().frame(width: 176, height: 176) }
                    }
                    VStack(alignment: .leading, spacing: DD.Space.sm) {
                        Text(tr("Scan with the DroidDock app")).font(DD.Font.headline)
                        Text(tr("Open DroidDock on your phone and scan this code. Both devices need to be on the same Wi-Fi."))
                            .font(DD.Font.callout).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
                        if let p = model.pairing {
                            Text(verbatim: "\(p.ips.first ?? p.host):\(String(p.port))").font(DD.Font.mono).foregroundStyle(DD.dim).textSelection(.enabled)
                        }
                        StatusPill(text: model.isConnected ? tr("Linked to {name}", ["name": model.phoneName ?? tr("phone")]) : tr("Waiting for a phone"),
                                   kind: model.isConnected ? .link : .neutral)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, DD.Space.xs)
            }

            ServerConfigCard()

            DDSection(tr("This Mac")) {
                LabeledContent(tr("Device name")) {
                    TextField("", text: $name, prompt: Text(c.deviceName ?? tr("This Mac"))).labelsHidden()
                        .textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing).frame(width: 200)
                        .focused($nameFocused)
                        .onSubmit(commitName)
                        .onChange(of: nameFocused) { _, f in if !f { commitName() } }
                }
                DescribedToggle(tr("Reconnect automatically"), detail: tr("Find the phone again after sleep or a network change."),
                                isOn: model.cfg(\.autoReconnect, "autoReconnect"))
                DescribedToggle(tr("Encrypt the link"), detail: tr("AES-256-GCM on everything after pairing, keyed off your pairing code. Each side needs a recent enough app; anything unsupported stays as it was."),
                                isOn: model.cfg(\.encryptLink, "encryptLink"))
                DescribedToggle(tr("Clipboard sync"), detail: tr("Share what you copy both ways. Off stops all clipboard traffic."),
                                isOn: model.cfg(\.clipboardSync, "clipboardSync"))
            }

            DDSection(tr("Share with the phone")) {
                DescribedToggle(tr("This Mac's status"), detail: tr("Name and battery, so the phone's home screen can show both. Read-only."),
                                isOn: model.cfg(\.macInfoSync, "macInfoSync"))
                DescribedToggle(tr("What's playing"), detail: tr("Shows the current track on the phone. Play, pause and skip already work for every app."),
                                isOn: model.cfg(\.macMediaSync, "macMediaSync"))
                if c.macMediaSync {
                    DescribedToggle(tr("Include media websites"), detail: tr("Reads the active browser tab's title, only on known media sites."),
                                    isOn: model.cfg(\.macMediaBrowser, "macMediaBrowser"))
                }
            }

            DDSection(tr("Pause"), footer: tr("Pausing mutes notification banners and clipboard sync.")) {
                LabeledContent {
                    if model.isPaused {
                        Button(tr("Resume")) { pause(nil) }.buttonStyle(PrimaryButtonStyle(compact: true))
                    } else {
                        HStack(spacing: DD.Space.sm) {
                            Button(tr("1 hour")) { pause(Date().timeIntervalSince1970 * 1000 + 3_600_000) }
                            Button(tr("8 hours")) { pause(Date().timeIntervalSince1970 * 1000 + 28_800_000) }
                            Button(tr("Until resumed")) { pause(9_007_199_254_740_991) }
                        }
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr("Pause DroidDock"))
                        Text(pauseText(c)).font(DD.Font.caption).foregroundStyle(model.isPaused ? DD.warnText : DD.dim)
                    }
                }
            }

            DDSection(tr("Wireless ADB"), footer: tr("Needed for ADB mirroring and desktop mode. Android 11 or newer.")) {
                LabeledContent(tr("Pair over Wi-Fi")) {
                    HStack(spacing: DD.Space.sm) {
                        Button(tr("Reconnect")) { reconnect() }.disabled(busy)
                        Button(tr("Pair…")) { showPair = true }
                    }
                }
                if model.devices.isEmpty {
                    Text(tr("No ADB devices found.")).foregroundStyle(DD.dim)
                } else {
                    ForEach(model.devices) { d in
                        LabeledContent {
                            StatusPill(text: d.state == "device" ? tr("Ready") : d.state, kind: d.state == "device" ? .ok : .warn)
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(d.model.isEmpty ? d.serial : d.model)
                                    Text(d.serial).font(DD.Font.monoSmall).foregroundStyle(DD.dim)
                                }
                            } icon: { Image(systemName: d.transport == "wifi" ? "wifi" : "cable.connector").foregroundStyle(DD.dim) }
                        }
                    }
                }
                LabeledContent(tr("Forget wireless pairing")) {
                    Button(tr("Forget"), role: .destructive) { Task { try? await Core.shared.adbUnpair(); model.toast(.info, tr("Wireless pairing removed")) } }
                }
            }
        }
        .onAppear { name = c.deviceName ?? "" }
        .onChange(of: c.deviceName) { _, v in if !nameFocused { name = v ?? "" } }
        .sheet(isPresented: $showPair) { WirelessPairSheet() }
    }

    private func commitName() {
        let n = name.trimmingCharacters(in: .whitespaces)
        if n != (model.config?.deviceName ?? "") { model.setSetting("deviceName", n) }
    }
    private func pause(_ until: Double?) {
        Task { _ = try? await Core.shared.pauseSet(until: until) }   // the core echoes `config`
    }
    private func reconnect() {
        busy = true
        Task {
            do { model.toast(.info, try await Core.shared.adbReconnectNow()) } catch { model.toast(.bad, error.localizedDescription) }
            busy = false
        }
    }
    private func pauseText(_ c: DroidConfig) -> String {
        guard model.isPaused, let u = c.pausedUntil else { return tr("Mutes notification banners and clipboard sync.") }
        if u - Date().timeIntervalSince1970 * 1000 > 3.1e11 { return tr("Paused until you resume.") }
        let d = Date(timeIntervalSince1970: u / 1000)
        return tr("Paused until {time}", ["time": d.formatted(date: .omitted, time: .shortened)])
    }
}
