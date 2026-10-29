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
 * Bottom navigation, per style:
 * - Liquid Glass: floating glass pill, inset from the edges, content scrolls under it.
 * - Material You: M3 bar with the pill indicator on surfaceContainer.
 * - Minimal Mono: flat page-coloured bar, hairline on top, mono labels, a square tick.
 * - Reach: M3 bar on the page colour with bold labels, a touch taller.
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
        UiStyle.MATERIAL_YOU, UiStyle.REACH -> M3Bar(items, selected, pick, modifier)
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
            val pos = animateFloatAsState(last[0].toFloat(), motion.chrome(), label = "pill-pos")
            val shown = animateFloatAsState(if (idx >= 0) 1f else 0f, motion.fade(), label = "pill-a")
            val indicator = s.primary.copy(alpha = if (DockTheme.colors.isDark) 0.24f else 0.14f)
            val itemShape = RoundedCornerShape(h / 2 - pad)  // concentric: outer radius - padding
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                val itemW = maxWidth / n
                // Matched-element style: ONE indicator that slides between items (chrome spring,
                // never bounces); position is read in the layout lambda, not in composition.
                Box(
                    Modifier
                        .offset { IntOffset((itemW.toPx() * pos.value).roundToInt(), 0) }
                        .width(itemW)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = shown.value }
                        .clip(itemShape)
                        .background(indicator),
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
private fun M3Bar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    val reach = DockTheme.style == UiStyle.REACH
    Column(modifier) {
        if (reach) HorizontalDivider(thickness = 0.5.dp, color = DockTheme.colors.divider)
        NavigationBar(
            containerColor = if (reach) DockTheme.colors.page else s.surfaceContainer,
            tonalElevation = 0.dp,
            modifier = Modifier.height(DockTheme.spacing.navHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            items.forEach { item ->
                val on = item.id == selected
                NavigationBarItem(
                    selected = on,
                    onClick = { onSelect(item.id) },
                    icon = { BadgeIcon(item, on, if (on) s.onSecondaryContainer else s.onSurfaceVariant) },
                    label = {
                        Text(item.label, style = if (reach) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = s.secondaryContainer,
                        selectedTextColor = s.onSurface,
                        unselectedTextColor = s.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MonoBar(items: List<DockNavItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val s = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().background(DockTheme.colors.page)) {
        HorizontalDivider(thickness = 1.dp, color = DockTheme.colors.hairline)
        Row(
            Modifier.fillMaxWidth().height(DockTheme.spacing.navHeight).selectableGroup(),
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
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(width = 14.dp, height = 2.dp).background(if (on) s.tertiary else Color.Transparent))
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
