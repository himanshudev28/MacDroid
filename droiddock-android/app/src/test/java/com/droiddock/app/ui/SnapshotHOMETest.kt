package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ConnectionManager
import com.droiddock.app.KnownDevice
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.clipboard.ClipboardTab
import com.droiddock.app.ui.clipboard.HomeClipboardCard
import com.droiddock.app.ui.home.HomeTab
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

class SnapshotHOMETest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private val styles = listOf(
        "glass" to UiStyle.LIQUID_GLASS, "you" to UiStyle.MATERIAL_YOU, "mono" to UiStyle.MINIMAL_MONO, "reach" to UiStyle.REACH,
    )

    private fun shoot(screen: String, content: @Composable () -> Unit) {
        for ((sn, style) in styles) for (dark in listOf(false, true)) {
            paparazzi.snapshot(name = "HOME_${screen}_${sn}_${if (dark) "dark" else "light"}") {
                DroidDockTheme(
                    Appearance(style = style, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                    dynamicColor = false,
                ) {
                    // Final frame of the entrance stagger, not frame zero.
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.droiddock.app.ui.theme.LocalDockMotion provides com.droiddock.app.ui.theme.LocalDockMotion.current.snapped(),
                    ) { Box(Modifier.fillMaxSize().dockPageBackground()) { content() } }
                }
            }
        }
    }

    private val now = System.currentTimeMillis() - 5_000L
    private val devices = listOf(
        KnownDevice("Himanshu's MacBook Pro", listOf("192.168.1.20"), 48484, "tok-a", now),
        KnownDevice("Studio iMac", listOf("192.168.1.31"), 48484, "tok-b", now - 86_400_000L),
    )
    private val clips = listOf(
        ConnectionManager.ClipEntry("https://example.com/invoice/42", true, now),
        ConnectionManager.ClipEntry("Meet at 6pm", false, now - 60_000L),
    )

    @Test fun home_connected() = shoot("connected") {
        HomeTab(
            connected = true, isPaused = false, paired = true, macName = "Himanshu's MacBook Pro",
            macInfo = ConnectionManager.MacInfo("MacBook Pro", 78, false, true, 40),
            event = "Clipboard synced", pausedUntil = 0L, devices = devices, activeToken = "tok-a",
            disconnected = false, canControlMac = true,
            macMedia = ConnectionManager.MacMedia(true, "Nights", "Frank Ocean", "Spotify"),
            onPause = {}, onResume = {}, onQuickConnect = {}, onDisconnect = {},
            onPickDevice = {}, onGoToConnect = {}, onGoToFiles = {},
            clipboard = { HomeClipboardCard(clips, true, {}) },
        )
    }

    @Test fun home_unpaired() = shoot("unpaired") {
        HomeTab(
            connected = false, isPaused = false, paired = false, macName = null, macInfo = null,
            event = null, pausedUntil = 0L, devices = emptyList(), activeToken = null,
            disconnected = false, canControlMac = false, macMedia = null,
            onPause = {}, onResume = {}, onQuickConnect = {}, onDisconnect = {},
            onPickDevice = {}, onGoToConnect = {}, onGoToFiles = {},
        )
    }

    @Test fun clipboard() = shoot("clipboard") {
        ClipboardTab(connected = true, history = clips, keepHistory = true, onKeepHistory = {})
    }
}
