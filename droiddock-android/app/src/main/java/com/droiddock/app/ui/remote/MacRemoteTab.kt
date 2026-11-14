package com.droiddock.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.droiddock.app.ui.components.DockTether
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.theme.dockEnterTransition
import com.droiddock.app.ui.theme.dockExitTransition
import com.droiddock.app.ui.theme.dockPress
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.droiddock.app.ui.theme.dockGlow
import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.glassControl
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.dockSurface
import com.droiddock.app.ui.theme.DockHaptics
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle

@Composable
internal fun MacRemoteTab(connected: Boolean, previewDrag: Boolean? = null, previewLocked: Boolean = false) {
    var typed by remember { mutableStateOf("") }
    // Relative deltas accumulated into absolute moves: the Mac's `mouse_move`
    // takes screen coordinates, so the phone owns the cursor while dragging.
    var cursorX by remember { mutableFloatStateOf(600f) }
    var cursorY by remember { mutableFloatStateOf(400f) }

    val sp = DockTheme.spacing
    val haptics = DockTheme.haptics
    val style = DockTheme.style
    val scheme = MaterialTheme.colorScheme
    val shell = LocalDockContentPadding.current
    // Glass and Mono buttons tick on their own; the others are silent by
    // design, but a key's result lands on the Mac, so confirm locally.
    val caps by ConnectionManager.macCaps.collectAsState()
    val dragCap = previewDrag ?: caps.contains("remotedrag")
    // Three-finger swipes and pinch need a Mac that understands the `gesture` action.
    val gestureCap = gesturesEnabled(caps)
    val gestureCapNow by rememberUpdatedState(gestureCap)
    // Hold-button state. `pressed` = a finger is on Hold; `locked` = drag lock.
    // `downSent` is the truth about what the Mac has been told.
    var holdPressed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(previewLocked) }
    var downSent by remember { mutableStateOf(previewLocked) }
    val releaseHold = {
        if (downSent) { ConnectionManager.sendRemoteButton(false); downSent = false }
        holdPressed = false
        locked = false
    }
    val releaseNow by rememberUpdatedState(releaseHold)
    // Never leave the Mac's button down: link loss, screen off, backgrounding, leaving the tab.
    LaunchedEffect(connected, dragCap) { if (!connected || !dragCap) releaseNow() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) releaseNow() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); releaseNow() }
    }
    // Heartbeat so the Mac's 30 s idle release never fires on a deliberate drag lock.
    LaunchedEffect(downSent) {
        while (downSent) { delay(10_000); if (downSent) ConnectionManager.sendRemoteButton(true) }
    }
    // Every command sent to the Mac sends one pulse down the tether at the top.
    var sent by remember { mutableIntStateOf(0) }
    val keyFeel = remember(haptics, style) { { sent++; if (style != UiStyle.LIQUID_GLASS && style != UiStyle.MINIMAL_MONO) haptics.longPress() } }

    // A landscape phone is wide but short (the shell shows a rail). Stacked, the fixed
    // controls took all the height and left the trackpad 0 dp tall, so go side by side there.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight && maxHeight < 560.dp
        val hint: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            DockTether(
                state = if (connected) TetherState.LINKED else TetherState.OFFLINE,
                pulseKey = sent, height = 20.dp,
                start = { Icon(Icons.Outlined.PhoneAndroid, null, tint = scheme.primary, modifier = Modifier.size(16.dp)) },
                end = { Icon(Icons.Outlined.LaptopMac, null, tint = if (connected) scheme.primary else scheme.outline, modifier = Modifier.size(16.dp)) },
            )
            Text(
                if (connected) t("Drag to move · tap to click · long-press to right-click")
                else t("Not connected to your Mac."),
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) scheme.onSurfaceVariant else scheme.error,
            )
            }
        }
        val pad: @Composable (Modifier) -> Unit = { padModifier ->
            // The trackpad is the point of this screen: it takes all the slack
            // (about two thirds of the page) and is floored at a size worth dragging on.
            var pressed by remember { mutableStateOf(false) }
            // Touch feedback: a glow under every finger, a fading trail behind the first, and a ring that
            // expands from where the last finger lifted. Event-driven (no loop), so it needs no ambient gate.
            val motion = DockTheme.motion
            val padScope = rememberCoroutineScope()
            var fingers by remember { mutableStateOf(emptyList<Offset>()) }
            var trail by remember { mutableStateOf(emptyList<Offset>()) }
            val ring = remember { Animatable(1f) }
            var ringAt by remember { mutableStateOf(Offset.Zero) }
            val gridColor = scheme.onSurface
            val pressTint = scheme.primary
            Box(
                modifier = padModifier
                    .dockSurface(DockTheme.shapes.card, DockTheme.colors.inset)
                    .drawBehind {
                        // Subtle grid so the surface reads as a pad, and so motion has reference.
                        val step = 32.dp.toPx()
                        val line = gridColor.copy(alpha = 0.06f)
                        var x = step
                        while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                        var y = step
                        while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
                        // Pressed state is read here, so a touch only redraws.
                        if (pressed) drawRect(pressTint.copy(alpha = 0.10f))
                        if (motion.enabled) {
                            val n = trail.size
                            trail.forEachIndexed { i, p ->
                                val f = (i + 1f) / (n + 1f)
                                drawCircle(pressTint.copy(alpha = 0.16f * f), radius = 4.dp.toPx() + 10.dp.toPx() * f, center = p)
                            }
                            fingers.forEach { p ->
                                drawCircle(pressTint.copy(alpha = 0.20f), radius = 36.dp.toPx(), center = p)
                                drawCircle(pressTint.copy(alpha = 0.55f), radius = 36.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
                            }
                            val r = ring.value
                            if (r < 1f) drawCircle(
                                pressTint.copy(alpha = 0.45f * (1f - r)),
                                radius = 36.dp.toPx() + 56.dp.toPx() * r, center = ringAt, style = Stroke(2.dp.toPx()),
                            )
                        }
                    }
                    .pointerInput(connected, motion.enabled) {
                        if (!motion.enabled) return@pointerInput
                        // Observe only (Initial pass, never consumed) so the gesture detectors below are untouched.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val down = e.changes.filter { it.pressed }
                                fingers = down.map { it.position }
                                down.firstOrNull()?.let { trail = (trail + it.position).takeLast(10) }
                                if (down.isEmpty()) e.changes.firstOrNull()?.let { up ->
                                    ringAt = up.position
                                    padScope.launch { ring.snapTo(0f); ring.animateTo(1f, motion.effects()) }
                                }
                            } while (down.isNotEmpty())
                            fingers = emptyList(); trail = emptyList()
                        }
                    }
                    .pointerInput(connected) {
                        if (!connected) return@pointerInput
                        // Two-finger scroll / right-click work with any Mac (existing verbs);
                        // three-finger swipe and pinch only when it advertises "remotegestures".
                        // Macs with "remotegestures" (3.6.2+) scroll in pixels; older ones read lines.
                        val lines = LineScroller()
                        detectTrackpadGestures(
                            gesturesEnabled = { gestureCapNow },
                            onScroll = { dx, dy ->
                                if (gestureCapNow) {
                                    ConnectionManager.sendRemote("mouse_scroll") {
                                        it.put("dx", dx.toDouble()).put("dy", dy.toDouble())
                                    }
                                } else {
                                    val (lx, ly) = lines.add(dx, dy)
                                    if (lx != 0 || ly != 0) ConnectionManager.sendRemote("mouse_scroll") {
                                        it.put("dx", lx).put("dy", ly)
                                    }
                                }
                            },
                            onTwoFingerTap = {
                                haptics.tap()
                                ConnectionManager.sendRemote("mouse_click") { it.put("button", "right") }
                            },
                            onGesture = { name ->
                                haptics.threshold()
                                ConnectionManager.sendRemote("gesture") { it.put("name", name) }
                            },
                        )
                    }
                    .pointerInput(connected) {
                        if (!connected) return@pointerInput
                        var travelled = 0f
                        detectDragGestures(
                            onDragStart = { travelled = 0f },
                        ) { change, drag ->
                            change.consume()
                            // 1.6x so a short phone drag crosses a large display.
                            cursorX = (cursorX + drag.x * 1.6f).coerceAtLeast(0f)
                            cursorY = (cursorY + drag.y * 1.6f).coerceAtLeast(0f)
                            ConnectionManager.sendRemote("mouse_move") {
                                it.put("x", cursorX.toDouble()).put("y", cursorY.toDouble())
                            }
                            // A detent every ~96 px of travel, like a notched wheel.
                            travelled += kotlin.math.abs(drag.x) + kotlin.math.abs(drag.y)
                            if (travelled > 96f) { travelled = 0f; haptics.frequentTick() }
                        }
                    }
                    .pointerInput(connected) {
                        if (!connected) return@pointerInput
                        detectTapGestures(
                            onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                            onTap = {
                                // A tap while dragging ends the drag instead of clicking.
                                if (downSent) {
                                    if (locked) { haptics.gestureEnd(); releaseHold() }
                                    return@detectTapGestures
                                }
                                sent++
                                haptics.tap().also { if (style != UiStyle.LIQUID_GLASS && style != UiStyle.MINIMAL_MONO) haptics.select() }
                                ConnectionManager.sendRemote("mouse_click") { it.put("button", "left") }
                            },
                            onLongPress = {
                                if (downSent) return@detectTapGestures
                                haptics.longPress()
                                ConnectionManager.sendRemote("mouse_click") { it.put("button", "right") }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Mouse, null, tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(sp.s))
                    Text(t("Trackpad"), style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
        val padHint: @Composable () -> Unit = {
            Text(
                if (gestureCap) t("2 fingers: scroll · tap for right-click · 3 fingers: Mission Control & desktops · pinch: zoom")
                else t("2 fingers: scroll · tap for right-click"),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        val hold: @Composable () -> Unit = {
            AnimatedVisibility(dragCap && connected, enter = dockEnterTransition(), exit = dockExitTransition()) {
                HoldButton(
                    holdPressed = holdPressed,
                    locked = locked,
                    onDown = {
                        holdPressed = true; downSent = true
                        ConnectionManager.sendRemoteButton(true); haptics.confirm()
                    },
                    onUp = { holdPressed = false; if (!locked) { haptics.gestureEnd(); releaseHold() } },
                    onLock = {
                        // Double-tap: the button goes down now and stays down.
                        locked = true; downSent = true
                        ConnectionManager.sendRemoteButton(true); haptics.threshold()
                    },
                    onUnlock = { haptics.gestureEnd(); releaseHold() },
                )
            }
        }
        val keys: @Composable () -> Unit = {
            // Arrows as a d-pad: a cross is the shape the fingers already expect.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.l),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(sp.xs)
                ) {
                    ArrowKey(Icons.Outlined.KeyboardArrowUp, t("Up"), haptics, "up", connected) { sent++ }
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                        ArrowKey(Icons.Outlined.KeyboardArrowLeft, t("Left"), haptics, "left", connected) { sent++ }
                        ArrowKey(Icons.Outlined.KeyboardArrowDown, t("Down"), haptics, "down", connected) { sent++ }
                        ArrowKey(Icons.Outlined.KeyboardArrowRight, t("Right"), haptics, "right", connected) { sent++ }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                        RemoteKey("Enter", Modifier.weight(1f), keyFeel) { sendKey("enter") }
                        RemoteKey("Esc", Modifier.weight(1f), keyFeel) { sendKey("escape") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                        RemoteKey("Space", Modifier.weight(1f), keyFeel) { sendKey("space") }
                        RemoteKey("Tab", Modifier.weight(1f), keyFeel) { sendKey("tab") }
                    }
                }
            }
        }
        val typing: @Composable () -> Unit = {
            // Media transport lives on the Home Now Playing card, next to the title it acts on.

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.s)
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text(t("Type on the Mac"), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = DockTheme.shapes.field, colors = com.droiddock.app.ui.components.dockFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                val canSend = connected && typed.isNotBlank()
                DockIconButton(
                    icon = Icons.Outlined.Send,
                    contentDescription = t("Send text"),
                    enabled = canSend,
                    tint = if (canSend) scheme.primary else scheme.onSurfaceVariant,
                    onClick = {
                        haptics.confirm(); sent++
                        ConnectionManager.sendRemote("text") { it.put("text", typed) }
                        typed = ""
                    },
                )
            }
        }
        val outer = Modifier
            .fillMaxSize()
            .padding(horizontal = sp.gutter)
            .padding(top = sp.s, bottom = shell.calculateBottomPadding() + sp.m)
        if (sideBySide) {
            Row(outer, horizontalArrangement = Arrangement.spacedBy(sp.l)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(sp.s)) {
                    hint()
                    pad(Modifier.fillMaxWidth().weight(1f))
                    padHint()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(sp.m),
                ) {
                    hold()
                    keys()
                    typing()
                }
            }
        } else {
            Column(outer, verticalArrangement = Arrangement.spacedBy(sp.m)) {
                hint()
                // The trackpad takes all the slack, floored at a size worth dragging on.
                pad(Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp))
                padHint()
                hold()
                keys()
                typing()
            }
        }
    }
}

