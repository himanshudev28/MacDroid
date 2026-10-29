import SwiftUI

/// Settings › General › Storage: what DroidDock keeps on this Mac, with a size and a Clear button for each.
struct StorageSection: View {
    @Environment(AppModel.self) private var model
    @State private var info = CacheInfo()
    @State private var imageBytes = 0
    @State private var logsBytes: Int64 = 0
    @State private var busy = false
    @State private var confirmAll = false
    @State private var confirmTools = false

    private func size(_ b: Int64) -> String {
        let f = ByteCountFormatter(); f.countStyle = .file; f.allowsNonnumericFormatting = false
        return f.string(fromByteCount: b)
    }
    private var allBytes: Int64 { Int64(imageBytes) + max(0, info.editCacheBytes) + logsBytes }

    var body: some View {
        DDSection(tr("Storage"), footer: tr("Pairing, settings, transfer history and anything in your Downloads or Pictures are never touched.")) {
            row(tr("Image cache"), detail: tr("Thumbnails, album art and app icons held in memory."), bytes: Int64(imageBytes)) {
                run(kinds: [], images: true, logs: false)
            }
            row(tr("Opened phone files"), detail: openedDetail, bytes: info.editCacheBytes) {
                run(kinds: ["editCache"], images: false, logs: false)
            }
            row(tr("Logs & crash reports"), detail: tr("Saved in ~/Library/Logs/DroidDock."), bytes: logsBytes) {
                run(kinds: [], images: false, logs: true)
            }
            LabeledContent {
                Button(tr("Clear All…")) { confirmAll = true }.disabled(busy || allBytes == 0)
            } label: {
                Text(tr("Everything above")).foregroundStyle(DD.dim)
            }
            LabeledContent {
                HStack(spacing: DD.Space.sm) {
                    Text(size(info.toolsBytes)).monospacedDigit().foregroundStyle(DD.dim)
                    Button(tr("Clear")) { confirmTools = true }.disabled(busy || info.toolsBytes == 0)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Downloaded tools (ADB)"))
                    Text(tr("Will be downloaded again next time ADB is needed. Not part of Clear All.")).font(DD.Font.caption).foregroundStyle(DD.dim)
                }
            }
        }
        .task { await refresh() }
        .confirmationDialog(tr("Clear all caches?"), isPresented: $confirmAll, titleVisibility: .visible) {
            Button(tr("Clear {size}", ["size": size(allBytes)]), role: .destructive) { run(kinds: ["editCache"], images: true, logs: true) }
            Button(tr("Cancel"), role: .cancel) {}
        } message: {
            Text(tr("This clears the image cache, opened phone files (edits still syncing back are kept) and logs & crash reports."))
        }
        .confirmationDialog(tr("Clear downloaded tools?"), isPresented: $confirmTools, titleVisibility: .visible) {
            Button(tr("Clear ADB tools"), role: .destructive) { run(kinds: ["tools"], images: false, logs: false) }
            Button(tr("Cancel"), role: .cancel) {}
        } message: {
            Text(tr("ADB will be downloaded again next time it is needed. This can't be done while a phone is connected over ADB or mirroring."))
        }
    }

    private var openedDetail: String {
        info.pendingSyncs > 0
            ? tn("{n} file still syncing back — kept", "{n} files still syncing back — kept", info.pendingSyncs)
            : tr("Copies of phone files you opened on the Mac.")
    }

    private func row(_ title: String, detail: String, bytes: Int64, clear: @escaping () -> Void) -> some View {
        LabeledContent {
            HStack(spacing: DD.Space.sm) {
                Text(size(bytes)).monospacedDigit().foregroundStyle(DD.dim)
                Button(tr("Clear"), action: clear).disabled(busy || bytes == 0)
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(DD.Font.caption).foregroundStyle(info.pendingSyncs > 0 && title == tr("Opened phone files") ? DD.warn : DD.dim)
            }
        }
    }

    private func refresh() async {
        if let i = try? await Core.shared.cacheInfo() { info = i }
        imageBytes = CacheCenter.imageCacheBytes
        logsBytes = await Task.detached { Self.folderBytes(CrashReporter.directory) }.value
    }

    private func run(kinds: [String], images: Bool, logs: Bool) {
        busy = true
        Task {
            defer { busy = false }
            var freed: Int64 = 0
            var kept = 0
            if images { freed += Int64(CacheCenter.imageCacheBytes); CacheCenter.clearImageCaches() }
            if logs {
                let dir = CrashReporter.directory
                freed += await Task.detached { () -> Int64 in
                    let b = Self.folderBytes(dir)
                    let fm = FileManager.default
                    for u in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] { try? fm.removeItem(at: u) }
                    return b
                }.value
            }
            if !kinds.isEmpty {
                do {
                    let r = try await Core.shared.cacheClear(kinds: kinds)
                    freed += r.freedBytes; kept = r.keptPending
                } catch {
                    model.toast(.bad, error.localizedDescription)
                }
            }
            await refresh()
            model.toast(.ok, tr("Cleared {size}", ["size": size(freed)]) + (kept > 0 ? " · " + tn("{n} file still syncing back — kept", "{n} files still syncing back — kept", kept) : ""))
        }
    }

    private nonisolated static func folderBytes(_ dir: URL) -> Int64 {
        guard let en = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) else { return 0 }
        var total: Int64 = 0
        for case let u as URL in en {
            let v = try? u.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if v?.isRegularFile == true { total += Int64(v?.fileSize ?? 0) }
        }
        return total
    }
}
