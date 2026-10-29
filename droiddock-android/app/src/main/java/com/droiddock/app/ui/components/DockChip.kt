package com.droiddock.app.ui.components

import com.droiddock.app.ui.theme.glass.glassControl
import com.droiddock.app.ui.theme.glass.frostActive
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.UiStyle

/** Filter / action chip. Selected = secondary container (ink in Mono) with a check. */
@Composable
fun DockChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val shape = DockTheme.shapes.chip
    val bg by animateColorAsState(
        when {
            selected && mono -> s.onSurface
            selected -> s.secondaryContainer
            else -> Color.Transparent
        }, DockTheme.motion.fade(), label = "chip-bg",
    )
    val fg = when {
        selected && mono -> s.background
        selected -> s.onSecondaryContainer
        else -> s.onSurfaceVariant
    }
    val haptics = DockTheme.haptics
    Row(
        modifier
            .heightIn(min = 36.dp)
            .then(
                if (frostActive()) Modifier.glassControl(
                    shape,
                    if (selected) s.primary.copy(alpha = if (d.isDark) 0.28f else 0.20f)
                    else s.onSurface.copy(alpha = if (d.isDark) 0.07f else 0.04f),
                )
                else Modifier.clip(shape).background(bg)
                    .then(if (!selected) Modifier.border(1.dp, if (mono) d.hairline else s.outlineVariant, shape) else Modifier)
            )
            .dockClickable { haptics.select(); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lead = if (selected && icon == null && !mono) Icons.Rounded.Check else icon
        if (lead != null) {
            Icon(lead, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (DockTheme.type.upperCaseLabels) label.uppercase() else label,
            style = if (mono) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = fg, maxLines = 1,
        )
    }
}

/**
 * Small status pill ("Syncing", "3 of 5 ready"). A leading dot carries state
 * by shape as well as colour.
 */
@Composable
fun DockStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.SUCCESS,
    dot: Boolean = true,
) {
    val c = toneColors(tone)
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val dotColor = when {
        mono && (tone == Tone.SUCCESS || tone == Tone.PRIMARY) -> MaterialTheme.colorScheme.tertiary
        tone == Tone.SUCCESS -> DockTheme.colors.success
        else -> c.content
    }
    Row(
        modifier
            .clip(if (mono) DockTheme.shapes.chip else CircleShape)
            .background(c.container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (DockTheme.type.upperCaseLabels) text.uppercase() else text,
            style = MaterialTheme.typography.labelMedium, color = c.content, maxLines = 1,
        )
    }
}

/**
 * Segmented control with a sliding indicator (spring). Replaces the old
 * `SegmentedHeader` and the 3-way theme row. Labels never wrap per character:
 * each segment ellipsises on one line.
 */
@Composable
fun DockSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val mono = DockTheme.style == UiStyle.MINIMAL_MONO
    val track = DockTheme.shapes.pill
    val haptics = DockTheme.haptics
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(
                if (frostActive()) Modifier.glassControl(track, s.onSurface.copy(alpha = if (d.isDark) 0.10f else 0.07f), 0.8f)
                else Modifier.clip(track).background(if (mono) Color.Transparent else d.inset)
                    .then(if (mono) Modifier.border(1.dp, d.hairline, track) else Modifier)
            )
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size
        // Index as a float on a chrome spring (quick, never bounces), turned into
        // pixels in the layout lambda: sliding never recomposes.
        val pos = animateFloatAsState(index.toFloat(), DockTheme.motion.chrome(), label = "seg")
        Box(
            Modifier
                .offset { IntOffset((segW.toPx() * pos.value).roundToInt(), 0) }
                .width(segW)
                .fillMaxHeight()
                .then(
                    if (frostActive()) Modifier.glassControl(track, s.primary.copy(alpha = if (d.isDark) 0.34f else 0.22f))
                    else Modifier.clip(track).background(if (mono) s.onSurface else s.surface)
                        .then(if (!mono && !d.isDark) Modifier.border(0.5.dp, s.outlineVariant, track) else Modifier)
                ),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { i, (id, label) ->
                val on = i == index
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(track)
                        .selectable(selected = on, role = Role.Tab) { if (!on) { haptics.select(); onSelect(id) } },
                    contentAlignment = Alignment.Center,
                ) {
                    val labelColor by animateColorAsState(
                        when {
                            on && mono -> s.background
                            on -> s.onSurface
                            else -> s.onSurfaceVariant
                        },
                        DockTheme.motion.fade(), label = "seg-label",
                    )
                    Text(
                        if (DockTheme.type.upperCaseLabels) label.uppercase() else label,
                        style = MaterialTheme.typography.labelLarge,
                        color = labelColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}
