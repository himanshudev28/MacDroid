package com.droiddock.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent

/**
 * Motion tokens. Springs for anything spatial (interruptible, keeps velocity),
 * tweens only for colour/alpha. Values come from [MotionPersonality] crossed
 * with the style: Minimal Mono is short and mechanical regardless.
 */
@Immutable
data class DockMotion(
    /** False when the system "Remove animations" is on: every spec snaps. */
    val enabled: Boolean,
    val spatialDamping: Float,
    val spatialStiffness: Float,
    val effectsDamping: Float,
    val effectsStiffness: Float,
    /** Scale a pressed control shrinks to. Bouncy = 0.94 (ColorOS squish). */
    val pressScale: Float,
    /** Colour/alpha fade length. */
    val fadeMs: Int,
    /** Screen / tab transition length. */
    val pageMs: Int,
    val easing: Easing,
    /** Bouncy: menus and sheets overshoot and stretch with the finger. */
    val stretchy: Boolean,
    /**
     * False under battery saver / thermal throttling: no shimmer, no drifting
     * trails, no connecting sweep. One-shot transitions still run (at Calm).
     */
    val ambient: Boolean = true,
) {
    /** Anything that moves or reshapes. May overshoot in Bouncy. */
    fun <T> spatial(threshold: T? = null): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(spatialDamping, spatialStiffness, threshold)

    /** Spatial without bounce: values that must not overshoot (progress, counters, size). */
    fun <T> smooth(threshold: T? = null): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(1f, spatialStiffness, threshold)

    /** Chrome (tabs, nav indicator, segmented): quicker and never bounces. */
    fun <T> chrome(threshold: T? = null): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(maxOf(spatialDamping, 0.92f), spatialStiffness * 1.5f, threshold)

    /** Playful pop (toggle thumb, success): Apple 0.30 s / bounce 0.25 at Bouncy, calmer below. */
    fun <T> pop(threshold: T? = null): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(if (stretchy) 0.6f else maxOf(spatialDamping, 0.8f), if (stretchy) 500f else 440f, threshold)

    /** Exits run at ~0.6x the entrance. */
    fun <T> exit(): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween((fadeMs * 0.6f).toInt(), easing = easing)

    /** The same motion with everything snapped: wrap keyboard-initiated changes in this. */
    fun snapped(): DockMotion = if (enabled) copy(enabled = false) else this

    fun <T> effects(): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(effectsDamping, effectsStiffness)

    fun <T> fade(): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween(fadeMs, easing = easing)

    fun <T> page(): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween(pageMs, easing = easing)

    companion object {
        private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        private val Mechanical = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

        fun of(style: UiStyle, personality: MotionPersonality, enabled: Boolean, ambient: Boolean = true): DockMotion {
            val base = when (personality) {
                MotionPersonality.CALM -> DockMotion(enabled, 1f, 300f, 1f, 1200f, 0.97f, 220, 320, Emphasized, false)
                MotionPersonality.STANDARD -> DockMotion(enabled, 0.8f, 380f, 1f, 1600f, 0.965f, 180, 260, Emphasized, false)
                MotionPersonality.BOUNCY -> DockMotion(enabled, 0.62f, 420f, 0.85f, 1400f, 0.94f, 180, 280, Emphasized, true)
            }
            return resolveStyle(style, base).copy(ambient = ambient)
        }

        private fun resolveStyle(style: UiStyle, base: DockMotion): DockMotion {
            return when (style) {
                // Short, mechanical, almost no overshoot: 120-180 ms.
                UiStyle.MINIMAL_MONO -> base.copy(
                    spatialDamping = maxOf(base.spatialDamping, 0.85f),
                    spatialStiffness = base.spatialStiffness * 2f,
                    effectsStiffness = Spring.StiffnessHigh,
                    fadeMs = 120, pageMs = 160, easing = Mechanical,
                    pressScale = maxOf(base.pressScale, 0.97f),
                )
                // Slower, smoother: lens morphs read better at 350-450 ms.
                UiStyle.LIQUID_GLASS -> base.copy(
                    spatialStiffness = base.spatialStiffness * 0.85f,
                    pageMs = base.pageMs + 60,
                )
                UiStyle.REACH -> base.copy(pageMs = base.pageMs + 20)
                UiStyle.MATERIAL_YOU -> base
            }
        }

        /** System "Remove animations" (animator duration scale 0). */
        fun systemAnimationsEnabled(): Boolean =
            runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
}

val LocalDockMotion = staticCompositionLocalOf {
    DockMotion.of(UiStyle.LIQUID_GLASS, MotionPersonality.STANDARD, true)
}

/**
 * THE press feedback for every interactive Dock component, in the current "Docking" signature.
 *
 * - DEPTH (Liquid Glass): shrinks to the personality's scale; with [tilt] it also leans up to 4° toward the finger,
 *   like a lens pressed at that point (cards and tiles only, never list rows; off under battery saver).
 * - BLOCKS (Material You): squashes to 0.94 and releases with a small overshoot.
 * - INSTRUMENT (Minimal Mono): no scale; an ink flash over the element's own pixels (mechanical, instant).
 * - SHELF (Reach): sinks 2 dp and to 0.98, like pressing into a shelf.
 * - Bouncy personality adds the ColorOS-style Y stretch to the scaling signatures.
 *
 * Pressed state starts on press-down (high-stiffness spring), never waits for release. Draw phase only (graphicsLayer /
 * drawWithContent): a press never recomposes the content. [onPressDown] fires on the press event for haptics.
 *
 * @param scale override the scale (e.g. 0.95 for large cards). Ignored by INSTRUMENT.
 * @param tilt lean toward the finger (DEPTH only). Pass true on cards and tiles, never on list rows.
 */
@Composable
fun Modifier.dockPress(
    interaction: InteractionSource,
    enabled: Boolean = true,
    scale: Float? = null,
    onPressDown: (() -> Unit)? = null,
    tilt: Boolean = false,
): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled || !enabled) return this
    val sig = LocalDockStyle.current.signature
    val progress = remember { Animatable(0f) }
    val at = remember { floatArrayOf(0.5f, 0.5f, 0f, 0f) }   // press x, y (px), then size w, h captured at draw
    val down = rememberUpdatedState(onPressDown)
    LaunchedEffect(interaction, motion, sig) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    at[0] = i.pressPosition.x; at[1] = i.pressPosition.y
                    down.value?.invoke()
                    launch { progress.animateTo(1f, spring(1f, if (sig == DockSignature.INSTRUMENT) 6000f else 3000f)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    progress.animateTo(
                        0f,
                        when {
                            motion.stretchy && sig != DockSignature.INSTRUMENT -> spring(motion.spatialDamping, motion.spatialStiffness)
                            sig == DockSignature.BLOCKS || sig == DockSignature.INSTRUMENT -> motion.signatureRelease(sig)
                            else -> spring(0.92f, 600f)
                        },
                    )
                }
            }
        }
    }
    if (sig == DockSignature.INSTRUMENT) {
        val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        // Offscreen + SrcAtop: the flash tints only the element's own pixels, so it never squares off rounded corners.
        // The offscreen layer exists only while a press is visible; idle clickables (long lists) stay on the cheap path.
        return this
            .graphicsLayer {
                compositingStrategy = if (progress.value > 0.01f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                else androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val p = progress.value
                if (p > 0.01f) drawRect(ink.copy(alpha = 0.14f * p), blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
            }
    }
    val target = when (sig) {
        DockSignature.BLOCKS -> minOf(scale ?: motion.pressScale, 0.94f)
        DockSignature.SHELF -> scale ?: maxOf(motion.pressScale, 0.98f)
        else -> scale ?: motion.pressScale
    }
    val stretchy = motion.stretchy
    val leans = tilt && sig == DockSignature.DEPTH && motion.ambient
    return this.graphicsLayer {
        val p = progress.value
        scaleX = 1f - (1f - target) * p
        scaleY = if (stretchy) 1f - (1f - target * 0.985f) * p else scaleX
        transformOrigin = TransformOrigin.Center
        if (sig == DockSignature.SHELF) translationY = 2.dp.toPx() * p
        if (leans && size.width > 0f && size.height > 0f) {
            val fx = (at[0] / size.width).coerceIn(0f, 1f) - 0.5f
            val fy = (at[1] / size.height).coerceIn(0f, 1f) - 0.5f
            // Lean the pressed point away from the viewer: max 4° each axis.
            rotationY = -fx * 8f * p
            rotationX = fy * 8f * p
            cameraDistance = 16f * density
        }
    }
}

@Deprecated("Use dockPress", ReplaceWith("dockPress(interaction, enabled)"))
@Composable
fun Modifier.pressSquish(interaction: InteractionSource, enabled: Boolean = true): Modifier =
    dockPress(interaction, enabled)

/**
 * Entrance on first composition (empty states, first-load sections), in the current signature: DEPTH rises out of
 * depth, BLOCKS pops from 0.9, INSTRUMENT wipes start → end, SHELF slides up from below. [index] staggers by 35 ms,
 * capped at 6 items. Draw-phase only. Animations off: no-op.
 */
@Composable
fun Modifier.dockEnter(index: Int = 0, riseDp: Float = 8f): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled) return this
    val sig = LocalDockStyle.current.signature
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(minOf(index, 6) * 35L)
        p.animateTo(1f, signatureEntranceSpec(motion, sig))
    }
    return dockSignatureLayer(sig, { p.value }, distanceDp = riseDp * 2f)
}