internal fun sendKey(name: String) {
    ConnectionManager.sendRemote("key") { it.put("key", name) }
}

@Composable
internal fun RemoteKey(label: String, modifier: Modifier = Modifier, feel: () -> Unit, onClick: () -> Unit) {
    DockButton(
        text = label,
        onClick = { feel(); onClick() },
        modifier = modifier,
        kind = ButtonKind.TONAL,
        fullWidth = true,
        height = DockTheme.spacing.touch,
        horizontalPadding = 8.dp,
    )
}

/**
 * A d-pad arrow that repeats while held: one `key` on press, then every [KeyRepeat.INTERVAL_MS]
 * after [KeyRepeat.DELAY_MS], until release, cancel, leaving composition or the link dropping.
 * Touch is handled on the Initial pass and consumed, so the button's own click doesn't fire a
 * second key; the click remains for TalkBack and keyboards.
 */
@Composable
private fun ArrowKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, haptics: DockHaptics, key: String, connected: Boolean, onSent: () -> Unit = {}) {
    val linkUp by rememberUpdatedState(connected)
    // The touch is consumed on the Initial pass (so the key repeats instead of double-firing), which also starves the
    // button of its own press interaction. Feed one by hand so the key still sinks under the finger.
    val interaction = remember { MutableInteractionSource() }
    val sentNow by rememberUpdatedState(onSent)
    Box(
        Modifier.dockPress(interaction, scale = 0.88f, tilt = true).pointerInput(key) {
            // One persistent pointer loop tracks the pressed state; the repeat runs as a job that is
            // cancelled when the last pointer lifts, on cancel, or when this leaves composition.
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var repeat: Job? = null
                    val press = PressInteraction.Press(down.position)
                    interaction.tryEmit(press)
                    try {
                        if (linkUp) {
                            haptics.longPress(); sendKey(key); sentNow()
                            repeat = launch {
                                delay(KeyRepeat.DELAY_MS)
                                while (isActive && linkUp) { sendKey(key); delay(KeyRepeat.INTERVAL_MS) }
                            }
                        }
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                    } finally {
                        interaction.tryEmit(PressInteraction.Release(press))
                        repeat?.cancel()
                    }
                }
            }
        }
    ) {
        DockIconButton(
            icon = icon,
            contentDescription = description,
            onClick = { if (linkUp) { haptics.longPress(); sendKey(key) } },
        )
    }
}

