package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.DiscoveredMac
import com.droiddock.app.ThemeMode
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockSection
import com.droiddock.app.ui.settings.AppearanceRows
import com.droiddock.app.ui.components.DockSheetContent
import com.droiddock.app.ui.guide.FeatureGuideScreen
import com.droiddock.app.ui.pairing.ConnectContent
import com.droiddock.app.ui.pairing.ManualPairForm
import com.droiddock.app.ui.settings.SettingsContent
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

/** Settings / Pairing / Guide snapshots in three styles, light and dark. */
class SnapshotSETTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private val styles = listOf(UiStyle.MATERIAL_YOU, UiStyle.MINIMAL_MONO, UiStyle.REACH) // Liquid Glass cannot load under the JDK 19 test runtime (Backdrop is class v65)

    private fun shoot(name: String, content: @Composable (Appearance) -> Unit) {
        for (s in styles) for (dark in listOf(false, true)) {
            val a = Appearance(style = s, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            paparazzi.snapshot(name = "SET_${name}_${s.name.lowercase()}_${if (dark) "dark" else "light"}") {
                DroidDockTheme(a, dynamicColor = false) {
                    Box(Modifier.fillMaxSize().dockPageBackground()) { content(a) }
                }
            }
        }
    }

    @Test fun settings() = shoot("settings") { a ->
        SettingsContent(
            appearance = a, onAppearance = {},
            clipA11y = true, clipAuto = true, notifAccess = true, phonePerms = false, allFiles = false,
            deviceAdmin = false, defaultTab = "dynamic", onDefaultTab = {}, crashNotify = true,
            onCrashNotify = {}, onOpenConnect = {}, expandNet = false, onExpandNet = {},
            onEnableClip = {}, onToggleClipAuto = {}, onEnableNotif = {}, onGrantPhonePerms = {},
            onGrantFiles = {}, onBattery = {}, onOpenGuide = {}, knownUpdate = null,
        )
    }

    /** Liquid Glass Appearance rows on their own (tint tier; Backdrop itself cannot load here). */
    @Test fun appearance_glass() {
        for (dark in listOf(false, true)) {
            val a = Appearance(style = UiStyle.LIQUID_GLASS, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            paparazzi.snapshot(name = "SET_appearance_liquid_glass_${if (dark) "dark" else "light"}") {
                DroidDockTheme(a, dynamicColor = false) {
                    Box(Modifier.fillMaxSize().dockPageBackground()) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                            DockSection(t("Appearance")) { AppearanceRows(a, {}) }
                        }
                    }
                }
            }
        }
    }

    @Test fun connect() = shoot("connect") {
        ConnectContent(
            connected = false, paired = true, macName = "MacBook Pro",
            discovered = listOf(DiscoveredMac("MacBook Pro", "192.168.1.20", 48484, "mdns")),
            scanning = true, onScan = {}, onManual = {}, onForget = {}, onBack = {},
            onConnect = { _, _ -> },
        )
    }

    @Test fun guide() = shoot("guide") { FeatureGuideScreen(onBack = {}) }

    @Test fun manual_sheet() = shoot("manualsheet") {
        DockSheetContent(title = t("Pair manually"), modifier = Modifier.padding(top = 80.dp)) {
            ManualPairForm(onCancel = {}, onPair = {})
        }
    }

    @Test fun language_sheet() = shoot("languagesheet") {
        DockSheetContent(title = t("Language"), modifier = Modifier.padding(top = 80.dp)) {
            DockCard(contentPadding = 0.dp) {
                DockListItem("System", icon = null, onClick = {})
                DockListItem("English", icon = null, onClick = {}, trailing = {
                    androidx.compose.material3.Icon(Icons.Outlined.Check, null)
                })
            }
        }
    }
}
