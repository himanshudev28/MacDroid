package com.droiddock.app.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.droiddock.app.A11yTileService
import com.droiddock.app.ClipTileService
import com.droiddock.app.ConnectionManager
import com.droiddock.app.ConnectionTileService
import com.droiddock.app.I18n
import com.droiddock.app.Prefs
import com.droiddock.app.R
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockChevron
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockIconBadge
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockSegmented
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.setup.ServiceRow
import com.droiddock.app.ui.setup.ToggleRow
import com.droiddock.app.ui.theme.DockTheme
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Auto-clipboard, as its own switch (the accessibility grant lives in the Permissions section). */
@Composable
internal fun AutoClipRow(
    a11yOn: Boolean,
    auto: Boolean,
    onEnable: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    if (!a11yOn) {
        ServiceRow(
            icon = Icons.Outlined.ContentCopy, tone = Tone.TERTIARY,
            title = t("Auto clipboard"),
            subtitle = t("Needs the accessibility service below"),
            granted = null, action = "Enable",
        ) { onEnable() }
    } else {
        ToggleRow(
            icon = Icons.Outlined.ContentCopy, tone = Tone.TERTIARY,
            title = t("Auto clipboard"),
            subtitle = if (auto) t("Copies on this phone go to the Mac automatically")
            else t("Off. Send copies manually from the Clipboard tab"),
            checked = auto, onChecked = onToggle,
        )
    }
}

/** Whether the app looks for a new release on its own. Owns its state. */
@Composable
internal fun AutoUpdateRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.autoCheckUpdates(ctx)) }
    ToggleRow(
        icon = Icons.Outlined.Schedule, tone = Tone.NEUTRAL,
        title = t("Check automatically"),
        subtitle = t("Looks for a new version when you open the app, at most once a day. It only tells you; nothing downloads or installs on its own."),
        checked = on,
        onChecked = { v -> on = v; Prefs.setAutoCheckUpdates(ctx, v) },
    )
}

/**
 * Language: one row showing the current choice, opening a bottom sheet list.
 * (The old inline segmented control rendered one character per line.)
 * `t` reads `I18n.locale`, so choosing a language repaints everything at once.
 */
