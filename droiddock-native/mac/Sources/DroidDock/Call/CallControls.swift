import Foundation
import Observation

/// Call actions shared by the overlay and the Calls screen. Mirrors CallOverlay.tsx: Wi-Fi `call-action`
/// when the phone says it can (`canEnd` / `canAudio`), the ADB fallbacks otherwise.
@Observable @MainActor
final class CallControls {
    static let shared = CallControls()

    private(set) var busy: String?
    private(set) var activeSince: Date?
    /// Route the phone actually settled on (the dialer can override what we asked for).
    private(set) var muted: Bool?
    private(set) var speaker: Bool?
    private var lastState: String?
    private init() {}

    var model: AppModel { AppModel.shared }

    /// Called whenever `activeCall` changes.
    func sync(_ call: IncomingCall?) {
        guard let call else { activeSince = nil; muted = nil; speaker = nil; lastState = nil; return }
        if call.state == "active" || call.state == "dialing" {
            if activeSince == nil || lastState == "ringing" { activeSince = Date() }
        } else { activeSince = nil }
        if let m = call.muted { muted = m }
        if let s = call.speaker { speaker = s }
        lastState = call.state
    }

    func answer() { run("answer") { _ = try await Core.shared.callAction("answer") } }

    /// Decline when ringing, hang up when active: both are `end`.
    func end() {
        run("end") {
            if self.model.activeCall?.canEnd == true { _ = try await Core.shared.callAction("end") }
            else { try await Core.shared.adbCallEnd() }
        }
    }

    func toggleMute() {
        let want = !(muted ?? model.activeCall?.muted ?? false)
        run("mute") {
            if self.model.activeCall?.canAudio == true {
                let r = try await Core.shared.callAction("mute", on: want)
                self.muted = r.muted ?? want
            } else { try await Core.shared.adbCallMute(); self.muted = want }
        }
    }

    func toggleSpeaker() {
        let want = !(speaker ?? model.activeCall?.speaker ?? false)
        run("speaker") {
            if self.model.activeCall?.canAudio == true {
                let r = try await Core.shared.callAction("speaker", on: want)
                self.speaker = r.speaker ?? want
            } else { try await Core.shared.adbCallSpeaker(); self.speaker = want }
        }
    }

    private func run(_ what: String, _ body: @escaping @MainActor () async throws -> Void) {
        guard busy == nil else { return }
        busy = what
        Task {
            do { try await body() }
            catch { model.toast(.bad, error.localizedDescription) }
            busy = nil
        }
    }
}
