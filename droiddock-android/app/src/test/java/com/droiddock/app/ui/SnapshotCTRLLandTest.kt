package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.android.resources.ScreenOrientation
import com.droiddock.app.ThemeMode
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockSheetContent
import com.droiddock.app.ui.pairing.ManualPairForm
import com.droiddock.app.ui.remote.MacRemoteTab
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.LocalDockMotion
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

/**
 * Landscape layouts. Mac Remote: the trackpad must keep real height (it used to collapse to 0 dp).
 * Manual pairing: Pair and Cancel must stay on screen (the fields used to push them off).
 */
class SnapshotCTRLLandTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE),
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    @Test fun remote_landscape() {
        for (style in listOf(UiStyle.LIQUID_GLASS, UiStyle.MATERIAL_YOU, UiStyle.MINIMAL_MONO)) for (dark in listOf(false, true)) {
            paparazzi.snapshot(name = "ctrl_remote_land_${style.name.lowercase()}_${if (dark) "dark" else "light"}") {
                DroidDockTheme(
                    appearance = Appearance(style = style, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                    dynamicColor = false,
                ) {
                    CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                        Box(Modifier.fillMaxSize().dockPageBackground()) { MacRemoteTab(connected = true, previewDrag = true) }
                    }
                }
            }
        }
    }

    @Test fun manual_pair_landscape() {
        for (dark in listOf(false, true)) {
            paparazzi.snapshot(name = "pair_manual_land_${if (dark) "dark" else "light"}") {
                DroidDockTheme(
                    appearance = Appearance(style = UiStyle.LIQUID_GLASS, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                    dynamicColor = false,
                ) {
                    CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                        Box(Modifier.fillMaxSize().dockPageBackground()) {
                            DockSheetContent(title = t("Pair manually")) { ManualPairForm(onCancel = {}, onPair = {}) }
                        }
                    }
                }
            }
        }
    }
}
