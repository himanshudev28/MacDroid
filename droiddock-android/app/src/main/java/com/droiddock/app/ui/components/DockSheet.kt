package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import android.view.ViewGroup
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme

/**
 * iOS-style bottom sheet (custom Dialog, plain opaque surface: never a glass sheet,
 * see [DockSheetSystem] for why).
 *
 * - Enter: slides up on a sheet spring (Calm 1.0/300, Standard 0.85/380, Bouncy
 *   0.7/400); with [origin] (the tapped Hub tile's centre in window px) it
 *   instead scales 0.6 -> 1 + fades from that point (shared-element-like; a real
 *   `SharedTransitionLayout` cannot cross the Dialog window).
 * - Drag: the sheet tracks the finger 1:1 anywhere on it (children that scroll
 *   hand their leftover scroll over via nested scroll). Pulling UP past the
 *   open position rubber-bands (iOS `1 - 1/(x*0.55/d + 1)` curve).
 * - Release: end state chosen by the PROJECTED position (offset + velocity * 0.2 s),
 *   the spring starts with the finger's velocity. Past 50% = dismiss.
 *   Haptics: GestureThresholdActivate crossing 40%, GestureEnd on dismiss.
 * - Back: predictive; the sheet scales to 0.94 with the swipe, spring-cancels.
 * - Scrim follows the sheet position. Animations off: everything snaps.
 * - Keyboard: content lifts above the IME; no animation of keyboard changes.
 *
 * @param fullHeight Hub destinations open full height; pickers wrap their content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    fullHeight: Boolean = false,
    origin: Offset? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val onDismissNow by rememberUpdatedState(onDismiss)

    var sheetH by remember { mutableIntStateOf(0) }
    // 0 = open. Positive = pulled down. Negative = pulled up (rubber-banded on screen).
    var raw by remember { mutableFloatStateOf(100_000f) }
    var enter by remember { mutableFloatStateOf(if (origin != null) 0f else 1f) }
    var dismissing by remember { mutableStateOf(false) }
    var backActive by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val pastThreshold = remember { booleanArrayOf(false) }

    val openSpec: AnimationSpec<Float> = if (!motion.enabled) snap() else spring(
        dampingRatio = when { motion.stretchy -> 0.7f; motion.spatialDamping >= 1f -> 1f; else -> 0.85f },
        stiffness = motion.spatialStiffness, visibilityThreshold = 0.5f,
    )
    val closeSpec: AnimationSpec<Float> =
        if (!motion.enabled) snap() else spring(1f, motion.spatialStiffness * 1.6f, 0.5f)
    val rubberDim = with(density) { 140.dp.toPx() }

    suspend fun dismissNow(v: Float) {
        if (dismissing) return
        dismissing = true
        haptics.gestureEnd()
        try {
            animate(raw.coerceAtLeast(0f), sheetH.toFloat().coerceAtLeast(1f) + 1f, v, closeSpec) { x, _ -> raw = x }
        } catch (e: CancellationException) {
            // Interrupted (a new drag, or the sheet left composition): never stay stuck in
            // "dismissing", which would disable Back and leave the sheet half off-screen.
            dismissing = false
            throw e
        }
        onDismissNow()
    }
    fun requestDismiss() {
        if (dismissing) return
        settleJob?.cancel()
        settleJob = scope.launch { dismissNow(0f) }
    }
    fun settle(v: Float) {
        if (dismissing) return
        settleJob?.cancel()
        settleJob = scope.launch {
            val h = sheetH.toFloat().coerceAtLeast(1f)
            if (raw + v * 0.2f > h * 0.5f) dismissNow(v)
            else if (raw != 0f) animate(raw, 0f, v, openSpec) { x, _ -> raw = x }
        }
    }
    fun dragBy(dy: Float) {
        if (dismissing) return
        settleJob?.cancel()
        raw += dy
        val past = raw > sheetH * 0.4f
        if (past != pastThreshold[0]) { pastThreshold[0] = past; haptics.threshold() }
    }

    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Scrolling content up while the sheet is pulled down: close the gap first.
                if (available.y < 0f && raw > 0f) {
                    val c = maxOf(available.y, -raw)
                    dragBy(c)
                    return Offset(0f, c)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                // Content hit its edge: the leftover drags (down) or rubber-bands (up) the sheet.
                dragBy(available.y)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (raw == 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true, dismissOnClickOutside = false,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setWindowAnimations(-1)
        }
        // Always enabled while the sheet exists: while it is already closing, Back is
        // swallowed (requestDismiss no-ops) instead of leaking to the Activity / launcher.
        PredictiveBackHandler(enabled = true) { events ->
            backActive = true
            try {
                events.collect { backProgress = it.progress }
                backActive = false
                requestDismiss()
            } catch (e: CancellationException) {
                backActive = false
                // Gesture cancelled: spring the scale back and make sure the sheet is at rest.
                if (!dismissing) {
                    backProgress = 0f
                    if (raw != 0f && raw < 100_000f && enter >= 1f) settle(0f)
                }
                throw e
            }
        }
        LaunchedEffect(backActive) {
            if (!backActive && backProgress != 0f && !dismissing) {
                animate(backProgress, 0f, 0f, openSpec) { x, _ -> backProgress = x }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val topGap = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp) + 8.dp

            LaunchedEffect(Unit) {
                snapshotFlow { sheetH }.first { it > 0 }
                if (origin != null) {
                    raw = 0f
                    animate(0f, 1f, 0f, if (!motion.enabled) snap() else spring(motion.spatialDamping.coerceAtLeast(0.8f), motion.spatialStiffness)) { x, _ -> enter = x }
                } else {
                    animate(sheetH.toFloat() + 1f, 0f, 0f, openSpec) { x, _ -> raw = x }
                }
            }

            // Scrim: follows the sheet, tap to dismiss.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val h = sheetH.toFloat().coerceAtLeast(1f)
                        alpha = 0.40f * (1f - (raw / h).coerceIn(0f, 1f)) * enter.coerceIn(0f, 1f) * (1f - 0.5f * backProgress)
                    }
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures { requestDismiss() } },
            )

            Box(
                modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .then(
                        if (fullHeight) Modifier.padding(top = topGap).fillMaxHeight()
                        else Modifier.heightIn(max = maxHeight - topGap),
                    )
                    .onSizeChanged { sheetH = it.height }
                    .graphicsLayer {
                        val h = sheetH.toFloat().coerceAtLeast(1f)
                        val r = raw
                        translationY = if (r >= 0f) minOf(r, h + 1f)
                        else -((1f - 1f / (-r * 0.55f / rubberDim + 1f)) * rubberDim)
                        val e = enter
                        val s = (if (origin != null) 0.6f + 0.4f * e else 1f) * (1f - 0.06f * backProgress)
                        scaleX = s; scaleY = s
                        transformOrigin = if (origin != null && e < 1f) TransformOrigin(
                            (origin.x / screenW).coerceIn(0f, 1f),
                            ((origin.y - (screenH - h)) / h).coerceIn(0f, 1f),
                        ) else TransformOrigin(0.5f, 1f)
                        alpha = if (origin != null) minOf(1f, e * 2.2f) else 1f
                    }
                    .then(
                        // Sheets stay on the plain tint path (Backdrop sheet crash): frosted, but
                        // never below 88% opaque, since moving content sits right behind them.
                        if (frostActive()) Modifier.frosted(DockTheme.shapes.sheet, DockTheme.colors.raised, minAlpha = 0.88f)
                        else Modifier.clip(DockTheme.shapes.sheet).background(DockTheme.colors.raised)
                    )
                    .nestedScroll(nested)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragBy(it) },
                        onDragStarted = { settleJob?.cancel() },
                        onDragStopped = { settle(it) },
                    ),
            ) {
                Column(
                    Modifier.windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
                    ),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        DockSheetContent(title = title, content = content)
                    }
                }
            }
        }
    }
}

/**
 * The stock Material bottom sheet, kept as a fallback for [DockSheet]. ALWAYS the plain (opaque, tonal) path, even in Liquid Glass:
 * Backdrop's documented RenderThread crash is tied to glass bottom sheets, so
 * sheets stay solid until verified on device (ANDROID-UPGRADE.md).
 *
 * @param fullHeight Hub destinations open full height; pickers stay partial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockSheetSystem(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = DockTheme.shapes.sheet,
        containerColor = DockTheme.colors.raised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
    ) {
        DockSheetContent(title = title, content = content)
    }
}

/** The sheet's body on its own: what [DockSheet] shows, and what snapshots render inline. */
@Composable
fun DockSheetContent(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = DockTheme.spacing.gutter)) {
        if (title != null) {
            Text(
                title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = DockTheme.spacing.m, start = DockTheme.spacing.xs),
            )
        }
        content()
        Spacer(Modifier.height(DockTheme.spacing.xl))
    }
}
