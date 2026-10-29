package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.theme.glass.frosted
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.droiddock.app.ui.theme.DockTheme
import kotlin.math.max
import kotlin.math.min

@Immutable
data class DockMenuEntry(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
)

private val RowHeight: Dp = 48.dp
private val PadV: Dp = 6.dp

/**
 * Context menu with a ColorOS-style highlight pill. Place it next to its anchor
 * inside the same `Box`, like `DropdownMenu`.
 *
 * - Open: scales 0.8 -> 1 from the anchor corner on a spatial spring + fades in;
 *   close runs at 0.6x. Calm/Standard: no overshoot to speak of (0.8-1.0 damping).
 * - The highlight pill follows the finger / selection and SLIDES between rows on
 *   a spatial spring (not jumping), SegmentTick haptic per row change.
 * - Bouncy only: dragging past the menu's top/bottom stretches the container's
 *   Y scale (up to +6%) and it springs back on release (ColorOS stretchy menu).
 * - Press, slide, lift to choose: the whole menu is one pointer gesture.
 *   Animations off: snaps, no stretch.
 */
@Composable
fun DockMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<DockMenuEntry>,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = true,
) {
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    val s = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) shown = true
        progress.animateTo(if (expanded) 1f else 0f, if (expanded) motion.spatial() else motion.exit())
        if (!expanded) shown = false
    }
    if (!shown) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowPx = with(density) { RowHeight.toPx() }
    val padPx = with(density) { PadV.toPx() }
    var hi by remember { mutableIntStateOf(-1) }
    var overshoot by remember { mutableFloatStateOf(0f) }
    val stretch = animateFloatAsState(if (motion.stretchy) overshoot else 0f, motion.spatial(), label = "menu-stretch")
    val pillY = remember { Animatable(0f) }
    val pillA = remember { Animatable(0f) }
    LaunchedEffect(hi) {
        if (hi >= 0) {
            val top = padPx + hi * rowPx
            if (pillA.value < 0.05f) pillY.snapTo(top) else pillY.animateTo(top, motion.spatial())
            pillA.animateTo(1f, motion.fade())
        } else pillA.animateTo(0f, motion.fade())
    }
    val currentEntries = rememberUpdatedState(entries)
    val dismiss = rememberUpdatedState(onDismissRequest)
    val shape = RoundedCornerShape(20.dp)
    val origin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)

    Popup(
        popupPositionProvider = remember(alignEnd, density) { MenuPositionProvider(alignEnd, with(density) { 4.dp.roundToPx() }) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier
                .padding(8.dp)
                .graphicsLayer {
                    val p = progress.value
                    val base = 0.8f + 0.2f * p
                    scaleX = base
                    scaleY = base * (1f + stretch.value)
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = origin
                }
                .then(
                    if (frostActive()) Modifier.frosted(shape, DockTheme.colors.raised, floating = true, minAlpha = 0.90f)
                    else Modifier.shadow(10.dp, shape).clip(shape).background(DockTheme.colors.raised)
                )
                .widthIn(min = 180.dp, max = 280.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun rowAt(y: Float): Int {
                            val i = ((y - padPx) / rowPx).toInt()
                            return if (y >= padPx && i in currentEntries.value.indices) i else -1
                        }
                        hi = rowAt(down.position.y)
                        down.consume()
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.first()
                            val y = c.position.y
                            val i = rowAt(y)
                            if (i != hi) { hi = i; if (i >= 0) haptics.select() }
                            val total = currentEntries.value.size * rowPx + 2 * padPx
                            val over = when { y < 0f -> -y; y > total -> y - total; else -> 0f }
                            overshoot = min(over / rowPx * 0.06f, 0.06f)
                            c.consume()
                        } while (ev.changes.any { it.pressed })
                        val chosen = hi
                        hi = -1
                        overshoot = 0f
                        if (chosen >= 0) {
                            haptics.confirm()
                            currentEntries.value[chosen].onClick()
                            dismiss.value()
                        }
                    }
                },
        ) {
            // Highlight pill, behind the rows.
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .fillMaxWidth()
                    .height(RowHeight)
                    .graphicsLayer { translationY = pillY.value; alpha = pillA.value }
                    .clip(RoundedCornerShape(14.dp))
                    .background(s.onSurface.copy(alpha = 0.10f)),
            )
            Column(Modifier.padding(vertical = PadV)) {
                entries.forEach { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(RowHeight)
                            .padding(horizontal = 18.dp)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                onClick(label = e.label) { e.onClick(); dismiss.value(); true }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (e.destructive) s.error else s.onSurface
                        if (e.icon != null) {
                            Icon(e.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(e.label, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private class MenuPositionProvider(private val alignEnd: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left)
            .coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else max(0, anchorBounds.top - popupContentSize.height - gap)
        return IntOffset(x, y)
    }
}
