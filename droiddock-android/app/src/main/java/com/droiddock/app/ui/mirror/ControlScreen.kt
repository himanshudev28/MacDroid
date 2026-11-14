package com.droiddock.app.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.droiddock.app.ui.components.DockSegmented
import com.droiddock.app.ui.macfiles.MacAppsTab
import com.droiddock.app.ui.remote.MacRemoteTab
import com.droiddock.app.ui.theme.DockTheme

/** Control: the Mac's view of this phone, and this phone's control of the Mac. */
@Composable
internal fun ControlScreen(
    remoteAvailable: Boolean,
    macAppsAvailable: Boolean,
    connected: Boolean,
    mirror: @Composable () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("mirror") }
    // A capability can vanish mid-session (the Mac's switch is turned off, or we
    // reconnect to an older build). Bounce off a segment that no longer exists
    // rather than showing an empty pane.
    if (mode == "remote" && !remoteAvailable) mode = "mirror"
    if (mode == "macapps" && !macAppsAvailable) mode = "mirror"

    val sp = DockTheme.spacing
    // The status bar is owned here; the shell owns the bottom inset.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        if (remoteAvailable || macAppsAvailable) {
            Spacer(Modifier.height(sp.s))
            DockSegmented(
                options = buildList {
                    add("mirror" to "Mirror")
                    if (remoteAvailable) add("remote" to "Mac Remote")
                    if (macAppsAvailable) add("macapps" to "Mac Apps")
                },
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.padding(horizontal = sp.gutter),
            )
        }
        val bleOnly = com.droiddock.app.ui.components.rememberBleOnly()
        val motion = DockTheme.motion
        val pane = when {
            mode == "remote" -> "remote"
            bleOnly -> "wifi"
            mode == "macapps" -> "macapps"
            else -> "mirror"
        }
        // Panes dock in with a fade + short rise from the segment's side; snaps under Remove animations.
        AnimatedContent(
            targetState = pane,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(motion.fade()) + slideInVertically(motion.spatial()) { it / 24 }) togetherWith fadeOut(motion.exit())
            },
            label = "control-pane",
        ) { p ->
            when (p) {
                "remote" -> MacRemoteTab(connected = connected)
                "wifi" -> com.droiddock.app.ui.components.NeedsWifiState()
                "macapps" -> MacAppsTab(connected = connected)
                else -> mirror()
            }
        }
    }
}
