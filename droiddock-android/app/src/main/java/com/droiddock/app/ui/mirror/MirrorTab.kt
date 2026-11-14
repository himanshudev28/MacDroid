package com.droiddock.app.ui.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.droiddock.app.MirrorService
import com.droiddock.app.ui.components.DockNumeral
import com.droiddock.app.ui.components.DockTether
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.theme.dockEnterTransition
import com.droiddock.app.ui.theme.dockExitTransition
import com.droiddock.app.ui.theme.dockStagger
import com.droiddock.app.ui.theme.rememberAmbientActive
import com.droiddock.app.ui.theme.rememberDockEntrance
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.Composable
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockIconBadge
import com.droiddock.app.ui.components.DockSectionHeader
import com.droiddock.app.ui.components.DockSwitch
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.components.dockSurface
import com.droiddock.app.ui.components.toneColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.droiddock.app.ui.theme.DockTheme

/** What the phone-side stream is doing, as the tether draws it. */
internal enum class MirrorLink { OFFLINE, READY, STARTING, LIVE }

/**
 * Polls [MirrorService.instance] (it is a plain field, not a flow) once a second while the screen is resumed.
 * An instance whose encoder has not started yet is STARTING; once [MirrorService.startedAt] is set it is LIVE.
 */
@Composable
private fun rememberMirrorLink(connected: Boolean, preview: MirrorLink?): State<Pair<MirrorLink, Int>> {
    val owner = LocalLifecycleOwner.current
    val out = remember { mutableStateOf(MirrorLink.OFFLINE to 0) }
    if (preview != null) return remember(preview) { mutableStateOf(preview to if (preview == MirrorLink.LIVE) 128 else 0) }
    LaunchedEffect(connected, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val up = MirrorService.instance != null
                val began = MirrorService.startedAt
                // The service owns the start time, so resuming the tab never restarts the clock.
                out.value = when {
                    up && began > 0L -> MirrorLink.LIVE
                    up -> MirrorLink.STARTING
                    connected -> MirrorLink.READY
                    else -> MirrorLink.OFFLINE
                } to (if (up && began > 0L) ((now - began) / 1000).toInt() else 0)
                delay(500)
            }
        }
    }
    return out
}

