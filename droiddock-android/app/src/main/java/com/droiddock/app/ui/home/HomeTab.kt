package com.droiddock.app.ui.home

import com.droiddock.app.ui.theme.glass.glassControl
import com.droiddock.app.ui.theme.glass.frostActive
import com.droiddock.app.ui.components.AnimatedCounter
import com.droiddock.app.ui.theme.dockItemAnimation
import com.droiddock.app.ui.theme.dockStagger
import com.droiddock.app.ui.theme.rememberDockEntrance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import com.droiddock.app.ConnectionManager
import com.droiddock.app.KnownDevice
import com.droiddock.app.LinkSecurity
import com.droiddock.app.t
import com.droiddock.app.ui.common.pauseSubtitle
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockCircleToggle
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockHero
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockSectionHeader
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.components.HeroState
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.components.dockClickable
import com.droiddock.app.ui.theme.DockTheme

/**
 * Home: connection hero, quick actions, Mac stats, now playing, Mac controls,
 * an optional clipboard card (4-tab layout) and the devices list.
 *
 * Screens never branch on style; every surface is a Dock* component. Behaviour
 * is unchanged: the same callbacks as before, plus the optional [clipboard]
 * slot the shell fills when Clipboard is not a tab of its own.
 */
@Composable
internal fun HomeTab(
    connected:    Boolean,
    isPaused:     Boolean,
    paired:       Boolean,
    macName:      String?,
    macInfo:      ConnectionManager.MacInfo?,
    event:        String?,
    pausedUntil:  Long,
    devices:      List<KnownDevice>,
    activeToken:  String?,
    disconnected: Boolean,
    canControlMac: Boolean,
    macMedia:     ConnectionManager.MacMedia?,
    onPause:      () -> Unit,
    onResume:     () -> Unit,
    onQuickConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPickDevice: (KnownDevice) -> Unit,
    onRemoveDevice: (KnownDevice) -> Unit = {},
    onGoToConnect: () -> Unit,
    onGoToFiles:   () -> Unit,
    clipboard: (@Composable () -> Unit)? = null,
    transport: com.droiddock.app.link.Transport? = null,
    secure: Boolean? = null,
    identityChanged: Boolean = false,
    securityProblem: LinkSecurity.Problem? = null,
) {
    val sp = DockTheme.spacing
    val shell = LocalDockContentPadding.current
    val dir = LocalLayoutDirection.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val entrance = rememberDockEntrance("home")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = sp.gutter + shell.calculateStartPadding(dir),
            end = sp.gutter + shell.calculateEndPadding(dir),
            top = statusTop + sp.m,
            bottom = shell.calculateBottomPadding() + sp.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(sp.m),
    ) {
        item(key = "hero", contentType = "hero") {
            Box(dockItemAnimation().dockStagger(entrance, 0)) { ConnectionHeroCard(
                connected = connected, isPaused = isPaused, paired = paired,
                macName = macName, macInfo = macInfo, event = event, pausedUntil = pausedUntil,
                knownName = devices.firstOrNull()?.macName?.takeIf { it.isNotBlank() && it != "Mac" },
                disconnected = disconnected, transport = transport,
                secure = secure, identityChanged = identityChanged, securityProblem = securityProblem,
                onPause = onPause, onResume = onResume,
                onQuickConnect = onQuickConnect, onGoConnect = onGoToConnect, onGoToFiles = onGoToFiles,
            ) }
        }

        item(key = "quick", contentType = "quick") {
            Box(dockItemAnimation().dockStagger(entrance, 1)) { QuickActions(
                paired = paired, isPaused = isPaused,
                showMacControls = connected && canControlMac,
                onPause = onPause, onResume = onResume,
                onGoToFiles = onGoToFiles, onGoToConnect = onGoToConnect,
            ) }
        }

        if (macInfo != null) {
            item(key = "stats", contentType = "stats") {
                Row(Modifier.fillMaxWidth().then(dockItemAnimation()).dockStagger(entrance, 2), horizontalArrangement = Arrangement.spacedBy(sp.m)) {
                    val info = macInfo
                    StatTile(
                        text = if (!info.hasBattery || info.battery == null) "AC" else null,
                        number = info.battery,
                        suffix = "%",
                        label = if (info.charging) t("Mac battery · charging") else t("Mac battery"),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        number = devices.size,
                        label = t("Paired Macs"),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Only while the Mac says it will accept input, same gate as the Control tab.
        if (connected && canControlMac) {
            item(key = "playing", contentType = "playing") { Box(dockItemAnimation().dockStagger(entrance, 3)) { MacNowPlayingCard(media = macMedia) } }
            item(key = "controls", contentType = "controls") { Box(dockItemAnimation().dockStagger(entrance, 4)) { MacControlsCard(volume = macInfo?.volume) } }
        }

        if (clipboard != null) {
            item(key = "clipboard", contentType = "clipboard") { Box(dockItemAnimation().dockStagger(entrance, 5)) { clipboard() } }
        }

        if (devices.isNotEmpty()) {
            item(key = "devices", contentType = "devices") {
                Box(dockItemAnimation().dockStagger(entrance, 6)) { DevicesCard(
                    devices = devices, connected = connected, activeToken = activeToken,
                    liveName = macName,
                    onDisconnect = onDisconnect, onPickDevice = onPickDevice,
                    onRemoveDevice = onRemoveDevice,
                ) }
            }
        }
    }
}

@Composable
internal fun ConnectionHeroCard(
    connected:   Boolean,
    isPaused:    Boolean,
    paired:      Boolean,
    macName:     String?,
    macInfo:     ConnectionManager.MacInfo?,
    event:       String?,
    pausedUntil: Long,
    knownName:   String?,
    disconnected: Boolean,
    onPause:     () -> Unit,
    onResume:    () -> Unit,
    onQuickConnect: () -> Unit,
    onGoConnect: () -> Unit,
    onGoToFiles: () -> Unit,
    transport: com.droiddock.app.link.Transport? = null,
    secure: Boolean? = null,
    identityChanged: Boolean = false,
    securityProblem: LinkSecurity.Problem? = null,
) {
    // Radar only for the first 30 s of a search; after that a calm "offline" (no endless loop).
    var searchSettled by remember(connected, paired, disconnected) { mutableStateOf(false) }
    LaunchedEffect(connected, paired, disconnected) {
        if (!connected && paired) { kotlinx.coroutines.delay(30_000); searchSettled = true }
    }
    val state = when {
        isPaused  -> HeroState.PAUSED
        connected -> HeroState.CONNECTED
        // Still looking for the Mac: radar rings and the sweep, not a dead "offline".
        paired && !disconnected && !identityChanged && securityProblem == null && !searchSettled -> HeroState.CONNECTING
        paired    -> HeroState.OFFLINE
        else      -> HeroState.UNPAIRED
    }
    val title = when {
        isPaused  -> t("Paused")
        connected -> macName ?: knownName ?: "Mac"
        paired    -> knownName ?: t("Searching…")
        else      -> t("Not paired")
    }
    val status = when {
        isPaused  -> pauseSubtitle(pausedUntil)
        connected && transport == com.droiddock.app.link.Transport.BLE -> t("Connected · Bluetooth")
        connected && secure == true -> t("Connected · Encrypted")
        connected && secure == false -> t("Connected · Not encrypted")
        connected -> if (event.isNullOrBlank()) t("Connected · Wi-Fi") else "Connected · $event"
        paired    -> if (knownName != null) t("Searching for this Mac…") else t("Waiting for Mac to come online")
        else      -> t("Pair with your Mac to get started")
    }
    val detail = when {
        !connected && !isPaused && paired && identityChanged -> t("This Mac's identity changed. Scan its QR code again to reconnect.")
        !connected && !isPaused && paired && securityProblem == LinkSecurity.Problem.TLS_REQUIRED -> t("This Mac only accepts secure connections. Scan its QR code again.")
        !connected && !isPaused && paired && securityProblem == LinkSecurity.Problem.NOT_TLS -> t("This Mac isn't using a secure connection. Update DroidDock on the Mac or scan its QR code again.")
        !connected && !isPaused && paired && disconnected -> t("Auto-reconnect is off until you connect again.")
        else -> null
    }

    val onPrimary: () -> Unit
    val primaryLabel: String
    val primaryIcon: ImageVector
    when (state) {
        HeroState.UNPAIRED -> { primaryLabel = t("Pair with Mac"); primaryIcon = Icons.Rounded.QrCodeScanner; onPrimary = onGoConnect }
        HeroState.PAUSED   -> { primaryLabel = t("Resume"); primaryIcon = Icons.Rounded.PowerSettingsNew; onPrimary = onResume }
        HeroState.CONNECTED -> { primaryLabel = t("Send File"); primaryIcon = Icons.Rounded.UploadFile; onPrimary = onGoToFiles }
        else -> { primaryLabel = t("Quick Connect"); primaryIcon = Icons.Rounded.Cable; onPrimary = onQuickConnect }
    }

    DockHero(
        title = title,
        status = status,
        state = state,
        detail = detail,
        battery = if (connected) macInfo?.takeIf { it.hasBattery }?.battery else null,
        charging = macInfo?.charging == true,
        deviceIcon = Icons.Rounded.Laptop,
        primaryLabel = primaryLabel,
        primaryIcon = primaryIcon,
        onPrimary = onPrimary,
        secondaryLabel = if (connected && !isPaused) t("Pause") else null,
        onSecondary = if (connected && !isPaused) onPause else null,
    )
}

/** Circular quick actions. Sync is a real toggle; the rest are momentary. */
@Composable
private fun QuickActions(
    paired: Boolean,
    isPaused: Boolean,
    showMacControls: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onGoToFiles: () -> Unit,
    onGoToConnect: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        if (paired) {
            DockCircleToggle(
                icon = Icons.Rounded.WifiTethering, label = t("Sync"),
                checked = !isPaused,
                onToggle = { on -> if (on) onResume() else onPause() },
            )
        }
        QuickAction(Icons.Rounded.UploadFile, t("Send File"), onGoToFiles)
        QuickAction(Icons.Rounded.QrCodeScanner, t("Pair Mac"), onGoToConnect)
        if (showMacControls) {
            QuickAction(Icons.Rounded.Lock, t("Lock Mac")) { ConnectionManager.sendRemote("lock") }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = DockTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DockTheme.spacing.s),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .then(
                    (if (DockTheme.shapes.control == CircleShape) CircleShape else DockTheme.shapes.control).let { sh ->
                        if (frostActive()) Modifier.glassControl(sh, DockTheme.colors.raised.copy(alpha = 0.62f))
                        else Modifier.clip(sh).background(DockTheme.colors.raised)
                    }
                )
                .dockClickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = s.onSurface, modifier = Modifier.size(26.dp))
        }
        Text(
            if (DockTheme.type.upperCaseLabels) label.uppercase() else label,
            style = if (DockTheme.type.upperCaseLabels) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = s.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun StatTile(label: String, modifier: Modifier = Modifier, number: Int? = null, text: String? = null, suffix: String? = null) {
    val s = MaterialTheme.colorScheme
    DockCard(modifier) {
        if (text != null || number == null) {
            Text(text ?: "", style = DockTheme.type.numeral, color = s.onSurface, maxLines = 1)
        } else {
            AnimatedCounter(number, style = DockTheme.type.numeral, color = s.onSurface, suffix = suffix)
        }
        Spacer(Modifier.height(DockTheme.spacing.xs))
        Text(
            if (DockTheme.type.upperCaseLabels) label.uppercase() else label,
            style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Known Macs. The head is whichever Mac the connect loop points at; the rest
 * are previous pairings, tappable to switch.
 */
@Composable
internal fun DevicesCard(
    devices:      List<KnownDevice>,
    connected:    Boolean,
    activeToken:  String?,
    liveName:     String?,
    onDisconnect: () -> Unit,
    onPickDevice: (KnownDevice) -> Unit,
    onRemoveDevice: (KnownDevice) -> Unit = {},
) {
    val sp = DockTheme.spacing
    // The row the user asked to remove, waiting on the confirm dialog.
    var confirmRemove by remember { mutableStateOf<KnownDevice?>(null) }
    confirmRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(t("Remove {name}?", "name" to d.macName)) },
            text = { Text(t("You'll need its QR code to pair it again.")) },
            confirmButton = { TextButton(onClick = { confirmRemove = null; onRemoveDevice(d) }) { Text(t("Remove")) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(t("Cancel")) } },
        )
    }
    val removeButton: @Composable (KnownDevice) -> Unit = { d ->
        IconButton(onClick = { confirmRemove = d }) {
            Icon(Icons.Rounded.Close, contentDescription = t("Remove {name}", "name" to d.macName),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val active = devices.firstOrNull { it.token == activeToken } ?: devices.first()
    val others = devices.filter { it.token != active.token }
    // The live name reflects a rename on the Mac; the stored one keeps the card
    // truthful while the link is down.
    val activeName = (if (connected) liveName else null)?.takeIf { it.isNotBlank() }
        ?: active.macName.takeIf { it.isNotBlank() && it != "Mac" }
        ?: "Mac"

    Column(Modifier.fillMaxWidth()) {
        DockSectionHeader(t("Your Macs"))
        DockCard(contentPadding = 0.dp) {
            DockListItem(
                headline = activeName,
                supporting = if (connected) "Connected · ${active.ips.firstOrNull().orEmpty()}:${active.port}"
                             else lastSeenLabel(active.lastSeenAt),
                icon = Icons.Rounded.Laptop,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connected) DockStatusPill(t("Online"))
                        removeButton(active)
                    }
                },
            )
            others.forEach { device ->
                DockDivider()
                DockListItem(
                    headline = device.macName,
                    supporting = lastSeenLabel(device.lastSeenAt),
                    icon = Icons.Rounded.Laptop,
                    iconTone = Tone.NEUTRAL,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Connect"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            removeButton(device)
                        }
                    },
                    onClick = { onPickDevice(device) },
                )
            }
            if (connected) {
                Box(Modifier.padding(horizontal = sp.l, vertical = sp.m)) {
                    DockButton(
                        t("Disconnect"), onDisconnect, kind = ButtonKind.SECONDARY,
                        icon = Icons.Outlined.LinkOff, fullWidth = true,
                    )
                }
            }
        }
    }
}

/** "Last seen 4m ago", including "just now" for anything under a minute. */
internal fun lastSeenLabel(ts: Long): String {
    if (ts <= 0L) return "Not connected yet"
    val diff = System.currentTimeMillis() - ts
    return "Last seen " + when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else               -> "${diff / 86_400_000L}d ago"
    }
}
