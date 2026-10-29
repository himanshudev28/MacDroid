import SwiftUI

/// Prompt for an incoming Quick Share transfer. The PIN is the point: it is the only defence against
/// accepting from the wrong device, so it gets the visual weight. No dismiss-by-clicking-away and no
/// default action: the sender is blocked waiting for an answer.
struct QuickShareReceivePrompt: View {
    @Environment(AppModel.self) private var model
    let request: QuickShareRequest
    @State private var accepted = false

    private var total: Double { request.files.reduce(0) { $0 + max(0, $1.size) } }

    var body: some View {
        VStack(spacing: DD.Space.lg) {
            Image(systemName: "arrow.down.circle.fill").font(.system(size: 40)).foregroundStyle(.secondary)
                .symbolRenderingMode(.hierarchical).accessibilityHidden(true)
            VStack(spacing: 4) {
                Text(tn("{peer} wants to send you {n} file", "{peer} wants to send you {n} files", request.files.count, ["peer": request.peer]))
                    .font(DD.Font.title2).multilineTextAlignment(.center)
                Text(accepted ? tr("Receiving…") : tr("Accept only if this code matches the one on the sending device."))
                    .font(DD.Font.callout).foregroundStyle(DD.dim).multilineTextAlignment(.center)
            }

            Text(request.pin)
                .font(.system(size: 34, weight: .semibold, design: .monospaced)).tracking(8)
                .foregroundStyle(DD.fg).monospacedDigit()
                .frame(maxWidth: .infinity).padding(.vertical, DD.Space.lg)
                .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(DD.panel2))
                .accessibilityLabel(tr("Code {pin}", ["pin": request.pin]))

            Card(padding: DD.Space.md) {
              VStack(spacing: 0) {
                ForEach(Array(request.files.prefix(6).enumerated()), id: \.offset) { i, f in
                    HStack {
                        Image(systemName: "doc").foregroundStyle(DD.dim)
                        Text(f.name).lineLimit(1).truncationMode(.middle)
                        Spacer()
                        Text(Self.size(f.size)).foregroundStyle(DD.dim).monospacedDigit()
                    }
                    .font(DD.Font.body).padding(.vertical, 6)
                    if i < min(6, request.files.count) - 1 { Divider() }
                }
                if request.files.count > 6 {
                    Text(tn("and {n} more", "and {n} more", request.files.count - 6)).font(DD.Font.caption).foregroundStyle(DD.dim).padding(.top, 4)
                }
                if request.files.count > 1 {
                    Text(tr("{size} total", ["size": Self.size(total)])).font(DD.Font.caption).foregroundStyle(DD.dim)
                        .frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 4)
                }
              }
            }

            if accepted {
                let p = model.quickShareProgress
                VStack(spacing: 4) {
                    if let p, p.total > 0 { ProgressView(value: min(1, p.received / p.total)) }
                    else { ProgressView().progressViewStyle(.linear) }
                    if let p, p.total > 0 {
                        Text("\(Self.size(p.received)) / \(Self.size(p.total))").font(DD.Font.caption).foregroundStyle(DD.dim).monospacedDigit()
                    }
                }
            } else {
                HStack {
                    Spacer()
                    Button(tr("Decline")) { respond(false) }.buttonStyle(SecondaryButtonStyle()).keyboardShortcut(.cancelAction)
                    Button(tr("Accept")) { respond(true) }.buttonStyle(PrimaryButtonStyle()).keyboardShortcut(.defaultAction)
                }
                .controlSize(.large)
            }
        }
        .padding(DD.Space.xl)
        .frame(width: 420)
        .ddAppearance(AppearanceSettings.shared)
        .interactiveDismissDisabled()
    }

    private func respond(_ accept: Bool) {
        let id = request.id
        Task { try? await Core.shared.quickshareRespond(id: id, accept: accept) }
        if accept { accepted = true } else { model.quickShare = nil }
    }

    static func size(_ bytes: Double) -> String {
        guard bytes > 0 else { return "—" }
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}
