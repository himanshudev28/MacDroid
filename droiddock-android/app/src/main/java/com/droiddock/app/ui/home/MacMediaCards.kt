package com.droiddock.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ScreenLockLandscape
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockNowPlaying
import com.droiddock.app.ui.components.DockSectionHeader
import com.droiddock.app.ui.theme.DockTheme

/**
 * What's playing on the Mac, and the transport for it.
 *
 * Shown whenever the Mac accepts remote control, not only when a track could be
 * named: the transport posts real HID media keys, so it drives whatever owns the
 * Mac's now-playing session even when the title can't be read.
 */
@Composable
internal fun MacNowPlayingCard(media: ConnectionManager.MacMedia?) {
    // Cosmetic head start; the Mac's next report (remember key = media) wins.
    var optimistic by remember(media) { mutableStateOf<Boolean?>(null) }
    val playing = optimistic ?: (media?.playing == true)
    val title = media?.title.orEmpty()

    DockNowPlaying(
        title = when {
            title.isNotEmpty() -> title
            playing            -> t("Playing on your Mac")
            else               -> t("Nothing Playing")
        },
        subtitle = listOfNotNull(
            media?.artist?.takeIf { it.isNotBlank() },
            media?.app?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { t("from your Mac") },
        playing = playing,
        onPrevious = { ConnectionManager.sendRemote("media") { it.put("key", "prev") } },
        onPlayPause = {
            optimistic = !playing
            ConnectionManager.sendRemote("media") { it.put("key", "playpause") }
        },
        onNext = { ConnectionManager.sendRemote("media") { it.put("key", "next") } },
        playLabel = t("Play"),
        pauseLabel = t("Pause"),
        previousLabel = t("Previous"),
        nextLabel = t("Next"),
    )
}

/** Value readout at the end of a Mac-control slider row; shared so brightness and volume match exactly. */
@Composable
private fun SliderValue(text: String) {
    Text(
        text, style = DockTheme.type.numeral.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp),
    )
}

/**
 * Controlling the Mac from the phone. Brightness is an absolute slider when the Mac
 * advertises `macbrightness`, else stepped buttons; volume is absolute, seeded from `mac-info`.
 */
@Composable
internal fun MacControlsCard(volume: Int?) {
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    var slider by remember(volume) { mutableFloatStateOf((volume ?: 50).toFloat()) }
    val caps by ConnectionManager.macCaps.collectAsState()
    val macBrightness = ConnectionManager.macInfo.collectAsState().value?.brightness
    val absoluteBrightness = "macbrightness" in caps
    val haptics = LocalHapticFeedback.current
    var bright by remember(macBrightness) { mutableFloatStateOf(macBrightness ?: 0.5f) }
    var lastSentAt by remember { mutableStateOf(0L) }

    Column(Modifier.fillMaxWidth()) {
        DockSectionHeader(t("Mac controls"))
        DockCard {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                DockButton(
                    t("Lock"), { ConnectionManager.sendRemote("lock") },
                    modifier = Modifier.weight(1f), kind = ButtonKind.TONAL,
                    icon = Icons.Rounded.Lock, fullWidth = true,
                )
                DockButton(
                    t("Saver"), { ConnectionManager.sendRemote("screensaver") },
                    modifier = Modifier.weight(1f), kind = ButtonKind.TONAL,
                    icon = Icons.Rounded.ScreenLockLandscape, fullWidth = true,
                )
            }

            Spacer(Modifier.height(sp.m))
            if (absoluteBrightness) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BrightnessLow, null, tint = s.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(sp.m))
                    val brightLabel = t("Brightness")
                    Slider(
                        value = bright,
                        onValueChange = {
                            val edge = (it <= 0f && bright > 0f) || (it >= 1f && bright < 1f)
                            bright = it
                            if (edge) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // At most 10 sends a second while dragging; the Mac coalesces the rest.
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastSentAt >= 100) {
                                lastSentAt = now
                                ConnectionManager.sendMacBrightness(it)
                            }
                        },
                        onValueChangeFinished = { ConnectionManager.sendMacBrightness(bright) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = brightLabel },
                    )
                    Spacer(Modifier.width(sp.s))
                    SliderValue("${Math.round(bright * 100)}")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BrightnessMedium, null, tint = s.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(sp.m))
                    Text(t("Brightness"), style = MaterialTheme.typography.bodyLarge, color = s.onSurface,
                        modifier = Modifier.weight(1f))
                    DockIconButton(Icons.Rounded.Remove, t("Dim"), {
                        ConnectionManager.sendRemote("brightness") { it.put("dir", "down") }
                    })
                    Spacer(Modifier.width(sp.s))
                    DockIconButton(Icons.Rounded.Add, t("Brighten"), {
                        ConnectionManager.sendRemote("brightness") { it.put("dir", "up") }
                    })
                }
            }

            if (volume != null) {
                Spacer(Modifier.height(sp.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeUp, null, tint = s.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(sp.m))
                    val volLabel = t("Volume")
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        // Only the settled value is sent: each one is an osascript run on the Mac.
                        onValueChangeFinished = {
                            ConnectionManager.sendRemote("volume_set") { it.put("level", slider.toInt()) }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = volLabel },
                    )
                    Spacer(Modifier.width(sp.s))
                    SliderValue("${slider.toInt()}")
                }
            }
        }
    }
}
