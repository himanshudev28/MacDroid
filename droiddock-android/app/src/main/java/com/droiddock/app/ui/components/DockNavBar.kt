package com.droiddock.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import com.droiddock.app.ui.theme.dockPress
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.glass.GlassRole
import com.droiddock.app.ui.theme.glass.GlassSurface

@Immutable
data class DockNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    /** A dot, never a count (one pending update is not "1 unread"). */
    val badge: Boolean = false,
)

/**
 * Bottom navigation, per "Docking" signature:
 * - DEPTH (Liquid Glass): floating glass pill; the indicator is a droplet whose two edges travel on different springs,
 *   so it stretches toward the new tab and settles.
 * - BLOCKS (Material You): icons only, except the selected tab, which grows into a filled primary block with its label.
 * - INSTRUMENT (Minimal Mono): flat bar, hairline on top, mono labels; one underline steps across mechanically.
 * - SHELF (Reach): labels always, the selected icon lifts and a dot sits under its label.
 */
@Composable
fun DockNavBar(
    items: List<DockNavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = DockTheme.haptics
    val pick: (String) -> Unit = { id -> if (id != selected) haptics.select(); onSelect(id) }
    when (DockTheme.style) {
        UiStyle.LIQUID_GLASS -> GlassPillBar(items, selected, pick, modifier)
        UiStyle.MINIMAL_MONO -> MonoBar(items, selected, pick, modifier)
        UiStyle.MATERIAL_YOU -> BlocksBar(items, selected, pick, modifier)
        UiStyle.REACH -> ShelfBar(items, selected, pick, modifier)
    }
}

/** How much bottom space [DockNavBar] occupies (excluding system nav bar inset). */
@Composable
fun dockNavBarHeight(): Dp = when (DockTheme.style) {
    UiStyle.LIQUID_GLASS -> DockTheme.spacing.navHeight + 12.dp
    else -> DockTheme.spacing.navHeight
}

