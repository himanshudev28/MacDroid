package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.mirror.ControlScreen
import com.droiddock.app.ui.mirror.MirrorTab
import com.droiddock.app.ui.remote.MacRemoteTab
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

/** Control: Mirror tab and Mac Remote across styles, light and dark. */
class SnapshotCTRLTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private fun shoot(screen: String, body: @Composable () -> Unit) {
        for (style in listOf(UiStyle.LIQUID_GLASS, UiStyle.MATERIAL_YOU, UiStyle.MINIMAL_MONO)) {
            for (dark in listOf(false, true)) {
                paparazzi.snapshot(name = "ctrl_${screen}_${style.name.lowercase()}_${if (dark) "dark" else "light"}") {
                    DroidDockTheme(
                        appearance = Appearance(style = style, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                        dynamicColor = false,
                    ) { Box(Modifier.fillMaxSize().dockPageBackground()) { body() } }
                }
            }
        }
    }

    @Test fun mirror() = shoot("mirror") {
        ControlScreen(remoteAvailable = true, macAppsAvailable = true, connected = true) {
            MirrorTab(connected = true, overlayOk = true, autoMirror = true, onEnableOverlay = {}, onToggleAutoMirror = {})
        }
    }

    @Test fun mirror_enable() = shoot("mirrorEnable") {
        ControlScreen(remoteAvailable = false, macAppsAvailable = false, connected = true) {
            MirrorTab(connected = true, overlayOk = false, autoMirror = false, onEnableOverlay = {}, onToggleAutoMirror = {})
        }
    }

    @Test fun remote() = shoot("remote") {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { MacRemoteTab(connected = true) }
    }

    @Test fun remoteHold() = shoot("remote_hold") {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { MacRemoteTab(connected = true, previewDrag = true) }
    }

    @Test fun remoteHoldLocked() = shoot("remote_hold_locked") {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { MacRemoteTab(connected = true, previewDrag = true, previewLocked = true) }
    }
}