@Composable
internal fun LanguageRow() {
    val ctx = LocalContext.current
    val current = I18n.locale
    var open by remember { mutableStateOf(false) }
    val systemLabel = t("System")
    val options = remember(systemLabel, current) { listOf("" to systemLabel) + I18n.available() }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: systemLabel

    DockListItem(
        headline = t("Language"),
        supporting = currentLabel,
        icon = Icons.Outlined.Language,
        iconTone = Tone.SECONDARY,
        trailing = { DockChevron() },
        onClick = { open = true },
    )
    if (open) {
        DockSheet(onDismiss = { open = false }, title = t("Language")) {
            if (I18n.onlyEnglish()) {
                Text(
                    t("Only English is bundled with this build, so there is nothing else to switch to yet. System follows your phone's language once a translation for it exists."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = DockTheme.spacing.m),
                )
            }
            options.forEach { (tag, label) ->
                val selected = tag == current
                DockListItem(
                    headline = label,
                    role = Role.RadioButton,
                    onClick = { I18n.set(ctx, tag); open = false },
                    trailing = {
                        if (selected) androidx.compose.material3.Icon(
                            Icons.Outlined.Check, t("Selected"), tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }
}

/** A title + subtitle + [DockSegmented] stacked, for small closed choices. */
@Composable
internal fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tone: Tone = Tone.PRIMARY,
) {
    val sp = DockTheme.spacing
    Column(Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockIconBadge(icon, tone = tone)
            Column(Modifier.weight(1f).padding(start = sp.l)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DockSegmented(options, selected, onSelect, Modifier.padding(top = sp.m))
    }
}

/**
 * Editable phone name + the address the Mac would reach it on. The name is
 * committed on focus loss (it goes out in `hello`); the IP lookup stays off the
 * main thread.
 */
@Composable
internal fun DeviceIdentityRows() {
    val ctx = LocalContext.current
    val sp = DockTheme.spacing
    var name by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    var resolved by remember { mutableStateOf(ConnectionManager.lastKnownLocalIp != null) }
    var localIp by remember { mutableStateOf(ConnectionManager.lastKnownLocalIp) }
    LaunchedEffect(Unit) {
        localIp = withContext(Dispatchers.IO) { ConnectionManager.localIpAddress() }
        resolved = true
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockIconBadge(Icons.Outlined.PhoneAndroid, tone = Tone.SUCCESS)
            Column(Modifier.weight(1f).padding(start = sp.l)) {
                Text(t("This phone"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        localIp != null -> t("Local IP: {ip}", "ip" to localIp)
                        resolved -> t("No network address")
                        else -> t("Checking network…")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text(t("Device name")) },
            placeholder = { Text(ConnectionManager.hardwareName()) },
            singleLine = true,
            shape = DockTheme.shapes.field, colors = com.droiddock.app.ui.components.dockFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = sp.m)
                .onFocusChanged { state -> if (!state.isFocused) Prefs.setDeviceName(ctx, name) },
        )
        Text(
            t("Shown on your Mac. Takes effect on the next reconnect."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = sp.xs, start = sp.xs),
        )
    }
}

/**
 * "Add tile" rows for all three Quick Settings tiles. `requestAddTileService`
 * is API 33+; below that the row explains where to find them by hand.
 */
@Composable
internal fun TileRows() {
    val ctx = LocalContext.current

    if (Build.VERSION.SDK_INT < 33) {
        Text(
            t("Add DroidDock's Connection and Clipboard tiles by editing your Quick Settings panel."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(DockTheme.spacing.cardPadding),
        )
        return
    }

    val addTile: (Class<*>, String) -> Unit = { cls, label ->
        runCatching {
            val sm = ctx.getSystemService(android.app.StatusBarManager::class.java)
            sm?.requestAddTileService(
                android.content.ComponentName(ctx, cls),
                label,
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat),
                { it.run() },
                { /* the system already told the user */ },
            )
        }.onFailure {
            Toast.makeText(ctx, t("Add it from the Quick Settings editor"), Toast.LENGTH_SHORT).show()
        }
    }

    ServiceRow(
        icon = Icons.Outlined.LaptopMac, tone = Tone.SECONDARY,
        title = t("Connection tile"),
        subtitle = t("Connect or disconnect from Quick Settings"),
        granted = null, action = "Add",
    ) { addTile(ConnectionTileService::class.java, "DroidDock") }
    DockDivider()
    ServiceRow(
        icon = Icons.Outlined.ContentPaste, tone = Tone.TERTIARY,
        title = t("Clipboard tile"),
        subtitle = t("Send what you copied straight to the Mac"),
        granted = null, action = "Add",
    ) { addTile(ClipTileService::class.java, "Send to Mac") }
    DockDivider()
    ServiceRow(
        icon = Icons.Outlined.Accessibility, tone = Tone.PRIMARY,
        title = t("Accessibility tile"),
        subtitle = t("Switch screen control off in one tap, for banking apps"),
        granted = null, action = "Add",
    ) { addTile(A11yTileService::class.java, "DroidDock Access") }
}

/** Which tab the app opens on. "Auto" is Connect while unpaired, Home once linked. */
@Composable
internal fun DefaultTabRow(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "dynamic" to t("Auto"),
        "home" to t("Home"),
        "connect" to t("Connect"),
        "clipboard" to t("Clipboard"),
    )
    ChoiceRow(
        icon = Icons.Outlined.Dashboard, tone = Tone.TERTIARY,
        title = t("Opening tab"),
        subtitle = if (selected == "dynamic") t("Connect until paired, then Home")
        else t("Always open on {tab}", "tab" to (options.firstOrNull { it.first == selected }?.second ?: t("Home"))),
        options = options, selected = selected, onSelect = onSelect,
    )
}
