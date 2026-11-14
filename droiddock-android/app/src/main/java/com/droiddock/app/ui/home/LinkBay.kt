package com.droiddock.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.ui.components.DockIconBadge
import com.droiddock.app.ui.components.DockTether
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map

/**
 * The "link bay": phone ↔ tether ↔ Mac. The one picture of the connection that Home, Connect and onboarding share, so the
 * link always looks the same wherever it is shown. Endpoints are tonal device badges; the tether between them carries the
 * state (taut, reaching, cut) and, while linked, a pulse per event.
 *
 * @param caption optional labels under each end (phone, Mac). Hidden from accessibility: [description] speaks for the bay.
 * @param progress one-shot draw-in for onboarding (0..1); null = full line.
 */
@Composable
internal fun LinkBay(
    state: TetherState,
    description: String,
    modifier: Modifier = Modifier,
    pulseKey: Any? = null,
    progress: Float? = null,
    phoneCaption: String? = null,
    macCaption: String? = null,
    badge: Dp = 44.dp,
    showMac: Boolean = true,
) {
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(DockTheme.spacing.s),
    ) {
        DockTether(
            state = state,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            progress = progress,
            pulseKey = pulseKey,
            height = badge,
            start = { DockIconBadge(Icons.Rounded.Smartphone, tone = Tone.PRIMARY, size = badge) },
            // Before pairing there is no Mac yet: the line reaches toward an empty end.
            end = if (showMac) {
                { DockIconBadge(Icons.Rounded.Laptop, tone = if (state == TetherState.LINKED) Tone.PRIMARY else Tone.NEUTRAL, size = badge) }
            } else null,
        )
        if (phoneCaption != null || macCaption != null) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                Caption(phoneCaption.orEmpty(), Modifier.weight(1f), Alignment.Start)
                Caption(macCaption.orEmpty(), Modifier.weight(1f), Alignment.End)
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier, align: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = align) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A counter that bumps when something actually crosses the link (a reply, an action, a clipboard item), for
 * [DockTether]'s `pulseKey`. Rate-limited: a burst coalesces into at most one pulse per [minGapMs], so a flood of
 * notifications reads as a few beats, never a loop. Frozen (no pulses) while not [linked] or with motion off.
 */
@Composable
internal fun rememberTetherPulse(linked: Boolean, minGapMs: Long = 1_500L): Int {
    var beat by remember { mutableIntStateOf(0) }
    val motionOn by rememberUpdatedState(DockTheme.motion.enabled)
    LaunchedEffect(linked) {
        if (!linked) return@LaunchedEffect
        var last = 0L
        // Skip each flow's current value: only changes after the link is up count as traffic. Collection is sequential
        // and conflated, so any number of events arriving during the wait become the one pulse that follows.
        merge(
            ConnectionManager.lastEvent.drop(1).map { },
            ConnectionManager.clipHistory.drop(1).map { },
        ).conflate().collect {
            if (!motionOn) return@collect
            val wait = last + minGapMs - android.os.SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            last = android.os.SystemClock.uptimeMillis()
            beat++
        }
    }
    return beat
}
