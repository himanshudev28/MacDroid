import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Finder-like browser for the phone's storage. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct FilesScreen: View {
    @Environment(AppModel.self) private var model
    private let accent = Color.accentColor

    @State private var files = PhoneFilesModel()
    @State private var selection = Set<String>()
    @State private var sortOrder = [KeyPathComparator(\FsEntry.name, comparator: .localizedStandard)]
    @State private var asGrid = false
    @State private var query = ""
    @State private var dropTargeted = false
    @State private var renaming: FsEntry?
    @State private var renameText = ""
    @State private var deleting: [FsEntry] = []
    @State private var showShare = false
    @State private var sharePaths: [String] = []
    @State private var busy = Set<String>()
    @State private var webdavBusy = false
    @State private var creatingFolder = false
    @State private var newFolderName = ""
    @FocusState private var newFolderFocused: Bool
    private static let newFolderID = "\u{1}new-folder"

    /// Filtered + sorted rows, cached: `body` reads this several times and re-sorting each time is wasted work.
    @State private var sortedCache: [FsEntry]?
    private var sorted: [FsEntry] { sortedCache ?? computeSorted() }
    private func computeSorted() -> [FsEntry] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let base = q.isEmpty ? files.entries : files.entries.filter { $0.name.lowercased().contains(q) }
        let s = base.sorted(using: sortOrder)
        let placeholder = creatingFolder ? [FsEntry(name: Self.newFolderID, dir: true, size: 0, modified: Date().timeIntervalSince1970 * 1000)] : []
        return placeholder + s.filter(\.dir) + s.filter { !$0.dir }
    }
    private func entries(for ids: Set<String>) -> [FsEntry] { files.entries.filter { ids.contains($0.name) } }
    private var activeTransfers: [TransferProgress] {
        model.transferProgress.values.filter { $0.dir != "phone" }.sorted { $0.transferId < $1.transferId }
    }

    var body: some View {
        Group {
            if !model.isConnected && !files.loadedOnce {
                EmptyState.disconnected(feature: tr("your phone's files"))
            } else {
                content
            }
        }
        .onAppear { sortedCache = computeSorted() }
        .onChange(of: files.entries) { _, _ in sortedCache = computeSorted() }
        .onChange(of: query) { _, _ in sortedCache = computeSorted() }
        .onChange(of: creatingFolder) { _, _ in sortedCache = computeSorted() }
        .onChange(of: sortOrder) { _, _ in sortedCache = computeSorted() }
        .task(id: model.isConnected) {
            if model.isConnected { files.reload() } else if !files.loadedOnce { files.reset() }
        }
        .searchable(text: $query, prompt: tr("Filter this folder"))
        .toolbar { toolbar }
        .sheet(isPresented: $showShare) { QuickShareSendSheet(paths: sharePaths) }
        .alert(tr("Rename"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(tr("Name"), text: $renameText)
            Button(tr("Cancel"), role: .cancel) { renaming = nil }
            Button(tr("Rename")) { commitRename() }
        }
        .confirmationDialog(deleteTitle, isPresented: Binding(get: { !deleting.isEmpty }, set: { if !$0 { deleting = [] } }),
                            titleVisibility: .visible) {
            Button(tr("Delete from Phone"), role: .destructive) { performDelete() }
            Button(tr("Cancel"), role: .cancel) { deleting = [] }
        } message: { Text(tr("This can't be undone.")) }
    }

    // MARK: Content

    private var content: some View {
        VStack(spacing: 0) {
            pathBar
            Divider()
            ZStack {
                listing
                    .opacity(model.isConnected ? 1 : 0.6)
                    .disabled(!model.isConnected)
                    .ddAnimation(Motion.content, value: asGrid)           // list ⇄ grid cross-fade
                    .ddAnimation(Motion.content, value: files.loadedOnce)  // skeleton → content fade-in
                if dropTargeted { dropOverlay }
            }
            if !activeTransfers.isEmpty { transferStrip }
        }
        .dropDestination(for: URL.self) { urls, _ in
            upload(urls.filter(\.isFileURL).map(\.path)); return !urls.isEmpty
        } isTargeted: { dropTargeted = $0 }
        .ddAnimation(Motion.content, value: dropTargeted)
    }

    @ViewBuilder private var listing: some View {
        if let err = files.error {
            EmptyState.failed(err) { files.reload() }
        } else if !files.loadedOnce || (files.loading && files.entries.isEmpty && files.showSkeleton) {
            if files.showSkeleton { LoadingState(rows: 8, avatar: true).padding(.top, DD.Space.sm) } else { Color.clear }
        } else if files.entries.isEmpty && !creatingFolder {
            EmptyState(systemImage: "folder", title: tr("This folder is empty"),
                       message: tr("Drop files here, or send some from your Mac."),
                       actionTitle: tr("Send to Phone…"), action: pickAndUpload)
        } else if sorted.isEmpty && !creatingFolder {
            EmptyState(systemImage: "magnifyingglass", title: tr("No matches"),
                       message: tr("Nothing in this folder matches “{q}”.", ["q": query]))
        } else if asGrid {
            gridView
        } else {
            tableView
        }
    }

    private var pathBar: some View {
        HStack(spacing: DD.Space.xs) {
            ForEach(Array(files.crumbs.enumerated()), id: \.offset) { i, c in
                if i > 0 {
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(DD.faint).flipsForRightToLeftLayoutDirection(true).accessibilityHidden(true)
                }
                let last = i == files.crumbs.count - 1
                Button { files.navigate(to: c.path); selection = [] } label: {
                    Text(c.title).font(last ? DD.Font.headline : DD.Font.body)
                        .foregroundStyle(last ? DD.fg : DD.dim).lineLimit(1)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                }
                .buttonStyle(.plain).allowsHitTesting(!last)
                .accessibilityLabel(c.title)
            }
            Spacer(minLength: DD.Space.sm)
            if files.loading && files.loadedOnce { ProgressView().controlSize(.small).transition(.opacity) }
            Text(tn("{n} item", "{n} items", files.entries.count)).font(DD.Font.caption).monospacedDigit()
                .foregroundStyle(DD.dim)
                .contentTransition(.numericText(value: Double(files.entries.count)))
                .ddAnimation(Motion.content, value: files.entries.count)
            Picker(tr("View"), selection: $asGrid) {
                Label(tr("List"), systemImage: "list.bullet").tag(false)
                Label(tr("Grid"), systemImage: "square.grid.2x2").tag(true)
            }
            .pickerStyle(.segmented).labelStyle(.iconOnly).labelsHidden().controlSize(.small).fixedSize()
            .help(tr("View as list or grid"))
            moreMenu
        }
        .padding(.horizontal, DD.Space.content)
        .frame(height: 38)
    }

    private var moreMenu: some View {
        Menu {
            Button { files.reload() } label: { Label(tr("Refresh"), systemImage: "arrow.clockwise") }
                .keyboardShortcut("r", modifiers: .command)
            Button { files.goUp(); selection = [] } label: { Label(tr("Enclosing Folder"), systemImage: "chevron.up") }
                .disabled(!files.canGoUp).keyboardShortcut(.upArrow, modifiers: .command)
            Button { sharePaths = []; showShare = true } label: { Label(tr("Share with Quick Share…"), systemImage: "dot.radiowaves.left.and.right") }
                .disabled(!model.isConnected)
            Divider()
            Button { Opener.reveal(path: downloadsPath) } label: { Label(tr("Show Downloads in Finder"), systemImage: "folder") }
            Divider()
            Button { mountInFinder() } label: { Label(tr("Mount Phone in Finder"), systemImage: "externaldrive.connected.to.line.below") }
                .disabled(webdavBusy || !model.isConnected)
        } label: { Image(systemName: "ellipsis.circle").symbolRenderingMode(.hierarchical) }
        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
        .help(tr("More"))
        .accessibilityLabel(tr("More"))
    }

    // MARK: Table

    private var tableView: some View {
        Table(of: FsEntry.self, selection: $selection, sortOrder: $sortOrder) {
            TableColumn(tr("Name"), value: \.name, comparator: .localizedStandard) { e in nameCell(e) }
                .width(min: 140, ideal: 170)
            TableColumn(tr("Size"), value: \.ddSize) { e in
                Text(e.dir ? "—" : FileFormat.bytes(e.size)).monospacedDigit().foregroundStyle(DD.dim)
            }.width(min: 60, ideal: 72, max: 110)
            TableColumn(tr("Date Modified"), value: \.ddModified) { e in
                Text(Self.dateText(e.ddModified)).monospacedDigit().foregroundStyle(DD.dim)
            }.width(min: 112, ideal: 136, max: 200)
            TableColumn(tr("Kind"), value: \.ddKind) { e in Text(e.ddKind).foregroundStyle(DD.dim) }
                .width(min: 84, ideal: 100, max: 180)
        } rows: {
            ForEach(sorted) { e in
                TableRow(e).draggable(PhoneFileDrag(path: files.remote(e.name), name: e.name, isDir: e.dir))
            }
        }
        .contextMenu(forSelectionType: String.self) { ids in menu(for: entries(for: ids)) } primaryAction: { ids in
            if let e = entries(for: ids).first { activate(e) }
        }
        .scrollContentBackground(.hidden)
        .alternatingRowBackgrounds(.disabled)
        .onKeyPress(.return) { primaryAction(); return .handled }
        .onKeyPress(.space) { previewSelection(); return .handled }
        .onDeleteCommand { requestDelete(entries(for: selection)) }
        .animation(nil, value: selection)
    }

    private static func dateText(_ d: Date) -> String {
        Calendar.current.isDate(d, equalTo: .now, toGranularity: .year)
            ? d.formatted(.dateTime.day().month(.abbreviated).hour().minute())
            : d.formatted(.dateTime.day().month(.abbreviated).year())
    }

    private func newFolderField(width: CGFloat? = nil) -> some View {
        TextField(tr("Folder name"), text: $newFolderName)
            .textFieldStyle(.roundedBorder).focused($newFolderFocused)
            .onSubmit { commitNewFolder() }
            .onExitCommand { cancelNewFolder() }
            .onChange(of: newFolderFocused) { _, f in if !f && creatingFolder { commitNewFolder() } }
            .frame(width: width)
            .onAppear { newFolderFocused = true }
    }

    @ViewBuilder private func nameCell(_ e: FsEntry) -> some View {
        if e.name == Self.newFolderID {
            HStack(spacing: 8) {
                Image(systemName: "folder").symbolRenderingMode(.hierarchical).foregroundStyle(accent).font(.system(size: 15)).frame(width: 20)
                newFolderField()
            }
        } else { nameRow(e) }
    }

    private func nameRow(_ e: FsEntry) -> some View {
        HStack(spacing: 8) {
            Image(systemName: FileFormat.symbol(name: e.name, dir: e.dir))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(FileFormat.tint(name: e.name, dir: e.dir, accent: accent))
                .font(.system(size: 15)).frame(width: 20)
            Text(e.name).lineLimit(1).truncationMode(.middle).foregroundStyle(DD.fg)
            if !e.dir, isPending(e) {
                Image(systemName: "arrow.triangle.2.circlepath").font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(accent)
                    .help(syncing(e) ? tr("Syncing your edits back to the phone…") : tr("Edited on Mac. Waiting to sync back to the phone."))
                    .accessibilityLabel(tr("Waiting to sync"))
            }
            if busy.contains(e.name) { ProgressView().controlSize(.mini) }
        }
    }

    private func isPending(_ e: FsEntry) -> Bool {
        let p = files.remote(e.name)
        return model.editSync[p] != nil || files.pendingSyncs.contains(p)
    }
    private func syncing(_ e: FsEntry) -> Bool { model.editSync[files.remote(e.name)]?.status == "syncing" }

    // MARK: Grid

    private var gridView: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 130), spacing: DD.Space.sm)], spacing: DD.Space.sm) {
                ForEach(sorted) { e in
                    tile(e)
                }
            }
            .padding(DD.Space.content)
        }
        .contentShape(Rectangle())
        .onTapGesture { selection = [] }
        .onKeyPress(.return) { primaryAction(); return .handled }
        .onKeyPress(.space) { previewSelection(); return .handled }
        .onDeleteCommand { requestDelete(entries(for: selection)) }
        .focusable()
    }

    private func tile(_ e: FsEntry) -> some View {
        if e.name == Self.newFolderID {
            return AnyView(VStack(spacing: 6) {
                Image(systemName: "folder").font(.system(size: 38)).symbolRenderingMode(.hierarchical).foregroundStyle(accent).frame(height: 48)
                newFolderField()
            }.frame(maxWidth: .infinity).padding(.vertical, 10).padding(.horizontal, 4))
        }
        return AnyView(fileTile(e))
    }

    private func fileTile(_ e: FsEntry) -> some View {
        let selected = selection.contains(e.name)
        return VStack(spacing: 6) {
            Image(systemName: FileFormat.symbol(name: e.name, dir: e.dir))
                .font(.system(size: 38, weight: .regular)).symbolRenderingMode(.hierarchical)
                .foregroundStyle(FileFormat.tint(name: e.name, dir: e.dir, accent: accent))
                .frame(height: 48)
            Text(e.name).font(DD.Font.caption).lineLimit(2).multilineTextAlignment(.center)
                .truncationMode(.middle).foregroundStyle(DD.fg)
                .padding(.horizontal, 5).padding(.vertical, 1)
                .background(RoundedRectangle(cornerRadius: 4, style: .continuous).fill(selected ? DD.selection : .clear))
                .foregroundStyle(selected ? DD.accentInk : DD.fg)
            Text(e.dir ? tr("Folder") : FileFormat.bytes(e.size)).font(DD.Font.footnote).foregroundStyle(DD.dim)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 10).padding(.horizontal, 4)
        .background(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous).fill(selected ? DD.selectionInactive.opacity(0.55) : .clear))
        .contentShape(RoundedRectangle(cornerRadius: DD.Radius.md, style: .continuous))
        .onTapGesture(count: 2) { activate(e) }
        .onTapGesture {
            if NSEvent.modifierFlags.contains(.command) {
                if selection.contains(e.name) { selection.remove(e.name) } else { selection.insert(e.name) }
            } else { selection = [e.name] }
        }
        .draggable(PhoneFileDrag(path: files.remote(e.name), name: e.name, isDir: e.dir))
        .contextMenu { menu(for: selected ? entries(for: selection) : [e]) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityAction(named: tr("Open")) { activate(e) }
    }

    // MARK: Menus + toolbar

    @ViewBuilder private func menu(for items: [FsEntry]) -> some View {
        if items.isEmpty {
            Button { startNewFolder() } label: { Label(tr("New Folder"), systemImage: "folder.badge.plus") }
                .disabled(!model.isConnected)
        }
        if let one = items.first, items.count == 1 {
            if one.dir {
                Button { files.open(one) } label: { Label(tr("Open"), systemImage: "folder") }
            } else {
                Button { openInPlace(one) } label: { Label(tr("Open on Mac"), systemImage: "arrow.up.forward.app") }
                Button { preview([one]) } label: { Label(tr("Quick Look"), systemImage: "eye") }
                Button { Task { await download([one], reveal: true) } } label: { Label(tr("Save to Downloads"), systemImage: "arrow.down.circle") }
            }
            Divider()
            Button { startRename(one) } label: { Label(tr("Rename…"), systemImage: "pencil") }
        } else if !items.isEmpty {
            Button { Task { await download(items.filter { !$0.dir }, reveal: true) } } label: {
                Label(tr("Save {n} Items to Downloads", ["n": items.filter { !$0.dir }.count]), systemImage: "arrow.down.circle")
            }
        }
        if !items.isEmpty {
            let fileItems = items.filter { !$0.dir }
            if !fileItems.isEmpty {
                Button { Task { await shareToNearby(fileItems) } } label: { Label(tr("Share with Quick Share…"), systemImage: "dot.radiowaves.left.and.right") }
            }
            Divider()
            Button(role: .destructive) { requestDelete(items) } label: { Label(tr("Delete…"), systemImage: "trash") }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigation) {
            Button { files.goBack(); selection = [] } label: { Label(tr("Back"), systemImage: "chevron.backward") }
                .disabled(files.back.isEmpty).keyboardShortcut("[", modifiers: .command).help(tr("Back  ⌘["))
            Button { files.goForward(); selection = [] } label: { Label(tr("Forward"), systemImage: "chevron.forward") }
                .disabled(files.forward.isEmpty).keyboardShortcut("]", modifiers: .command).help(tr("Forward  ⌘]"))
        }
        ToolbarItemGroup {
            Button(action: startNewFolder) { Label(tr("New Folder"), systemImage: "folder.badge.plus") }
                .keyboardShortcut("n", modifiers: [.command, .shift]).help(tr("New folder  ⇧⌘N")).disabled(!model.isConnected)
            Button(action: pickAndUpload) { Label(tr("Send to Phone"), systemImage: "arrow.up.doc") }
                .keyboardShortcut("u", modifiers: .command).help(tr("Send files to this folder  ⌘U")).disabled(!model.isConnected)
        }
    }

    // MARK: Overlays

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous)
            .strokeBorder(accent, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(accent.opacity(0.07)))
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "arrow.down.doc.fill").font(.system(size: 28)).symbolRenderingMode(.hierarchical)
                    Text(tr("Drop to send to {folder}", ["folder": files.crumbs.last?.title ?? "/"])).font(DD.Font.headline)
                }
                .foregroundStyle(accent).padding(DD.Space.lg)
                .glassBackground(radius: DD.Radius.lg, elevation: .floating)
            }
            .padding(DD.Space.md).allowsHitTesting(false)
            .transition(.opacity)
    }

    private var transferStrip: some View {
        VStack(spacing: 0) {
            Divider()
            ForEach(activeTransfers, id: \.transferId) { p in
                ProgressRow(name: p.name, systemImage: FileFormat.symbol(name: p.name, dir: false), incoming: p.dir == "pull",
                            fraction: p.total > 0 ? p.sent / p.total : 0,
                            detail: tr("{done} of {total}", ["done": FileFormat.bytes(p.sent), "total": FileFormat.bytes(p.total)]),
                            state: .running) {
                    IconButton(systemImage: "xmark", label: tr("Cancel transfer"), size: 24) {
                        Task { await model.run(tr("Couldn't cancel the transfer")) { try await Core.shared.fsCancel(transferId: p.transferId) } }
                    }
                }
            }
        }
        .background(.bar)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    // MARK: Actions

    private var downloadsPath: String { FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first?.path ?? NSHomeDirectory() }
    private var deleteTitle: String {
        deleting.count == 1 ? tr("Delete “{name}” from your phone?", ["name": deleting[0].name])
                            : tr("Delete {n} items from your phone?", ["n": deleting.count])
    }

    private func activate(_ e: FsEntry) {
        if e.dir { files.open(e); selection = [] } else { openInPlace(e) }
    }
    private func primaryAction() {
        if let e = entries(for: selection).first { activate(e) }
    }
    private func previewSelection() { preview(entries(for: selection).filter { !$0.dir }) }

    private func openInPlace(_ e: FsEntry) {
        let p = files.remote(e.name)
        Task {
            do {
                try await Core.shared.fsOpenInPlace(path: p)
                model.toast(.ok, tr("Opening {name}. Edits sync back automatically.", ["name": e.name]))
            } catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }

    private func preview(_ items: [FsEntry]) {
        guard !items.isEmpty else { return }
        Task {
            var urls: [URL] = []
            for e in items {
                busy.insert(e.name)
                if let saved = try? await Core.shared.fsPull(path: files.remote(e.name), name: e.name) { urls.append(URL(fileURLWithPath: saved)) }
                busy.remove(e.name)
            }
            if urls.isEmpty { model.toast(.bad, tr("Couldn't download the file to preview it.")) }
            else { QuickLookPresenter.shared.show(urls) }
        }
    }

    private func download(_ items: [FsEntry], reveal: Bool) async {
        var saved: [String] = []
        for e in items {
            busy.insert(e.name)
            do { saved.append(try await Core.shared.fsPull(path: files.remote(e.name), name: e.name)) }
            catch { model.toast(.bad, "\(e.name): \(FileFormat.errorText(error.localizedDescription))") }
            busy.remove(e.name)
        }
        if let first = saved.first {
            model.toast(.ok, tn("Saved {n} file to Downloads", "Saved {n} files to Downloads", saved.count))
            if reveal { Opener.reveal(path: first) }
        }
    }

    private func shareToNearby(_ items: [FsEntry]) async {
        var paths: [String] = []
        for e in items {
            busy.insert(e.name)
            if let p = try? await Core.shared.fsPull(path: files.remote(e.name), name: e.name) { paths.append(p) }
            busy.remove(e.name)
        }
        guard !paths.isEmpty else { model.toast(.bad, tr("Couldn't download the files to share.")); return }
        sharePaths = paths; showShare = true
    }

    private func pickAndUpload() {
        Task {
            let urls = await FilePicker.open(files: true, directories: true, multiple: true, title: tr("Send to Phone"), prompt: tr("Send"))
            upload(urls.map(\.path))
        }
    }

    private func upload(_ paths: [String]) {
        guard model.isConnected, !paths.isEmpty else { return }
        let dest = files.path
        Task {
            for p in paths {
                model.notePush(path: p, dest: dest)
                do { try await Core.shared.fsPush(localPath: p, dest: dest) }
                catch { model.toast(.bad, "\((p as NSString).lastPathComponent): \(FileFormat.errorText(error.localizedDescription))") }
            }
            if files.path == dest { files.reload() }
            Haptics.generic()
        }
    }

    private func startNewFolder() {
        guard model.isConnected else { return }
        newFolderName = tr("untitled folder")
        creatingFolder = true
        selection = []
    }
    private func cancelNewFolder() { creatingFolder = false; newFolderName = "" }
    private func commitNewFolder() {
        guard creatingFolder else { return }
        creatingFolder = false
        let name = newFolderName.trimmingCharacters(in: .whitespaces)
        newFolderName = ""
        guard !name.isEmpty else { return }
        let parent = files.path
        Task {
            do {
                try await Core.shared.fsMkdir(path: parent, name: name)
                if files.path == parent { files.reload(); selection = [name] }
            } catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }

    private func startRename(_ e: FsEntry) { renameText = e.name; renaming = e }
    private func commitRename() {
        guard let e = renaming else { return }
        renaming = nil
        let next = renameText.trimmingCharacters(in: .whitespaces)
        guard !next.isEmpty, next != e.name else { return }
        Task {
            do { try await Core.shared.fsRename(path: files.remote(e.name), newName: next); selection = [next]; files.reload() }
            catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }

    private func requestDelete(_ items: [FsEntry]) { if !items.isEmpty { deleting = items } }
    private func performDelete() {
        let items = deleting; deleting = []
        Task {
            for e in items {
                do { try await Core.shared.fsDelete(path: files.remote(e.name)) }
                catch { model.toast(.bad, "\(e.name): \(FileFormat.errorText(error.localizedDescription))") }
            }
            selection = []; files.reload()
        }
    }

    private func mountInFinder() {
        webdavBusy = true
        Task {
            defer { webdavBusy = false }
            do {
                let st = try await Core.shared.webdavStart()
                if let m = st.mountPoint { Opener.open(path: m) }
                else if let u = st.url { Opener.open(u) }
                model.toast(.ok, tr("Phone mounted. Look for it in Finder."))
            } catch { model.toast(.bad, FileFormat.errorText(error.localizedDescription)) }
        }
    }
}
