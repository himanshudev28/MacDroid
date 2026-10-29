import SwiftUI

/// ADB + scrcpy availability, capability badges, and the install flow.
struct ToolsCard: View {
    let tools: ToolsStatus?
    var onRefreshed: (ToolsStatus) -> Void = { _ in }
    @Environment(AppModel.self) private var model
    @State private var installing = false
    @State private var checking = false

    var body: some View {
        Section {
            if let t = tools {
                toolRow(name: "ADB", symbol: "terminal.fill", ok: t.adb,
                        detail: t.adb ? (t.adbPath ?? tr("Ready")) : tr("Downloaded automatically the first time you use a device."))
                toolRow(name: "scrcpy", symbol: "rectangle.on.rectangle", ok: t.scrcpy,
                        detail: t.scrcpy ? [t.scrcpyVersion.map { "v\($0)" }, t.scrcpyPath].compactMap { $0 }.joined(separator: " · ")
                                         : tr("Screen mirroring engine. Optional: Wi-Fi mirroring works without it."),
                        trailing: t.scrcpy ? nil : AnyView(installControl(t)))
                if t.scrcpy { capabilityRow(t.caps) }
            } else {
                LoadingState(rows: 2, avatar: true).frame(height: 120)
            }
        } header: {
            HStack {
                Text(tr("Tools"))
                Spacer()
                Button { recheck() } label: {
                    if checking { ProgressView().controlSize(.small) } else { Text(tr("Check Again")) }
                }
                .buttonStyle(.borderless).font(DD.Font.label).disabled(checking)
            }
        }
    }

    private func toolRow(name: String, symbol: String, ok: Bool, detail: String, trailing: AnyView? = nil) -> some View {
        HStack(spacing: DD.Space.md) {
            Image(systemName: symbol).font(.system(size: 16)).symbolRenderingMode(.hierarchical)
                .foregroundStyle(.secondary).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: DD.Space.sm) {
                    Text(name).font(DD.Font.body)
                    StatusPill(text: ok ? tr("Installed") : tr("Not installed"), kind: ok ? .ok : .neutral,
                               systemImage: ok ? "checkmark" : "minus")
                }
                Text(detail).font(ok ? DD.Font.monoSmall : DD.Font.callout).foregroundStyle(.secondary)
                    .lineLimit(2).truncationMode(.middle)
            }
            Spacer(minLength: DD.Space.md)
            trailing
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func installControl(_ t: ToolsStatus) -> some View {
        if installing {
            HStack(spacing: 6) { ProgressView().controlSize(.small); Text(tr("Installing…")).font(DD.Font.caption).foregroundStyle(DD.dim) }
        } else if t.brew {
            Button { install() } label: { Label(tr("Install"), systemImage: "arrow.down.circle") }
                .buttonStyle(PrimaryButtonStyle(compact: true)).help(tr("brew install scrcpy"))
        } else {
            Button { Opener.open("https://brew.sh") } label: { Label(tr("Get Homebrew"), systemImage: "arrow.up.right") }
                .buttonStyle(SecondaryButtonStyle(compact: true)).help(tr("scrcpy installs through Homebrew"))
        }
    }

    private func capabilityRow(_ c: ScrcpyCaps) -> some View {
        let items: [(String, Bool, String)] = [
            (tr("Virtual display"), c.virtualDisplay, "display"), (tr("Keep content"), c.keepContent, "rectangle.stack"),
            (tr("Flex display"), c.flexDisplay, "rectangle.expand.vertical"), (tr("UHID keyboard"), c.uhid, "keyboard"),
        ]
        return LabeledContent(tr("Capabilities")) {
            HStack(spacing: 6) {
                ForEach(items, id: \.0) { item in
                    StatusPill(text: item.0, kind: item.1 ? .ok : .neutral, systemImage: item.1 ? "checkmark" : "xmark")
                        .opacity(item.1 ? 1 : 0.7)
                }
            }
        }
    }

    private func install() {
        installing = true
        Task {
            do {
                try await Core.shared.adbScrcpyInstall()
                model.toast(.ok, tr("scrcpy installed"))
            } catch { model.toast(.bad, error.localizedDescription) }
            installing = false
        }
    }

    private func recheck() {
        checking = true
        Task { if let t = try? await Core.shared.adbTools() { onRefreshed(t) }; try? await Task.sleep(for: .milliseconds(400)); checking = false }
    }
}
