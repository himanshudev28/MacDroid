package com.droiddock.app.ui.components

import androidx.compose.ui.draw.drawBehind
import com.droiddock.app.ui.theme.glass.glassControl
import com.droiddock.app.ui.theme.glass.frostActive
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockGlow
import com.droiddock.app.ui.theme.dockPress

/**
 * Switch with a spring thumb. Replaces the M3 `Switch` so the thumb can move
 * on a pop spring and stretch while pressed (ColorOS elastic toggle).
 *
 * - Thumb position, size and press-stretch are animated floats read in
 *   `graphicsLayer` lambdas: a flip never recomposes the track.
 * - Calm/Standard: thumb grows 17% while pressed, 0.8-1.0 damping.
 *   Bouncy: grows 17% and stretches a further 25% in X, under-damped (0.6/500).
 * - The haptic fires on the flip (ToggleOn/Off), same frame as the thumb starts.
 * - Animations off: snaps.
 */
@Composable
fun DockSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val haptics = DockTheme.haptics
    val motion = DockTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val sig = DockTheme.signature
    // Per signature: INSTRUMENT clicks across in two mechanical steps; SHELF glides with no overshoot; the others pop.
    val twoSteps = androidx.compose.animation.core.Easing { t -> kotlin.math.floor(t * 2f) / 2f }
    val posSpec: androidx.compose.animation.core.AnimationSpec<Float> = when {
        !motion.enabled -> androidx.compose.animation.core.snap()
        sig == com.droiddock.app.ui.theme.DockSignature.INSTRUMENT -> androidx.compose.animation.core.tween(90, easing = twoSteps)
        sig == com.droiddock.app.ui.theme.DockSignature.SHELF -> motion.smooth()
        else -> motion.pop()
    }
    val pos = animateFloatAsState(if (checked) 1f else 0f, posSpec, label = "sw-pos")
    val grow = animateFloatAsState(
        if (pressed && enabled && sig != com.droiddock.app.ui.theme.DockSignature.INSTRUMENT) 1f else 0f, motion.pop(), label = "sw-grow",
    )
    val trackShape = if (mono) DockTheme.shapes.control else CircleShape
    val depth = sig == com.droiddock.app.ui.theme.DockSignature.DEPTH
    val blocks = sig == com.droiddock.app.ui.theme.DockSignature.BLOCKS

    val trackOn = if (mono) s.onSurface else s.primary
    val trackOff = if (mono) s.background else s.surfaceContainerHighest
    val thumbOn = if (mono) s.background else s.onPrimary
    val thumbOff = if (mono) s.onSurfaceVariant else s.outline
    val borderOff = if (mono) d.hairline.copy(alpha = 0.6f) else s.outline
    val track by animateColorAsState(if (checked) trackOn else trackOff, motion.fade(), label = "sw-track")
    val thumb by animateColorAsState(if (checked) thumbOn else thumbOff, motion.fade(), label = "sw-thumb")
    val border by animateColorAsState(if (checked) trackOn else borderOff, motion.fade(), label = "sw-border")
    val stretchy = motion.stretchy

    Box(
        modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onCheckedChange != null) Modifier.toggleable(
                    value = checked, enabled = enabled, role = Role.Switch,
                    interactionSource = interaction, indication = null,
                ) { v -> haptics.toggle(v); onCheckedChange(v) } else Modifier,
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .size(width = 52.dp, height = 32.dp)
            .then(
                if (frostActive()) Modifier.glassControl(
                    CircleShape,
                    if (checked) s.primary.copy(alpha = 0.88f) else s.onSurface.copy(alpha = if (d.isDark) 0.14f else 0.10f),
                )
                else Modifier.clip(trackShape).background(track).border(if (mono) 1.dp else 2.dp, border, trackShape)
            ),
    ) {
        Box(
            Modifier
                .padding(start = 4.dp)
                .align(Alignment.CenterStart)
                .size(24.dp)
                .graphicsLayer {
                    val p = pos.value
                    val g = grow.value
                    // Centre travels 16 dp -> 36 dp inside the 52 dp track.
                    translationX = 20.dp.toPx() * p
                    // INSTRUMENT: a full-size square block in both positions (no grow-on-check).
                    val base = if (mono) 0.8f else 0.67f + 0.33f * p
                    // DEPTH: the thumb stretches like a droplet while it travels (peaks mid-way, gone at rest).
                    val travel = if (depth) kotlin.math.sin(Math.PI * p.coerceIn(0f, 1f)).toFloat() * 0.32f else 0f
                    scaleY = base * (1f + 0.17f * g) * (1f - travel * 0.25f)
                    scaleX = base * (1f + (if (stretchy) 0.42f else 0.17f) * g) * (1f + travel)
                }
                .background(thumb, if (mono) DockTheme.shapes.control else CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!mono) {
                Icon(
                    Icons.Rounded.Check, null, tint = trackOn,
                    modifier = Modifier.size(SwitchDefaults.IconSize).graphicsLayer {
                        alpha = pos.value.coerceIn(0f, 1f)
                        // BLOCKS: the check spins in with the thumb's pop.
                        if (blocks) rotationZ = (1f - pos.value) * -90f
                    },
                )
            }
        }
    }
}