@Composable
private fun BadgeIcon(item: DockNavItem, on: Boolean, tint: Color) {
    val icon = if (on) item.selectedIcon else item.icon
    if (item.badge) {
        BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
    } else {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun GlassPillBar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    val h = DockTheme.spacing.navHeight
    val pad = 6.dp
    Box(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            shape = CircleShape,
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth().height(h),
            role = GlassRole.CONTROL,
        ) {
            val motion = DockTheme.motion
            val n = items.size.coerceAtLeast(1)
            val idx = items.indexOfFirst { it.id == selected }
            // The last real selection: the indicator fades out (not slides away) when nothing is selected.
            val last = remember { intArrayOf(maxOf(idx, 0)) }
            if (idx >= 0) last[0] = idx
            // Droplet: the leading edge runs ahead (stiff), the trailing edge follows (soft), so the indicator stretches
            // toward the new tab and pulls itself together. Animations off: both snap.
            val lead = animateFloatAsState(
                last[0].toFloat(),
                if (motion.enabled) androidx.compose.animation.core.spring(0.85f, motion.spatialStiffness * 2.2f) else androidx.compose.animation.core.snap(),
                label = "pill-lead",
            )
            val trail = animateFloatAsState(
                last[0].toFloat(),
                if (motion.enabled) androidx.compose.animation.core.spring(0.78f, motion.spatialStiffness * 0.9f) else androidx.compose.animation.core.snap(),
                label = "pill-trail",
            )
            val shown = animateFloatAsState(if (idx >= 0) 1f else 0f, motion.fade(), label = "pill-a")
            val indicator = s.primary.copy(alpha = if (DockTheme.colors.isDark) 0.24f else 0.14f)
            val itemShape = RoundedCornerShape(h / 2 - pad)  // concentric: outer radius - padding
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                val itemW = maxWidth / n
                // Matched-element style: ONE indicator that slides between items (chrome spring,
                // never bounces); position is read in the layout lambda, not in composition.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = shown.value }
                        .drawBehind {
                            val w = itemW.toPx()
                            val a = minOf(lead.value, trail.value)
                            val b = maxOf(lead.value, trail.value)
                            // Stretched droplet: thinner while it is long, so the area stays about the same.
                            val stretch = (b - a).coerceIn(0f, 1f)
                            val inset = size.height * 0.08f * stretch
                            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                            val left = if (rtl) size.width - (b + 1f) * w else a * w
                            drawRoundRect(
                                indicator,
                                topLeft = androidx.compose.ui.geometry.Offset(left, inset),
                                size = androidx.compose.ui.geometry.Size((b - a + 1f) * w, size.height - inset * 2),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius((size.height - inset * 2) / 2),
                            )
                        },
                )
                Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    items.forEach { item ->
                        val on = item.id == selected
                        val fg by animateColorAsState(
                            if (on) s.primary else s.onSurface.copy(alpha = 0.78f), motion.fade(), label = "pill-fg",
                        )
                        val interaction = remember(item.id) { MutableInteractionSource() }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .dockPress(interaction)
                                .clip(itemShape)
                                .selectable(on, interactionSource = interaction, indication = ripple(), role = Role.Tab) { onSelect(item.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BadgeIcon(item, on, fg)
                            Text(item.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlocksBar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    val motion = DockTheme.motion
    Column(modifier.fillMaxWidth().background(s.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().height(DockTheme.spacing.navHeight).padding(horizontal = 12.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                val on = item.id == selected
                // The selected tab takes more of the row and fills; the width change pops (BLOCKS' one bounce).
                val grow by animateFloatAsState(if (on) 1f else 0f, motion.pop(), label = "block-grow")
                val bg by animateColorAsState(if (on) s.primary else Color.Transparent, motion.fade(), label = "block-bg")
                val fg by animateColorAsState(if (on) s.onPrimary else s.onSurfaceVariant, motion.fade(), label = "block-fg")
                val interaction = remember(item.id) { MutableInteractionSource() }
                val shape = DockTheme.shapes.tile
                Row(
                    Modifier
                        .weight(1f + 1.4f * grow.coerceAtLeast(0f))
                        .height(48.dp)
                        .dockPress(interaction)
                        .clip(shape)
                        .background(bg)
                        .selectable(on, interactionSource = interaction, indication = ripple(), role = Role.Tab) { onSelect(item.id) }
                        .semantics { contentDescription = item.label },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgeIcon(item, on, fg)
                    if (grow > 0.02f) {
                        Spacer(Modifier.width(6.dp * grow.coerceIn(0f, 1f)))
                        Text(
                            item.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.graphicsLayer { alpha = grow.coerceIn(0f, 1f) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun ShelfBar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    val motion = DockTheme.motion
    Column(modifier.fillMaxWidth().background(DockTheme.colors.page)) {
        HorizontalDivider(thickness = 0.5.dp, color = DockTheme.colors.divider)
        Row(
            Modifier.fillMaxWidth().height(DockTheme.spacing.navHeight).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val on = item.id == selected
                // The selected icon lifts off the shelf; calm spring, no overshoot.
                val lift by animateFloatAsState(if (on) 1f else 0f, motion.smooth(), label = "shelf-lift")
                val fg by animateColorAsState(if (on) s.primary else s.onSurfaceVariant, motion.fade(), label = "shelf-fg")
                val interaction = remember(item.id) { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .dockPress(interaction)
                        .selectable(on, interactionSource = interaction, indication = ripple(bounded = false), role = Role.Tab) { onSelect(item.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.graphicsLayer { translationY = -3.dp.toPx() * lift }) { BadgeIcon(item, on, fg) }
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.size(5.dp)
                            .graphicsLayer { alpha = lift; scaleX = lift; scaleY = lift }
                            .background(s.primary, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun MonoBar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    val motion = DockTheme.motion
    val idx = items.indexOfFirst { it.id == selected }
    // One underline that steps between tabs in four mechanical clicks (INSTRUMENT never glides).
    val stepped = androidx.compose.animation.core.Easing { t -> kotlin.math.floor(t * 4f) / 4f }
    val pos by animateFloatAsState(
        maxOf(idx, 0).toFloat(),
        if (motion.enabled) androidx.compose.animation.core.tween(160, easing = stepped) else androidx.compose.animation.core.snap(),
        label = "mono-tick",
    )
    val tick = s.tertiary
    val n = items.size.coerceAtLeast(1)
    Column(modifier.fillMaxWidth().background(DockTheme.colors.page)) {
        HorizontalDivider(thickness = 1.dp, color = DockTheme.colors.hairline)
        Row(
            Modifier.fillMaxWidth().height(DockTheme.spacing.navHeight).selectableGroup()
                .drawBehind {
                    if (idx < 0) return@drawBehind
                    val w = size.width / n
                    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val cx = if (rtl) size.width - (pos + 0.5f) * w else (pos + 0.5f) * w
                    val tw = 14.dp.toPx(); val th = 2.dp.toPx()
                    drawRect(tick, androidx.compose.ui.geometry.Offset(cx - tw / 2, size.height - 10.dp.toPx()), androidx.compose.ui.geometry.Size(tw, th))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val on = item.id == selected
                val fg = if (on) s.onSurface else s.onSurfaceVariant
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) { onSelect(item.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BadgeIcon(item, on, fg)
                    Spacer(Modifier.height(4.dp))
                    Text(item.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** Navigation rail for >= 600 dp. Glass gets a floating vertical capsule. */
@Composable
fun DockNavRail(
    items: List<DockNavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val s = MaterialTheme.colorScheme
    val haptics = DockTheme.haptics
    val pick: (String) -> Unit = { id -> if (id != selected) haptics.select(); onSelect(id) }
    val style = DockTheme.style
    if (style == UiStyle.LIQUID_GLASS) {
        Box(modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp), contentAlignment = Alignment.Center) {
            GlassSurface(RoundedCornerShape(36.dp), Modifier.width(80.dp), role = GlassRole.CONTROL) {
                Column(
                    Modifier.padding(vertical = 12.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header?.invoke()
                    items.forEach { item ->
                        val on = item.id == selected
                        val fg = if (on) s.primary else s.onSurface.copy(alpha = 0.78f)
                        Column(
                            Modifier.size(64.dp).clip(RoundedCornerShape(24.dp))
                                .background(if (on) s.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .selectable(on, role = Role.Tab) { pick(item.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BadgeIcon(item, on, fg)
                            Text(item.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
                        }
                    }
                }
            }
        }
        return
    }
    Row(modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = if (style == UiStyle.MATERIAL_YOU) s.surfaceContainer else DockTheme.colors.page,
            header = header?.let { { it() } },
            windowInsets = WindowInsets.systemBars,
        ) {
            Spacer(Modifier.weight(1f))
            items.forEach { item ->
                val on = item.id == selected
                NavigationRailItem(
                    selected = on,
                    onClick = { pick(item.id) },
                    icon = { BadgeIcon(item, on, if (on) s.onSecondaryContainer else s.onSurfaceVariant) },
                    label = {
                        Text(if (style == UiStyle.MINIMAL_MONO) item.label.uppercase() else item.label,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    },
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = if (style == UiStyle.MINIMAL_MONO) s.surfaceContainerHigh else s.secondaryContainer,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
        }
        if (style == UiStyle.MINIMAL_MONO) VerticalDivider(thickness = 1.dp, color = DockTheme.colors.hairline)
    }
}
