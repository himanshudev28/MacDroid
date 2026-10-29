import AppKit

enum Opener {
    static func open(_ url: URL) { NSWorkspace.shared.open(url) }
    static func open(_ string: String) { if let u = URL(string: string) { open(u) } }
    static func open(path: String) { NSWorkspace.shared.open(URL(fileURLWithPath: path)) }
    static func reveal(path: String) {
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }
    /// e.g. `Opener.systemSettings("Privacy_Accessibility")`.
    static func systemSettings(_ pane: String? = nil) {
        let s = pane.map { "x-apple.systempreferences:com.apple.preference.security?\($0)" } ?? "x-apple.systempreferences:"
        open(s)
    }
}
