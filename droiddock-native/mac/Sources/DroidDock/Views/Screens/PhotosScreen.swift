import AppKit
import SwiftUI

/// Photos screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct PhotosScreen: View {
    @Environment(AppModel.self) private var model
    @State private var store = PhotoStore()
    @State private var reloadGate = ReloadGate()
    @State private var selection = Set<Int>()
    @State private var saving = false

    private var days: [(day: Date, items: [MediaItem])] {
        let cal = Calendar.current
        let groups = Dictionary(grouping: store.items) { cal.startOfDay(for: FileFormat.date(ms: $0.date)) }
        return groups.keys.sorted(by: >).map { ($0, groups[$0]!.sorted { $0.date > $1.date }) }
    }
    private var chosen: [MediaItem] { store.items.filter { selection.contains($0.id) } }

    var body: some View {
        Group {
            if !model.isConnected && !store.loadedOnce {
                EmptyState.disconnected(feature: tr("your photos"))
            } else if let err = store.error {
                EmptyState.failed(err) { Task { await store.reload() } }
            } else if !store.loadedOnce {
                if store.showSkeleton { skeletonGrid } else { Color.clear }
            } else if store.items.isEmpty {
                EmptyState(systemImage: "photo.on.rectangle", title: tr("No photos yet"),
                           message: tr("Photos and videos you take on your phone will show up here."))
            } else {
                VStack(spacing: 0) {
                    if let sync = syncStrip { sync }
                    grid
                }
                .opacity(model.isConnected ? 1 : 0.6).disabled(!model.isConnected)
            }
        }
        .task(id: model.isConnected) { if model.isConnected, await reloadGate.ready() { await store.reload() } }
        .toolbar {
            ToolbarItemGroup {
                Button { Task { await save(chosen) } } label: {
                    Label(selection.isEmpty ? tr("Save") : tr("Save {n}", ["n": selection.count]), systemImage: "square.and.arrow.down")
                }
                .disabled(selection.isEmpty || saving).help(tr("Save the selected items to Downloads"))
                Button { backUpNow() } label: { Label(tr("Back Up Now"), systemImage: "icloud.and.arrow.down") }
                    .disabled(!model.isConnected || model.config?.photoSyncEnabled != true)
                    .help(model.config?.photoSyncEnabled == true ? tr("Copy every photo on the phone to your Mac now") : tr("Turn on photo sync in Settings to back up automatically"))
                Button { selection = []; Task { await store.reload() } } label: { Label(tr("Refresh"), systemImage: "arrow.clockwise") }
                    .keyboardShortcut("r", modifiers: .command).help(tr("Refresh  ⌘R")).disabled(!model.isConnected)
            }
        }
        .onKeyPress(.escape) { selection = []; return .handled }
    }

    // MARK: Sync strip

    private var syncStrip: AnyView? {
        if let s = model.photoSync, s.total > 0, s.done < s.total {
            return AnyView(
                ProgressRow(name: tr("Backing up photos"), systemImage: "photo.stack.fill", incoming: true,
                            fraction: s.done / s.total,
                            detail: tr("{done} of {total} photos", ["done": Int(s.done), "total": Int(s.total)]) + (s.name.map { " · \($0)" } ?? ""),
                            state: .running)
                    .background(.bar).overlay(alignment: .bottom) { Divider() }
                    .transition(.move(edge: .top).combined(with: .opacity)))
        }
        if let e = model.photoSync?.error {
            return AnyView(HStack {
                Label(e, systemImage: "exclamationmark.triangle.fill").font(DD.Font.caption).foregroundStyle(DD.bad)
                Spacer()
            }.padding(.horizontal, DD.Space.content).padding(.vertical, 8).background(.bar))
        }
        return nil
    }

    // MARK: Grid

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 116, maximum: 170), spacing: 4)], spacing: 4, pinnedViews: .sectionHeaders) {
                ForEach(days, id: \.day) { group in
                    Section {
                        ForEach(group.items) { item in
                            PhotoTile(item: item, store: store, selected: selection.contains(item.id),
                                      onSelect: { extend in select(item, extend: extend) },
                                      onOpen: { open(item) },
                                      menu: { menu(for: item) })
                                .onAppear { if item.id == store.items.last?.id { Task { await store.loadMore() } } }
                        }
                    } header: { dayHeader(group.day, count: group.items.count) }
                }
            }
            .padding(.horizontal, DD.Space.content - 4).padding(.bottom, DD.Space.lg)
        }
        .contentShape(Rectangle())
        .onTapGesture { selection = [] }
        .onKeyPress(.return) { if let i = chosen.first { open(i) }; return .handled }
    }

    private func dayHeader(_ d: Date, count: Int) -> some View {
        let cal = Calendar.current
        let title = cal.isDateInToday(d) ? tr("Today") : cal.isDateInYesterday(d) ? tr("Yesterday")
            : d.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
        return HStack(alignment: .firstTextBaseline) {
            Text(title).font(DD.Font.headline).foregroundStyle(DD.fg)
            Text("\(count)").font(DD.Font.caption).monospacedDigit().foregroundStyle(DD.dim)
            Spacer()
        }
        .padding(.horizontal, 4).padding(.top, DD.Space.md).padding(.bottom, 6)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial)
    }

    private var skeletonGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 116, maximum: 170), spacing: 4)], spacing: 4) {
            ForEach(0..<24, id: \.self) { _ in
                RoundedRectangle(cornerRadius: DD.Radius.xs, style: .continuous).fill(DD.panel2).aspectRatio(1, contentMode: .fit)
            }
        }
        .modifier(Shimmer()).padding(DD.Space.content).accessibilityLabel(tr("Loading"))
        .frame(maxHeight: .infinity, alignment: .top)
    }

    // MARK: Actions

    private func select(_ item: MediaItem, extend: Bool) {
        if extend { if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) } }
        else { selection = [item.id] }
    }

    @ViewBuilder private func menu(for item: MediaItem) -> some View {
        let targets = selection.contains(item.id) ? chosen : [item]
        Button { open(item) } label: { Label(item.kind == "video" ? tr("Open Video") : tr("Open Photo"), systemImage: "arrow.up.forward.app") }
        Button { Task { await save(targets) } } label: {
            Label(targets.count > 1 ? tr("Save {n} Items to Downloads", ["n": targets.count]) : tr("Save to Downloads"), systemImage: "square.and.arrow.down")
        }
        Button {
            Task {
                var paths: [String] = []
                for t in targets { if let p = try? await Core.shared.fsPull(path: t.path, name: t.name) { paths.append(p) } }
                if !paths.isEmpty { QuickShareSendSheet.present(paths: paths) }
            }
        } label: { Label(tr("Share with Quick Share…"), systemImage: "dot.radiowaves.left.and.right") }
        if selection.isEmpty || !selection.contains(item.id) {
            Divider()
            Button { selection = [item.id] } label: { Label(tr("Select"), systemImage: "checkmark.circle") }
        }
    }

    private func backUpNow() {
        model.toast(.info, tr("Backing up photos…"))
        Task { await model.run(tr("Couldn't start the backup")) { try await Core.shared.photoSyncBackfill() } }
    }

    private func open(_ item: MediaItem) {
        Task {
            do { try await Core.shared.photoOpen(path: item.path, name: item.name) }
            catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }

    private func save(_ items: [MediaItem]) async {
        saving = true; defer { saving = false }
        var saved: [String] = []
        for i in items {
            do { saved.append(try await Core.shared.fsPull(path: i.path, name: i.name)) }
            catch { model.toast(.bad, "\(i.name): \(FileFormat.errorText(error.localizedDescription))") }
        }
        if let first = saved.first {
            model.toast(.ok, tn("Saved {n} item to Downloads", "Saved {n} items to Downloads", saved.count))
            Haptics.generic()
            if saved.count > 0 { Opener.reveal(path: first) }
        }
    }
}

