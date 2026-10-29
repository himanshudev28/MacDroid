import SwiftUI

/// Every file moving between the phone and this Mac, plus outgoing Quick Share sends.
/// Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct TransfersScreen: View {
    @Environment(AppModel.self) private var model

    enum Filter: String, CaseIterable, Identifiable {
        case all, toPhone, toMac, quickShare
        var id: String { rawValue }
        var title: String {
            switch self {
            case .all: tr("All")
            case .toPhone: tr("To Phone")
            case .toMac: tr("To Mac")
            case .quickShare: tr("Quick Share")
            }
        }
    }
    @State private var filter: Filter = .all
    @State private var showShare = false
    /// Files handed to the Quick Share sheet (dropped on its tile); empty = choose inside the sheet.
    @State private var sharePaths: [String] = []

    /// A stalled byte transfer reads "waiting", not a stale speed.
    private static let stallMs = 3000.0

    private var entries: [TransferEntry] {
        model.transfers.filter {
            switch filter {
            case .all: true
            case .toPhone: $0.dir == "toPhone"
            case .toMac: $0.dir == "toMac"
            case .quickShare: $0.source == "quickShare"
            }
        }
    }
    private var active: [TransferEntry] { entries.filter(\.isActive) }
    private var finished: [TransferEntry] { entries.filter { !$0.isActive }.sorted { ($0.endedMs ?? $0.startedMs) > ($1.endedMs ?? $1.startedMs) } }

    /// Live outgoing sends that the transfer log does not (yet) show; finished ones stay in the log.
    private var sends: [QuickShareSend] {
        guard filter == .all || filter == .quickShare else { return [] }
        return model.quickShareSends.values.sorted { $0.transferId > $1.transferId }
            .filter { [.connecting, .pin, .waiting, .sending, .failed, .rejected].contains($0.state) }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: UIActivity.shared.period(model.activeTransferCount > 0 ? 1 : 30))) { ctx in
            content(now: ctx.date.timeIntervalSince1970 * 1000)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheet(isPresented: $showShare, onDismiss: { sharePaths = [] }) { QuickShareSendSheet(paths: sharePaths) }
    }

    /// Anything "Clear History" would remove: finished log rows, or Quick Share sends that are no longer running.
    private var hasHistory: Bool {
        model.transfers.contains { !$0.isActive } || model.quickShareSends.values.contains { !$0.isLive }
    }

    private func clearHistory() {
        Task { try? await Core.shared.transfersClear() }
        model.clearFinishedQuickShareSends()
    }

    private func quickShare(_ urls: [URL]) {
        sharePaths = urls.filter(\.isFileURL).map(\.path)
        showShare = true
    }

    /// The two ways to start a send, each a click target and a drop target.
    private var sendBar: some View {
        HStack(spacing: DD.Space.md) {
            SendTile(title: tr("Send to Phone"),
                     detail: model.isConnected ? tr("Drop files here, or click to choose") : tr("No phone linked"),
                     systemImage: "arrow.up.doc", enabled: model.isConnected,
                     action: { ShellActions.pickAndSend() },
                     onDrop: { urls in
                         guard model.isConnected else { return false }
                         ShellActions.sendFiles(urls); return true
                     })
            SendTile(title: tr("Quick Share"),
                     detail: tr("Send to a nearby Android device"),
                     systemImage: "dot.radiowaves.left.and.right", enabled: true,
                     action: { quickShare([]) },
                     onDrop: { urls in quickShare(urls); return !sharePaths.isEmpty })
        }
        .padding(.horizontal, DD.Space.content).padding(.top, DD.Space.md)
    }

    @ViewBuilder private func content(now: Double) -> some View {
            Group {
                if model.transfers.isEmpty && model.quickShareSends.isEmpty {
                    VStack(spacing: 0) {
                        sendBar
                        EmptyState(systemImage: "arrow.up.arrow.down", title: tr("No transfers yet"),
                                   message: tr("Files moving between your phone and this Mac show up here, with live speed and time left."))
                    }
                } else {
                    VStack(spacing: 0) {
                        sendBar
                        HStack(spacing: DD.Space.md) {
                            Picker("", selection: $filter) {
                                ForEach(Filter.allCases) { Text($0.title).tag($0) }
                            }
                            .pickerStyle(.segmented).labelsHidden().controlSize(.small)
                            .frame(maxWidth: 380)
                            Spacer(minLength: 0)
                            Button { clearHistory() } label: { Label(tr("Clear History"), systemImage: "trash") }
                                .buttonStyle(.borderless).controlSize(.small)
                                .disabled(!hasHistory)
                                .help(tr("Remove finished, failed and cancelled transfers from this list. Files stay where they are."))
                        }
                        .padding(.horizontal, DD.Space.content).padding(.vertical, DD.Space.sm)
                        if entries.isEmpty && sends.isEmpty {
                            EmptyState(systemImage: "line.3.horizontal.decrease.circle", title: tr("Nothing here"),
                                       message: tr("No {kind} transfers yet.", ["kind": filter.title.lowercased()]))
                        } else { list(now: now) }
                    }
                }
            }
    }

    private func list(now: Double) -> some View {
        List {
            if !active.isEmpty || !sends.isEmpty {
                Section {
                    ForEach(sends) { s in sendRow(s) }
                    ForEach(active) { e in activeRow(e, now: now) }
                } header: {
                    HStack {
                        Text(tr("Active")).font(DD.Font.label).foregroundStyle(DD.dim)
                        Text("\(active.count + sends.count)").font(DD.Font.label).monospacedDigit().foregroundStyle(DD.dim)
                            .contentTransition(.numericText())
                        Spacer()
                        let total = active.filter { $0.unit == "bytes" && now - $0.updatedMs < Self.stallMs }.reduce(0) { $0 + $1.speed }
                        if total > 0 { Text(FileFormat.speed(total)).font(DD.Font.label).monospacedDigit().foregroundStyle(DD.dim) }
                    }
                }
            }
            if !finished.isEmpty {
                Section {
                    ForEach(finished) { e in finishedRow(e, now: now) }
                } header: { Text(tr("Finished")).font(DD.Font.label).foregroundStyle(DD.dim) }
            }
        }
        .listStyle(.inset).scrollContentBackground(.hidden)
        .ddAnimation(Motion.content, value: finished.count)
        .ddAnimation(Motion.content, value: active.count + sends.count)
    }

    // MARK: Rows

    private func activeRow(_ e: TransferEntry, now: Double) -> some View {
        let stalled = e.unit == "bytes" && now - e.updatedMs > Self.stallMs
        var parts: [String] = []
        if e.unit == "items" { parts.append(tr("{done} of {total} photos", ["done": Int(e.done), "total": Int(e.total)])) }
        else if e.total > 0 { parts.append(tr("{done} of {total}", ["done": FileFormat.bytes(e.done), "total": FileFormat.bytes(e.total)])) }
        else { parts.append(tr("{done} received", ["done": FileFormat.bytes(e.done)])) }
        if stalled { parts.append(tr("Waiting for the phone…")) } else {
            if e.unit == "bytes", e.speed > 0 { parts.append(FileFormat.speed(e.speed)) }
            if let eta = e.etaSecs { parts.append(tr("{time} left", ["time": FileFormat.secs(eta)])) }
        }
        return ProgressRow(name: displayName(e), systemImage: icon(e), incoming: e.dir == "toMac",
                           fraction: e.total > 0 ? e.done / e.total : 0, detail: parts.joined(separator: " · "),
                           state: stalled ? .paused : .running) {
            if e.cancellable {
                IconButton(systemImage: "xmark", label: tr("Cancel transfer"), size: 24) { Task { try? await Core.shared.transferCancel(key: e.key) } }
            }
        }
        .listRowSeparator(.hidden)
    }

    private func finishedRow(_ e: TransferEntry, now: Double) -> some View {
        let took = e.endedMs.map { ($0 - e.startedMs) / 1000 } ?? 0
        var detail: String
        var state: DDTransferState = .done
        switch e.status {
        case "done":
            var bits = [FileFormat.relative(ms: e.endedMs ?? e.startedMs), tr("took {time}", ["time": FileFormat.secs(took)])]
            if e.unit == "bytes", e.total > 0 { bits.insert(FileFormat.bytes(e.total), at: 0) }
            else if e.unit == "items" { bits.insert(tr("{done} of {total} photos", ["done": Int(e.done), "total": Int(e.total)]), at: 0) }
            detail = bits.joined(separator: " · ")
        case "cancelled":
            detail = e.cancelledBy == "phone" ? tr("Cancelled on the phone") : tr("Cancelled")
            state = .failed(detail)
        default:
            detail = FileFormat.errorText(e.error ?? tr("The transfer failed."))
            state = .failed(detail)
        }
        let fresh = e.status == "done" && now - (e.endedMs ?? 0) < 4000
        return ProgressRow(name: displayName(e), systemImage: icon(e), incoming: e.dir == "toMac", fraction: 1, detail: detail, state: state) {
            HStack(spacing: 2) {
                if e.status == "done", e.path != nil {
                    IconButton(systemImage: "magnifyingglass", label: tr("Show in Finder"), size: 24) { Task { try? await Core.shared.transferReveal(key: e.key) } }
                }
                if e.status != "done", canRetry(e) {
                    IconButton(systemImage: "arrow.clockwise", label: tr("Retry"), size: 24) { retry(e) }
                }
            }
        }
        .celebrating(fresh)
        .contentShape(Rectangle())
        .contextMenu {
            if e.path != nil {
                Button { Task { try? await Core.shared.transferOpen(key: e.key) } } label: { Label(tr("Open"), systemImage: "arrow.up.forward.app") }
                Button { Task { try? await Core.shared.transferReveal(key: e.key) } } label: { Label(tr("Show in Finder"), systemImage: "magnifyingglass") }
            }
            if e.status != "done", canRetry(e) { Button { retry(e) } label: { Label(tr("Retry"), systemImage: "arrow.clockwise") } }
        }
        .listRowSeparator(.hidden)
    }

    private func sendRow(_ s: QuickShareSend) -> some View {
        var state: DDTransferState = .running
        var detail = ""
        switch s.state {
        case .connecting: state = .queued; detail = tr("Connecting…")
        case .pin: state = .queued; detail = tr("Confirm PIN {pin} on {name}", ["pin": s.pin ?? "", "name": s.peer ?? tr("the other device")])
        case .waiting: state = .queued; detail = tr("Waiting for {name} to accept", ["name": s.peer ?? tr("the other device")])
        case .sending: detail = "\(FileFormat.bytes(Double(s.sent))) / \(FileFormat.bytes(Double(s.total)))"
        case .rejected: state = .failed(tr("Declined on the other device"))
        case .failed: state = .failed(s.error ?? tr("Quick Share failed"))
        case .done: state = .done
        }
        let live = [.connecting, .pin, .waiting, .sending].contains(s.state)
        return ProgressRow(name: s.peer.map { tr("Quick Share to {name}", ["name": $0]) } ?? tr("Quick Share"),
                           systemImage: "dot.radiowaves.left.and.right", incoming: false, fraction: s.fraction, detail: detail, state: state) {
            if live {
                IconButton(systemImage: "xmark", label: tr("Cancel transfer"), size: 24) { Task { try? await Core.shared.quickshareSendCancel(transferId: s.transferId) } }
            } else {
                IconButton(systemImage: "arrow.clockwise", label: tr("Send Again"), size: 24) { showShare = true }
            }
        }
        .listRowSeparator(.hidden)
    }

    // MARK: Helpers

    private func displayName(_ e: TransferEntry) -> String {
        e.source == "photoSync" ? tr("Photo backup") : e.name
    }
    private func icon(_ e: TransferEntry) -> String {
        e.source == "photoSync" ? "photo.stack.fill" : e.source == "quickShare" ? "dot.radiowaves.left.and.right" : FileFormat.symbol(name: e.name, dir: false)
    }
    /// Only Mac → phone sends keep their local source path, so only those can be retried here.
    private func canRetry(_ e: TransferEntry) -> Bool { e.dir == "toPhone" && e.path != nil && e.source != "quickShare" }
    private func retry(_ e: TransferEntry) {
        guard let p = e.path else { return }
        Task {
            do { try await Core.shared.fsPush(localPath: p, dest: "/sdcard/Download") }
            catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }
}

