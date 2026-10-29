package com.droiddock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droiddock.app.ConnectionManager
import com.droiddock.app.link.Transport
import com.droiddock.app.t

/** True while the only link to the Mac is Bluetooth. Files, mirroring and Mac apps need Wi-Fi. */
@Composable
internal fun rememberBleOnly(): Boolean {
    val tr by ConnectionManager.transport.collectAsStateWithLifecycle()
    return tr == Transport.BLE
}

/** The designed "Needs Wi-Fi" state (BLE-DESIGN §2): never a blank pane or a button that does nothing. */
@Composable
internal fun NeedsWifiState(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.Center,
    ) {
        DockEmptyState(
            icon = Icons.Outlined.WifiOff,
            title = t("Needs Wi-Fi"),
            body = t("Your Mac is connected over Bluetooth. This comes back when Wi-Fi does."),
        )
    }
}