private struct PhotoTile<Menu: View>: View {
    let item: MediaItem
    let store: PhotoStore
    let selected: Bool
    var onSelect: (Bool) -> Void
    var onOpen: () -> Void
    @ViewBuilder var menu: () -> Menu

    @State private var image: NSImage?
    @State private var hovering = false
    private let accent = Color.accentColor

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: DD.Radius.xs, style: .continuous)
        // A square cell sized by the grid, NOT by the image: a scaledToFill image reports its own size, which made
        // tall/wide photos overflow their cell and overlap the neighbours.
        DD.panel2
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(nsImage: image).resizable().scaledToFill().transition(.opacity)
                } else if store.didFail(item) {
                    Image(systemName: item.kind == "video" ? "film" : "photo").foregroundStyle(DD.faint).font(.system(size: 20))
                }
            }
            .clipped()
        .overlay(alignment: .bottomLeading) {
            if item.kind == "video" {
                HStack(spacing: 3) {
                    Image(systemName: "play.fill").font(.system(size: 10))
                    if let d = item.duration, d > 0 { Text(FileFormat.duration(d)).monospacedDigit() }
                }
                .font(.system(size: 10, weight: .semibold)).foregroundStyle(DD.OnCard.primary)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(.ultraThinMaterial).environment(\.colorScheme, .dark)).padding(5)
            }
        }
        .overlay(alignment: .topTrailing) {
            if selected {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 18))
                    .symbolRenderingMode(.palette).foregroundStyle(DD.accentInk, accent).padding(5)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .overlay { shape.fill(DD.hover.opacity(hovering && !selected ? 1 : 0)) }
        .overlay { shape.strokeBorder(accent, lineWidth: selected ? 3 : 0) }
        .clipShape(shape)
        .contentShape(shape)
        .onHover { hovering = $0 }
        .onTapGesture(count: 2) { onOpen() }
        .onTapGesture { onSelect(NSEvent.modifierFlags.contains(.command) || NSEvent.modifierFlags.contains(.shift)) }
        .contextMenu { menu() }
        .ddAnimation(Motion.select, value: selected)
        .task(id: item.id) {
            if let hit = store.cached(item) { image = hit; return }
            let img = await store.thumb(item)
            if let img { withDDAnimation(Motion.content) { image = img } }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.name)
        .accessibilityValue(item.kind == "video" ? tr("Video") : tr("Photo"))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(named: tr("Open")) { onOpen() }
    }
}
