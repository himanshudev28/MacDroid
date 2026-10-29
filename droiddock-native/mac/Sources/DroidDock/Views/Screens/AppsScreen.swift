import AppKit
import SwiftUI

/// The phone's app drawer on the Mac. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct AppsScreen: View {
    @Environment(AppModel.self) private var model

    @State private var reloadGate = ReloadGate()
    @State private var apps: [PhoneApp]?
    @State private var error: String?
    @State private var query = ""
    @State private var loadingVisible = false
    @State private var launching: String?

    private static let gate = FetchGate(limit: 4)
    private let columns = [GridItem(.adaptive(minimum: 88, maximum: 110), spacing: DD.Space.sm)]

    private var pinned: [String] { model.config?.pinnedApps ?? [] }
    private var openOnMac: Bool { model.config?.openAppsOnMac ?? false }
    private var adbSerial: String? { model.devices.first { $0.state == "device" }?.serial }

    private var shown: [PhoneApp] {
        guard let apps else { return [] }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return apps.sorted { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending } }
        return apps.filter { $0.label.lowercased().contains(q) }.sorted {
            let a = $0.label.lowercased().hasPrefix(q), b = $1.label.lowercased().hasPrefix(q)
            return a != b ? a : $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending
        }
    }
    private var pinnedApps: [PhoneApp] {
        let by = Dictionary(uniqueKeysWithValues: (apps ?? []).map { ($0.pkg, $0) })
        return pinned.compactMap { by[$0] }
    }

    var body: some View {
        Group {
            if !model.isConnected && apps == nil {
                EmptyState.disconnected(feature: tr("your apps"))
            } else if let error {
                EmptyState.failed(error) { Task { await load() } }
            } else if apps == nil {
                if loadingVisible { skeleton } else { Color.clear }
            } else if shown.isEmpty {
                EmptyState(systemImage: query.isEmpty ? "square.grid.2x2" : "magnifyingglass",
                           title: query.isEmpty ? tr("No apps found") : tr("No matches"),
                           message: query.isEmpty ? tr("The phone reported no launchable apps.") : tr("Nothing matching “{q}”.", ["q": query]))
            } else { grid }
        }
        .task(id: model.isConnected) { if model.isConnected, await reloadGate.ready() { await load() } }
        .searchable(text: $query, prompt: tr("Search apps"))
        .onSubmit(of: .search) { if let a = shown.first { activate(a, invert: NSEvent.modifierFlags.contains(.option)) } }
        .toolbar {
            ToolbarItemGroup {
                Menu {
                    Picker(tr("Open apps on"), selection: Binding(get: { openOnMac && adbSerial != nil }, set: { model.setSetting("openAppsOnMac", $0) })) {
                        Label(tr("Phone"), systemImage: "iphone").tag(false)
                        Label(tr("Mac window"), systemImage: "macwindow").tag(true).disabled(adbSerial == nil)
                    }.pickerStyle(.inline)
                    if adbSerial == nil { Text(tr("Mac windows need a USB or wireless ADB connection.")) }
                } label: { Label(tr("Open On"), systemImage: openOnMac && adbSerial != nil ? "macwindow" : "iphone") }
                    .help(tr("Where a click opens an app. Hold Option to do the other one."))
                Button { Task { await load() } } label: { Label(tr("Refresh"), systemImage: "arrow.clockwise") }
                    .keyboardShortcut("r", modifiers: .command).help(tr("Refresh app list  ⌘R")).disabled(!model.isConnected)
            }
        }
    }

    private var grid: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DD.Space.lg) {
                if query.isEmpty && !pinnedApps.isEmpty {
                    VStack(alignment: .leading, spacing: DD.Space.sm) {
                        SectionHeader(tr("Pinned"))
                        LazyVGrid(columns: columns, spacing: DD.Space.sm) { ForEach(pinnedApps) { tile($0) } }
                    }
                    Divider()
                }
                VStack(alignment: .leading, spacing: DD.Space.sm) {
                    SectionHeader(query.isEmpty ? tr("All Apps") : tr("Results")) {
                        Text("\(shown.count)").font(DD.Font.caption).monospacedDigit().foregroundStyle(DD.dim)
                    }
                    LazyVGrid(columns: columns, spacing: DD.Space.sm) { ForEach(shown) { tile($0) } }
                }
            }
            .padding(DD.Space.content)
        }
        .opacity(model.isConnected ? 1 : 0.6).disabled(!model.isConnected)
    }

    private func tile(_ app: PhoneApp) -> some View {
        PhoneAppTile(app: app, gate: Self.gate, launching: launching == app.pkg) {
            activate(app, invert: NSEvent.modifierFlags.contains(.option))
        }
        .contextMenu {
            Button { activate(app, invert: false, forcePhone: true) } label: { Label(tr("Open on Phone"), systemImage: "iphone") }
            Button { activate(app, invert: false, forceMac: true) } label: { Label(tr("Open in Mac Window"), systemImage: "macwindow") }
                .disabled(adbSerial == nil)
            Divider()
            if pinned.contains(app.pkg) {
                Button { setPins(pinned.filter { $0 != app.pkg }) } label: { Label(tr("Unpin"), systemImage: "pin.slash") }
            } else {
                Button { setPins(pinned + [app.pkg]) } label: { Label(tr("Pin to Top"), systemImage: "pin") }
            }
            Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(app.pkg, forType: .string) } label: {
                Label(tr("Copy Package Name"), systemImage: "doc.on.doc")
            }
        }
    }

    private var skeleton: some View {
        LazyVGrid(columns: columns, spacing: DD.Space.md) {
            ForEach(0..<24, id: \.self) { _ in
                VStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: DD.Size.appTile * 0.225, style: .continuous).fill(DD.panel2).frame(width: DD.Size.appTile, height: DD.Size.appTile)
                    Capsule().fill(DD.panel2).frame(width: 50, height: 8)
                }
            }
        }
        .modifier(Shimmer()).padding(DD.Space.content).frame(maxHeight: .infinity, alignment: .top)
        .accessibilityLabel(tr("Loading"))
    }

    // MARK: Actions

    private func load() async {
        error = nil
        let t = Task { try? await Task.sleep(for: .milliseconds(300), tolerance: .milliseconds(100)); if !Task.isCancelled { loadingVisible = true } }
        defer { t.cancel(); loadingVisible = false }
        do { apps = try await Core.shared.appsList() }
        catch { if apps == nil { self.error = FileFormat.errorText(error.localizedDescription) } else { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) } }
    }

    private func setPins(_ next: [String]) { model.setSetting("pinnedApps", next) }

    private func activate(_ app: PhoneApp, invert: Bool, forcePhone: Bool = false, forceMac: Bool = false) {
        let wantsMac = forceMac ? true : forcePhone ? false : (invert ? !openOnMac : openOnMac)
        let serial = adbSerial
        launching = app.pkg
        ShellState.shared.recordRecent(app.pkg)   // fills the phone card's recent-apps row
        Task {
            defer { launching = nil }
            do {
                if wantsMac, let serial {
                    try await Core.shared.adbMirrorApp(serial: serial, package: app.pkg, newDisplay: true)
                    model.toast(.ok, tr("Opening {name} in a Mac window…", ["name": app.label]))
                } else {
                    try await Core.shared.appLaunch(pkg: app.pkg)
                    model.toast(wantsMac ? .info : .ok, wantsMac ? tr("No ADB device. Opened {name} on your phone instead.", ["name": app.label])
                                                                  : tr("Opened {name} on your phone", ["name": app.label]))
                }
            } catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }
}

/// An `AppTile` that fetches its own icon lazily (only tiles scrolled into view ask the phone).
private struct PhoneAppTile: View {
    let app: PhoneApp
    let gate: FetchGate
    let launching: Bool
    let action: () -> Void
    @State private var icon: NSImage?

    var body: some View {
        AppTile(name: app.label, icon: icon, action: action)
            .overlay { if launching { ProgressView().controlSize(.small).padding(6).background(.regularMaterial, in: Circle()) } }
            .task(id: app.pkg) {
                if let hit = PhoneIconStore.shared.cached(app.pkg) { icon = hit; return }
                icon = await PhoneIconStore.shared.load(app.pkg)
            }
    }
}
