package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.glassControl
import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.SurfaceMode
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPress

/**
 * Padding the shell reserves around a destination: the floating nav bar /
 * pill plus system bars. Screens add it to their list's `contentPadding` so
 * content scrolls *under* glass instead of stopping above it.
 */
val LocalDockContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

/** Semantic tint for icon badges, chips and tiles. Maps to colour roles per style. */
enum class Tone { NEUTRAL, PRIMARY, SECONDARY, TERTIARY, SUCCESS, WARNING, ERROR }

data class TonePair(val container: Color, val content: Color)

@Composable
fun toneColors(tone: Tone): TonePair {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    if (mono) {
        // Mono: no colour fills. Ink on a faint step; only error/warning get the signal.
        return when (tone) {
            Tone.ERROR, Tone.WARNING -> TonePair(d.raised, s.tertiary)
            else -> TonePair(d.raised, s.onSurface)
        }
    }
    return when (tone) {
        Tone.NEUTRAL -> TonePair(d.raised, s.onSurfaceVariant)
        Tone.PRIMARY -> TonePair(s.primaryContainer, s.onPrimaryContainer)
        Tone.SECONDARY -> TonePair(s.secondaryContainer, s.onSecondaryContainer)
        Tone.TERTIARY -> TonePair(s.tertiaryContainer, s.onTertiaryContainer)
        Tone.SUCCESS -> TonePair(d.successContainer, d.onSuccessContainer)
        Tone.WARNING -> TonePair(d.warningContainer, d.onWarningContainer)
        Tone.ERROR -> TonePair(s.errorContainer, s.onErrorContainer)
    }
}

/**
 * Clickable with the style's press feedback (squish + ripple). Use instead of
 * bare `clickable` on any custom tap target.
 */
@Composable
fun Modifier.dockClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .dockPress(interaction, enabled)
        .clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * Icon in a tonal container: the replacement for the old `IconBadge`.
 * Squircle in Material You, circle in Glass/Reach, outlined square in Mono.
 */
@Composable
fun DockIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.PRIMARY,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    val c = toneColors(tone)
    val shape = DockTheme.shapes.badge
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val glass = frostActive()
    Box(
        modifier
            .size(size)
            .then(
                if (glass) Modifier.glassControl(shape, c.container.copy(alpha = 0.78f))
                else Modifier.clip(shape).then(
                    if (mono) Modifier.border(1.dp, DockTheme.colors.hairline, shape)
                    else Modifier.background(c.container)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = c.content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Section header above a group. Upper-cased mono in Minimal Mono, primary-coloured elsewhere. */
@Composable
fun DockSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val type = DockTheme.type
    val style = DockTheme.style
    val color = when (style) {
        UiStyle.MINIMAL_MONO -> MaterialTheme.colorScheme.onSurfaceVariant
        UiStyle.REACH -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier.fillMaxWidth().padding(
            start = DockTheme.spacing.xs, end = DockTheme.spacing.xs,
            top = DockTheme.spacing.s, bottom = DockTheme.spacing.s,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (type.upperCaseLabels) title.uppercase() else title,
            style = type.overline, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                if (type.upperCaseLabels) action.uppercase() else action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(DockTheme.shapes.chip).dockClickable(onClick = onAction)
                    .padding(horizontal = DockTheme.spacing.s, vertical = DockTheme.spacing.xs),
            )
        }
    }
}

/** Divider between rows inside a card. Hairline in Mono; inset tonal line elsewhere. */
@Composable
fun DockDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val hairline = DockTheme.surfaces.mode == SurfaceMode.HAIRLINE
    HorizontalDivider(
        modifier.padding(start = inset),
        thickness = if (hairline) 1.dp else 0.5.dp,
        color = DockTheme.colors.divider,
    )
}

/** A shape's surface as the style draws it: tonal fill, or hairline outline on the page. */
@Composable
fun Modifier.dockSurface(shape: Shape, color: Color = DockTheme.colors.card): Modifier {
    if (frostActive()) return this.frosted(shape, color)
    val border = DockTheme.surfaces.cardBorder
    return this.clip(shape).background(color).let { if (border != null) it.border(border, shape) else it }
}
