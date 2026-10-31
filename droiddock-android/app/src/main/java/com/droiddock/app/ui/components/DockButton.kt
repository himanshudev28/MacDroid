package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.glassControl
import com.droiddock.app.ui.theme.glass.frostActive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockGlow

enum class ButtonKind {
    /** The one main action on a screen. Filled primary (ink in Mono). */
    PRIMARY,
    /** Filled tonal: secondary actions. */
    TONAL,
    /** Outlined: low-emphasis alternatives (Disconnect, Cancel). */
    SECONDARY,
    /** Text only. */
    GHOST,
    /** Destructive. */
    DANGER,
}

/**
 * Style-aware button. 48 dp min height, squish on press, haptic tap in
 * Glass/Mono. Set [glow] on the single primary action per screen.
 */
@Composable
fun DockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    glow: Boolean = false,
    fullWidth: Boolean = false,
    height: Dp = 52.dp,
    /** Side padding; small keys in a 2-column grid pass less so mono caps do not ellipsize. */
    horizontalPadding: Dp = 22.dp,
) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val glass = DockTheme.style == UiStyle.LIQUID_GLASS
    val shape = DockTheme.shapes.control
    val haptics = DockTheme.haptics
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(s.primary, s.onPrimary, null)
        ButtonKind.TONAL -> when {
            mono -> Triple(d.raised, s.onSurface, null)
            glass -> Triple(s.onSurface.copy(alpha = if (d.isDark) 0.12f else 0.09f), s.onSurface, d.hairline)
            else -> Triple(s.secondaryContainer, s.onSecondaryContainer, null)
        }
        ButtonKind.SECONDARY -> Triple(Color.Transparent, if (mono) s.onSurface else s.primary,
            if (mono) d.hairline.copy(alpha = 0.5f) else s.outlineVariant)
        ButtonKind.GHOST -> Triple(Color.Transparent, if (mono) s.onSurface else s.primary, null)
        ButtonKind.DANGER -> Triple(s.errorContainer, s.onErrorContainer, null)
    }
    val frost = frostActive()
    val dark = d.isDark
    // Glass: primary is a tinted capsule, the rest are clear pills.
    val glassFill = when (kind) {
        ButtonKind.PRIMARY -> s.primary.copy(alpha = 0.90f)
        // Strong enough to read as a button on a frosted card over the colour field.
        ButtonKind.TONAL -> s.onSurface.copy(alpha = if (dark) 0.14f else 0.10f)
        ButtonKind.SECONDARY -> s.onSurface.copy(alpha = if (dark) 0.08f else 0.06f)
        ButtonKind.DANGER -> s.errorContainer.copy(alpha = 0.80f)
        ButtonKind.GHOST -> Color.Transparent
    }
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = height)
            .then(if (glow && kind == ButtonKind.PRIMARY && enabled) Modifier.dockGlow(shape, 0.9f) else Modifier)
            .then(
                if (frost && kind != ButtonKind.GHOST) Modifier.glassControl(shape, glassFill, 1f)
                else Modifier.clip(shape).background(bg)
                    .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            )
            .dockClickable(enabled = enabled) { haptics.tap(); onClick() }
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = if (kind == ButtonKind.GHOST) 12.dp else horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (mono && kind != ButtonKind.GHOST) text.uppercase() else text,
            style = MaterialTheme.typography.labelLarge, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round icon button (48 dp target). [filled] uses the tonal container. */
@Composable
fun DockIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    size: Dp = 48.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val d = DockTheme.colors
    val shape = if (DockTheme.style == UiStyle.MINIMAL_MONO) DockTheme.shapes.control else androidx.compose.foundation.shape.CircleShape
    Box(
        modifier
            .size(size)
            .then(
                if (!filled) Modifier.clip(shape)
                else if (frostActive()) Modifier.glassControl(shape, d.raised.copy(alpha = 0.62f))
                else if (DockTheme.style == UiStyle.MINIMAL_MONO) Modifier.clip(shape).border(1.dp, d.hairline, shape)
                else Modifier.clip(shape).background(d.raised)
            )
            .dockClickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(size * 0.46f))
    }
}
