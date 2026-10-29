package com.droiddock.app.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockNavBar
import com.droiddock.app.ui.components.DockNavItem
import com.droiddock.app.ui.components.DockNavRail
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.dockNavBarHeight
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.NavLayout
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import com.droiddock.app.ui.theme.glass.GlassHost

/** Destination ids. Strings (not an enum) because Prefs.defaultTab stores them. */
object Dest {
    const val HOME = "home"
    const val FILES = "files"
    const val CLIPBOARD = "clipboard"
    const val CONTROL = "control"
    const val SETTINGS = "settings"
    /** Pairing: an overlay page reached from Home/Settings, never a nav item. */
    const val CONNECT = "connect"
}

/** Navigation a screen can trigger without knowing the layout. */
@Immutable
data class ShellActions(
    val navigate: (String) -> Unit = {},
    /** Clipboard: a tab in Classic 5, a sheet in 4 tabs and Hub. */
    val openClipboard: () -> Unit = {},
)

val LocalShellActions = staticCompositionLocalOf { ShellActions() }

/** Where each Hub tile was last laid out (window px), so a sheet can expand from the tapped one. */
class HubOrigin {
    val centers = HashMap<String, Offset>()
}

val LocalHubOrigin = staticCompositionLocalOf<HubOrigin?> { null }

/** Predictive-back pose of the page being swiped away. Read in draw lambdas only. */
@Stable
internal class BackState {
    var from by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    /** BackEventCompat.EDGE_LEFT = 0, EDGE_RIGHT = 1. */
    var edge by mutableIntStateOf(0)
    var active by mutableStateOf(false)
    var committed by mutableStateOf(false)
    fun reset() { from = null; progress = 0f; active = false; committed = false }
}

@Composable
private fun navItems(layout: NavLayout, settingsBadge: Boolean): List<DockNavItem> {
    val home = DockNavItem(Dest.HOME, t("Home"), Icons.Outlined.Home, Icons.Rounded.Home)
    val files = DockNavItem(Dest.FILES, t("Files"), Icons.Outlined.Folder, Icons.Rounded.Folder)
    val clip = DockNavItem(Dest.CLIPBOARD, t("Clipboard"), Icons.Outlined.ContentPaste, Icons.Rounded.ContentPaste)
    val control = DockNavItem(Dest.CONTROL, t("Control"), Icons.Outlined.ScreenShare, Icons.Rounded.ScreenShare)
    val settings = DockNavItem(Dest.SETTINGS, t("Settings"), Icons.Outlined.Settings, Icons.Rounded.Settings, badge = settingsBadge)
    return when (layout) {
        NavLayout.CLASSIC_5 -> listOf(home, files, clip, control, settings)
        NavLayout.TABS_4, NavLayout.HUB -> listOf(home, files, control, settings)
    }
}

/**
 * The app frame. Destinations are unchanged across layouts; only how you move
 * between them differs:
 *
 * - [NavLayout.TABS_4]: Home | Files | Control | Settings. Clipboard opens as a
 *   full-height sheet ([ShellActions.openClipboard]; Home shows a card for it).
 * - [NavLayout.CLASSIC_5]: today's five tabs.
 * - [NavLayout.HUB]: no bar. [hubHome] is a one-handed tile screen; every other
 *   destination opens as a full-height sheet over it.
 * - Width >= 600 dp: a navigation rail, whatever the picker says.
 *
 * Content draws edge to edge; [LocalDockContentPadding] tells screens how much
 * the floating bar covers so lists scroll under it.
 *
 * @param content renders one destination. Called for [Dest.CONNECT] too.
 */
@Composable
fun AppShell(
    layout: NavLayout,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    settingsBadge: Boolean = false,
    hubHome: @Composable () -> Unit = {},
    content: @Composable (String) -> Unit,
) {
    // Clipboard state lives here so "open clipboard" works from any screen.
    var clipSheet by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val actions = ShellActions(
        navigate = onSelect,
        openClipboard = {
            if (layout == NavLayout.CLASSIC_5) onSelect(Dest.CLIPBOARD) else clipSheet = true
        },
    )
    val items = navItems(layout, settingsBadge)
    val selectedId = current.takeIf { id -> items.any { it.id == id } }

    val hubOrigin = remember { HubOrigin() }
    val back = remember { BackState() }
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    // Predictive back: only off non-root pages (root back leaves the app with the
    // system animation). The page follows the finger (scale to 0.92, 24 dp drift,
    // 28 dp corners); commit settles the pose and goes Home, cancel springs back.
    PredictiveBackHandler(enabled = current != Dest.HOME && layout != NavLayout.HUB) { events ->
        back.from = current
        back.committed = false
        back.active = true
        try {
            events.collect { back.progress = it.progress; back.edge = it.swipeEdge }
            haptics.gestureEnd()
            back.committed = true
            animate(back.progress, 1f, 0f, motion.chrome<Float>()) { v, _ -> back.progress = v }
            onSelect(Dest.HOME)
        } catch (e: CancellationException) {
            back.active = false
        }
    }
    LaunchedEffect(back.active) {
        if (!back.active && !back.committed && back.from != null) {
            animate(back.progress, 0f, 0f, motion.spatial<Float>()) { v, _ -> back.progress = v }
            back.reset()
        }
    }
    LaunchedEffect(current) {
        // The old page stays in its shrunken pose while it fades out; reset once it is gone.
        if (back.committed) { delay(motion.pageMs + 140L); back.reset() }
    }

    CompositionLocalProvider(LocalShellActions provides actions, LocalHubOrigin provides hubOrigin) {
        BoxWithConstraints(modifier.fillMaxSize().dockPageBackground()) {
            val wide = maxWidth >= 600.dp
            when {
                wide -> RailFrame(items, selectedId, current, onSelect, layout, back, content)
                layout == NavLayout.HUB -> HubFrame(current, onSelect, hubHome, hubOrigin, content)
                else -> BarFrame(items, selectedId, current, onSelect, back, content)
            }
        }
        if (clipSheet) {
            DockSheet(onDismiss = { clipSheet = false }, fullHeight = true) {
                CompositionLocalProvider(LocalDockContentPadding provides PaddingValues(0.dp)) {
                    Box(Modifier.fillMaxSize()) { content(Dest.CLIPBOARD) }
                }
            }
        }
    }
}

