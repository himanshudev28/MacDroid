package com.droiddock.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * "Docking" design language: each [UiStyle] is a *signature* with its own shape grammar, type, colour logic and motion,
 * so the four styles differ in behaviour, not just radii. Components and the `Modifier.dock*` motion helpers read it;
 * screens never branch on it (read [DockTheme.signature] only for the rare case that truly needs it).
 *
 * - [DEPTH] (Liquid Glass): floating lenses over a drifting aurora; rises out of depth; cards tilt toward the finger.
 * - [BLOCKS] (Material You): colour-blocked tonal slabs with one "docked" corner; pops from 0.9 with an overshoot.
 * - [INSTRUMENT] (Minimal Mono): ink on paper, hairlines, dot-matrix numerals; horizontal wipes, ink-flash presses.
 * - [SHELF] (Reach): content stacks up from the bottom edge; slides up from below; presses sink.
 */
enum class DockSignature { DEPTH, BLOCKS, INSTRUMENT, SHELF }

val UiStyle.signature: DockSignature
    get() = when (this) {
        UiStyle.LIQUID_GLASS -> DockSignature.DEPTH
        UiStyle.MATERIAL_YOU -> DockSignature.BLOCKS
        UiStyle.MINIMAL_MONO -> DockSignature.INSTRUMENT
        UiStyle.REACH -> DockSignature.SHELF
    }

/**
 * True only while looping decoration may run: animations on, not battery saver / thermal ([DockMotion.ambient]), and
 * the host lifecycle at least RESUMED (screen visible, app in the foreground). Every idle loop (aurora drift, tether
 * pulse, connecting dashes) gates on this, so nothing keeps the GPU awake in the background.
 */
@Composable
fun rememberAmbientActive(): Boolean {
    val motion = DockTheme.motion
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return motion.enabled && motion.ambient && state.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * Style-aware show/hide that keeps its layout slot (draw-phase only, no recomposition per frame). For content that
 * enters or leaves the *layout*, use `AnimatedVisibility(visible, enter = dockEnterTransition(), exit = dockExitTransition())`.
 *
 * DEPTH: scale 0.94 → 1 + fade. BLOCKS: pop from 0.9 with an overshoot. INSTRUMENT: start-to-end wipe, 140 ms.
 * SHELF: rises 24 dp from below + fade. Reduced motion: a 150 ms fade. Animations off: instant.
 */
@Composable
fun Modifier.dockReveal(visible: Boolean): Modifier {
    val motion = DockTheme.motion
    val sig = DockTheme.signature
    val p = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, motion) {
        val target = if (visible) 1f else 0f
        when {
            !motion.enabled -> p.snapTo(target)
            !visible -> p.animateTo(target, motion.exit())
            sig == DockSignature.INSTRUMENT -> p.animateTo(target, tween(140, easing = LinearEasing))
            sig == DockSignature.BLOCKS -> p.animateTo(target, motion.pop())
            else -> p.animateTo(target, motion.spatial())
        }
    }
    return dockSignatureLayer(sig, { p.value })
}

/**
 * The shared draw-phase transform behind [dockReveal], `dockEnter` and `dockStagger`: progress 0 = hidden, 1 = shown.
 * Reads [progress] only inside draw lambdas.
 */
internal fun Modifier.dockSignatureLayer(sig: DockSignature, progress: () -> Float, distanceDp: Float = 16f): Modifier =
    when (sig) {
        DockSignature.INSTRUMENT -> this
            .graphicsLayer { alpha = if (progress() <= 0f) 0f else 1f }
            .drawWithContent {
                val v = progress().coerceIn(0f, 1f)
                val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                val w = size.width * v
                clipRect(left = if (rtl) size.width - w else 0f, right = if (rtl) size.width else w) {
                    this@drawWithContent.drawContent()
                }
            }
        DockSignature.SHELF -> graphicsLayer {
            val v = progress()
            alpha = v.coerceIn(0f, 1f)
            translationY = (1f - v) * (distanceDp * 1.5f).dp.toPx()
        }
        DockSignature.BLOCKS -> graphicsLayer {
            val v = progress()
            alpha = v.coerceIn(0f, 1f)
            val s = 0.9f + 0.1f * v
            scaleX = s; scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0.6f)
        }
        DockSignature.DEPTH -> graphicsLayer {
            val v = progress()
            alpha = v.coerceIn(0f, 1f)
            val s = 0.94f + 0.06f * v
            scaleX = s; scaleY = s
            translationY = (1f - v) * (distanceDp * 0.5f).dp.toPx()
        }
    }

/** `AnimatedVisibility` enter transition in the current signature (see [dockReveal]). */
@Composable
fun dockEnterTransition(): EnterTransition {
    val m = DockTheme.motion
    if (!m.enabled) return EnterTransition.None
    return when (DockTheme.signature) {
        DockSignature.INSTRUMENT -> expandHorizontally(tween(140, easing = LinearEasing), expandFrom = Alignment.Start) +
            fadeIn(tween(60))
        DockSignature.SHELF -> slideInVertically(m.spatial()) { it / 3 } + fadeIn(m.fade())
        DockSignature.BLOCKS -> scaleIn(m.pop(), initialScale = 0.9f) + fadeIn(m.fade())
        DockSignature.DEPTH -> scaleIn(m.spatial(), initialScale = 0.94f) + fadeIn(m.fade())
    }
}

/** `AnimatedVisibility` exit transition in the current signature: faster than the entrance, never bouncy. */
@Composable
fun dockExitTransition(): ExitTransition {
    val m = DockTheme.motion
    if (!m.enabled) return ExitTransition.None
    return when (DockTheme.signature) {
        DockSignature.INSTRUMENT -> shrinkHorizontally(tween(100, easing = LinearEasing), shrinkTowards = Alignment.Start) +
            fadeOut(tween(60))
        DockSignature.SHELF -> slideOutVertically(m.exit()) { it / 4 } + fadeOut(m.exit())
        DockSignature.BLOCKS, DockSignature.DEPTH -> scaleOut(m.exit(), targetScale = 0.96f) + fadeOut(m.exit())
    }
}

/** Spring used where a signature needs one outside [DockMotion] (kept here so screens never write their own). */
internal fun DockMotion.signatureRelease(sig: DockSignature) =
    if (!enabled) spring<Float>(1f, 10_000f)
    else when (sig) {
        DockSignature.BLOCKS -> spring(minOf(spatialDamping, 0.55f), 520f)
        DockSignature.INSTRUMENT -> spring(1f, 2400f)
        DockSignature.SHELF -> spring(1f, spatialStiffness)
        DockSignature.DEPTH -> spring(0.7f, spatialStiffness)
    }
