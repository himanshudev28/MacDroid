import Foundation
import Observation
import SwiftUI
import UniformTypeIdentifiers

extension FsEntry {
    var ddModified: Date { FileFormat.date(ms: modified) }
    var ddKind: String { FileFormat.kind(name: name, dir: dir) }
    var ddSize: Double { dir ? -1 : size }
}

/// A phone file offered to Finder: pulled when (and only when) a drop actually happens.
struct PhoneFileDrag: Transferable {
    let path: String
    let name: String
    let isDir: Bool

    struct DragError: LocalizedError { var errorDescription: String? { tr("Folders can't be dragged out yet. Open the folder and drag its files.") } }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .item) { item in
            if item.isDir { throw DragError() }
            let saved = try await Core.shared.fsPull(path: item.path, name: item.name)
            return SentTransferredFile(URL(fileURLWithPath: saved), allowAccessingOriginalFile: true)
        }
    }
}

/// Browsing state for the phone's storage.
@Observable @MainActor
final class PhoneFilesModel {
    static let root = "/sdcard"
    var path = PhoneFilesModel.root
    private(set) var entries: [FsEntry] = []
    private(set) var loading = false
    /// True only once a load has taken > 300 ms (UI-GUIDE §9).
    private(set) var showSkeleton = false
    private(set) var loadedOnce = false
    private(set) var error: String?
    private(set) var back: [String] = []
    private(set) var forward: [String] = []
    private(set) var pendingSyncs: Set<String> = []
    private var loadTask: Task<Void, Never>?

    var canGoUp: Bool { path != "/" && path.split(separator: "/").count > 1 }
    var crumbs: [(title: String, path: String)] {
        let parts = path.split(separator: "/").map(String.init)
        return parts.indices.map { i in
            let p = "/" + parts[0...i].joined(separator: "/")
            return (i == 0 && parts[0] == "sdcard" ? tr("Internal storage") : parts[i], p)
        }
    }

    func remote(_ name: String) -> String { path == "/" ? "/\(name)" : "\(path)/\(name)" }

    func navigate(to p: String, recordHistory: Bool = true) {
        guard p != path else { return }
        if recordHistory { back.append(path); forward.removeAll() }
        path = p
        reload()
    }
    func goBack() {
        guard let p = back.popLast() else { return }
        forward.append(path); path = p; reload()
    }
    func goForward() {
        guard let p = forward.popLast() else { return }
        back.append(path); path = p; reload()
    }
    func goUp() {
        guard canGoUp else { return }
        navigate(to: "/" + path.split(separator: "/").dropLast().joined(separator: "/"))
    }
    func open(_ e: FsEntry) { if e.dir { navigate(to: remote(e.name)) } }

    #if DEBUG
    static let debugEntries: [FsEntry] = {
        let now = Date().timeIntervalSince1970 * 1000
        return [FsEntry(name: "DCIM", dir: true, size: 0, modified: now), FsEntry(name: "Download", dir: true, size: 0, modified: now - 8.64e7),
                FsEntry(name: "Documents", dir: true, size: 0, modified: now - 3e8),
                FsEntry(name: "IMG_20260101_120000.jpg", dir: false, size: 3_400_000, modified: now - 1e8),
                FsEntry(name: "notes.pdf", dir: false, size: 180_000, modified: now - 5e8)]
    }()
    #endif

    func reload() {
        loadTask?.cancel()
        let target = path
        loading = true
        error = nil
        let skeleton = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300), tolerance: .milliseconds(100))
            if !Task.isCancelled { self?.showSkeleton = true }
        }
        loadTask = Task { [weak self] in
            defer { skeleton.cancel() }
            do {
                #if DEBUG
                let list = AppModel.shared.debugFake ? Self.debugEntries : try await Core.shared.fsList(path: target)
                #else
                let list = try await Core.shared.fsList(path: target)
                #endif
                guard let self, !Task.isCancelled, self.path == target else { return }
                self.entries = list
                self.error = nil
            } catch {
                guard let self, !Task.isCancelled, self.path == target else { return }
                self.entries = []
                self.error = FileFormat.errorText(error.localizedDescription)
            }
            guard let self, self.path == target else { return }
            self.loading = false; self.showSkeleton = false; self.loadedOnce = true
        }
        Task { [weak self] in
            if let p = try? await Core.shared.fsPendingSyncs() { self?.pendingSyncs = Set(p) }
        }
    }

    func reset() {
        loadTask?.cancel()
        entries = []; error = nil; loadedOnce = false; loading = false; showSkeleton = false
    }
}
