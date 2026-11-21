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
    /// Set when the Mac answered with "Answer on speaker" on; consumed once the call turns active.
    private var speakerAfterAnswer = false
    /// Bumped when a call ends or the user touches the speaker, so a pending auto-switch gives up.
    private var speakerEpoch = 0
    private var speakerTask: Task<Void, Never>?
    private init() {}

    var model: AppModel { AppModel.shared }

    /// Called whenever `activeCall` changes.
    func sync(_ call: IncomingCall?) {
        guard let call else {
            activeSince = nil; muted = nil; speaker = nil; lastState = nil
            cancelSpeakerAfterAnswer()
            return
        }
        if call.state == "active" || call.state == "dialing" {
            if activeSince == nil || lastState == "ringing" { activeSince = Date() }
        } else { activeSince = nil }
        if let m = call.muted { muted = m }
        if let s = call.speaker { speaker = s }
        lastState = call.state
        if speakerAfterAnswer && call.state == "active" { applySpeakerAfterAnswer() }
    }

    /// `onSpeaker` nil follows Settings › Answer on speaker; the ringing pop-up's speaker button passes true.
    func answer(onSpeaker: Bool? = nil) {
        let wantSpeaker = onSpeaker ?? UserDefaults.standard.bool(forKey: NotifPrefs.answerOnSpeakerKey)
        cancelSpeakerAfterAnswer()
        let epoch = speakerEpoch
        run("answer") {
            _ = try await Core.shared.callAction("answer")
            // The call may have ended, or the user changed the route, while the answer was in flight.
            guard wantSpeaker, epoch == self.speakerEpoch, self.model.activeCall != nil else { return }
            if self.model.activeCall?.state == "active" { self.applySpeakerAfterAnswer(); return }
            self.speakerAfterAnswer = true
            // If no "active" update arrives (ADB-only link), switch anyway once the call has had time to connect.
            self.speakerTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled, epoch == self.speakerEpoch, self.speakerAfterAnswer else { return }
                self.applySpeakerAfterAnswer()
            }
        }
    }

    private func cancelSpeakerAfterAnswer() {
        speakerEpoch += 1
        speakerAfterAnswer = false
        speakerTask?.cancel(); speakerTask = nil
    }

    /// Turns the speaker on for a call the Mac just answered. The dialer can reset the route while the call
    /// connects, so it waits a moment and tries a second time if the phone reports the earpiece. Gives up as soon
    /// as the call ends or the user toggles the speaker.
    private func applySpeakerAfterAnswer() {
        speakerAfterAnswer = false
        speakerTask?.cancel()
        let epoch = speakerEpoch
        speakerTask = Task { @MainActor [weak self] in
            for attempt in 0..<2 {
                try? await Task.sleep(for: .milliseconds(attempt == 0 ? 600 : 1200))
                guard let self, !Task.isCancelled, epoch == self.speakerEpoch,
                      let call = self.model.activeCall, call.state == "active" || call.state == "dialing" else { return }
                if self.speaker == true { return }
                if call.canAudio == true {
                    do {
                        let r = try await Core.shared.callAction("speaker", on: true)
                        guard epoch == self.speakerEpoch else { return }
                        if let s = r.speaker { self.speaker = s }
                        if r.ok && r.speaker != false { self.speaker = true; return }
                        if attempt == 1 { self.model.toast(.bad, r.error ?? tr("Couldn't switch the call to speaker")) }
                    } catch {
                        if attempt == 1, epoch == self.speakerEpoch { self.model.toast(.bad, error.localizedDescription) }
                    }
                } else {
                    // The ADB route is a blind toggle: use it once, on the first attempt only, and only when the
                    // phone has said the call is on the earpiece, so it can never flip a speaker call off.
                    guard attempt == 0, self.speaker == false || call.speaker == false else { return }
                    do { try await Core.shared.adbCallSpeaker(); if epoch == self.speakerEpoch { self.speaker = true } }
                    catch { self.model.toast(.bad, error.localizedDescription) }
                    return
                }
            }
        }
    }

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
        cancelSpeakerAfterAnswer()
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
