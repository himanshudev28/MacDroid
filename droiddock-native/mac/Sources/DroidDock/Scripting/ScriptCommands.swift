import AppKit

/// AppleScript / JXA / Shortcuts verbs (see DroidDock.sdef). Each one runs the same action the command bar and
/// `ShellActions` use. Cocoa scripting delivers commands on the main thread; the work is async, so the command is
/// suspended and resumed with the result.
private let errAEEventFailed = -10000
private let errAEUserCancelled = -128

@MainActor
private enum ScriptRunner {
    static func run(_ cmd: NSScriptCommand, guarded verb: ScriptVerb? = nil, _ work: @escaping @MainActor () async throws -> Any?) {
        cmd.suspendExecution()
        Task { @MainActor in
            if let verb, !(await ScriptGuard.authorize(verb)) {
                cmd.scriptErrorNumber = errAEUserCancelled
                cmd.scriptErrorString = tr("The user did not allow this script to use DroidDock.")
                cmd.resumeExecution(withResult: nil)
                return
            }
            do { cmd.resumeExecution(withResult: try await work()) }
            catch {
                cmd.scriptErrorNumber = errAEEventFailed
                cmd.scriptErrorString = FileFormat.errorText(error.localizedDescription)
                cmd.resumeExecution(withResult: nil)
            }
        }
    }

    static func requireLink() throws {
        guard AppModel.shared.isConnected else { throw ScriptError(tr("No phone linked")) }
    }
}

private struct ScriptError: LocalizedError {
    let text: String
    init(_ t: String) { text = t }
    var errorDescription: String? { text }
}

@objc(DDRingPhoneCommand)
final class DDRingPhoneCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        MainActor.assumeIsolated {
            ScriptRunner.run(self, guarded: .ring) {
                try ScriptRunner.requireLink()
                // Ring on (not a toggle): scripts must be idempotent.
                ShellState.shared.ringing = try await Core.shared.ringPhone(on: true)
                return nil
            }
        }
        return nil
    }
}

@objc(DDSendClipboardCommand)
final class DDSendClipboardCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        MainActor.assumeIsolated {
            ScriptRunner.run(self, guarded: .sendClipboard) {
                try ScriptRunner.requireLink()
                try await Core.shared.clipboardPushNow()
                AppModel.shared.toast(.ok, tr("Clipboard sent to phone"))
                return nil
            }
        }
        return nil
    }
}

@objc(DDSendFileCommand)
final class DDSendFileCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        let paths = (directParameter as? [String]) ?? (directParameter as? String).map { [$0] } ?? []
        MainActor.assumeIsolated {
            let urls = paths.map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) }
            ScriptRunner.run(self, guarded: .sendFile(names: urls.map { $0.path })) {
                try ScriptRunner.requireLink()
                guard !urls.isEmpty else { throw ScriptError(tr("No files to send")) }
                if let missing = urls.first(where: { !FileManager.default.fileExists(atPath: $0.path) }) {
                    throw ScriptError(tr("File not found: {name}", ["name": missing.path]))
                }
                ShellActions.sendFiles(urls)
                return urls.count
            }
        }
        return nil
    }
}

@objc(DDGetBatteryCommand)
final class DDGetBatteryCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        MainActor.assumeIsolated {
            let m = AppModel.shared
            guard m.isConnected else {
                scriptErrorNumber = errAEEventFailed
                scriptErrorString = tr("No phone linked")
                return nil
            }
            return [NSNumber(value: m.batteryPercent ?? -1), NSNumber(value: m.isCharging)] as NSArray
        }
    }
}

@objc(DDStartMirroringCommand)
final class DDStartMirroringCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        MainActor.assumeIsolated {
            ScriptRunner.run(self, guarded: .mirror) {
                try ScriptRunner.requireLink()
                // Start only: never stop an existing session from a script.
                if AppModel.shared.mirror == nil, !MirrorSession.shared.isActive { ShellActions.toggleMirror() }
                return nil
            }
        }
        return nil
    }
}

@objc(DDShowCommandBarCommand)
final class DDShowCommandBarCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        MainActor.assumeIsolated { if !CommandBarController.shared.isVisible { CommandBarController.shared.show() } }
        return nil
    }
}

@objc(DDSendSMSCommand)
final class DDSendSMSCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        let to = (evaluatedArguments?["recipient"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let body = (evaluatedArguments?["body"] as? String) ?? ""
        MainActor.assumeIsolated {
            ScriptRunner.run(self, guarded: .sendSMS(to: to)) {
                try ScriptRunner.requireLink()
                guard !to.isEmpty, !body.isEmpty else { throw ScriptError(tr("A number and a message are required")) }
                try await Core.shared.smsSend(address: to, text: body)
                AppModel.shared.toast(.ok, tr("Message sent to {name}", ["name": to]))
                return nil
            }
        }
        return nil
    }
}
