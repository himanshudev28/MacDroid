import AppKit
import SwiftUI

/// The app's whole motion vocabulary. Screens never write a raw `.spring(...)`/`.easeInOut(...)`:
/// pick the named animation for the *job*, so the product moves one way everywhere.
///
/// Rules (see docs/UI-GUIDE.md §Motion):
/// - Nothing UI-facing runs longer than 0.35 s.
/// - Keyboard-initiated changes (⌘1…⌘5, arrow-key selection) do NOT animate.
/// - Springs for anything that can be interrupted; no bounce except `toast` / `pop`.
/// - Reduce Motion: movement becomes a short cross-fade (`Motion.resolved`).
enum Motion {
    /// Press feedback (scale 0.97). Fast in, fast out.
    static let press = Animation.spring(duration: 0.16, bounce: 0)
    /// Hover wash / colour change.
    static let hover = Animation.easeOut(duration: 0.12)
    /// Selection indicator sliding between segments/tabs, toggles, chips.
    static let select = Animation.snappy(duration: 0.24)
    /// Expand / collapse (mini-player, disclosure, notification stack).
    static let expand = Animation.smooth(duration: 0.30)
    /// Whole-card state change (disconnected → connected, pairing).
    static let state = Animation.smooth(duration: 0.35)
    /// Content swap inside a stable frame (list ⇄ empty, counters, blur-replace).
    static let content = Animation.easeOut(duration: 0.20)
    /// Toasts and floating confirmation: a hint of life.
    static let toast = Animation.spring(duration: 0.32, bounce: 0.14)
    /// Small celebratory pop (pairing succeeded, file arrived). Rare by design.
    static let pop = Animation.spring(duration: 0.30, bounce: 0.25)
    /// Backdrop cross-fade (wallpaper ⇄ album art). Background-only, so it may be unhurried.
    static let backdrop = Animation.easeInOut(duration: 0.35)
    /// Whole-screen swap on a pointer-driven tab/destination change. Quick: this is seen many times a day.
    static let screen = Animation.snappy(duration: 0.22)
    /// Exits are faster than entrances: removals, dismissals, banner leaving.
    static let exit = Animation.easeOut(duration: 0.14)
    /// Determinate progress bars: smooth between ticks that arrive about once a second.
    static let progress = Animation.smooth(duration: 0.30)
    /// Menu-bar panel morph ("Dynamic Island"): the panel springs open from a pill under the status item. The only other
    /// bounce besides `toast`/`pop`; Core Animation drives the real thing (`Motion.Island`), this is its SwiftUI twin
    /// for content that should settle in step with it.
    static let island = Animation.spring(duration: 0.46, bounce: 0.24)
    /// Reduce-Motion substitute for any of the above.
    static let reducedFade = Animation.easeOut(duration: 0.15)

    /// Delay for item `index` in a staggered entrance (menu bar segments only). At most 5 items, 35 ms apart.
    static func stagger(_ index: Int) -> Double { Double(min(index, 4)) * 0.035 }

    /// `true` while the event being handled is a mouse/trackpad click (not a key press or a programmatic change).
    /// Keyboard-initiated changes (⌘1…⌘5, arrow keys, ⌃⌘S) must not animate, so pointer-only motion asks this.
    @MainActor static var isPointerEvent: Bool {
        switch NSApp?.currentEvent?.type {
        case .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp,
             .leftMouseDragged, .scrollWheel, .gesture, .smartMagnify, .pressure: true
        default: false
        }
    }

    /// System Reduce Motion, readable outside a view (e.g. inside `withAnimation`).
    @MainActor static var systemReduceMotion: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["DD_REDUCE_MOTION"] == "1" { return true }   // QA without touching system settings
        #endif
        return NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
    }

    /// The animation to actually run given Reduce Motion.
    static func resolved(_ animation: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? reducedFade : animation
    }
}

/// `withAnimation` that honours Reduce Motion.
@MainActor
func withDDAnimation<Result>(_ animation: Animation, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(Motion.resolved(animation, reduceMotion: Motion.systemReduceMotion), body)
}

