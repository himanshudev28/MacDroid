package com.droiddock.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droiddock.app.ConnectionManager
import com.droiddock.app.Prefs
import com.droiddock.app.link.FallbackController
import com.droiddock.app.link.Transport
import com.droiddock.app.link.ble.BleStack
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.setup.ToggleRow
import com.droiddock.app.ui.theme.DockTheme

/**
 * Settings > Connection > Bluetooth fallback. Off by default; **nothing Bluetooth exists, is requested or
 * advertises until this switch is turned on.** Turning it on explains, asks for Nearby devices (Android 12+;
 * older versions need no prompt) and, if refused, switches back off with a way to fix it. Hidden on phones
 * without Bluetooth LE.
 */
@Composable
internal fun BluetoothFallbackRows() {
    val ctx = LocalContext.current
    if (!BleStack.hasBle(ctx)) return
    DockDivider()
    val mode by ConnectionManager.connectionMode.collectAsStateWithLifecycle()
    val autoReturn by ConnectionManager.bleAutoReturn.collectAsStateWithLifecycle()
    val transport by ConnectionManager.transport.collectAsStateWithLifecycle()
    val ui by ConnectionManager.linkUi.collectAsStateWithLifecycle()
    val state by BleStack.status.collectAsStateWithLifecycle()
    val on = mode == "fallback"

    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (BleStack.hasPermission(ctx)) {
            denied = false
            ConnectionManager.setConnectionMode(ctx, "fallback")
        } else {
            denied = grants.isNotEmpty()
            ConnectionManager.setConnectionMode(ctx, "network")
        }
    }
    fun enable() {
        if (BleStack.hasPermission(ctx)) ConnectionManager.setConnectionMode(ctx, "fallback")
        else explain = true
    }

    ToggleRow(
        icon = Icons.Outlined.Bluetooth, tone = Tone.PRIMARY,
        title = t("Bluetooth fallback"),
        subtitle = if (on) statusLine(state, transport)
        else t("Uses Bluetooth only when Wi-Fi to your Mac is unavailable, and switches back automatically."),
        checked = on,
        onChecked = { v -> if (v) enable() else { denied = false; ConnectionManager.setConnectionMode(ctx, "network") } },
    )

    if (denied && !on) {
        val sp = DockTheme.spacing
        Column(Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m)) {
            Text(
                t("Nearby devices permission was denied, so Bluetooth fallback stays off. Allow it in the app's settings to use it."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
            DockButton(
                t("Open app settings"),
                {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                },
                kind = ButtonKind.TONAL, modifier = Modifier.padding(top = sp.s),
            )
        }
    }

    if (on) {
        DockDivider()
        ToggleRow(
            icon = Icons.Outlined.SwapHoriz, tone = Tone.SECONDARY,
            title = t("Switch back automatically"),
            subtitle = t("Return to Wi-Fi by itself once it is stable again. Off keeps you on Bluetooth until you switch."),
            checked = autoReturn,
            onChecked = { ConnectionManager.setBleAutoReturn(ctx, it) },
        )
        if (ui.wifiAvailable || ui.bleAvailable) {
            val sp = DockTheme.spacing
            Column(Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m)) {
                if (ui.wifiAvailable) {
                    Text(
                        t("Wi-Fi available, switch now"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = sp.s),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                    if (ui.wifiAvailable) {
                        DockButton(
                            t("Use Wi-Fi now"),
                            { ConnectionManager.preferTransport(FallbackController.Prefer.WIFI) },
                            kind = ButtonKind.TONAL,
                        )
                    }
                    if (ui.bleAvailable) {
                        DockButton(
                            t("Use Bluetooth now"),
                            { ConnectionManager.preferTransport(FallbackController.Prefer.BLE) },
                            kind = ButtonKind.TONAL,
                        )
                    }
                }
            }
        }
    }

    if (explain) {
        DockSheet(onDismiss = { explain = false }, title = t("Allow Nearby devices?")) {
            Text(
                t("To keep notifications, clipboard text, calls and media working when Wi-Fi isn't available, Android needs your OK for Nearby devices. DroidDock only advertises to your paired Mac, never scans, and never uses your location. Files, photos and screen mirroring still need Wi-Fi."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = DockTheme.spacing.m),
            )
            DockButton(
                t("Continue"),
                { explain = false; launcher.launch(BleStack.permissionsToRequest()) },
                fullWidth = true,
            )
            DockButton(
                t("Not now"), { explain = false },
                kind = ButtonKind.TONAL, fullWidth = true, modifier = Modifier.padding(top = DockTheme.spacing.s),
            )
        }
    }
}

private fun statusLine(s: BleStack.Status, tr: Transport?): String = when {
    tr == Transport.BLE -> t("Using Bluetooth")
    s == BleStack.Status.ADVERTISING -> t("Waiting for your Mac over Bluetooth")
    s == BleStack.Status.RADIO_OFF -> t("Bluetooth is off on this phone")
    s == BleStack.Status.BUSY -> t("Bluetooth is busy, retrying")
    s == BleStack.Status.LOCKED -> t("Paused for 5 minutes after failed attempts")
    s == BleStack.Status.NO_PERMISSION -> t("Needs the Nearby devices permission")
    tr == Transport.WIFI -> t("Using Wi-Fi, Bluetooth is idle")
    else -> t("Bluetooth is idle until Wi-Fi drops")
}
