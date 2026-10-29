package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockEnter

/**
 * A big tappable tile: Hub destinations, Home quick actions, stat tiles.
 * Icon top-start, title bottom-start; optional [value] numeral (stat tile)
 * and [badge] text (e.g. "2 new"). Height from [com.droiddock.app.ui.theme.DockSpacing.tileHeight].
 */
@Composable
fun DockTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    tone: Tone = Tone.PRIMARY,
    height: Dp = DockTheme.spacing.tileHeight,
    badge: String? = null,
) {
    val style = DockTheme.style
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val shape = DockTheme.shapes.tile
    val c = toneColors(tone)
    // Material You fills the tile with the tone; others keep a card surface and
    // put the tone in the icon only (one accent per screen).
    val filled = style == UiStyle.MATERIAL_YOU
    val bg = if (filled) c.container else d.card
    val fg = if (filled) c.content else s.onSurface
    Column(
        modifier
            .heightIn(min = height)
            .then(
                if (frostActive()) Modifier.frosted(shape, bg)
                else Modifier.clip(shape).background(bg)
                    .then(DockTheme.surfaces.cardBorder?.let { Modifier.border(it, shape) } ?: Modifier)
            )
            .dockClickable(onClick = onClick)
            .padding(DockTheme.spacing.l),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (filled) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            } else {
                DockIconBadge(icon, tone = tone, size = 44.dp)
            }
            Spacer(Modifier.weight(1f))
            if (badge != null) DockStatusPill(badge, tone = Tone.PRIMARY, dot = false)
        }
        Spacer(Modifier.height(DockTheme.spacing.m))
        Column {
            if (value != null) {
                Text(value, style = DockTheme.type.numeral.copy(fontSize = DockTheme.type.numeral.fontSize * 0.8f),
                    color = fg, maxLines = 1)
            }
            Text(
                if (style == UiStyle.MINIMAL_MONO && value != null) title.uppercase() else title,
                style = if (style == UiStyle.MINIMAL_MONO && value != null) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.titleMedium,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Centered empty state: icon, one-line title, short body, optional action. Fades in with an 8 dp rise on first show. */
@Composable
fun DockEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val s = MaterialTheme.colorScheme
    Column(
        modifier.dockEnter().fillMaxWidth().padding(vertical = DockTheme.spacing.xxl, horizontal = DockTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DockIconBadge(icon, tone = Tone.SECONDARY, size = 72.dp)
        Spacer(Modifier.height(DockTheme.spacing.l))
        Text(title, style = MaterialTheme.typography.titleMedium, color = s.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(DockTheme.spacing.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(DockTheme.spacing.l))
            DockButton(actionLabel, onAction, kind = ButtonKind.TONAL)
        }
    }
}
