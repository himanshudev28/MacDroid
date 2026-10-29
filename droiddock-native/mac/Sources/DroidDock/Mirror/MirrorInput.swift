import AppKit
import Foundation

/// Mouse / trackpad / keyboard -> `mirror_input {msg}`. Message shapes are IDENTICAL to the old
/// MirrorWindow.tsx: `mirror-tap {x,y}`, `mirror-swipe {x1,y1,x2,y2,dur}`, `mirror-key {key}`,
/// `mirror-text {text}` / `{op: backspace|enter}`, `camera-flip {facing}`.
/// Coordinates are 0...1 fractions of the phone screen (top-left origin).
final class MirrorInput: @unchecked Sendable {
    /// One lane: taps, swipes and typed characters must reach the phone in the order they happened.
    private static let lane = SerialLane()
    var send: @Sendable ([String: Any]) -> Void = { msg in
        nonisolated(unsafe) let m = msg
        MirrorInput.lane.submit { try? await Core.shared.mirrorInput(m) }
    }

    private var down: (x: Double, y: Double, t: Date)?
    private var lastScroll = Date.distantPast

    static func clamp01(_ v: Double) -> Double { min(1, max(0, v)) }

    func pointerDown(x: Double, y: Double) {
        down = (Self.clamp01(x), Self.clamp01(y), Date())
    }

    func pointerUp(x: Double, y: Double) {
        guard let d = down else { return }
        down = nil
        let p = (x: Self.clamp01(x), y: Self.clamp01(y))
        let dist = hypot(p.x - d.x, p.y - d.y)
        if dist < 0.02 {
            send(["type": "mirror-tap", "x": p.x, "y": p.y])
        } else {
            let ms = Date().timeIntervalSince(d.t) * 1000
            send(["type": "mirror-swipe", "x1": d.x, "y1": d.y, "x2": p.x, "y2": p.y,
                  "dur": Int(min(800, max(60, ms)))])
        }
    }

    /// `domDeltaY` follows the DOM convention (> 0 = scroll down). Web sent one fixed swipe per
    /// wheel event; trackpads fire dozens per gesture, so this is rate-limited to one per 150 ms.
    func scroll(domDeltaY: Double) {
        guard domDeltaY != 0 else { return }
        let now = Date()
        guard now.timeIntervalSince(lastScroll) > 0.15 else { return }
        lastScroll = now
        let dy = domDeltaY > 0 ? -0.3 : 0.3
        send(["type": "mirror-swipe", "x1": 0.5, "y1": 0.5, "x2": 0.5, "y2": 0.5 + dy, "dur": 120])
    }

    func key(_ k: String) { send(["type": "mirror-key", "key": k]) }
    func flipCamera(to facing: String) { send(["type": "camera-flip", "facing": facing]) }

    /// Typed characters (screen mode). Returns true if consumed. Shortcuts (cmd/ctrl/opt) pass through.
    func keyDown(_ e: NSEvent) -> Bool {
        if !e.modifierFlags.intersection([.command, .control, .option]).isEmpty { return false }
        switch e.keyCode {
        case 51: send(["type": "mirror-text", "op": "backspace"]); return true
        case 36, 76: send(["type": "mirror-text", "op": "enter"]); return true
        default: break
        }
        guard let s = e.characters, s.count == 1, let u = s.unicodeScalars.first else { return false }
        // Skip control chars and AppKit's private-use function-key codes (arrows, F-keys…).
        if u.value < 0x20 || u.value == 0x7F || (0xF700...0xF8FF).contains(u.value) { return false }
        send(["type": "mirror-text", "text": s])
        return true
    }
}
