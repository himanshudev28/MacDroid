package com.droiddock.app.ui.remote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/** Hold-to-repeat timing for the d-pad: one send on press, then after [DELAY_MS] every [INTERVAL_MS]. */
internal object KeyRepeat {
    const val DELAY_MS = 350L
    const val INTERVAL_MS = 70L

    /** How many `key` messages a press held for [heldMs] has produced by then. */
    fun sendsAfter(heldMs: Long): Int = when {
        heldMs < 0 -> 0
        heldMs < DELAY_MS -> 1
        else -> 1 + ((heldMs - DELAY_MS) / INTERVAL_MS).toInt() + 1
    }
}

/** The Mac advertises these in its welcome caps. */
internal const val CAP_REMOTE_GESTURES = "remotegestures"

/** Three-finger and pinch gestures need the Mac to understand the `gesture` action. */
internal fun gesturesEnabled(caps: Collection<String>): Boolean =
    caps.contains("remote") && caps.contains(CAP_REMOTE_GESTURES)

/**
 * Three-finger swipe -> gesture name, or null if the travel is under [thresholdPx] on its dominant axis.
 * Like a Mac trackpad: swiping left reveals the desktop on the right (`space_right`).
 */
internal fun swipeGesture(dx: Float, dy: Float, thresholdPx: Float): String? {
    val ax = abs(dx); val ay = abs(dy)
    if (maxOf(ax, ay) < thresholdPx) return null
    return if (ay >= ax) { if (dy < 0) "mission_control" else "app_expose" }
    else { if (dx < 0) "space_right" else "space_left" }
}

/** A two-finger touch shorter than this, that never scrolled or pinched, is a right-click. */
internal const val TWO_FINGER_TAP_MS = 350L

/** True when a "pinch" is really two fingers travelling together: centroid travel well beyond the spread change. */
internal fun pinchIsReallyScroll(travelPx: Float, spreadPx: Float, slopPx: Float): Boolean =
    travelPx > slopPx && travelPx > 2f * spreadPx

/**
 * For Macs older than 3.6.2, which read `mouse_scroll` values as lines rather than pixels: turns pixel
 * deltas into whole lines (about [perLine] px each) and keeps the remainder, so slow scrolls still move.
 */
internal class LineScroller(private val perLine: Float = 20f) {
    private var rx = 0f
    private var ry = 0f
    fun add(dx: Float, dy: Float): Pair<Int, Int> {
        rx += dx; ry += dy
        val lx = (rx / perLine).toInt(); val ly = (ry / perLine).toInt()
        rx -= lx * perLine; ry -= ly * perLine
        return lx to ly
    }
}

internal const val PINCH_OUT = 1.25f
internal const val PINCH_IN = 0.8f

/** [scale] is the current finger span over the span at the last step: one step per threshold crossing. */
internal fun pinchGesture(scale: Float): String? = when {
    scale >= PINCH_OUT -> "zoom_in"
    scale <= PINCH_IN -> "zoom_out"
    else -> null
}

/**
 * Multi-finger recognition for the trackpad. Runs on the Initial pass so it sees every touch, and
 * consumes events only once a second finger is down — the one-finger move / tap handlers placed
 * after it then see cancelled changes and stay out of the way.
 */
internal suspend fun PointerInputScope.detectTrackpadGestures(
    gesturesEnabled: () -> Boolean,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onTwoFingerTap: () -> Unit,
    onGesture: (name: String) -> Unit,
) {
    val swipePx = 80.dp.toPx()
    val moveSlop = 12.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var multi = false
        var maxPointers = 1
        var startMs = 0L
        var pinched = false
        var mode = 0 // 0 undecided, 1 scroll, 2 pinch
        var lastC = Offset.Zero
        var startC = Offset.Zero
        var swipeStart: Offset? = null
        var swiped = false
        var refSpan = 0f
        var spanAtStart = 0f
        var pending = Offset.Zero
        var lastFlush = 0L
        var endMs = 0L
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            val n = down.size
            endMs = event.changes.first().uptimeMillis
            if (n >= 2 && !multi) {
                multi = true
                startMs = event.changes.first().uptimeMillis
                lastC = centroid(down.map { it.position }); startC = lastC
                refSpan = span(down.map { it.position }); spanAtStart = refSpan
            }
            if (multi) {
                event.changes.forEach { if (it.positionChanged() || it.pressed != it.previousPressed) it.consume() }
                if (n > maxPointers) maxPointers = n
                if (n >= 2) {
                    val pos = down.map { it.position }
                    val c = centroid(pos)
                    val canGesture = gesturesEnabled()
                    if (n >= 3) {
                        if (canGesture && !swiped) {
                            val s = swipeStart ?: c.also { swipeStart = it }
                            val name = swipeGesture(c.x - s.x, c.y - s.y, swipePx)
                            if (name != null) { swiped = true; onGesture(name) }
                        }
                        lastC = c
                    } else if (maxPointers == 2) {
                        val sp = span(pos)
                        if (mode == 0) {
                            val travel = hypot(c.x - startC.x, c.y - startC.y)
                            val spread = if (spanAtStart > 0f) abs(sp / spanAtStart - 1f) else 0f
                            if (canGesture && spread > 0.12f && spread * spanAtStart > travel) mode = 2
                            else if (travel > moveSlop) { mode = 1; lastC = c }
                        }
                        if (mode == 2 && !pinched &&
                            pinchIsReallyScroll(hypot(c.x - startC.x, c.y - startC.y), abs(sp - spanAtStart), moveSlop)
                        ) { mode = 1; lastC = c }
                        if (mode == 1) {
                            pending += c - lastC; lastC = c
                            val now = event.changes.first().uptimeMillis
                            if (now - lastFlush >= 16 && (pending.x != 0f || pending.y != 0f)) {
                                onScroll(pending.x, pending.y); pending = Offset.Zero; lastFlush = now
                            }
                        } else if (mode == 2 && refSpan > 0f) {
                            val name = pinchGesture(sp / refSpan)
                            if (name != null) { onGesture(name); refSpan = sp; pinched = true }
                        }
                    }
                }
            }
        } while (event.changes.any { it.pressed })
        if (multi) {
            if (mode == 1 && (pending.x != 0f || pending.y != 0f)) onScroll(pending.x, pending.y)
            if (maxPointers == 2 && mode == 0 && endMs - startMs <= TWO_FINGER_TAP_MS) onTwoFingerTap()
        }
    }
}

private fun centroid(p: List<Offset>): Offset =
    Offset(p.sumOf { it.x.toDouble() }.toFloat() / p.size, p.sumOf { it.y.toDouble() }.toFloat() / p.size)

/** Mean distance from the centroid, doubled: the finger span for any finger count. */
private fun span(p: List<Offset>): Float {
    val c = centroid(p)
    return 2f * p.map { hypot(it.x - c.x, it.y - c.y) }.average().toFloat()
}
