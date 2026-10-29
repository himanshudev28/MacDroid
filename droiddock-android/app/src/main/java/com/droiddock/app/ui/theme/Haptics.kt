package com.droiddock.app.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Haptics as personality, at most one per gesture. Only predefined effects
 * (they respect the user's system intensity and never "ring").
 *
 * - Material You / Liquid Glass: Confirm/Reject on outcomes, soft tick on toggles.
 * - Minimal Mono: the visuals are quiet, so toggles and selection tick crisply.
 * - Reach: Samsung-restrained: Confirm/Reject only; toggles stay silent.
 */
@Stable
class DockHaptics internal constructor(
    private val feedback: HapticFeedback?,
    private val enabled: Boolean,
    private val style: UiStyle,
) {
    private fun play(type: HapticFeedbackType) {
        if (enabled) feedback?.performHapticFeedback(type)
    }

    /** Success: paired, sent, saved. */
    fun confirm() = play(HapticFeedbackType.Confirm)
    /** Failure: send failed, not connected. */
    fun reject() = play(HapticFeedbackType.Reject)
    /** Switch / circular toggle flipped. */
    fun toggle(on: Boolean) {
        if (style == UiStyle.REACH) return
        play(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    }
    /** Segment, chip or list selection. */
    fun select() {
        if (style == UiStyle.REACH) return
        play(HapticFeedbackType.SegmentTick)
    }
    /** Trackpad detents, scroll notches: may fire often. */
    fun frequentTick() = play(HapticFeedbackType.SegmentFrequentTick)
    /** Long-press to enter selection. */
    fun longPress() = play(HapticFeedbackType.LongPress)
    /** A drag crossed a commit threshold (sheet will dismiss, detent snap). */
    fun threshold() = play(HapticFeedbackType.GestureThresholdActivate)
    /** A gesture was released and committed. */
    fun gestureEnd() = play(HapticFeedbackType.GestureEnd)
    /** Glass buttons in Liquid Glass get a soft tap; others nothing. */
    fun tap() {
        if (style == UiStyle.LIQUID_GLASS || style == UiStyle.MINIMAL_MONO) play(HapticFeedbackType.VirtualKey)
    }
}

val LocalDockHaptics = staticCompositionLocalOf { DockHaptics(null, false, UiStyle.LIQUID_GLASS) }
