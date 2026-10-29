import AppKit
import SwiftUI

/// Quick Share send (#14): discover nearby devices, pick files, confirm the PIN, watch progress.
/// Embed with `.sheet`, or call `QuickShareSendSheet.present(paths:)` from anywhere.
struct QuickShareSendSheet: View {
    var paths: [String] = []
    /// Set when hosted in its own window (a sheet uses `dismiss`).
    var onClose: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model
    private let accent = Color.accentColor

    private func close() { if let onClose { onClose() } else { dismiss() } }

    @State private var chosen: [String] = []
    @State private var deviceID: String?
    @State private var activeID: Int?
    @State private var starting = false
    @State private var startError: String?
    @State private var discovering = false

    private var send: QuickShareSend? { activeID.flatMap { model.quickShareSends[$0] } }
    private var device: QuickShareDevice? { model.quickShareDevices.first { $0.id == deviceID } }
    private var canSend: Bool { device != nil && !chosen.isEmpty && !starting }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            Group {
                if let send { progressPane(send) } else { setupPane }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            footer
        }
        .frame(minWidth: 520, idealWidth: 520, minHeight: 440, idealHeight: 480)
        .task {
            if chosen.isEmpty { chosen = paths }
            do { try await Core.shared.quickshareDiscoverStart(); discovering = true }
            catch { startError = FileFormat.errorText(error.localizedDescription) }
        }
        .onDisappear {
            if let id = activeID, let s = model.quickShareSends[id], [.connecting, .pin, .waiting, .sending].contains(s.state) {
                Task { try? await Core.shared.quickshareSendCancel(transferId: id) }
            }
            Task { try? await Core.shared.quickshareDiscoverStop() }
        }
        .onExitCommand { close() }
    }

    // MARK: Header / footer

    private var header: some View {
        HStack(spacing: DD.Space.md) {
            Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 28)).symbolRenderingMode(.hierarchical)
                .foregroundStyle(DD.dim).frame(width: 40).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(tr("Quick Share")).font(DD.Font.title2).foregroundStyle(DD.fg)
                Text(tr("Send files from this Mac to a nearby Android device")).font(DD.Font.callout).foregroundStyle(DD.dim)
            }
            Spacer()
        }
        .padding(DD.Space.content)
    }

    private var footer: some View {
        HStack {
            if send == nil, let startError {
                Label(startError, systemImage: "exclamationmark.triangle.fill").font(DD.Font.caption)
                    .foregroundStyle(DD.bad).lineLimit(2)
            }
            Spacer()
            switch send?.state {
            case nil:
                Button(tr("Cancel")) { close() }.buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
                Button(starting ? tr("Connecting…") : tr("Send")) { start() }
                    .buttonStyle(PrimaryButtonStyle()).keyboardShortcut(.defaultAction).disabled(!canSend)
            case .done?:
                Button(tr("Done")) { close() }.buttonStyle(PrimaryButtonStyle()).keyboardShortcut(.defaultAction)
            case .rejected?, .failed?:
                Button(tr("Close")) { close() }.buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
                Button(tr("Try Again")) { activeID = nil; startError = nil }.buttonStyle(PrimaryButtonStyle())
            default:
                Button(tr("Cancel Transfer")) { cancelActive() }.buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
            }
        }
        .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.md)
    }

    // MARK: Setup

    private var setupPane: some View {
        Form {
            Section {
                if chosen.isEmpty {
                    Button { pickFiles() } label: {
                        Label(tr("Choose files to send"), systemImage: "plus.circle.fill").symbolRenderingMode(.hierarchical)
                            .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                } else {
                    ForEach(chosen, id: \.self) { fileRow($0) }
                }
            } header: {
                HStack {
                    Text(tr("Files"))
                    Spacer()
                    if !chosen.isEmpty { Button(tr("Add…")) { pickFiles() }.buttonStyle(.link).controlSize(.small) }
                }
            }
            Section {
                if model.quickShareDevices.isEmpty { searching } else {
                    ForEach(model.quickShareDevices, id: \.id) { deviceRow($0) }
                }
            } header: {
                HStack {
                    Text(tr("Nearby devices"))
                    Spacer()
                    if discovering && model.quickShareDevices.isEmpty { ProgressView().controlSize(.small) }
                }
            }
        }
        .formStyle(.grouped).scrollContentBackground(.hidden)
        .dropDestination(for: URL.self) { urls, _ in
            addPaths(urls.filter(\.isFileURL).map(\.path)); return true
        }
    }

    private var searching: some View {
        VStack(spacing: 6) {
            Image(systemName: "wifi").font(.system(size: 22)).foregroundStyle(DD.dim).symbolRenderingMode(.hierarchical)
            Text(tr("Looking for nearby devices…")).font(DD.Font.headline).foregroundStyle(DD.fg)
            Text(tr("On the other device, open Quick Share and set it to be visible to everyone or your contacts.")).font(DD.Font.callout)
                .foregroundStyle(DD.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity).padding(.vertical, DD.Space.md)
    }

    private func fileRow(_ p: String) -> some View {
        let name = (p as NSString).lastPathComponent
        let size = (try? FileManager.default.attributesOfItem(atPath: p)[.size] as? NSNumber)?.doubleValue
        return ListRow(title: name, meta: size.map(FileFormat.bytes), hoverHighlight: false) {
            Image(systemName: FileFormat.symbol(name: name, dir: false)).font(.system(size: 18)).symbolRenderingMode(.hierarchical)
                .foregroundStyle(DD.dim).frame(width: 28)
        } trailing: {
            IconButton(systemImage: "xmark", label: tr("Remove {name}", ["name": name]), size: 24) { chosen.removeAll { $0 == p } }
        }
    }

    private func deviceRow(_ d: QuickShareDevice) -> some View {
        let on = d.id == deviceID
        return Button { deviceID = d.id } label: {
            ListRow(title: d.name, subtitle: "\(typeName(d.type)) · \(d.host)", hoverHighlight: false) {
                Image(systemName: Self.symbol(d.type)).font(.system(size: 22)).symbolRenderingMode(.hierarchical)
                    .foregroundStyle(DD.dim).frame(width: 32)
            } trailing: {
                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 17)).foregroundStyle(on ? accent : DD.faint)
                    .contentTransition(.symbolEffect(.replace))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .ddAnimation(Motion.select, value: on)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    static func symbol(_ type: String) -> String {
        switch type {
        case "phone": "iphone"
        case "tablet": "ipad"
        case "laptop": "laptopcomputer"
        default: "dot.radiowaves.left.and.right"
        }
    }
    private func typeName(_ t: String) -> String {
        switch t { case "phone": tr("Phone"); case "tablet": tr("Tablet"); case "laptop": tr("Computer"); default: tr("Nearby device") }
    }

    // MARK: Progress

    private func progressPane(_ s: QuickShareSend) -> some View {
        let name = s.peer ?? device?.name ?? tr("the other device")
        return VStack(spacing: DD.Space.lg) {
            switch s.state {
            case .connecting:
                ProgressView().controlSize(.large)
                title(tr("Connecting to {name}…", ["name": name]), tr("Keep the other device unlocked and nearby."))
            case .pin:
                Text(s.pin ?? "").font(.system(size: 44, weight: .semibold, design: .monospaced)).kerning(6)
                    .foregroundStyle(DD.fg).monospacedDigit()
                    .padding(.horizontal, 28).padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(DD.panel2))
                    .accessibilityLabel(tr("PIN {pin}", ["pin": (s.pin ?? "").map(String.init).joined(separator: " ")]))
                title(tr("Check this PIN on {name}", ["name": name]), tr("If it matches, accept the transfer there."))
            case .waiting:
                ProgressView().controlSize(.large)
                title(tr("Waiting for {name} to accept…", ["name": name]), tr("Tap Accept on the other device."))
            case .sending:
                VStack(spacing: 8) {
                    ProgressView(value: s.fraction).frame(width: 260)
                    Text("\(FileFormat.bytes(Double(s.sent))) / \(FileFormat.bytes(Double(s.total)))")
                        .font(DD.Font.caption).monospacedDigit().foregroundStyle(DD.dim)
                }
                title(tr("Sending to {name}", ["name": name]), "\(Int((s.fraction * 100).rounded()))%")
            case .done:
                Image(systemName: "checkmark.circle.fill").font(.system(size: 52)).foregroundStyle(DD.ok).symbolEffect(.bounce, value: s.state)
                title(tr("Sent to {name}", ["name": name]), tn("{n} file delivered", "{n} files delivered", chosen.count))
            case .rejected:
                Image(systemName: "hand.raised.fill").font(.system(size: 44)).foregroundStyle(DD.warn).symbolRenderingMode(.hierarchical)
                title(tr("{name} declined", ["name": name]), tr("Nothing was sent."))
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 44)).foregroundStyle(DD.bad).symbolRenderingMode(.hierarchical)
                title(tr("Couldn't send"), s.error ?? tr("Something went wrong. Try again."))
            }
        }
        .padding(DD.Space.xl).frame(maxWidth: .infinity, maxHeight: .infinity)
        .ddAnimation(Motion.state, value: s.state)
    }

    private func title(_ t: String, _ sub: String) -> some View {
        VStack(spacing: 4) {
            Text(t).font(DD.Font.title2).foregroundStyle(DD.fg).multilineTextAlignment(.center)
            Text(sub).font(DD.Font.callout).foregroundStyle(DD.dim).multilineTextAlignment(.center)
        }
    }

    // MARK: Actions

    private func pickFiles() {
        Task { addPaths(await FilePicker.open(files: true, directories: false, multiple: true, title: tr("Choose files to send")).map(\.path)) }
    }
    private func addPaths(_ p: [String]) { for x in p where !chosen.contains(x) { chosen.append(x) } }

    private func start() {
        guard let d = device else { return }
        starting = true; startError = nil
        Task {
            defer { starting = false }
            do { activeID = try await Core.shared.quickshareSend(deviceId: d.id, paths: chosen) }
            catch { startError = FileFormat.errorText(error.localizedDescription) }
        }
    }
    private func cancelActive() {
        if let id = activeID { Task { try? await Core.shared.quickshareSendCancel(transferId: id) } }
        close()
    }
}

// MARK: - Present from anywhere

extension QuickShareSendSheet {
    @MainActor private static var window: NSWindow?

    /// Opens the sheet in its own window (shell-agnostic entry point for other screens and the menu bar).
    @MainActor static func present(paths: [String] = []) {
        if let w = window { w.close(); window = nil }
        let root = QuickShareSendSheet(paths: paths, onClose: { window?.close() })
            .environment(AppModel.shared)
            .ddAppearance(AppearanceSettings.shared)
        let host = NSHostingController(rootView: root)
        let w = NSWindow(contentViewController: host)
        w.title = tr("Quick Share")
        w.styleMask = [.titled, .closable, .fullSizeContentView]
        w.titlebarAppearsTransparent = true
        w.isReleasedWhenClosed = false
        w.setContentSize(NSSize(width: 520, height: 480))
        w.center()
        window = w
        NSApp.activate()
        w.makeKeyAndOrderFront(nil)
    }
}
