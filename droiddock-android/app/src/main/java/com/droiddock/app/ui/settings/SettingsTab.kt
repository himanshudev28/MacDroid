package com.droiddock.app.ui.settings

import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droiddock.app.BuildConfig
import com.droiddock.app.ThemeMode
import com.droiddock.app.UpdateChecker
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.components.DockSection
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.setup.PermissionChecklist
import com.droiddock.app.ui.setup.PermissionItem
import com.droiddock.app.ui.setup.ScreenControlRows
import com.droiddock.app.ui.setup.ServiceRow
import com.droiddock.app.ui.setup.ToggleRow
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.AppearanceStore
import com.droiddock.app.ui.theme.DockTheme

/**
 * Settings: DockScaffold (large collapsing header in Reach, normal top bar
 * elsewhere) over grouped sections. Lazy, so only the visible sections compose.
 *
 * Appearance reads/writes [AppearanceStore] directly, so every control switches
 * the whole app live. [themeMode]/[pitchBlack] callbacks are still forwarded so
 * the host's own copies of those two values stay in sync.
 */
@Composable
internal fun SettingsTab(
    clipA11y:         Boolean,
    clipAuto:         Boolean,
    notifAccess:      Boolean,
    phonePerms:       Boolean,
    allFiles:         Boolean,
    deviceAdmin:      Boolean,
    themeMode:        ThemeMode,
    onThemeMode:      (ThemeMode) -> Unit,
    pitchBlack:       Boolean,
    onPitchBlack:     (Boolean) -> Unit,
    defaultTab:       String,
    onDefaultTab:     (String) -> Unit,
    crashNotify:      Boolean,
    onCrashNotify:    (Boolean) -> Unit,
    onOpenConnect:    () -> Unit,
    expandNet:        Boolean,
    onExpandNet:      (Boolean) -> Unit,
    onEnableClip:     () -> Unit,
    onToggleClipAuto: (Boolean) -> Unit,
    onEnableNotif:    () -> Unit,
    onGrantPhonePerms: () -> Unit,
    onGrantFiles:     () -> Unit,
    onBattery:        () -> Unit,
    onOpenGuide:      () -> Unit,
    /** Seeded by the app-open check so the nav-bar update dot lands on a result. */
    knownUpdate:      UpdateChecker.Release?,
) {
    val ctx = LocalContext.current
    val appearance by AppearanceStore.state.collectAsStateWithLifecycle()
    SettingsContent(
        appearance = appearance,
        onAppearance = { transform ->
            AppearanceStore.update(ctx, transform)
            val next = transform(appearance)
            if (next.themeMode != themeMode) onThemeMode(next.themeMode)
            if (next.amoled != pitchBlack) onPitchBlack(next.amoled)
        },
        clipA11y = clipA11y, clipAuto = clipAuto, notifAccess = notifAccess, phonePerms = phonePerms,
        allFiles = allFiles, deviceAdmin = deviceAdmin,
        defaultTab = defaultTab, onDefaultTab = onDefaultTab,
        crashNotify = crashNotify, onCrashNotify = onCrashNotify,
        onOpenConnect = onOpenConnect, expandNet = expandNet, onExpandNet = onExpandNet,
        onEnableClip = onEnableClip, onToggleClipAuto = onToggleClipAuto, onEnableNotif = onEnableNotif,
        onGrantPhonePerms = onGrantPhonePerms, onGrantFiles = onGrantFiles, onBattery = onBattery,
        onOpenGuide = onOpenGuide, knownUpdate = knownUpdate,
    )
}