/** Entrance curve per signature: mechanical for INSTRUMENT, a pop for BLOCKS, the page tween otherwise. */
internal fun signatureEntranceSpec(motion: DockMotion, sig: DockSignature): AnimationSpec<Float> = when (sig) {
    DockSignature.INSTRUMENT -> tween(140, easing = androidx.compose.animation.core.LinearEasing)
    DockSignature.BLOCKS -> motion.pop()
    DockSignature.SHELF -> spring(1f, motion.spatialStiffness * 0.7f)
    DockSignature.DEPTH -> tween(motion.pageMs, easing = motion.easing)
}

/**
 * List item insert / remove / reorder with Dock specs. Use on every lazy
 * list row that has a stable `key`:
 * `items(list, key = { it.id }) { Row(dockItemAnimation()) }`.
 * Placement is a spatial spring (springs keep velocity when the list changes
 * mid-move); fade-in is a tween, fade-out runs at 0.6x. No-op when animations
 * are off.
 */
@Composable
fun LazyItemScope.dockItemAnimation(): Modifier {
    val m = LocalDockMotion.current
    if (!m.enabled) return Modifier
    return Modifier.animateItem(
        fadeInSpec = m.fade(),
        placementSpec = m.spatial(IntOffset.VisibilityThreshold),
        fadeOutSpec = m.exit(),
    )
}

