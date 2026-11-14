package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockGlow

/** Connection state, which the hero shows by colour AND shape AND text. */
/** The hero's tether key when the caller sends no events. */
private object HeroNoEvents

enum class HeroState { CONNECTED, CONNECTING, PAUSED, OFFLINE, UNPAIRED }

/**
 * The connection hero: the only large coloured thing on Home. Device name in
 * display type, one status line, battery as a numeral, ONE primary action.
 *
 * - Material You: state-coloured container (primary/tertiary/neutral), cookie badge.
 * - Liquid Glass: content card over the colour field, glowing contour, round badge.
 * - Minimal Mono: hairline box, signal dot, thin tabular numeral, ink button.
 * - Reach: tall card, larger type, pill action at the bottom (thumb zone).
 *
 * Connecting shows a light line sweeping the bottom edge, not a spinner.
 */
@Composable
fun DockHero(
    title: String,
    status: String,
    state: HeroState,
    modifier: Modifier = Modifier,
    detail: String? = null,
    battery: Int? = null,
    charging: Boolean = false,
    deviceIcon: ImageVector = Icons.Rounded.Laptop,
    primaryLabel: String? = null,
    primaryIcon: ImageVector? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /** Change to send one pulse along the hero's tether (a file, a clip, a notification). */
    tetherPulseKey: Any? = null,
    /** The phone ↔ Mac tether under the title. Off for compact heroes. */
    showTether: Boolean = true,
) {
    val style = DockTheme.style
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val sp = DockTheme.spacing
    val shape = DockTheme.shapes.hero
    val motion = DockTheme.motion
    val live = state == HeroState.CONNECTED

    val targetBg = when (style) {
        // BLOCKS: the connected hero is the one solid primary slab on the screen.
        UiStyle.MATERIAL_YOU -> when (state) {
            HeroState.CONNECTED -> s.primary
            HeroState.CONNECTING -> s.tertiaryContainer
            else -> s.surfaceContainerHigh
        }
        UiStyle.REACH -> if (live) s.primaryContainer else d.card
        UiStyle.LIQUID_GLASS -> d.card
        UiStyle.MINIMAL_MONO -> d.card
    }
    val bg by animateColorAsState(targetBg, motion.fade(), label = "hero-bg")
    val onBg = when {
        style == UiStyle.MATERIAL_YOU && state == HeroState.CONNECTED -> s.onPrimary
        style == UiStyle.MATERIAL_YOU && state == HeroState.CONNECTING -> s.onTertiaryContainer
        style == UiStyle.REACH && live -> s.onPrimaryContainer
        else -> s.onSurface
    }
    val onBgDim = onBg.copy(alpha = 0.72f)
    val dotColor = when (state) {
        HeroState.CONNECTED -> if (style == UiStyle.MINIMAL_MONO) s.tertiary else d.success
        HeroState.CONNECTING -> s.tertiary
        HeroState.PAUSED -> d.warning
        HeroState.OFFLINE, HeroState.UNPAIRED -> s.outline
    }

    // Loops run only in the foreground (rememberAmbientActive). While connecting, the tether's drifting dashes already say
    // "connecting", so the bottom-edge shimmer only runs when the tether is hidden (at most two loops: radar + one).
    val ambientActive = com.droiddock.app.ui.theme.rememberAmbientActive()
    val shimmer = if (state == HeroState.CONNECTING && ambientActive && !showTether) {
        rememberInfiniteTransition(label = "hero-shimmer").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x",
        )
    } else null

    Column(
        modifier
            .fillMaxWidth()
            // Content swaps between states change the height (detail line, action): glide it.
            .animateContentSize(motion.smooth())
            .dockGlow(shape, visible = live || style == UiStyle.LIQUID_GLASS)
            .then(if (onClick != null) Modifier.clip(shape).dockClickable(tilt = true, onClick = onClick) else Modifier)
            .then(
                if (frostActive()) Modifier.frosted(shape, bg, accent = s.tertiary)
                else Modifier.clip(shape).background(bg)
                    .then(DockTheme.surfaces.cardBorder?.let { Modifier.border(it, shape) } ?: Modifier)
            )
            .dockCornerTicks()
            .drawWithContent {
                drawContent()
                val p = shimmer?.value ?: return@drawWithContent
                val w = size.width
                val x = -w * 0.4f + p * w * 1.8f
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, dotColor, Color.Transparent), startX = x - w * 0.3f, endX = x + w * 0.3f,
                    ),
                    topLeft = Offset(0f, size.height - 3.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(w, 3.dp.toPx()),
                )
            }
            .padding(if (style == UiStyle.REACH) sp.xl else sp.l + sp.xs),
    ) {
        // Top row: device badge, and the battery numeral on the trailing side.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceBadge(
                deviceIcon, live, onBg,
                Modifier.connectionRipple(
                    state,
                    connectedColor = if (style == UiStyle.MINIMAL_MONO) s.tertiary else d.success,
                    searchColor = s.tertiary,
                ),
            )
            Spacer(Modifier.weight(1f))
            if (battery != null) {
                Column(horizontalAlignment = Alignment.End) {
                    DockNumeral(battery, unit = "%", color = onBg)
                    if (charging) {
                        Icon(Icons.Rounded.BatteryChargingFull, null, tint = onBgDim, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(if (style == UiStyle.REACH) sp.xl else sp.l))

        // Status line: dot (shape channel) + text. State changes cross-fade with a
        // slight scale from the leading edge; the dot colour rides along.
        AnimatedContent(
            targetState = state to status,
            transitionSpec = {
                (fadeIn(motion.fade()) + scaleIn(motion.spatial(), initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 0.5f))) togetherWith
                    fadeOut(motion.exit())
            },
            contentAlignment = Alignment.CenterStart,
            label = "hero-status",
        ) { (st, text) ->
            val dot = when (st) {
                HeroState.CONNECTED -> if (style == UiStyle.MINIMAL_MONO) s.tertiary else d.success
                HeroState.CONNECTING -> s.tertiary
                HeroState.PAUSED -> d.warning
                HeroState.OFFLINE, HeroState.UNPAIRED -> s.outline
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PingDot(
                    dot,
                    if (style == UiStyle.MINIMAL_MONO) androidx.compose.foundation.shape.RoundedCornerShape(1.dp) else CircleShape,
                    ping = st == HeroState.CONNECTED,
                )
                Spacer(Modifier.width(sp.s))
                Text(
                    if (DockTheme.type.upperCaseLabels) text.uppercase() else text,
                    style = if (style == UiStyle.MINIMAL_MONO) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = onBgDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(sp.xs))
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.exit()) },
            label = "hero-title",
        ) { t ->
            Text(
                t, style = DockTheme.type.heroTitle, color = onBg,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        AnimatedContent(
            targetState = detail,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.exit()) },
            label = "hero-detail",
        ) { dtl ->
            if (dtl != null) {
                Column {
                    Spacer(Modifier.height(sp.xs))
                    Text(dtl, style = MaterialTheme.typography.bodyMedium, color = onBgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (showTether && state != HeroState.UNPAIRED) {
            Spacer(Modifier.height(sp.m))
            DockTether(
                state = when (state) {
                    HeroState.CONNECTED -> TetherState.LINKED
                    HeroState.CONNECTING -> TetherState.CONNECTING
                    else -> TetherState.OFFLINE
                },
                // Never null: the hero always takes the tether's animated path, so it gets its one reconnect pulse.
                pulseKey = tetherPulseKey ?: HeroNoEvents,
                height = 16.dp,
                // On BLOCKS' solid primary slab the tether is drawn in the slab's own content colour.
                tint = if (style == UiStyle.MATERIAL_YOU && live) onBg else null,
            )
        }
        // The action row: a state change that swaps its label cross-fades with a slight scale.
        AnimatedContent(
            targetState = primaryLabel,
            transitionSpec = {
                (fadeIn(motion.fade()) + scaleIn(motion.spatial(), initialScale = 0.97f)) togetherWith fadeOut(motion.exit())
            },
            label = "hero-action",
        ) { label ->
            if (label != null && onPrimary != null) {
                Column {
                    Spacer(Modifier.height(if (style == UiStyle.REACH) sp.xxl else sp.l + sp.xs))
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalButtonOnAccent provides (style == UiStyle.MATERIAL_YOU && live),
                    ) { Row(horizontalArrangement = Arrangement.spacedBy(sp.s), verticalAlignment = Alignment.CenterVertically) {
                        DockButton(
                            label, onPrimary, icon = primaryIcon,
                            kind = ButtonKind.PRIMARY, glow = true,
                            modifier = Modifier.weight(1f), fullWidth = true,
                        )
                        if (secondaryLabel != null && onSecondary != null) {
                            DockButton(secondaryLabel, onSecondary, kind = ButtonKind.TONAL)
                        }
                    } }
                }
            }
        }
    }
}

@Composable
private fun DeviceBadge(icon: ImageVector, live: Boolean, onBg: Color, modifier: Modifier = Modifier) {
    val style = DockTheme.style
    val s = MaterialTheme.colorScheme
    val shape = DockTheme.shapes.deviceBadge
    val size = if (style == UiStyle.REACH) 64.dp else 56.dp
    val fill: Modifier = when (style) {
        UiStyle.MINIMAL_MONO -> Modifier.border(1.dp, DockTheme.colors.hairline, shape)
        UiStyle.LIQUID_GLASS -> Modifier.background(
            Brush.linearGradient(listOf(s.primary, s.tertiary)), shape,
        )
        // On the primary slab the badge flips to onPrimary so it stays visible.
        UiStyle.MATERIAL_YOU -> Modifier.background(if (live) s.onPrimary else s.surfaceContainerHighest, shape)
        UiStyle.REACH -> Modifier.background(if (live) s.primary else DockTheme.colors.raised, shape)
    }
    val tint = when (style) {
        UiStyle.MINIMAL_MONO -> onBg
        UiStyle.LIQUID_GLASS -> s.onPrimary
        UiStyle.MATERIAL_YOU -> if (live) s.primary else s.onSurfaceVariant
        else -> if (live) s.onPrimary else s.onSurfaceVariant
    }
    // BLOCKS: the cookie turns an eighth (a one-shot pop) when the link comes up or drops; the icon stays upright.
    val turn by androidx.compose.animation.core.animateFloatAsState(
        if (style == UiStyle.MATERIAL_YOU && live) 45f else 0f, DockTheme.motion.pop(), label = "badge-turn",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize()
                .then(if (turn != 0f) Modifier.graphicsLayer { rotationZ = turn } else Modifier)
                .clip(shape).then(fill),
        )
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}
