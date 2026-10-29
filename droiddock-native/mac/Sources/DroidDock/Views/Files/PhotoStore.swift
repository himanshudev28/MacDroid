import AppKit
import Observation

/// Photo list paging + a bounded, cached thumbnail pipeline.
@Observable @MainActor
final class PhotoStore {
    static let pageSize = 300
    private(set) var items: [MediaItem] = []
    private(set) var loading = false
    private(set) var showSkeleton = false
    private(set) var loadedOnce = false
    private(set) var hasMore = true
    private(set) var error: String?

    @ObservationIgnored private let cache = TrackedCache<NSNumber, NSImage>(totalCostLimit: 48 * 1024 * 1024)
    @ObservationIgnored private let gate = FetchGate(limit: 3)
    @ObservationIgnored private var failed = Set<Int>()

    func reload() async {
        items = []; hasMore = true; error = nil; loadedOnce = false
        failed.removeAll()
        await loadMore()
    }

    func loadMore() async {
        guard !loading, hasMore else { return }
        loading = true
        let skeleton = Task { try? await Task.sleep(for: .milliseconds(300), tolerance: .milliseconds(100)); if !Task.isCancelled { showSkeleton = true } }
        defer { skeleton.cancel(); loading = false; showSkeleton = false; loadedOnce = true }
        do {
            let page = try await Core.shared.photosList(offset: items.count, limit: Self.pageSize)
            let known = Set(items.map(\.id))
            items += page.filter { !known.contains($0.id) }
            hasMore = page.count >= Self.pageSize
            error = nil
        } catch {
            if items.isEmpty { self.error = FileFormat.errorText(error.localizedDescription) }
            hasMore = false
        }
    }

    @ObservationIgnored private static let live = NSHashTable<PhotoStore>.weakObjects()
    init() { Self.live.add(self) }
    /// Main window closed to the menu bar: thumbnails reload on demand when it reopens.
    static func purgeAll() { for s in live.allObjects { s.cache.removeAll() } }
    func cached(_ item: MediaItem) -> NSImage? { cache.object(forKey: NSNumber(value: item.id)) }
    func didFail(_ item: MediaItem) -> Bool { failed.contains(item.id) }

    func thumb(_ item: MediaItem) async -> NSImage? {
        if let hit = cached(item) { return hit }
        await gate.acquire()
        defer { Task { await gate.release() } }
        if Task.isCancelled { return nil }
        if let hit = cached(item) { return hit }
        do {
            let url = try await Core.shared.photoThumb(id: item.id, kind: item.kind)
            // Base64 decode + ImageIO downsample off the main actor.
            let cg = await Task.detached(priority: .utility) { () -> CGImage? in
                guard let data = FileFormat.imageData(fromDataURL: url) else { return nil }
                return ImageCache.downsample(data, maxPixel: 360)
            }.value
            guard let cg else { throw CancellationError() }
            let img = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
            cache.setObject(img, forKey: NSNumber(value: item.id), cost: cg.width * cg.height * 4)
            return img
        } catch {
            if !Task.isCancelled { failed.insert(item.id) }
            return nil
        }
    }
}
