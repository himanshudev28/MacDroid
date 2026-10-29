import AppKit

/// NSCache wrapper that knows roughly how many bytes it holds (NSCache itself can't say), for Settings › Storage.
/// Thread-safe; eviction is observed through `NSCacheDelegate`.
final class TrackedCache<Key: AnyObject, Value: AnyObject>: NSObject, NSCacheDelegate, @unchecked Sendable {
    private let cache = NSCache<Key, Value>()
    private let lock = NSLock()
    private var costs: [ObjectIdentifier: Int] = [:]
    private var total = 0

    init(totalCostLimit: Int = 0, countLimit: Int = 0) {
        super.init()
        cache.totalCostLimit = totalCostLimit
        cache.countLimit = countLimit
        cache.delegate = self
        CacheCenter.register { [weak self] in self?.removeAll() } size: { [weak self] in self?.bytes ?? 0 }
    }

    var bytes: Int { lock.lock(); defer { lock.unlock() }; return total }
    func object(forKey key: Key) -> Value? { cache.object(forKey: key) }
    func setObject(_ obj: Value, forKey key: Key, cost: Int = 0) {
        lock.lock()
        // Replacing a key: NSCache doesn't necessarily call willEvictObject for the old value, so drop its cost here.
        if let old = cache.object(forKey: key), let c = costs.removeValue(forKey: ObjectIdentifier(old)) {
            total = max(0, total - c)
        }
        let id = ObjectIdentifier(obj)
        if let c = costs.updateValue(cost, forKey: id) { total = max(0, total - c) }
        total += cost
        lock.unlock()
        cache.setObject(obj, forKey: key, cost: cost)
    }
    func removeAll() {
        cache.removeAllObjects()
        lock.lock(); costs.removeAll(); total = 0; lock.unlock()
    }
    func cache(_ cache: NSCache<AnyObject, AnyObject>, willEvictObject obj: Any) {
        let o = obj as AnyObject
        lock.lock()
        if let c = costs.removeValue(forKey: ObjectIdentifier(o)) { total = max(0, total - c) }
        lock.unlock()
    }
}

/// One place that knows every in-memory image cache (ImageCache, PhotoStore thumbnails, app icons).
enum CacheCenter {
    private struct Entry { let clear: @Sendable () -> Void; let size: @Sendable () -> Int }
    nonisolated(unsafe) private static var entries: [Entry] = []
    private static let lock = NSLock()

    static func register(clear: @escaping @Sendable () -> Void, size: @escaping @Sendable () -> Int) {
        lock.lock(); entries.append(Entry(clear: clear, size: size)); lock.unlock()
    }
    private static var snapshot: [Entry] { lock.lock(); defer { lock.unlock() }; return entries }

    /// Estimated bytes held by decoded images (sum of the cost each cache was given).
    static var imageCacheBytes: Int { snapshot.reduce(0) { $0 + $1.size() } }

    @MainActor static func clearImageCaches() {
        for e in snapshot { e.clear() }
        ImageCache.shared.purge()
        PhotoStore.purgeAll()
    }
}
