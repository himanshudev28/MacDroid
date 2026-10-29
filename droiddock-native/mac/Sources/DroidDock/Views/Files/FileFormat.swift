import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Formatting + file-type helpers shared by Files, Photos, Transfers and Apps.
enum FileFormat {
    static func bytes(_ n: Double) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(max(0, n)), countStyle: .file)
    }

    static func speed(_ bps: Double) -> String { "\(bytes(bps))/s" }

    static func secs(_ s: Double) -> String {
        let s = max(0, Int(s.rounded()))
        if s < 60 { return "\(s) s" }
        if s < 3600 { return "\(s / 60) min" + (s % 60 > 0 && s < 600 ? " \(s % 60) s" : "") }
        return "\(s / 3600) h \((s % 3600) / 60) min"
    }

    static func duration(_ s: Double) -> String {
        let t = Int(s.rounded())
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
                         : String(format: "%d:%02d", t / 60, t % 60)
    }

    static func date(ms: Double) -> Date { Date(timeIntervalSince1970: ms / 1000) }

    static func relative(ms: Double) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: date(ms: ms), relativeTo: Date())
    }

    static func ext(_ name: String) -> String { (name as NSString).pathExtension.lowercased() }

    static func type(_ name: String) -> UTType? { UTType(filenameExtension: ext(name)) }

    /// SF Symbol for a file name.
    static func symbol(name: String, dir: Bool) -> String {
        if dir { return "folder.fill" }
        guard let t = type(name) else { return "doc.fill" }
        if t.conforms(to: .image) { return "photo.fill" }
        if t.conforms(to: .movie) || t.conforms(to: .video) { return "film.fill" }
        if t.conforms(to: .audio) { return "waveform" }
        if t.conforms(to: .archive) { return "doc.zipper" }
        if t.conforms(to: .pdf) { return "doc.richtext.fill" }
        if t.conforms(to: .spreadsheet) { return "tablecells.fill" }
        if t.conforms(to: .sourceCode) || t.conforms(to: .plainText) { return "doc.text.fill" }
        return "doc.fill"
    }

    static func tint(name: String, dir: Bool, accent: Color) -> Color {
        if dir { return accent }
        guard let t = type(name) else { return DD.dim }
        if t.conforms(to: .image) { return DDTint.palette[3] }
        if t.conforms(to: .movie) || t.conforms(to: .video) { return DDTint.palette[1] }
        if t.conforms(to: .audio) { return DDTint.palette[2] }
        if t.conforms(to: .archive) { return DDTint.palette[6] }
        if t.conforms(to: .pdf) { return DDTint.palette[7] }
        return DD.dim
    }

    static func kind(name: String, dir: Bool) -> String {
        if dir { return tr("Folder") }
        return type(name)?.localizedDescription.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? (ext(name).isEmpty ? tr("Document") : ext(name).uppercased())
    }

    /// `data:image/png;base64,…` (what `photo_thumb` / `app_icon` return) → image bytes.
    static func imageData(fromDataURL s: String) -> Data? {
        let payload = s.firstIndex(of: ",").map { String(s[s.index(after: $0)...]) } ?? s
        return Data(base64Encoded: payload, options: .ignoreUnknownCharacters)
    }

    static func errorText(_ raw: String) -> String {
        raw.replacingOccurrences(of: "command(\"", with: "").replacingOccurrences(of: "\")", with: "")
    }
}

/// Bounded concurrency for thumbnail / icon fetches (the phone serves them one at a time anyway).
actor FetchGate {
    private let limit: Int
    private var active = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []
    init(limit: Int) { self.limit = limit }

    func acquire() async {
        if active < limit { active += 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() {
        if waiters.isEmpty { active -= 1 } else { waiters.removeFirst().resume() }
    }
}
