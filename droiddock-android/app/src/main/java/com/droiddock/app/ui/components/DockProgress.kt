package com.droiddock.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle

/**
 * Determinate progress. Thick rounded track (Pixel Expressive) in most styles;
 * a row of segments in Minimal Mono. Progress follows a critically damped
 * spring (no overshoot, keeps velocity between updates: chunky byte counts
 * glide) and is read in the draw phase only. Same in Calm/Standard/Bouncy
 * apart from the spring stiffness (300/380/420).
 */
@Composable
fun DockProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = if (DockTheme.style == UiStyle.MINIMAL_MONO) 6.dp else 8.dp,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), DockTheme.motion.smooth(), label = "progress")
    val track = DockTheme.colors.inset
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val signal = if (mono) MaterialTheme.colorScheme.onSurface else color
    Canvas(
        modifier.fillMaxWidth().height(height).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        },
    ) {
        val r = size.height / 2
        if (mono) {
            val n = 24
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val on = (i + 0.5f) / n <= p
                drawRect(if (on) signal else track, Offset(i * (w + gap), 0f), Size(w, size.height))
            }
        } else {
            drawRoundRect(track, cornerRadius = CornerRadius(r))
            if (p > 0f) drawRoundRect(signal, size = Size(maxOf(size.height, size.width * p), size.height), cornerRadius = CornerRadius(r))
        }
    }
}

/**
 * Skeleton placeholder rows while a list loads: stable layout, no spinner.
 * The shimmer sweeps in the draw phase and runs ONLY while the skeleton is
 * on screen (viewport visibility), the app is resumed (the frame clock stops
 * otherwise), animations are on and battery saver is off. Otherwise it is a
 * flat placeholder.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DockSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    val base = DockTheme.colors.inset
    val hi = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.55f)
    val motion = DockTheme.motion
    var visible by remember { mutableStateOf(true) }
    val phase = remember { Animatable(0.3f) }
    val active = motion.enabled && motion.ambient && visible
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(1200, easing = LinearEasing))
        }
    }
    val anim = phase.asState()
    val shape = DockTheme.shapes.chip
    Column(
        modifier.fillMaxWidth().onVisibilityChanged(minDurationMs = 0, minFractionVisible = 0.01f) { visible = it },
        verticalArrangement = Arrangement.spacedBy(DockTheme.spacing.l),
    ) {
        repeat(rows) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = DockTheme.spacing.cardPadding)) {
                Spacer(Modifier.size(40.dp).clip(DockTheme.shapes.badge).shimmer(base, hi, anim, active))
                Spacer(Modifier.width(DockTheme.spacing.l))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.fillMaxWidth(if (i % 2 == 0) 0.7f else 0.55f).height(14.dp).clip(shape).shimmer(base, hi, anim, active))
                    Spacer(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(shape).shimmer(base, hi, anim, active))
                }
            }
        }
    }
}

private fun Modifier.shimmer(base: Color, hi: Color, t: State<Float>, active: Boolean) = drawBehind {
    drawRect(base)
    if (!active) return@drawBehind
    val w = size.width
    val x = -w + t.value * 3 * w
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = x, endX = x + w))
}
