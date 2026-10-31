package com.droiddock.app.ui.macfiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockSegmented
import com.droiddock.app.ui.files.LocalSourceToggle

/** Files: this phone's storage, or the Mac's when it offers it. */
@Composable
internal fun FilesScreen(
    macFsAvailable: Boolean,
    connected: Boolean,
    phoneFiles: @Composable () -> Unit,
) {
    var source by rememberSaveable { mutableStateOf("phone") }
    // Fall back rather than showing an empty screen if the Mac stops offering
    // its filesystem while we're looking at it.
    if (source == "mac" && !macFsAvailable) source = "phone"

    // Each tab owns its scaffold (title, insets, scrolling); the switch is
    // handed down as a slot and rendered as the first row under the title.
    val toggle: (@Composable () -> Unit)? = if (macFsAvailable) remember {
        @Composable {
            DockSegmented(
                options = listOf("phone" to t("This phone"), "mac" to t("Mac")),
                selected = source,
                onSelect = { source = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else null

    if (com.droiddock.app.ui.components.rememberBleOnly()) {
        com.droiddock.app.ui.components.NeedsWifiState()
        return
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSourceToggle provides toggle) {
            if (source == "mac") MacFilesTab(connected = connected) else phoneFiles()
        }
    }
}
