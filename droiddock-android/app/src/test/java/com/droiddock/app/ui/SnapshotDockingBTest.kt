package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.common.PauseDialog
import com.droiddock.app.ui.guide.FeatureGuideScreen
import com.droiddock.app.ui.mirror.MirrorLink
import com.droiddock.app.ui.mirror.MirrorTab
import com.droiddock.app.ui.remote.MacRemoteTab
import com.droiddock.app.ui.settings.SettingsContent
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.LocalDockMotion
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

/** Docking B screens: Mirror, Mac Remote, Settings, Guide, Pause dialog. */
class SnapshotDockingBTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    // Liquid Glass may not load under the JDK 19 test runtime; the snapshot tries it, so run both.
    private val styles = listOf(UiStyle.LIQUID_GLASS, UiStyle.MINIMAL_MONO)

    private fun shoot(name: String, content: @Composable () -> Unit) {
        for (s in styles) for (dark in listOf(false, true)) {
            val a = Appearance(style = s, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            paparazzi.snapshot(name = "DKB_${name}_${s.name.lowercase()}_${if (dark) "dark" else "light"}") {
                DroidDockTheme(a, dynamicColor = false) {
                    CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                        Box(Modifier.fillMaxSize().dockPageBackground()) { content() }
                    }
                }
            }
        }
    }

    @Test fun mirror_live() = shoot("mirror_live") {
        MirrorTab(connected = true, overlayOk = true, autoMirror = true, onEnableOverlay = {}, onToggleAutoMirror = {}, previewLink = MirrorLink.LIVE)
    }
    @Test fun mirror_offline() = shoot("mirror_offline") {
        MirrorTab(connected = false, overlayOk = false, autoMirror = false, onEnableOverlay = {}, onToggleAutoMirror = {}, previewLink = MirrorLink.OFFLINE)
    }
    @Test fun remote() = shoot("remote") { MacRemoteTab(connected = true, previewDrag = true) }
    @Test fun remote_locked() = shoot("remote_locked") { MacRemoteTab(connected = true, previewDrag = true, previewLocked = true) }
    @Test fun guide() = shoot("guide") { FeatureGuideScreen(onBack = {}) }
    @Test fun pause() = shoot("pause") { PauseDialog(onDismiss = {}, onPause = {}) }
    @Test fun settings() {
        for (s in styles) {
            val a = Appearance(style = s, themeMode = ThemeMode.LIGHT)
            paparazzi.snapshot(name = "DKB_settings_${s.name.lowercase()}_light") {
                DroidDockTheme(a, dynamicColor = false) {
                    CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                        Box(Modifier.fillMaxSize().dockPageBackground()) {
                            SettingsContent(
                                appearance = a, onAppearance = {},
                                clipA11y = true, clipAuto = true, notifAccess = true, phonePerms = false, allFiles = false,
                                deviceAdmin = false, defaultTab = "dynamic", onDefaultTab = {}, crashNotify = true,
                                onCrashNotify = {}, onOpenConnect = {}, expandNet = false, onExpandNet = {},
                                onEnableClip = {}, onToggleClipAuto = {}, onEnableNotif = {}, onGrantPhonePerms = {},
                                onGrantFiles = {}, onBattery = {}, onOpenGuide = {}, knownUpdate = null,
                            )
                        }
                    }
                }
            }
        }
    }
}
