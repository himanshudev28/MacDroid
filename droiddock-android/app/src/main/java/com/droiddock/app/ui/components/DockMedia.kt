package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockGlow

/**
 * Now-playing card for the Mac's media (ColorOS-inspired): frosted colour
 * wash, glowing contour while playing, and a light-trail progress line. The Mac
 * doesn't report a position yet, so with [progress] null the trail drifts
 * while playing (and sits still when paused or animations are off).
 */
@Composable
fun DockNowPlaying(
    title: String,
    subtitle: String,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    playLabel: String = "Play",
    pauseLabel: String = "Pause",
    previousLabel: String = "Previous",
    nextLabel: String = "Next",
) {
    val style = DockTheme.style
    val motion = DockTheme.motion
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val shape = DockTheme.shapes.card
    val mono = style == UiStyle.MINIMAL_MONO
    val wash = when (style) {
        UiStyle.MINIMAL_MONO -> Brush.linearGradient(listOf(d.card, d.card))
        else -> Brush.linearGradient(
            listOf(
                lerpC(d.card, s.tertiaryContainer, if (d.isDark) 0.55f else 0.45f),
                lerpC(d.card, s.primaryContainer, if (d.isDark) 0.35f else 0.30f),
            ),
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (playing) Modifier.dockGlow(shape, 0.7f) else Modifier)
            .then(
                if (frostActive()) Modifier.frosted(shape, lerpC(d.card, s.tertiaryContainer, if (d.isDark) 0.5f else 0.35f), accent = s.tertiary)
                else Modifier.clip(shape).background(wash)
                    .then(DockTheme.surfaces.cardBorder?.let { Modifier.border(it, shape) } ?: Modifier)
            )
            .padding(DockTheme.spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockIconBadge(Icons.Rounded.MusicNote, tone = Tone.TERTIARY, size = 48.dp)
            Spacer(Modifier.width(DockTheme.spacing.m))
            // Track change: the old line fades out while the new one rises 8 dp and fades in.
            AnimatedContent(
                targetState = title to subtitle,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(motion.fade()) + slideInVertically(motion.smooth()) { it / 3 }) togetherWith
                        fadeOut(motion.exit())
                },
                contentAlignment = Alignment.CenterStart,
                label = "np-track",
            ) { (t, sub) ->
                Column {
                    Text(t, style = MaterialTheme.typography.titleMedium, color = s.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(DockTheme.spacing.l))
        LightTrail(progress, playing)
        Spacer(Modifier.height(DockTheme.spacing.m))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DockTheme.spacing.l, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockIconButton(Icons.Rounded.SkipPrevious, previousLabel, onPrevious, filled = false)
            val playShape = if (mono) DockTheme.shapes.control else CircleShape
            Box(
                Modifier
                    .size(64.dp)
                    .clip(playShape)
                    .background(s.primary)
                    .dockClickable(onClick = onPlayPause)
                    .semantics { contentDescription = if (playing) pauseLabel else playLabel },
                contentAlignment = Alignment.Center,
            ) {
                // Play <-> pause morph: scale + fade pop (a vector morph would need a custom AVD).
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = {
                        (fadeIn(motion.fade()) + scaleIn(motion.pop(), initialScale = 0.6f)) togetherWith
                            (fadeOut(motion.exit()) + scaleOut(motion.exit(), targetScale = 0.6f))
                    },
                    label = "np-playpause",
                ) { on ->
                    Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                        tint = s.onPrimary, modifier = Modifier.size(32.dp))
                }
            }
            DockIconButton(Icons.Rounded.SkipNext, nextLabel, onNext, filled = false)
        }
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
private fun LightTrail(progress: Float?, playing: Boolean) {
    val s = MaterialTheme.colorScheme
    val track = DockTheme.colors.inset
    val head = if (DockTheme.style == UiStyle.MINIMAL_MONO) s.tertiary else s.primary
    val motion = DockTheme.motion
    // Position is only known when the Mac reports it: glide to each new value
    // (critically damped, no overshoot). The trail dims 250 ms after a pause.
    val shown = animateFloatAsState(progress?.coerceIn(0f, 1f) ?: 0f, motion.smooth(), label = "np-progress")
    val trailAlpha = animateFloatAsState(if (playing) 1f else 0.45f, if (motion.enabled) tween(250) else snap(), label = "np-trail")
    // The fake drift is the only loop here: only while playing, animations on, not on battery saver.
    val drift = if (progress == null && playing && motion.enabled && motion.ambient) {
        rememberInfiniteTransition(label = "trail").animateFloat(
            0.05f, 0.95f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "t",
        )
    } else null
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = CornerRadius(r))
        val p = (if (progress != null) shown.value else drift?.value ?: 0.35f).coerceIn(0f, 1f)
        val x = size.width * p
        val a = trailAlpha.value
        // The trail: fades in behind the head, like a long exposure.
        drawRoundRect(
            Brush.horizontalGradient(listOf(head.copy(alpha = 0f), head.copy(alpha = 0.85f * a)), startX = 0f, endX = x),
            size = Size(x, size.height), cornerRadius = CornerRadius(r),
        )
        drawCircle(head.copy(alpha = 0.25f * a), radius = size.height * 1.6f, center = Offset(x, r))
        drawCircle(head, radius = size.height * 0.9f, center = Offset(x, r))
    }
}
