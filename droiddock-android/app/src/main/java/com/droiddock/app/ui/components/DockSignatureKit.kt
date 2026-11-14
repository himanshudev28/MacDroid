package com.droiddock.app.ui.components

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockSignature
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.rememberAmbientActive

// MARK: - DockNumeral

/**
 * The big number of the current signature (battery %, counts, speeds). Tabular, accessible as one string.
 *
 * - INSTRUMENT: dot-matrix digits drawn on a 5×7 grid (unlit dots faintly visible), rolling per digit like an odometer.
 * - Other signatures: [AnimatedCounter]'s per-digit roll in [style].
 * - `value == null`: a quiet placeholder dash (unknown / not yet reported), never "0".
 *
 * @param unit drawn after the number at 45% size (e.g. "%", "MB/s"); not animated.
 * @param prefix drawn before the number at 45% size (e.g. "+").
 */
@Composable
fun DockNumeral(
    value: Int?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    prefix: String? = null,
    style: TextStyle = DockTheme.type.numeral,
    color: Color = LocalContentColor.current,
) {
    val small = style.copy(fontSize = style.fontSize * 0.45f, fontFeatureSettings = "tnum")
    val description = (prefix ?: "") + (value?.toString() ?: "–") + (unit ?: "")
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (prefix != null) Text(prefix, style = small, color = color.copy(alpha = 0.7f), maxLines = 1)
        when {
            value == null -> Text("–", style = style, color = color.copy(alpha = 0.45f), maxLines = 1)
            DockTheme.signature == DockSignature.INSTRUMENT -> DotMatrixNumber(value, style, color)
            else -> AnimatedCounter(value, style = style, color = color)
        }
        if (unit != null) Text(unit, style = small, color = color.copy(alpha = 0.7f), maxLines = 1)
    }
}

/** 5×7 dot-matrix glyphs (rows top → bottom, 5 bits each, MSB = leftmost). Our own grid; no font. */
private val DOT_GLYPHS: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
    '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
    '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
    '3' to intArrayOf(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110),
    '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
    '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
    '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
    '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
    '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
    '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),
    '-' to intArrayOf(0, 0, 0, 0b11111, 0, 0, 0),
)

@Composable
private fun DotMatrixNumber(value: Int, style: TextStyle, color: Color) {
    val motion = DockTheme.motion
    val density = LocalDensity.current
    // Glyph height ≈ cap height of the numeral style; 7 rows of dots.
    val glyphH: Dp = with(density) { (style.fontSize.toPx() * 0.78f).toDp() }
    val pitch = glyphH / 7
    val last = remember { intArrayOf(value) }
    val up = value >= last[0]
    last[0] = value
    val text = value.toString()
    Row(horizontalArrangement = Arrangement.spacedBy(pitch)) {
        text.forEachIndexed { i, ch ->
            key(text.length - 1 - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        // Odometer: a full-height roll, linear like a mechanism; snaps when animations are off.
                        val d = if (motion.enabled) 160 else 0
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(d, easing = LinearEasing)) { it * dir } + fadeIn(tween(d / 2))) togetherWith
                            (slideOutVertically(tween(d, easing = LinearEasing)) { -it * dir } + fadeOut(tween(d / 2)))
                    },
                    modifier = Modifier.clipToBounds(),
                    label = "dot$i",
                ) { c ->
                    Canvas(Modifier.size(width = pitch * 5, height = glyphH)) { drawDotGlyph(c, color, pitch.toPx()) }
                }
            }
        }
    }
}

private fun DrawScope.drawDotGlyph(c: Char, color: Color, pitch: Float) {
    val rows = DOT_GLYPHS[c] ?: return
    val r = pitch * 0.45f
    for (row in 0 until 7) for (col in 0 until 5) {
        val lit = rows[row] shr (4 - col) and 1 == 1
        drawCircle(
            color = if (lit) color else color.copy(alpha = 0.06f),
            radius = r,
            center = Offset(pitch * (col + 0.5f), pitch * (row + 0.5f)),
        )
    }
}

// MARK: - DockTether

/** Sentinel: the first pulse key has been consumed. */
private object Unset

/** The phone ↔ Mac link as drawn by [DockTether]. */
enum class TetherState { LINKED, CONNECTING, OFFLINE }

