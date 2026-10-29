package com.droiddock.app.ui.pairing

import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.ConnectionManager
import com.droiddock.app.DiscoveredMac
import com.droiddock.app.KnownDevice
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockChevron
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.components.DockSection
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme

@Composable
internal fun ConnectTab(
    connected: Boolean,
    paired:    Boolean,
    macName:   String?,
    onScan:    () -> Unit,
    onManual:  () -> Unit,
    onForget:  () -> Unit,
    onBack:    () -> Unit,
) {
    val ctx = LocalContext.current
    val discovered by ConnectionManager.discovered.collectAsStateWithLifecycle()
    val scanning   by ConnectionManager.scanning.collectAsStateWithLifecycle()

    // Sweep when the tab opens and keep it fresh while the app is on screen (it holds a multicast browse
    // open). Once linked there is nothing to find, so it stops; a backgrounded app never scans.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected) {
        if (connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                ConnectionManager.scanNow(ctx)
                kotlinx.coroutines.delay(6_000)
            }
        }
    }
    ConnectContent(connected, paired, macName, discovered, scanning, onScan, onManual, onForget, onBack) { mac, known ->
        ConnectionManager.connectToDiscovered(ctx, mac, known)
    }
}

/** Stateless body, for snapshots. */
@Composable
internal fun ConnectContent(
    connected: Boolean,
    paired: Boolean,
    macName: String?,
    discovered: List<DiscoveredMac>,
    scanning: Boolean,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
    onConnect: (DiscoveredMac, KnownDevice) -> Unit,
) {
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    DockScaffold(
        title = t("Connection"),
        subtitle = t("Pair or manage your Mac link"),
        onBack = onBack, backLabel = t("Back"),
    ) {
        item(key = "status", contentType = "status") {
            DockCard(dockItemAnimation()) {
                DockStatusPill(
                    text = when {
                        connected -> t("Connected to {mac}", "mac" to (macName ?: "Mac"))
                        paired -> t("Searching for Mac…")
                        else -> t("Not paired")
                    },
                    tone = if (connected) Tone.SUCCESS else Tone.NEUTRAL,
                )
                DockButton(
                    text = if (paired) t("Re-pair with Mac") else t("Scan QR code"),
                    onClick = onScan, icon = Icons.Outlined.QrCodeScanner,
                    fullWidth = true, glow = true,
                    modifier = Modifier.padding(top = sp.l),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DockButton(t("Enter IP manually"), onManual, kind = ButtonKind.GHOST, icon = Icons.Outlined.Keyboard)
                    if (paired) DockButton(t("Forget this Mac"), onForget, kind = ButtonKind.GHOST)
                }
            }
        }
        item(key = "devices", contentType = "section") {
            Box(dockItemAnimation()) { AvailableDevicesCard(discovered, scanning, onConnect) }
        }
        item(key = "how", contentType = "section") {
            DockSection(t("How to pair"), dockItemAnimation()) {
                val steps = listOf(
                    "Open DroidDock on your Mac",
                    "Click 'Pair Device' or the QR icon in the sidebar",
                    "Tap 'Scan QR code' above and scan the code on screen",
                    "Both devices join the same Wi-Fi network automatically",
                )
                steps.forEachIndexed { i, step ->
                    if (i > 0) DockDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier.size(24.dp).background(s.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = s.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(sp.m))
                        Text(
                            t(step), style = MaterialTheme.typography.bodyMedium,
                            color = s.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Macs currently advertising over mDNS. Discovery yields an address, never a
 * token: a previously paired Mac connects from here; an unknown one still needs
 * QR or manual pairing.
 */
@Composable
internal fun AvailableDevicesCard(
    discovered: List<DiscoveredMac>,
    scanning:   Boolean,
    onConnect:  (DiscoveredMac, KnownDevice) -> Unit,
) {
    DockSection(if (scanning) t("Available devices · Scanning…") else t("Available devices")) {
        if (discovered.isEmpty()) {
            Text(
                if (scanning) t("Looking for Macs on this network…") else t("No Macs found on this network"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(DockTheme.spacing.xl),
            )
        } else {
            discovered.forEachIndexed { idx, mac ->
                if (idx > 0) DockDivider()
                val known = ConnectionManager.knownFor(mac)
                DockListItem(
                    headline = mac.name,
                    supporting = "${mac.ip}:${mac.port} · ${mac.via}",
                    icon = Icons.Outlined.LaptopMac,
                    iconTone = if (known != null) Tone.PRIMARY else Tone.NEUTRAL,
                    onClick = if (known != null) ({ onConnect(mac, known) }) else null,
                    trailing = {
                        if (known != null) DockChevron()
                        else Text(
                            t("Scan QR to pair"), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}