@Composable
internal fun MirrorTab(
    connected:          Boolean,
    overlayOk:          Boolean,
    autoMirror:         Boolean,
    onEnableOverlay:    () -> Unit,
    onToggleAutoMirror: (Boolean) -> Unit,
    /** Snapshots only: pins the tether state, since there is no service to poll. */
    previewLink:        MirrorLink? = null,
) {
    val sp = DockTheme.spacing
    val shell = LocalDockContentPadding.current
    val ctx = LocalContext.current
    val entrance = rememberDockEntrance("mirror")
    // Only the link card reads the per-second value; the list above reads just the coarse state.
    val linkState = rememberMirrorLink(connected, previewLink)
    val link by remember { derivedStateOf { linkState.value.first } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = sp.gutter, end = sp.gutter, top = sp.m,
            bottom = shell.calculateBottomPadding() + sp.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(sp.m),
    ) {
        item(key = "title", contentType = "title") {
            Column(Modifier.padding(horizontal = sp.xs).dockStagger(entrance, 0)) {
                Text(t("Mirror & Camera"), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(t("Two ways to project your screen or camera"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item(key = "link", contentType = "link") {
            Box(Modifier.dockStagger(entrance, 1)) {
                MirrorLinkCard(link, linkState, onStop = { MirrorService.stop(ctx) })
            }
        }

        item(key = "h-screen", contentType = "header") {
            DockSectionHeader(t("Screen mirror"), Modifier.dockStagger(entrance, 2))
        }

        item(key = "mirror-wifi", contentType = "mode") {
            MirrorModeCard(
                title = t("Mirror via Wi-Fi"), badge = "Wi-Fi", badgeTone = Tone.SUCCESS,
                icon = Icons.Outlined.ScreenShare,
                description = t("Stream your screen over local Wi-Fi. No USB or Developer Options needed. Start from Mac's 'Screen Mirror' tab."),
                modifier = Modifier.dockStagger(entrance, 3),
            ) {
                Spacer(Modifier.height(sp.m))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.m),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Auto-accept mirror"), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(t("Start instantly, no per-session tap"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!overlayOk) {
                        DockButton(t("Enable"), onEnableOverlay, kind = ButtonKind.TONAL, height = sp.touch)
                    } else {
                        DockSwitch(checked = autoMirror, onCheckedChange = onToggleAutoMirror)
                    }
                }
            }
        }

        item(key = "mirror-adb", contentType = "mode") {
            MirrorModeCard(
                title = t("Mirror via ADB"), badge = "ADB", badgeTone = Tone.WARNING,
                icon = Icons.Outlined.Usb,
                description = t("Higher quality, lower latency mirror using scrcpy over USB. Requires Developer Options enabled on this phone."),
                modifier = Modifier.dockStagger(entrance, 4),
            ) {
                Spacer(Modifier.height(sp.m))
                InfoStrip(t("Connect USB cable, then click 'Mirror via ADB' in the Mac app's Screen Mirror tab."))
            }
        }

        item(key = "h-camera", contentType = "header") {
            DockSectionHeader(t("Camera streaming"), Modifier.dockStagger(entrance, 5))
        }

        item(key = "camera-wifi", contentType = "mode") {
            MirrorModeCard(
                title = t("Camera via Wi-Fi"), badge = "Wi-Fi", badgeTone = Tone.SUCCESS,
                icon = Icons.Outlined.Videocam,
                description = t("Stream your phone camera over local Wi-Fi — use it as a webcam on your Mac. Start from Mac's 'Camera' tab."),
                modifier = Modifier.dockStagger(entrance, 5),
            )
        }

        item(key = "camera-adb", contentType = "mode") {
            MirrorModeCard(
                title = t("Camera via ADB"), badge = "ADB", badgeTone = Tone.WARNING,
                icon = Icons.Outlined.CameraAlt,
                description = t("Higher quality camera stream using scrcpy over USB. Requires Developer Options and USB cable."),
                modifier = Modifier.dockStagger(entrance, 6),
            ) {
                Spacer(Modifier.height(sp.m))
                InfoStrip(t("Connect USB cable, then click 'Start Camera (ADB)' in the Mac app's Camera tab."))
            }
        }
    }
}

/**
 * The mirror's state as one tether: phone ... Mac. OFFLINE breaks the line, STARTING drifts dashes, LIVE is solid and
 * pushes a pulse every few seconds of uptime, with a big odometer of how long it has been docked. Stop is a danger
 * button that docks in only while a stream exists.
 */
@Composable
private fun MirrorLinkCard(link: MirrorLink, linkState: State<Pair<MirrorLink, Int>>, onStop: () -> Unit) {
    val seconds = linkState.value.second
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    val state = when (link) {
        MirrorLink.OFFLINE -> TetherState.OFFLINE
        MirrorLink.STARTING -> TetherState.CONNECTING
        else -> TetherState.LINKED
    }
    val headline = when (link) {
        MirrorLink.OFFLINE -> t("Not connected to your Mac.")
        MirrorLink.READY -> t("Ready to mirror")
        MirrorLink.STARTING -> t("Starting the stream…")
        MirrorLink.LIVE -> t("Streaming to your Mac")
    }
    val live = link == MirrorLink.LIVE
    // The periodic pulse is a loop while live: only when ambient motion is allowed (not battery saver/thermal/background).
    val ambient = rememberAmbientActive()
    DockCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, color = s.onSurface)
                Text(
                    if (link == MirrorLink.READY) t("Start from the Mac's Screen Mirror tab")
                    else if (live) t("Live for") else t("The link carries the stream"),
                    style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant,
                )
            }
            if (live) {
                if (seconds >= 60) DockNumeral(seconds / 60, unit = t("min"), color = s.primary)
                else DockNumeral(seconds, unit = t("s"), color = s.primary)
            }
        }
        Spacer(Modifier.height(sp.m))
        DockTether(
            state = state,
            pulseKey = if (live && ambient) seconds / 4 else null,
            height = 28.dp,
            start = { Icon(Icons.Outlined.PhoneAndroid, null, tint = s.primary, modifier = Modifier.size(20.dp)) },
            end = { Icon(Icons.Outlined.LaptopMac, null, tint = if (link == MirrorLink.OFFLINE) s.outline else s.primary, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.semantics { contentDescription = headline },
        )
        AnimatedVisibility(
            visible = link == MirrorLink.LIVE || link == MirrorLink.STARTING,
            enter = dockEnterTransition(), exit = dockExitTransition(),
        ) {
            DockButton(
                t("Stop mirroring"), onStop, kind = ButtonKind.DANGER, icon = Icons.Outlined.StopCircle,
                fullWidth = true, modifier = Modifier.padding(top = sp.l),
            )
        }
    }
}

/** Recessed hint line inside a mode card. */
@Composable
private fun InfoStrip(text: String) {
    val sp = DockTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clip(DockTheme.shapes.field)
            .background(DockTheme.colors.inset)
            .padding(horizontal = sp.l, vertical = sp.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.m),
    ) {
        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MirrorModeCard(
    title:       String,
    badge:       String,
    badgeTone:   Tone,
    icon:        ImageVector,
    description: String,
    modifier:    Modifier = Modifier,
    extra:       (@Composable () -> Unit)? = null,
) {
    val sp = DockTheme.spacing
    val c = toneColors(badgeTone)
    DockCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.m)) {
            DockIconBadge(icon, tone = badgeTone, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    Box(
                        Modifier.clip(DockTheme.shapes.chip).background(c.container)
                            .padding(horizontal = sp.s, vertical = sp.xxs)
                    ) { Text(badge, style = MaterialTheme.typography.labelSmall, color = c.content) }
                }
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        extra?.invoke()
    }
}
