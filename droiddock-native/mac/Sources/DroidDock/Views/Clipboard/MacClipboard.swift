import AppKit
import Observation

/// Read-only view of the Mac pasteboard for the Clipboard screen preview. Polls `changeCount`
/// (cheap) only while the screen is on display; reads contents when it changes.
@Observable @MainActor
final class MacClipboard {
    enum Content: Equatable {
        case empty
        case text(String)
        case image(NSImage, String)   // image + "1170 × 2532"
        case files([String])
    }
    private(set) var content: Content = .empty
    private(set) var copiedAt: Date?
    private var lastCount = -1

    func poll() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastCount else { return }
        if lastCount != -1 { copiedAt = Date() }
        lastCount = pb.changeCount
        content = Self.read(pb)
    }

    /// Writes text/image back (used by "Copy again").
    private static func read(_ pb: NSPasteboard) -> Content {
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            return .files(urls.map(\.lastPathComponent))
        }
        if let s = pb.string(forType: .string), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .text(s)
        }
        if let img = NSImage(pasteboard: pb), img.isValid {
            let rep = img.representations.first
            let w = rep?.pixelsWide ?? Int(img.size.width), h = rep?.pixelsHigh ?? Int(img.size.height)
            return .image(img, "\(w) × \(h)")
        }
        return .empty
    }
}
