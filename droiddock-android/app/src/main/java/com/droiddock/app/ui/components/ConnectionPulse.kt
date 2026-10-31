package com.droiddock.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * The last hero state seen, process-wide. Home is re-created on every tab
 * switch, so without this the "connected" burst would replay each time the
 * user comes back to Home instead of once per real connection.
 */
private object PulseMemory {
    var lastState: HeroState? = null
}

/**
 * Motion around the hero's device badge:
 * - **Connected:** the badge pops and three rings ripple out once (plus a
 *   Confirm haptic when the link came up while watching). Plays on the
 *   transition into CONNECTED, and once on first launch if already connected.
 * - **Searching:** two faint radar rings loop while the phone looks for the
 *   Mac. Only with ambient motion (off under battery saver / thermal).
 *
 * Rings draw behind the badge, outside its bounds; values are read in the
 * draw phase, so nothing recomposes per frame.
 */
@Composable
fun Modifier.connectionRipple(state: HeroState, connectedColor: Color, searchColor: Color): Modifier {
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    val burst = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(state) {
        val prev = PulseMemory.lastState
        PulseMemory.lastState = state
        val first = firstRun[0]
        firstRun[0] = false
        // First composition of this hero (Home re-created on a tab switch): only a cold
        // start plays, silently. Later changes happened while watching: burst + haptic.
        val play = state == HeroState.CONNECTED && motion.enabled &&
            if (first) prev == null else prev != HeroState.CONNECTED
        if (!play) return@LaunchedEffect
        if (!first) haptics.confirm()
        try {
            launch { pop.snapTo(0.84f); pop.animateTo(1f, spring(0.42f, 360f)) }
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1500, easing = LinearEasing))
        } finally {
            // Cancelled mid-burst (state changed): never leave a frozen ring or a shrunk badge.
            withContext(NonCancellable) { burst.snapTo(1f); pop.snapTo(1f) }
        }
    }
    val searching = state == HeroState.CONNECTING && motion.enabled && motion.ambient
    val radar = if (searching) {
        rememberInfiniteTransition(label = "radar").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "radar-p",
        )
    } else null

    return this
        .drawBehind {
            val r0 = size.minDimension / 2f
            radar?.let { st ->
                val v = st.value
                for (k in 0..1) {
                    val p = (v + k * 0.5f) % 1f
                    val e = LinearOutSlowInEasing.transform(p)
                    drawCircle(
                        searchColor.copy(alpha = 0.38f * (1f - p)),
                        r0 + e * 26.dp.toPx(),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            val b = burst.value
            if (b < 1f) {
                for (k in 0..2) {
                    val p = ((b - k * 0.16f) / 0.68f).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) continue
                    val e = 1f - (1f - p) * (1f - p) * (1f - p)
                    val r = r0 * 0.92f + e * 48.dp.toPx()
                    if (k == 0) drawCircle(connectedColor.copy(alpha = 0.16f * (1f - p)), r)
                    drawCircle(
                        connectedColor.copy(alpha = 0.6f * (1f - p)), r,
                        style = Stroke((3.5f * (1f - p) + 0.8f).dp.toPx()),
                    )
                }
            }
        }
        .graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
        }
}

/**
 * The 8 dp status dot. With [ping], one soft ring spreads from it when it
 * first appears: the hero re-creates the dot whenever the status line
 * changes, so each sync event reads as a single "blip" without a perpetual
 * animation.
 */
@Composable
fun PingDot(color: Color, shape: Shape, ping: Boolean, modifier: Modifier = Modifier) {
    val motion = DockTheme.motion
    val p = remember { Animatable(if (ping && motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (ping && motion.enabled) {
            delay(140)
            p.animateTo(1f, tween(1000, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        modifier
            .size(8.dp)
            .drawBehind {
                val v = p.value
                if (v < 1f) drawCircle(color.copy(alpha = 0.5f * (1f - v)), size.minDimension / 2f * (1f + 1.8f * v))
            }
            .clip(shape)
            .background(color),
    )
}
