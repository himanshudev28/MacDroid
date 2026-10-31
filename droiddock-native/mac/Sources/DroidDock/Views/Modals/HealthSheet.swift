import SwiftUI

/// The setup check: every grant both devices need, ordered by what a failure costs.
struct HealthSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var items: [HealthItem]?
    @State private var error: String?
    @State private var fixing: String?
    @State private var refreshing = false

    private var problems: [HealthItem] {
        (items ?? []).filter { !$0.ok && $0.severity != "info" }
            .sorted { $0.severity == $1.severity ? false : $0.severity == "error" }
    }
    private var notes: [HealthItem] { (items ?? []).filter { !$0.ok && $0.severity == "info" } }
    private var fine: [HealthItem] { (items ?? []).filter(\.ok) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(tr("Setup check")).font(DD.Font.title2)
                Spacer()
                if refreshing { ProgressView().controlSize(.small) }
                IconButton(systemImage: "arrow.clockwise", label: tr("Check again")) { Task { await refresh() } }
            }
            .padding(.horizontal, DD.Space.xl).padding(.top, DD.Space.lg).padding(.bottom, DD.Space.sm)

            Form {
                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .symbolRenderingMode(.hierarchical).foregroundStyle(DD.badText).font(DD.Font.callout)
                    }
                }
                if items == nil, error == nil {
                    Section { ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 80) }
                }
                if items != nil, problems.isEmpty {
                    Section {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(tr("Everything DroidDock needs is granted")).font(DD.Font.headline)
                                Text(tn("{n} check passed across both devices.", "{n} checks passed across both devices.", fine.count))
                                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                            }
                        } icon: {
                            Image(systemName: "checkmark.circle.fill").symbolRenderingMode(.hierarchical)
                                .font(.system(size: 22)).foregroundStyle(DD.ok)
                        }
                    }
                }
                if !problems.isEmpty {
                    Section(tn("{n} thing needs attention", "{n} things need attention", problems.count)) {
                        ForEach(problems) { row($0) }
                    }
                }
                if !notes.isEmpty { Section(tr("Worth knowing")) { ForEach(notes) { row($0) } } }
                if !fine.isEmpty, !problems.isEmpty {
                    Section(tn("{n} fine", "{n} fine", fine.count)) {
                        ForEach(fine) { i in
                            HStack(spacing: DD.Space.sm) {
                                Image(systemName: "checkmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(DD.ok)
                                    .accessibilityHidden(true)
                                Text(i.title).foregroundStyle(DD.dim).lineLimit(1)
                                Spacer(minLength: 0)
                                sideTag(i.side)
                            }
                        }
                    }
                }
            }
            .formStyle(.grouped).scrollContentBackground(.hidden)

            Divider()
            HStack {
                Spacer()
                Button(tr("Done")) { dismiss() }.buttonStyle(PrimaryButtonStyle()).keyboardShortcut(.defaultAction)
            }
            .padding(.horizontal, DD.Space.xl).padding(.vertical, DD.Space.md)
        }
        .frame(width: 560, height: 520)
        .ddAppearance(AppearanceSettings.shared)
        .task {
            await UIActivity.shared.pollWhileWindowVisible(every: .seconds(4), tolerance: .seconds(1)) { await refresh() }
        }
    }

    private func sideTag(_ side: String) -> some View {
        Text(side == "mac" ? "Mac" : tr("Phone")).font(DD.Font.footnote).foregroundStyle(DD.dim)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Capsule().fill(DD.panel2))
    }

    private func row(_ item: HealthItem) -> some View {
        let tone: Color = item.severity == "error" ? DD.bad : item.severity == "warn" ? DD.warn : DD.dim
        return HStack(alignment: .top, spacing: DD.Space.md) {
            Image(systemName: item.severity == "info" ? "info.circle.fill" : "exclamationmark.triangle.fill")
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tone).font(.system(size: 15)).frame(width: 20)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: DD.Space.sm) { Text(item.title).font(DD.Font.headline); sideTag(item.side) }
                Text(item.detail).font(DD.Font.callout).foregroundStyle(DD.dim).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: DD.Space.md)
            if item.fix != nil {
                Button(fixing == item.id ? tr("Opening…") : tr("Fix")) { Task { await fix(item) } }
                    .buttonStyle(SecondaryButtonStyle(compact: true)).disabled(fixing != nil)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private func refresh() async {
        refreshing = true
        do { items = try await Core.shared.healthCheck(); error = nil } catch { self.error = error.localizedDescription }
        refreshing = false
    }
    private func fix(_ item: HealthItem) async {
        guard let f = item.fix else { return }
        fixing = item.id
        do { model.toast(.info, try await Core.shared.healthFix(id: f)); try? await Task.sleep(for: .seconds(1.2)); await refresh() }
        catch { model.toast(.bad, error.localizedDescription) }
        fixing = nil
    }
}
