import AppKit
import UniformTypeIdentifiers

/// Async wrappers over NSOpenPanel / NSSavePanel (sheet on the key window when there is one).
@MainActor
enum FilePicker {
    static func open(files: Bool = true, directories: Bool = false, multiple: Bool = false,
                     types: [UTType]? = nil, title: String? = nil, prompt: String? = nil) async -> [URL] {
        let p = NSOpenPanel()
        p.canChooseFiles = files; p.canChooseDirectories = directories
        p.allowsMultipleSelection = multiple; p.canCreateDirectories = directories
        if let types { p.allowedContentTypes = types }
        if let title { p.title = title }
        if let prompt { p.prompt = prompt }
        return await run(p) ? p.urls : []
    }

    static func chooseFolder(title: String? = nil) async -> URL? {
        await open(files: false, directories: true, title: title).first
    }

    static func save(suggestedName: String, types: [UTType]? = nil, directory: URL? = nil) async -> URL? {
        let p = NSSavePanel()
        p.nameFieldStringValue = suggestedName
        p.canCreateDirectories = true
        if let types { p.allowedContentTypes = types }
        if let directory { p.directoryURL = directory }
        return await run(p) ? p.url : nil
    }

    private static func run(_ panel: NSSavePanel) async -> Bool {
        NSApp.activate()
        if let w = NSApp.keyWindow {
            return await withCheckedContinuation { c in panel.beginSheetModal(for: w) { c.resume(returning: $0 == .OK) } }
        }
        return await withCheckedContinuation { c in panel.begin { c.resume(returning: $0 == .OK) } }
    }
}
