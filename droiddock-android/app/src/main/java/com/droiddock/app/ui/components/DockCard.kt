package com.droiddock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.SurfaceMode
import com.droiddock.app.ui.theme.UiStyle

enum class CardTone {
    /** The default content surface. */
    DEFAULT,
    /** One step up (nested emphasis, never card-in-card-in-card). */
    RAISED,
    /** Primary container: the one highlighted card on a screen. */
    ACCENT,
}

/** A content card. Tonal fill in Material You/Reach/Glass, hairline box in Mono/AMOLED. */
@Composable
fun DockCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.DEFAULT,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.ui.unit.Dp = DockTheme.spacing.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = DockTheme.shapes.card
    val s = MaterialTheme.colorScheme
    val color = when (tone) {
        CardTone.DEFAULT -> DockTheme.colors.card
        CardTone.RAISED -> DockTheme.colors.raised
        CardTone.ACCENT -> if (DockTheme.style == UiStyle.MINIMAL_MONO) DockTheme.colors.card else s.primaryContainer
    }
    Column(
        modifier
            .fillMaxWidth()
            .dockSurface(shape, color)
            .then(if (onClick != null) Modifier.dockClickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A grouped list (settings, devices, transfers). Each row is its own segment:
 * large outer corners, small inner corners, [com.droiddock.app.ui.theme.DockSpacing.listGap]
 * between them (Pixel/One UI). Mono draws one hairline box with hairline dividers.
 */
@Composable
fun DockGroup(
    modifier: Modifier = Modifier,
    rows: List<@Composable () -> Unit>,
) {
    if (rows.isEmpty()) return
    val hairline = DockTheme.surfaces.mode == SurfaceMode.HAIRLINE || DockTheme.spacing.listGap == 0.dp
    val r = DockTheme.shapes.cardRadius
    if (hairline) {
        Column(modifier.fillMaxWidth().dockSurface(DockTheme.shapes.card)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) DockDivider()
                row()
            }
        }
        return
    }
    val inner = 6.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DockTheme.spacing.listGap)) {
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) r else inner
            val bottom = if (i == rows.lastIndex) r else inner
            Box(Modifier.fillMaxWidth().dockSurface(RoundedCornerShape(top, top, bottom, bottom))) { row() }
        }
    }
}

/**
 * A list row: leading icon badge, headline, supporting text, trailing slot.
 * 56-72 dp tall, full-width tap target. Use inside [DockGroup] or [DockCard].
 */
@Composable
fun DockListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.PRIMARY,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    headlineColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val sp = DockTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting != null) 72.dp else 56.dp)
            .then(if (onClick != null) Modifier.dockClickable(role = role, onClick = onClick) else Modifier)
            .padding(horizontal = sp.cardPadding, vertical = sp.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(sp.l)) }
            icon != null -> { DockIconBadge(icon, tone = iconTone); Spacer(Modifier.width(sp.l)) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                headline, style = MaterialTheme.typography.bodyLarge, color = headlineColor,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) { Spacer(Modifier.width(sp.m)); trailing() }
    }
}

/** Trailing chevron for navigation rows. Mirrors in RTL. */
@Composable
fun DockChevron() {
    Icon(
        androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A plain titled card: overline header and body. Replaces the old `SectionCard`. */
@Composable
fun DockSection(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        DockSectionHeader(title, action = action, onAction = onAction)
        DockCard(contentPadding = 0.dp) {
            Column(Modifier.padding(vertical = DockTheme.spacing.xs), content = content)
        }
    }
}