/// Animates only when a pointer caused the change; a key press applies it instantly (and suppresses any
/// implicit `.ddAnimation` on the same value). Reduce Motion swaps the animation for a fade.
@MainActor
func withDDPointerAnimation<Result>(_ animation: Animation, _ body: () throws -> Result) rethrows -> Result {
    if Motion.isPointerEvent { return try withDDAnimation(animation, body) }
    var t = Transaction()
    t.disablesAnimations = true
    return try withTransaction(t, body)
}

private struct DDAnimationModifier<V: Equatable>: ViewModifier {
    let animation: Animation
    let value: V
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        // `|| systemReduceMotion`: same system setting in release; in DEBUG it also honours the `DD_REDUCE_MOTION` QA override.
        content.animation(Motion.resolved(animation, reduceMotion: reduceMotion || Motion.systemReduceMotion), value: value)
    }
}

/// Transition that becomes a plain fade under Reduce Motion.
private struct DDTransitionModifier: ViewModifier {
    let transition: AnyTransition
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.transition(reduceMotion || Motion.systemReduceMotion ? .opacity : transition)
    }
}

extension View {
    /// Value-scoped animation from the `Motion` vocabulary, Reduce-Motion aware.
    func ddAnimation<V: Equatable>(_ animation: Animation, value: V) -> some View {
        modifier(DDAnimationModifier(animation: animation, value: value))
    }

    /// Insertion/removal transition that degrades to `.opacity` under Reduce Motion.
    func ddTransition(_ transition: AnyTransition) -> some View {
        modifier(DDTransitionModifier(transition: transition))
    }
}

extension AnyTransition {
    /// Rise 6 pt + fade: popovers, toasts, rows appearing. Never scale from 0.
    static var ddRise: AnyTransition {
        .asymmetric(insertion: .offset(y: 6).combined(with: .opacity).combined(with: .scale(scale: 0.98)),
                    removal: .opacity)
    }
    /// Toast from the bottom edge (exit is faster: opacity only).
    static var ddToast: AnyTransition {
        .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity.combined(with: .scale(scale: 0.96)).animation(Motion.exit))
    }
}

extension AnyTransition {
    /// Whole-screen swap: the new screen fades in while settling up from 4 pt; the old one just fades (faster).
    static var ddScreen: AnyTransition {
        .asymmetric(insertion: .offset(y: 4).combined(with: .opacity), removal: .opacity)
    }
    /// A banner sliding down from the top edge, scaling from 0.96 (never 0). Exit: fade + slight shrink.
    static var ddBanner: AnyTransition {
        .asymmetric(insertion: .move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.96, anchor: .top)),
                    removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
    }
    /// A list row appearing (message bubble, notification): rise 6 pt + fade; leaves with a fade.
    static var ddRow: AnyTransition {
        .asymmetric(insertion: .offset(y: 6).combined(with: .opacity), removal: .opacity)
    }
}

/// Press feedback for any custom pressable: scales to 0.97 while held.
struct PressableButtonStyle: ButtonStyle {
    var scale: CGFloat = 0.97
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? scale : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.7 : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

extension Motion {
    /// Core Animation numbers for the menu-bar panel morph (`MenuBar/IslandPanel.swift`). Under-damped spring
    /// (damping ratio about 0.7): one small overshoot, settles in ~0.45 s. Width leads height slightly, like the
    /// iPhone's island. Closing is a plain ease-in, faster than opening (exits are quicker than entrances).
    enum Island {
        static let mass: CGFloat = 1
        static let widthStiffness: CGFloat = 320, widthDamping: CGFloat = 25
        static let heightStiffness: CGFloat = 260, heightDamping: CGFloat = 23
        /// Starting capsule: status-item width plus this, this tall.
        static let pillExtraWidth: CGFloat = 20, pillHeight: CGFloat = 24
        static let panelRadius: CGFloat = 22
        /// Content fades/settles in after the shape has begun to open.
        static let contentDelay: CFTimeInterval = 0.07, contentDuration: CFTimeInterval = 0.24
        static let contentScaleFrom: CGFloat = 0.95
        static let closeDuration: CFTimeInterval = 0.18
        static let reducedFade: CFTimeInterval = 0.15
    }
}