/// "Send to Phone" / "Quick Share": a click opens the picker, a file drag highlights it and drops send.
private struct SendTile: View {
    let title: String
    let detail: String
    let systemImage: String
    let enabled: Bool
    let action: () -> Void
    let onDrop: ([URL]) -> Bool
    @State private var targeted = false
    @State private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous)
        Button(action: action) {
            HStack(spacing: DD.Space.md) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(enabled ? Color.accentColor : DD.faint)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.accentColor.opacity(enabled ? 0.14 : 0.06)))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(DD.Font.headline).foregroundStyle(enabled ? DD.fg : DD.dim)
                    Text(targeted ? tr("Release to send") : detail)
                        .font(DD.Font.caption).foregroundStyle(DD.dim).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, DD.Space.md).padding(.vertical, DD.Space.sm + 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(targeted ? Color.accentColor.opacity(0.12) : (hovering ? DD.hover : DD.panel)))
            .overlay(shape.strokeBorder(targeted ? Color.accentColor : DD.line,
                                        style: StrokeStyle(lineWidth: targeted ? 1.5 : 1, dash: [6, 4])))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .dropDestination(for: URL.self) { urls, _ in onDrop(urls) } isTargeted: { targeted = $0 }
        .ddAnimation(Motion.content, value: targeted)
        .accessibilityLabel(title)
        .accessibilityHint(detail)
    }
}