/**
 * ColorOS-style circular quick toggle (Lock, Screensaver, Mute): a big round
 * button that fills with the accent when on, label underneath. 64 dp circle.
 */
@Composable
fun DockCircleToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    /** Different glyph while on (Mute / Unmute): crossfades with a scale pop. Null keeps [icon] and pops it. */
    checkedIcon: ImageVector? = null,
) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val motion = DockTheme.motion
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val shape = if (mono) DockTheme.shapes.control else CircleShape
    val bg by animateColorAsState(
        if (checked) (if (mono) s.onSurface else s.primary) else d.raised,
        if (mono) androidx.compose.animation.core.snap() else motion.fade(), label = "ct-bg",
    )
    // INSTRUMENT: the ink fills the key from the bottom like a gauge (140 ms, linear), instead of a colour fade.
    val level by animateFloatAsState(
        if (checked) 1f else 0f,
        if (motion.enabled) androidx.compose.animation.core.tween(140, easing = androidx.compose.animation.core.LinearEasing)
        else androidx.compose.animation.core.snap(),
        label = "ct-level",
    )
    val fg by animateColorAsState(
        if (checked) (if (mono) s.background else s.onPrimary) else s.onSurface,
        if (mono) androidx.compose.animation.core.tween(140) else motion.fade(), label = "ct-fg",
    )
    val haptics = DockTheme.haptics
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.widthIn(min = size + 8.dp).toggleable(
            value = checked, interactionSource = interaction, indication = null, role = Role.Switch,
        ) { haptics.toggle(it); onToggle(it) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(size)
                .dockPress(interaction)
                .dockGlow(shape, 0.6f, visible = checked && !mono)
                .then(
                    if (frostActive()) Modifier.glassControl(
                        shape, if (checked) s.primary.copy(alpha = 0.90f) else d.raised.copy(alpha = 0.62f),
                    )
                    else if (mono) Modifier.clip(shape).background(d.raised)
                        .drawBehind { drawRect(s.onSurface, topLeft = androidx.compose.ui.geometry.Offset(0f, this.size.height * (1f - level))) }
                        .border(1.dp, d.hairline, shape)
                    else Modifier.clip(shape).background(bg)
                )
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            if (checkedIcon != null) {
                AnimatedContent(
                    targetState = checked,
                    transitionSpec = {
                        (fadeIn(motion.fade()) + scaleIn(motion.pop(), initialScale = 0.8f)) togetherWith
                            (fadeOut(motion.exit()) + scaleOut(motion.exit(), targetScale = 0.8f))
                    },
                    label = "ct-icon",
                ) { on -> Icon(if (on) checkedIcon else icon, null, tint = fg, modifier = Modifier.size(size * 0.40f)) }
            } else {
                val pop = animateFloatAsState(if (checked) 1f else 0.88f, motion.pop(), label = "ct-pop")
                Icon(
                    icon, null, tint = fg,
                    modifier = Modifier.size(size * 0.40f).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                )
            }
        }
        Text(
            if (DockTheme.type.upperCaseLabels) label.uppercase() else label,
            style = if (mono) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = s.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(0.dp))
    }
}