/**
 * The live line between the phone and the Mac: hero, pairing, transfers. Endpoints sit at the start and end; the
 * line between them shows the link.
 *
 * - LINKED: a solid line. Each time [pulseKey] changes, ONE pulse travels along it (an event: a file, a clip, a
 *   notification). No idle loop.
 * - CONNECTING: a dashed line whose dashes drift toward the end while [rememberAmbientActive] (foreground, animations on,
 *   not battery saver); static dashes otherwise.
 * - OFFLINE: the line breaks in the middle; the end half is faint.
 * - [progress] (0..1, LINKED or CONNECTING): fills the line from the start (transfers, setup steps). Null resets it.
 * - A plain LINKED line with no [pulseKey] and no [progress] takes a static path (no animation state): cheap in lists.
 *
 * Signature looks: DEPTH = primary → tertiary gradient with a soft glow; BLOCKS = thick rounded bar on primaryContainer;
 * INSTRUMENT = a row of dots, square endpoints; SHELF = a thin calm line with ring endpoints.
 *
 * @param reverse pulse direction: false = start → end (phone → Mac in LTR), true = end → start.
 * @param start / [end] endpoint slots (e.g. a phone and a laptop glyph). Default: a small endpoint dot.
 */
@Composable
fun DockTether(
    state: TetherState,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    pulseKey: Any? = null,
    reverse: Boolean = false,
    height: Dp = 24.dp,
    /** Draw everything in this colour instead of the signature's (e.g. on a primary-filled surface). */
    tint: Color? = null,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
) {
    val sig = DockTheme.signature
    val s = DockTheme.scheme
    val motion = DockTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Fast static path (per-row tethers in lists): a plain linked line has no Animatables, effects or lifecycle
    // subscription at all. Anything that can move (events, progress, connecting dashes) takes the animated path.
    val animated = !(state == TetherState.LINKED && pulseKey == null && progress == null)
    // Bookkeeping lives outside the branches, so switching from the static path (e.g. pulseKey null → 1) never swallows
    // the first event.
    val firstKey = remember { arrayOf<Any?>(pulseKey) }
    val lastState = remember { arrayOf(state) }
    val pulseV: () -> Float
    val phaseV: () -> Float
    val fillV: () -> Float
    if (animated) {
        val pulse = remember { Animatable(-1f) }
        val pulseMs = if (sig == DockSignature.INSTRUMENT) 360 else 520
        // One pulse per event. A pulse interrupted mid-flight (new key, state change, leaving composition) always clears,
        // so a dot can never stay frozen on the line.
        suspend fun runPulse() {
            try {
                pulse.snapTo(0f)
                pulse.animateTo(1f, tween(pulseMs, easing = LinearEasing))
            } finally {
                withContext(NonCancellable) { pulse.snapTo(-1f) }
            }
        }
        LaunchedEffect(pulseKey) {
            // Not on first composition (a screen opening), and only along a live line.
            if (pulseKey == firstKey[0] || state != TetherState.LINKED || !motion.enabled) return@LaunchedEffect
            firstKey[0] = Unset
            runPulse()
        }
        // Reconnect: exactly one pulse when the line comes back up (not on first composition).
        LaunchedEffect(state) {
            val was = lastState[0]
            lastState[0] = state
            if (state != TetherState.LINKED) { pulse.snapTo(-1f); return@LaunchedEffect }
            if (was != TetherState.LINKED && motion.enabled) runPulse()
        }
        // Connecting dashes drift only while ambient motion may run.
        val ambient = rememberAmbientActive()
        val phase = remember { Animatable(0f) }
        LaunchedEffect(state, ambient) {
            if (state != TetherState.CONNECTING || !ambient) { phase.snapTo(0f); return@LaunchedEffect }
            while (true) {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(900, easing = LinearEasing))
            }
        }
        val fill = remember { Animatable(progress ?: 0f) }
        LaunchedEffect(progress) {
            // A finished transfer resets, so the next one draws in from 0 instead of animating back from 100 %.
            if (progress == null) fill.snapTo(0f) else fill.animateTo(progress.coerceIn(0f, 1f), motion.smooth())
        }
        pulseV = { pulse.value }; phaseV = { phase.value }; fillV = { fill.value }
    } else {
        pulseV = { -1f }; phaseV = { 0f }; fillV = { 0f }
    }

    val line = if (tint != null) tint.copy(alpha = 0.45f) else when (sig) {
        DockSignature.BLOCKS -> s.primaryContainer
        DockSignature.INSTRUMENT, DockSignature.SHELF -> s.onSurfaceVariant.copy(alpha = 0.55f)
        DockSignature.DEPTH -> s.primary.copy(alpha = 0.55f)
    }
    val strong = tint ?: when (sig) {
        DockSignature.INSTRUMENT -> DockTheme.colors.signal
        else -> s.primary
    }
    val glow = tint ?: s.tertiary

    Row(modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        if (start != null) start() else TetherEnd(sig, if (state == TetherState.OFFLINE) line else strong)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.weight(1f).height(height)) {
            val y = size.height / 2
            val w = size.width
            val thick = when (sig) {
                DockSignature.BLOCKS -> 6.dp.toPx()
                DockSignature.DEPTH -> 3.dp.toPx()
                DockSignature.INSTRUMENT -> 2.5.dp.toPx()
                DockSignature.SHELF -> 2.dp.toPx()
            }
            fun x(f: Float) = if (rtl) w * (1f - f) else w * f
            when (state) {
                TetherState.OFFLINE -> {
                    drawSegment(sig, x(0f), x(0.42f), y, thick, line)
                    drawSegment(sig, x(0.58f), x(1f), y, thick, line.copy(alpha = line.alpha * 0.4f))
                }
                TetherState.CONNECTING -> {
                    val dash = 8.dp.toPx(); val gap = 6.dp.toPx()
                    val shift = (if (rtl) 1 else -1) * phaseV() * (dash + gap)
                    drawLine(
                        line, Offset(0f, y), Offset(w, y), thick, StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), shift),
                    )
                    // Progress draws in over the dashes (e.g. pairing / setup steps) instead of looping forever.
                    if (progress != null) drawSegment(sig, x(0f), x(fillV()), y, thick, strong)
                }
                TetherState.LINKED -> {
                    if (sig == DockSignature.DEPTH) {
                        // Soft glow under the line; colour only, no blur (cheap on every API level).
                        drawLine(strong.copy(alpha = 0.18f), Offset(0f, y), Offset(w, y), thick * 4, StrokeCap.Round)
                        drawLine(
                            Brush.horizontalGradient(if (rtl) listOf(glow, strong) else listOf(strong, glow)),
                            Offset(0f, y), Offset(w, y), thick, StrokeCap.Round,
                        )
                    } else drawSegment(sig, 0f, w, y, thick, line)
                    if (progress != null) drawSegment(sig, x(0f), x(fillV()), y, thick, strong)
                    val p = pulseV()
                    if (p >= 0f) {
                        val f = if (reverse) 1f - p else p
                        val r = thick * if (sig == DockSignature.BLOCKS) 0.9f else 1.6f
                        val a = (1f - kotlin.math.abs(p - 0.5f) * 1.6f).coerceIn(0.25f, 1f)
                        if (sig == DockSignature.INSTRUMENT) {
                            drawRect(strong.copy(alpha = a), Offset(x(f) - r, y - r), Size(r * 2, r * 2))
                        } else {
                            drawCircle(strong.copy(alpha = a * 0.3f), r * 2.4f, Offset(x(f), y))
                            drawCircle(strong.copy(alpha = a), r, Offset(x(f), y))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (end != null) end() else TetherEnd(sig, if (state == TetherState.LINKED) strong else line)
    }
}

private fun DrawScope.drawSegment(sig: DockSignature, from: Float, to: Float, y: Float, thick: Float, color: Color) {
    if (sig == DockSignature.INSTRUMENT) {
        // A row of dots, like the matrix numerals.
        val pitch = thick * 2.6f
        val a = minOf(from, to); val b = maxOf(from, to)
        var x = a + thick / 2
        while (x <= b) { drawCircle(color, thick / 2, Offset(x, y)); x += pitch }
    } else {
        drawLine(color, Offset(from, y), Offset(to, y), thick, StrokeCap.Round)
    }
}

@Composable
private fun TetherEnd(sig: DockSignature, color: Color) {
    Canvas(Modifier.size(10.dp)) {
        when (sig) {
            DockSignature.INSTRUMENT -> drawRect(color)
            DockSignature.SHELF -> drawCircle(color, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            DockSignature.BLOCKS -> drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
            DockSignature.DEPTH -> {
                drawCircle(color.copy(alpha = 0.25f), size.minDimension / 2)
                drawCircle(color, size.minDimension / 3.2f)
            }
        }
    }
}