/**
 * Tab content. Fade-through with a subtle shift in the direction of travel:
 * the old page fades out in 90 ms, the new one fades in (60 ms later) while
 * drifting in from 1/14 of the width. Chrome never bounces (chrome spring,
 * damping >= 0.92), so Calm/Standard/Bouncy differ only in stiffness.
 * Minimal Mono: plain fade, no shift. Animations off: swap.
 */
@Composable
private fun Pages(current: String, order: List<String>, back: BackState, content: @Composable (String) -> Unit) {
    val motion = DockTheme.motion
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val density = LocalDensity.current
    val drift = with(density) { 24.dp.toPx() }
    val corner = RoundedCornerShape(28.dp)
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (!motion.enabled) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else if (mono) {
                fadeIn(motion.fade()) togetherWith fadeOut(motion.fade())
            } else {
                val from = order.indexOf(initialState).let { if (it < 0) order.size else it }
                val to = order.indexOf(targetState).let { if (it < 0) order.size else it }
                val dir = if (to >= from) 1 else -1
                val spec = motion.chrome<IntOffset>(IntOffset.VisibilityThreshold)
                (fadeIn(tween(motion.pageMs - 60, delayMillis = 60, easing = motion.easing)) +
                    slideInHorizontally(spec) { dir * it / 14 }) togetherWith
                    (fadeOut(tween(90, easing = motion.easing)) + slideOutHorizontally(spec) { -dir * it / 14 })
            }
        },
        label = "dest",
        modifier = Modifier.fillMaxSize(),
    ) { dest ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (dest == back.from) {
                    val p = back.progress
                    val sc = 1f - 0.08f * p
                    scaleX = sc; scaleY = sc
                    translationX = p * drift * (if (back.edge == 1) -1f else 1f)
                    if (p > 0f) { shape = corner; clip = true }
                }
            },
        ) { content(dest) }
    }
}

@Composable
private fun BarFrame(
    items: List<DockNavItem>,
    selectedId: String?,
    current: String,
    onSelect: (String) -> Unit,
    back: BackState,
    content: @Composable (String) -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reserved = PaddingValues(bottom = dockNavBarHeight() + navInset)
    val pages: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDockContentPadding provides reserved) {
            Box(Modifier.fillMaxSize()) { Pages(current, items.map { it.id }, back, content) }
        }
    }
    if (DockTheme.style == UiStyle.LIQUID_GLASS) {
        // Glass needs the page captured into a layer to refract; other styles skip the host entirely.
        GlassHost(
            modifier = Modifier.fillMaxSize(),
            content = { pages() },
            overlay = { DockNavBar(items, selectedId, onSelect, Modifier.align(Alignment.BottomCenter)) },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            pages()
            DockNavBar(items, selectedId, onSelect, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RailFrame(
    items: List<DockNavItem>,
    selectedId: String?,
    current: String,
    onSelect: (String) -> Unit,
    layout: NavLayout,
    back: BackState,
    content: @Composable (String) -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(Modifier.fillMaxSize()) {
        // The rail is solid in every style except Glass, which floats it over the page.
        DockNavRail(items, selectedId, onSelect)
        CompositionLocalProvider(LocalDockContentPadding provides PaddingValues(bottom = navInset)) {
            Box(Modifier.weight(1f).fillMaxSize()) { Pages(current, items.map { it.id }, back, content) }
        }
    }
}

@Composable
private fun HubFrame(
    current: String,
    onSelect: (String) -> Unit,
    hubHome: @Composable () -> Unit,
    hubOrigin: HubOrigin,
    content: @Composable (String) -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalDockContentPadding provides PaddingValues(bottom = navInset)) {
        Box(Modifier.fillMaxSize()) { hubHome() }
    }
    if (current != Dest.HOME) {
        DockSheet(onDismiss = { onSelect(Dest.HOME) }, fullHeight = true, origin = hubOrigin.centers[current]) {
            CompositionLocalProvider(LocalDockContentPadding provides PaddingValues(0.dp)) {
                Box(Modifier.fillMaxSize().padding(bottom = 0.dp)) { content(current) }
            }
        }
    }
}