/** The screen without the store, so snapshots can drive it with plain values. */
@Composable
internal fun SettingsContent(
    appearance: Appearance,
    onAppearance: ((Appearance) -> Appearance) -> Unit,
    clipA11y: Boolean, clipAuto: Boolean, notifAccess: Boolean, phonePerms: Boolean,
    allFiles: Boolean, deviceAdmin: Boolean,
    defaultTab: String, onDefaultTab: (String) -> Unit,
    crashNotify: Boolean, onCrashNotify: (Boolean) -> Unit,
    onOpenConnect: () -> Unit,
    expandNet: Boolean, onExpandNet: (Boolean) -> Unit,
    onEnableClip: () -> Unit, onToggleClipAuto: (Boolean) -> Unit, onEnableNotif: () -> Unit,
    onGrantPhonePerms: () -> Unit, onGrantFiles: () -> Unit, onBattery: () -> Unit,
    onOpenGuide: () -> Unit,
    knownUpdate: UpdateChecker.Release?,
) {
    val perms = listOf(
        PermissionItem(
            "notif", Icons.Outlined.Notifications, t("Notification Access"),
            t("Shows your phone's notifications on the Mac"), notifAccess,
            onAllow = onEnableNotif,
        ),
        PermissionItem(
            "phone", Icons.Outlined.Message, t("SMS · Contacts · Calls"),
            t("Texts, contacts and call alerts on the Mac"), phonePerms,
            onAllow = onGrantPhonePerms,
        ),
        PermissionItem(
            "files", Icons.Outlined.FolderOpen, t("All-files Access"),
            t("Lets the Mac browse and transfer your phone's files"), allFiles,
            onAllow = onGrantFiles,
        ),
        PermissionItem(
            "battery", Icons.Outlined.BatteryChargingFull, t("Background (Battery)"),
            t("Keeps the link alive when the screen is off"), null,
            onAllow = onBattery,
        ),
    )

    DockScaffold(title = t("Settings")) {
        section("appearance", t("Appearance")) {
            AppearanceRows(appearance, onAppearance)
            DockDivider()
            LanguageRow()
            DockDivider()
            DefaultTabRow(selected = defaultTab, onSelect = onDefaultTab)
            DockDivider()
            ToggleRow(
                icon = Icons.Outlined.BugReport, tone = Tone.ERROR,
                title = t("Notify on crash"),
                subtitle = t("Name the error in a notification if DroidDock stops. It never hides a crash, only reports it."),
                checked = crashNotify, onChecked = onCrashNotify,
            )
        }
        section("connection", t("Connection")) {
            ServiceRow(
                icon = Icons.Outlined.WifiTethering,
                title = t("Pair or change Mac"),
                subtitle = t("Scan a QR code, enter an IP, or forget this Mac"),
                granted = null, action = "Open",
            ) { onOpenConnect() }
            DockDivider()
            ToggleRow(
                icon = Icons.Outlined.VpnKey, tone = Tone.SUCCESS,
                title = t("Expand networking"),
                subtitle = t("Reach your Mac over a VPN such as Tailscale. Local discovery can't cross a tailnet, so this stops waiting on it and keeps dialling the saved addresses."),
                checked = expandNet, onChecked = onExpandNet,
            )
            BluetoothFallbackRows()
        }
        section("phone", t("This phone")) { DeviceIdentityRows() }
        section("permissions", t("Permissions")) {
            PermissionChecklist(perms)
            DockDivider()
            AutoClipRow(a11yOn = clipA11y, auto = clipAuto, onEnable = onEnableClip, onToggle = onToggleClipAuto)
            DockDivider()
            ScreenControlRows(serviceOn = clipA11y, adminOn = deviceAdmin)
        }
        section("tiles", t("Quick Settings tiles")) { TileRows() }
        section("help", t("Help")) {
            ServiceRow(
                icon = Icons.Outlined.LibraryBooks,
                title = t("Feature Guide"),
                subtitle = t("Step-by-step help for every feature"),
                granted = null, action = "Open",
            ) { onOpenGuide() }
        }
        section("updates", t("Updates")) {
            UpdateRow(knownUpdate)
            DockDivider()
            AutoUpdateRow()
        }
        item(key = "version", contentType = "footer") {
            Text(
                "DroidDock · ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = DockTheme.spacing.xs),
            )
        }
    }
}

private fun LazyListScope.section(key: String, title: String, content: @Composable () -> Unit) {
    item(key = key, contentType = "section") {
        DockSection(title, dockItemAnimation()) { content() }
    }
}
