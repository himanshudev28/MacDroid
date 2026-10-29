import AppKit
import ImageIO

/// One decode per artwork/wallpaper: base64 data URL -> ImageIO thumbnail (downsampled), off the main actor, kept in a
/// bounded cost-limited NSCache shared by every consumer (phone card, widget, Media screen, Now Playing bridge).
@MainActor
final class ImageCache {
    static let shared = ImageCache()

    private let cache = TrackedCache<NSString, NSImage>(totalCostLimit: 24 * 1024 * 1024)
    private var inflight: [String: Task<CGImage?, Never>] = [:]

    /// `maxPixel` is the longest edge in pixels; part of the key so different sizes don't collide.
    func image(fromDataURL s: String, maxPixel: Int = 600) async -> NSImage? {
        let key = Self.key(s, maxPixel)
        if let hit = cache.object(forKey: key as NSString) { return hit }
        let t: Task<CGImage?, Never>
        if let running = inflight[key] { t = running } else {
            t = Task.detached(priority: .utility) { () -> CGImage? in
                guard let data = Self.bytes(s) else { return nil }
                return Self.downsample(data, maxPixel: maxPixel)
            }
            inflight[key] = t
        }
        let cg = await t.value
        inflight[key] = nil
        if let hit = cache.object(forKey: key as NSString) { return hit }
        guard let cg else { return nil }
        let img = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
        cache.setObject(img, forKey: key as NSString, cost: cg.width * cg.height * 4)
        return img
    }

    /// Cheap key: length plus 16 sampled 32-byte slices (hashing a multi-hundred-KB string on every lookup is not).
    private nonisolated static func key(_ s: String, _ maxPixel: Int) -> String {
        var h = Hasher()
        var str = s
        let n = str.utf8.count
        str.withUTF8 { b in
            if n <= 1024 { h.combine(bytes: UnsafeRawBufferPointer(b)) } else {
                let step = (n - 32) / 15
                for i in 0..<16 { h.combine(bytes: UnsafeRawBufferPointer(UnsafeBufferPointer(rebasing: b[(i * step)..<(i * step + 32)]))) }
            }
        }
        return "\(maxPixel)-\(n)-\(h.finalize())"
    }

    func purge() { cache.removeAll() }

    private nonisolated static func bytes(_ s: String) -> Data? {
        let payload: Substring
        if let r = s.range(of: "base64,") { payload = s[r.upperBound...] } else { payload = Substring(s) }
        return Data(base64Encoded: String(payload), options: .ignoreUnknownCharacters)
    }

    nonisolated static func downsample(_ data: Data, maxPixel: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }
}
