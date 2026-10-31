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
 * THE press feedback for every interactive Dock component.
 *
 * - Pressed state starts on press-down (high-stiffness spring, ~40 ms to 90%),
 *   never waits for release.
 * - Scale = [DockMotion.pressScale]: 0.97 Calm/Standard, 0.94 Bouncy. Bouncy
 *   also stretches Y to 0.985x (ColorOS squish) and releases with its own
 *   under-damped spring, so it rebounds past 1. Calm/Standard release without
 *   overshoot (iOS).
 * - `graphicsLayer` only, value read in the draw phase: a press never recomposes
 *   the content. The Animatable keeps its velocity if the press is re-entered.
 * - [onPressDown] fires on the press event for haptics that must be felt with
 *   the motion start (long-press, keys). Toggles keep their haptic on the
 *   commit so a scroll that steals the touch never buzzes.
 *
 * @param scale override the personality's scale (e.g. 0.95 for large cards).
 */
@Composable
fun Modifier.dockPress(
    interaction: InteractionSource,
    enabled: Boolean = true,
    scale: Float? = null,
    onPressDown: (() -> Unit)? = null,
): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled || !enabled) return this
    val progress = remember { Animatable(0f) }
    val down = rememberUpdatedState(onPressDown)
    LaunchedEffect(interaction, motion) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    down.value?.invoke()
                    launch { progress.animateTo(1f, spring(1f, 3000f)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    progress.animateTo(
                        0f,
                        if (motion.stretchy) spring(motion.spatialDamping, motion.spatialStiffness)
                        else spring(0.92f, 600f),
                    )
                }
            }
        }
    }
    val target = scale ?: motion.pressScale
    val stretchy = motion.stretchy
    return this.graphicsLayer {
        val p = progress.value
        scaleX = 1f - (1f - target) * p
        scaleY = if (stretchy) 1f - (1f - target * 0.985f) * p else scaleX
        transformOrigin = TransformOrigin.Center
    }
}

@Deprecated("Use dockPress", ReplaceWith("dockPress(interaction, enabled)"))
@Composable
fun Modifier.pressSquish(interaction: InteractionSource, enabled: Boolean = true): Modifier =
    dockPress(interaction, enabled)

/**
 * Fade + small rise on first composition (empty states, first-load sections).
 * [index] staggers by 35 ms, capped at 6 items. Draw-phase only.
 */
@Composable
fun Modifier.dockEnter(index: Int = 0, riseDp: Float = 8f): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(minOf(index, 6) * 35L)
        p.animateTo(1f, tween(motion.pageMs, easing = motion.easing))
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * riseDp.dp.toPx()
    }
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
 * Staggered entrance for a screen's opening sections: fade, a 16 dp rise and
 * a 0.97 → 1 scale, 55 ms apart (capped at 6). Only items composed within
 * 400 ms of [entrance] animate, so scrolling never replays it. Draw-phase only.
 */
@Composable
fun Modifier.dockStagger(entrance: DockEntrance, index: Int): Modifier {
    val motion = LocalDockMotion.current
    if (!motion.enabled) return this
    val fresh = remember { android.os.SystemClock.uptimeMillis() - entrance.startedAt < 400 }
    if (!fresh) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L + minOf(index, 6) * 55L)
        p.animateTo(1f, spring(0.9f, motion.spatialStiffness * 0.6f))
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 16.dp.toPx()
        val sc = 0.97f + 0.03f * v
        scaleX = sc; scaleY = sc
    }
}
