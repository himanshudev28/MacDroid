import AppKit
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/// The one loader for Android app icons (notification rows, menu bar panel, dashboard, recents).
///
/// Lookup order: memory (cost-capped) -> disk (`Caches/DroidDock/app-icons/<pkg>.png`, so icons are there on the
/// next launch before the phone has connected) -> the phone (`app_icon`, at most 3 in flight). Concurrent requests
/// for one package share a single task. A failed fetch is retried after a minute, never on every row redraw.
@MainActor
final class PhoneIconStore {
    static let shared = PhoneIconStore()

    private let mem = TrackedCache<NSString, NSImage>(totalCostLimit: 6 * 1024 * 1024, countLimit: 300)
    private struct Box: @unchecked Sendable { var img: NSImage? }
    private var inflight: [String: Task<Box, Never>] = [:]
    private var failedAt: [String: Date] = [:]
    private let gate = FetchGate(limit: 3)
    private nonisolated static let pixels = 128

    /// Disk cache folder. Under `DD_DATA_DIR` when set (QA runs never touch the user's real cache).
    private nonisolated static let dir: URL = {
        let base: URL
        if let d = ProcessInfo.processInfo.environment["DD_DATA_DIR"], !d.isEmpty {
            base = URL(fileURLWithPath: d).appendingPathComponent("cache")
        } else {
            base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("DroidDock")
        }
        return base.appendingPathComponent("app-icons", isDirectory: true)
    }()

    private nonisolated static func file(_ pkg: String) -> URL {
        let safe = String(pkg.unicodeScalars.map { c in
            CharacterSet.alphanumerics.contains(c) || c == "." || c == "_" || c == "-" ? Character(c) : "_"
        })
        return dir.appendingPathComponent(safe.prefix(120) + ".png")
    }

    func cached(_ pkg: String) -> NSImage? { mem.object(forKey: pkg as NSString) }

    func load(_ pkg: String) async -> NSImage? {
        if let hit = cached(pkg) { return hit }
        if let t = failedAt[pkg], Date().timeIntervalSince(t) < 60 { return nil }
        if let running = inflight[pkg] { return await running.value.img }
        let task = Task { [weak self] () -> Box in Box(img: await self?.fetch(pkg)) }
        inflight[pkg] = task
        let img = await task.value.img
        inflight[pkg] = nil
        if img == nil { failedAt[pkg] = Date() } else { failedAt[pkg] = nil }
        return img
    }

    private func fetch(_ pkg: String) async -> NSImage? {
        let url = Self.file(pkg)
        if let cg = await Task.detached(priority: .utility, operation: { () -> CGImage? in
            guard let d = try? Data(contentsOf: url) else { return nil }
            return ImageCache.downsample(d, maxPixel: Self.pixels)
        }).value { return store(cg, pkg) }

        #if DEBUG
        if AppModel.shared.debugFake { return store(Self.fakeIcon(pkg), pkg) }
        #endif
        guard AppModel.shared.isConnected else { return nil }
        await gate.acquire()
        defer { Task { await gate.release() } }
        guard let s = try? await Core.shared.appIcon(pkg: pkg), !s.isEmpty else { return nil }
        let cg = await Task.detached(priority: .utility) { () -> CGImage? in
            guard let d = FileFormat.imageData(fromDataURL: s), let cg = ImageCache.downsample(d, maxPixel: Self.pixels) else { return nil }
            Self.writePNG(cg, to: url)
            return cg
        }.value
        return cg.map { store($0, pkg) }
    }

    private func store(_ cg: CGImage, _ pkg: String) -> NSImage {
        let img = NSImage(cgImage: cg, size: NSSize(width: cg.width / 2, height: cg.height / 2))
        mem.setObject(img, forKey: pkg as NSString, cost: cg.width * cg.height * 4)
        return img
    }

    private nonisolated static func writePNG(_ cg: CGImage, to url: URL) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, cg, nil)
        CGImageDestinationFinalize(dest)
    }

    #if DEBUG
    /// QA only: a tinted SF Symbol tile standing in for a phone icon (exercises the real-icon path without a phone).
    private static func fakeIcon(_ pkg: String) -> CGImage {
        let symbols = ["message.fill", "phone.fill", "envelope.fill", "bag.fill", "camera.fill", "map.fill"]
        let colors: [NSColor] = [.systemGreen, .systemBlue, .systemOrange, .systemPink, .systemPurple, .systemTeal]
        let h = abs(pkg.hashValue)
        let side = pixels
        let img = NSImage(size: NSSize(width: side, height: side), flipped: false) { r in
            colors[h % colors.count].setFill()
            NSBezierPath(rect: r).fill()
            if let sym = NSImage(systemSymbolName: symbols[(h / 7) % symbols.count], accessibilityDescription: nil)?
                .withSymbolConfiguration(.init(pointSize: 64, weight: .semibold)) {
                let t = sym.tinted(.white)
                let s = t.size
                t.draw(in: NSRect(x: (r.width - s.width) / 2, y: (r.height - s.height) / 2, width: s.width, height: s.height))
            }
            return true
        }
        var rect = NSRect(x: 0, y: 0, width: side, height: side)
        return img.cgImage(forProposedRect: &rect, context: nil, hints: nil)!
    }
    #endif

    /// Drops the disk cache (Settings › Storage may call this); memory entries stay until evicted.
    nonisolated static func clearDisk() { try? FileManager.default.removeItem(at: dir) }
}

#if DEBUG
private extension NSImage {
    func tinted(_ c: NSColor) -> NSImage {
        let out = NSImage(size: size, flipped: false) { r in
            self.draw(in: r)
            c.set()
            r.fill(using: .sourceAtop)
            return true
        }
        return out
    }
}
#endif

/// A phone app's real icon. Starts as the lettered tile, then cross-fades to the icon once it is cached or fetched.
/// Pass `pkg` (the Android package name) from any notification or app row; `name` only feeds the placeholder.
struct PhoneAppIcon: View {
    var pkg: String?
    var name: String
    var size: CGFloat = DD.Size.avatar

    @State private var image: NSImage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(pkg: String?, name: String, size: CGFloat = DD.Size.avatar) {
        self.pkg = pkg; self.name = name; self.size = size
        // A memory hit shows on the very first frame: no placeholder flash for icons already seen.
        _image = State(initialValue: pkg.flatMap { PhoneIconStore.shared.cached($0) })
    }

    var body: some View {
        ZStack {
            if image == nil { AppIconView(image: nil, name: name, size: size).transition(.opacity) }
            if let image { AppIconView(image: image, name: name, size: size).transition(.opacity) }
        }
        .frame(width: size, height: size)
        .animation(reduceMotion ? nil : Motion.content, value: image != nil)
        .task(id: pkg) {
            guard let pkg, !pkg.isEmpty else { image = nil; return }
            if let hit = PhoneIconStore.shared.cached(pkg) { image = hit; return }
            image = await PhoneIconStore.shared.load(pkg)
        }
        .onChange(of: AppModel.shared.isConnected) { _, on in
            // Phone arrived after the row: a failed attempt is worth another go.
            guard on, image == nil, let pkg else { return }
            Task { image = await PhoneIconStore.shared.load(pkg) }
        }
    }
}
