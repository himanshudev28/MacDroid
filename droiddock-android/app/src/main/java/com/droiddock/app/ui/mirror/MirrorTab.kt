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
import androidx.compose.material3.Icon
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

@Composable
internal fun MirrorTab(
    connected:          Boolean,
    overlayOk:          Boolean,
    autoMirror:         Boolean,
    onEnableOverlay:    () -> Unit,
    onToggleAutoMirror: (Boolean) -> Unit,
) {
    val sp = DockTheme.spacing
    val shell = LocalDockContentPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = sp.gutter, end = sp.gutter, top = sp.m,
            bottom = shell.calculateBottomPadding() + sp.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(sp.m),
    ) {
        item(key = "title", contentType = "title") {
            Column(Modifier.padding(horizontal = sp.xs)) {
                Text(t("Mirror & Camera"), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(t("Two ways to project your screen or camera"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item(key = "h-screen", contentType = "header") { DockSectionHeader(t("Screen mirror")) }

        item(key = "mirror-wifi", contentType = "mode") {
            MirrorModeCard(
                title = t("Mirror via Wi-Fi"), badge = "Wi-Fi", badgeTone = Tone.SUCCESS,
                icon = Icons.Outlined.ScreenShare,
                description = t("Stream your screen over local Wi-Fi. No USB or Developer Options needed. Start from Mac's 'Screen Mirror' tab."),
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
            ) {
                Spacer(Modifier.height(sp.m))
                InfoStrip(t("Connect USB cable, then click 'Mirror via ADB' in the Mac app's Screen Mirror tab."))
            }
        }

        item(key = "h-camera", contentType = "header") { DockSectionHeader(t("Camera streaming")) }

        item(key = "camera-wifi", contentType = "mode") {
            MirrorModeCard(
                title = t("Camera via Wi-Fi"), badge = "Wi-Fi", badgeTone = Tone.SUCCESS,
                icon = Icons.Outlined.Videocam,
                description = t("Stream your phone camera over local Wi-Fi — use it as a webcam on your Mac. Start from Mac's 'Camera' tab."),
            )
        }

        item(key = "camera-adb", contentType = "mode") {
            MirrorModeCard(
                title = t("Camera via ADB"), badge = "ADB", badgeTone = Tone.WARNING,
                icon = Icons.Outlined.CameraAlt,
                description = t("Higher quality camera stream using scrcpy over USB. Requires Developer Options and USB cable."),
            ) {
                Spacer(Modifier.height(sp.m))
                InfoStrip(t("Connect USB cable, then click 'Start Camera (ADB)' in the Mac app's Camera tab."))
            }
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
    extra:       (@Composable () -> Unit)? = null,
) {
    val sp = DockTheme.spacing
    val c = toneColors(badgeTone)
    DockCard {
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
