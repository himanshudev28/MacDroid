import AppKit

/// macOS Services ("Send to Phone", "Ring My Phone"; declared under NSServices in Info.plist). A Services click is a
/// direct user action, so unlike AppleScript verbs it skips `ScriptGuard`; every outcome is shown as a toast instead.
/// Text goes through the Mac clipboard (the one path the core has for it), so the selection ends up on the Mac
/// clipboard as well as the phone's.
@MainActor
final class ServicesProvider: NSObject {
    static let shared = ServicesProvider()

    /// Called once at launch.
    static func register() {
        NSApp.servicesProvider = shared
        NSUpdateDynamicServices()
    }

    /// File URLs on a Services pasteboard (Finder selection); empty when it carries text only.
    nonisolated static func fileURLs(from pb: NSPasteboard) -> [URL] {
        let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]
        return urls ?? []
    }

    nonisolated static func text(from pb: NSPasteboard) -> String? {
        guard let s = pb.string(forType: .string), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    /// NSMessage `sendToPhone`.
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let files = Self.fileURLs(from: pboard)
        let text = files.isEmpty ? Self.text(from: pboard) : nil
        if files.isEmpty && text == nil {
            MainActor.assumeIsolated { AppModel.shared.toast(.bad, tr("Nothing to send")) }
            return
        }
        MainActor.assumeIsolated {
            if !files.isEmpty { ShellActions.sendFiles(files); return }
            guard let text, ShellActions.requireLink() else { return }
            Task { @MainActor in
                let old = NSPasteboard.general.string(forType: .string)
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                do {
                    try await Core.shared.clipboardPushNow()
                    AppModel.shared.toast(.ok, tr("Text sent to phone"))
                } catch {
                    // A failed send must not leave the selection on the user's clipboard.
                    if let old { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(old, forType: .string) }
                    AppModel.shared.toast(.bad, FileFormat.errorText(error.localizedDescription))
                }
            }
        }
    }

    /// NSMessage `ringMyPhone`. Ring on, never a toggle, like the AppleScript verb.
    @objc func ringMyPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        MainActor.assumeIsolated {
            guard ShellActions.requireLink() else { return }
            Task { @MainActor in
                do {
                    ShellState.shared.ringing = try await Core.shared.ringPhone(on: true)
                    AppModel.shared.toast(.info, tr("Ringing your phone. It stops by itself after a minute."))
                } catch {
                    AppModel.shared.toast(.bad, FileFormat.errorText(error.localizedDescription))
                }
            }
        }
    }
}
