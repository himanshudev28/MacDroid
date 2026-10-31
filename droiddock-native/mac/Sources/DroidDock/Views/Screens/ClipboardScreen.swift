import SwiftUI

/// Clipboard screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
/// The core keeps no clip history (sync runs in Rust, like the old UI), so this shows sync status,
/// the live Mac clipboard, and the manual "send to phone" action.
struct ClipboardScreen: View {
    @Environment(AppModel.self) private var model
    @State private var clip = MacClipboard()
    @State private var sending = false
    @State private var sentFlash = false

    private var syncOn: Bool { model.config?.clipboardSync ?? true }

    var body: some View {
        Group {
            if !model.isConnected {
                EmptyState.disconnected(feature: tr("clipboard sync"))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: DD.Space.xl) {
                        syncCard
                        macClipboardSection
                    }
                    .padding(DD.Space.content)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { send() } label: { Label(tr("Send to Phone"), systemImage: "arrow.up.doc.on.clipboard") }
                    .keyboardShortcut("v", modifiers: [.command, .shift])
                    .help(tr("Send Mac clipboard to phone  ⌘⇧V"))
                    .disabled(!model.isConnected || sending)
            }
        }
        .task {   // poll only while visible
            await UIActivity.shared.pollWhileWindowVisible(every: .seconds(1), tolerance: .milliseconds(400)) { clip.poll() }
        }
    }

    // MARK: Sync status

    private var syncCard: some View {
        Card(padding: DD.Space.lg) {
            HStack(spacing: DD.Space.lg) {
                SymbolTile(systemImage: "doc.on.clipboard.fill", tint: syncOn ? DD.linkText : DD.dim, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: DD.Space.sm) {
                        Text(tr("Clipboard sync")).font(DD.Font.title2).foregroundStyle(DD.fg)
                        StatusPill(text: syncOn ? tr("On") : tr("Off"), kind: syncOn ? .link : .neutral)
                            .contentTransition(.opacity)
                    }
                    Text(tr("Copy text on either device and it appears on the other, in both directions. Images go Mac → phone automatically."))
                        .font(DD.Font.callout).foregroundStyle(DD.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: DD.Space.lg)
                Toggle(tr("Clipboard sync"), isOn: Binding(get: { syncOn }, set: { model.setSetting("clipboardSync", $0) }))
                    .toggleStyle(.switch).labelsHidden()
            }
        }
        .ddAnimation(Motion.content, value: syncOn)
    }

    // MARK: Mac clipboard

    private var macClipboardSection: some View {
        VStack(alignment: .leading, spacing: DD.Space.sm) {
            SectionHeader(tr("On this Mac right now"))
            Card(padding: 0) {
                VStack(spacing: 0) {
                    preview.padding(DD.Space.lg).frame(maxWidth: .infinity, alignment: .leading)
                    Divider().overlay(DD.line)
                    HStack {
                        Text(footnote).font(DD.Font.caption).foregroundStyle(DD.dim)
                        Spacer()
                        Button { copyAgain() } label: { Label(tr("Copy"), systemImage: "doc.on.doc") }
                            .buttonStyle(SecondaryButtonStyle(compact: true)).disabled(clip.content == .empty)
                            .help(tr("Copy to the Mac clipboard again"))
                        Button { send() } label: {
                            Label(sentFlash ? tr("Sent") : tr("Send to Phone"),
                                  systemImage: sentFlash ? "checkmark" : "arrow.up.doc.on.clipboard")
                                .contentTransition(.symbolEffect(.replace))
                        }
                        .buttonStyle(PrimaryButtonStyle(compact: true)).disabled(sending || clip.content == .empty)
                    }
                    .padding(.horizontal, DD.Space.lg).padding(.vertical, DD.Space.md)
                }
            }
            .ddAnimation(Motion.content, value: clip.content)
        }
    }

    @ViewBuilder private var preview: some View {
        switch clip.content {
        case .empty:
            HStack(spacing: DD.Space.md) {
                Image(systemName: "clipboard").font(.system(size: 22)).foregroundStyle(DD.faint)
                Text(tr("Copy something on either device")).font(DD.Font.body).foregroundStyle(DD.dim)
            }.frame(minHeight: 48)
        case .text(let s):
            Text(s.prefix(1200)).font(DD.Font.body).foregroundStyle(DD.fg).lineLimit(8)
                .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
        case .image(let img, _):
            Image(nsImage: img).resizable().aspectRatio(contentMode: .fit)
                .frame(maxHeight: 220)
                .clipShape(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).strokeBorder(DD.line))
        case .files(let names):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(names.prefix(5), id: \.self) { Label($0, systemImage: "doc.fill").font(DD.Font.body).foregroundStyle(DD.fg) }
                if names.count > 5 { Text(tr("+{n} more", ["n": "\(names.count - 5)"])).font(DD.Font.caption).foregroundStyle(DD.dim) }
            }
        }
    }

    private var footnote: String {
        switch clip.content {
        case .empty: return tr("Nothing to send")
        case .text(let s): return tn("{n} character", "{n} characters", s.count)
        case .image(_, let dims): return tr("Image") + " · " + dims
        case .files(let n): return tn("{n} file", "{n} files", n.count)
        }
    }

    private func copyAgain() {
        let pb = NSPasteboard.general
        switch clip.content {
        case .text(let s): pb.clearContents(); pb.setString(s, forType: .string)
        case .image(let i, _): pb.clearContents(); pb.writeObjects([i])
        default: break
        }
    }

    private func send() {
        guard !sending else { return }
        sending = true
        Task {
            do {
                try await Core.shared.clipboardPushNow()
                withDDAnimation(Motion.select) { sentFlash = true }
                model.toast(.ok, tr("Sent to your phone"))
                try? await Task.sleep(for: .seconds(1.5))
                withDDAnimation(Motion.select) { sentFlash = false }
            } catch { model.toast(.bad, error.localizedDescription) }
            sending = false
        }
    }
}