/** Grid flavour of [dockItemAnimation]. */
@Composable
fun LazyGridItemScope.dockItemAnimation(): Modifier {
    val m = LocalDockMotion.current
    if (!m.enabled) return Modifier
    return Modifier.animateItem(
        fadeInSpec = m.fade(),
        placementSpec = m.spatial(IntOffset.VisibilityThreshold),
        fadeOutSpec = m.exit(),
    )
}

/** When a screen first composed: lets [dockStagger] animate only the first screenful. */
@Stable
class DockEntrance internal constructor(val startedAt: Long)

private val playedEntrances = HashSet<String>()

/**
 * One per screen; plays once per process for [key] (cold start), so coming back
 * to a tab is instant. Items composed later (scrolled into view) appear without motion.
 */
@Composable
fun rememberDockEntrance(key: String): DockEntrance = remember {
    if (playedEntrances.add(key)) DockEntrance(android.os.SystemClock.uptimeMillis()) else DockEntrance(0L)
}

/**
 * Staggered entrance for a screen's opening sections, in the current signature (see [dockEnter]),
 * 55 ms apart (30 ms for INSTRUMENT), capped at 6. Only items composed within
 * 400 ms of [entrance] animate, so scrolling never replays it. Draw-phase only.
 */
@Composable
fun Modifier.dockStagger(entrance: DockEntrance, index: Int): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled) return this
    val fresh = remember { android.os.SystemClock.uptimeMillis() - entrance.startedAt < 400 }
    if (!fresh) return this
    val sig = LocalDockStyle.current.signature
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // INSTRUMENT ticks through quickly (a scan); the others cascade.
        val step = if (sig == DockSignature.INSTRUMENT) 30L else 55L
        kotlinx.coroutines.delay(60L + minOf(index, 6) * step)
        p.animateTo(
            1f,
            if (sig == DockSignature.DEPTH) spring(0.9f, motion.spatialStiffness * 0.6f) else signatureEntranceSpec(motion, sig),
        )
    }
    return dockSignatureLayer(sig, { p.value })
}
