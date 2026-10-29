package com.droiddock.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import com.droiddock.app.ui.theme.glass.GlassRole
import com.droiddock.app.ui.theme.glass.GlassSurface

/**
 * A destination screen: style-aware collapsing header + a LazyColumn that
 * already carries the shell's insets. Screens put their rows in [content] and
 * never pad for status/nav bars themselves.
 *
 * Header per style:
 * - Reach: One UI "viewing area": a large title in the top <= 25% of the
 *   screen that scrolls away, leaving a compact bar. Controls live below.
 * - Material You: large headline under a compact bar row (Pixel).
 * - Liquid Glass: large title; once content scrolls under it, a floating glass
 *   capsule with the small title fades in (one of the two glass surfaces).
 * - Minimal Mono: left-aligned title with a mono overline; hairline when scrolled.
 *
 * All scroll-linked visuals are read in the draw phase (no recomposition per frame).
 */
@Composable
fun DockScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    drawPageBackground: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val style = DockTheme.style
    val sp = DockTheme.spacing
    val shell = LocalDockContentPadding.current
    val dir = LocalLayoutDirection.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = 56.dp

    // 0 = header fully visible, 1 = scrolled past it. Derived: recomposes only
    // when crossing thresholds is observed in graphicsLayer lambdas below.
    val collapse by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else {
                val h = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.takeIf { it > 0 } ?: 1
                (listState.firstVisibleItemScrollOffset.toFloat() / (h * 0.6f)).coerceIn(0f, 1f)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().then(if (drawPageBackground) Modifier.dockPageBackground() else Modifier)) {
        val reachHeader = (maxHeight * 0.25f).coerceIn(120.dp, 240.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sp.gutter + shell.calculateStartPadding(dir),
                end = sp.gutter + shell.calculateEndPadding(dir),
                top = statusTop + barHeight,
                bottom = shell.calculateBottomPadding() + sp.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(sp.m),
        ) {
            item(key = "dock-header", contentType = "header") {
                LargeHeader(
                    title, subtitle,
                    minHeight = if (style == UiStyle.REACH) reachHeader - barHeight else 0.dp,
                    modifier = Modifier.graphicsLayer { alpha = 1f - collapse },
                )
            }
            content()
        }

        // Compact bar: back + small title + actions. Background fades in on scroll.
        Box(Modifier.fillMaxWidth()) {
            val barBg = DockTheme.colors.page
            when (style) {
                UiStyle.LIQUID_GLASS -> Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = sp.gutter + sp.xl, vertical = sp.xs)
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = collapse },
                ) {
                    GlassSurface(CircleShape, Modifier.height(barHeight - sp.s), role = GlassRole.CONTROL) {
                        Text(
                            title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = sp.xl),
                        )
                    }
                }
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = collapse }
                        .background(barBg),
                ) {
                    Spacer(Modifier.height(statusTop))
                    Box(Modifier.fillMaxWidth().height(barHeight), contentAlignment = Alignment.Center) {
                        Text(
                            title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 72.dp),
                        )
                    }
                    if (style == UiStyle.MINIMAL_MONO) HorizontalDivider(thickness = 1.dp, color = DockTheme.colors.hairline)
                }
            }
            // Controls row: always visible and tappable.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = statusTop)
                    .height(barHeight)
                    .padding(horizontal = sp.xs + sp.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    DockIconButton(
                        Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onBack,
                        filled = style != UiStyle.MINIMAL_MONO, size = 44.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

@Composable
private fun LargeHeader(title: String, subtitle: String?, minHeight: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val style = DockTheme.style
    val type = DockTheme.type
    val s = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().heightIn(min = minHeight).padding(
            start = DockTheme.spacing.xs, end = DockTheme.spacing.xs, bottom = DockTheme.spacing.s,
        ),
        verticalArrangement = if (style == UiStyle.REACH) Arrangement.Bottom else Arrangement.Top,
        horizontalAlignment = if (style == UiStyle.REACH) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (style == UiStyle.MINIMAL_MONO && subtitle != null) {
            Text(subtitle.uppercase(), style = type.overline, color = s.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(DockTheme.spacing.xs))
        }
        Text(
            title,
            style = when (style) {
                UiStyle.REACH -> type.largeTitle
                UiStyle.MINIMAL_MONO -> MaterialTheme.typography.headlineLarge
                else -> MaterialTheme.typography.headlineLarge
            },
            color = s.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null && style != UiStyle.MINIMAL_MONO) {
            Spacer(Modifier.height(DockTheme.spacing.xs))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant, maxLines = 2)
        }
        if (style == UiStyle.REACH) Spacer(Modifier.height(DockTheme.spacing.l))
    }
}