/**
 * Press-and-hold mouse button. Own pointer scope, so a second finger on the
 * trackpad moves the pointer while this one is down. Quick double-tap locks the
 * drag; a tap on a locked button releases it. A single quick tap sends nothing,
 * so it can never produce a stray click on the Mac.
 */
@Composable
private fun HoldButton(
    holdPressed: Boolean,
    locked: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val shape = DockTheme.shapes.control
    val active = holdPressed || locked
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val fg = if (active) s.onPrimary else s.onSurface
    val fill = when {
        active -> s.primary
        mono -> d.raised
        else -> s.onSurface.copy(alpha = if (d.isDark) 0.12f else 0.07f)
    }
    val label = if (locked) t("Drag lock on") else t("Hold to drag")
    val fillAnim by animateColorAsState(fill, DockTheme.motion.fade(), label = "holdFill")
    val fgAnim by animateColorAsState(fg, DockTheme.motion.fade(), label = "holdFg")
    // The hold is driven by raw pointer events, so it reports presses to dockPress by hand.
    val holdInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(holdPressed, locked) {
        if (holdPressed || locked) {
            val p = PressInteraction.Press(Offset.Zero)
            holdInteraction.emit(p)
            try { kotlinx.coroutines.awaitCancellation() } finally { holdInteraction.tryEmit(PressInteraction.Release(p)) }
        }
    }
    val holdDesc = t("Hold mouse button")
    val lockOn = t("Drag lock on")
    val lockOff = t("Drag lock off")
    val tapGap = 300L
    val holdAfter = 120L
    Row(
        Modifier
            .fillMaxWidth()
            .dockPress(holdInteraction, scale = 0.97f)
            .defaultMinSize(minHeight = DockTheme.spacing.touch + 12.dp)
            .then(if (locked) Modifier.dockGlow(shape, 0.9f) else Modifier)
            .then(
                if (frostActive()) Modifier.glassControl(shape, if (active) s.primary.copy(alpha = 0.90f) else fillAnim)
                else Modifier.clip(shape).background(fillAnim)
            )
            .semantics {
                contentDescription = holdDesc
                stateDescription = if (locked) lockOn else lockOff
                // TalkBack can't double-tap-and-hold: expose the lock as an action.
                onClick(label = if (locked) lockOff else lockOn) {
                    if (locked) onUnlock() else { onLock() }
                    true
                }
            }
            .pointerInput(locked) {
                var lastTapEnd = 0L
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val start = System.currentTimeMillis()
                    if (locked) {
                        waitForUpOrCancellation()
                        onUnlock()
                        return@awaitEachGesture
                    }
                    val quick = withTimeoutOrNull(holdAfter) { waitForUpOrCancellation() }
                    if (quick != null || System.currentTimeMillis() - start < holdAfter) {
                        // A tap. Second tap within the gap locks; the first sends nothing.
                        val now = System.currentTimeMillis()
                        if (now - lastTapEnd <= tapGap) { lastTapEnd = 0L; onLock() } else lastTapEnd = now
                        return@awaitEachGesture
                    }
                    // Still down after the grace period: a real hold.
                    lastTapEnd = 0L
                    onDown()
                    waitForUpOrCancellation()
                    onUp()
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.height(DockTheme.spacing.touch + 12.dp))
        Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.PanTool, null, tint = fgAnim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fgAnim)
    }
}
