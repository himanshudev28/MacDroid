package com.droiddock.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.shell.AppShell
import com.droiddock.app.ui.shell.Dest
import com.droiddock.app.ui.shell.HeroModel
import com.droiddock.app.ui.shell.HubHome
import com.droiddock.app.ui.shell.HubTileModel
import com.droiddock.app.ui.components.HeroState
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.NavLayout
import com.droiddock.app.ui.theme.UiStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/** Navigation shells: 4 tabs, Classic 5, Hub across styles and light/dark. */
class SnapshotNAVTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private fun shoot(name: String, style: UiStyle, layout: NavLayout, dark: Boolean, body: @Composable () -> Unit) {
        paparazzi.snapshot(name = "nav_${name}_${if (dark) "dark" else "light"}") {
            DroidDockTheme(
                appearance = Appearance(style = style, layout = layout, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                dynamicColor = false,
            ) { body() }
        }
    }

    @Composable
    private fun Page(dest: String) {
        val pad = LocalDockContentPadding.current
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)
                .padding(bottom = pad.calculateBottomPadding()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(dest, style = DockTheme.typography.headlineMedium, color = DockTheme.scheme.onSurface)
            Spacer(Modifier.height(12.dp))
            repeat(3) { DockCard(Modifier.padding(bottom = 12.dp)) { Text("Card $it on $dest", color = DockTheme.scheme.onSurface) } }
        }
    }

    private fun shell(layout: NavLayout, style: UiStyle, dark: Boolean, name: String) =
        shoot(name, style, layout, dark) {
            AppShell(layout = layout, current = Dest.HOME, onSelect = {}, settingsBadge = true,
                hubHome = { HubDemo() }) { Page(it) }
        }

    @Composable
    private fun HubDemo() = HubHome(
        appName = "DroidDock",
        hero = HeroModel("Himanshu's MacBook Pro", "Connected", HeroState.CONNECTED, battery = 82),
        tiles = listOf(
            HubTileModel("files", "Files", Icons.Rounded.Folder),
            HubTileModel("clipboard", "Clipboard", Icons.Rounded.ContentPaste),
            HubTileModel("control", "Control", Icons.Rounded.ScreenShare),
            HubTileModel("connect", "Devices", Icons.Rounded.Link),
        ),
        onTile = {}, onSettings = {}, settingsLabel = "Settings", settingsBadge = true,
        primaryLabel = "Pause", primaryIcon = Icons.Rounded.Pause, onPrimary = {},
    )

    @Test fun tabs4_glass_light() = shell(NavLayout.TABS_4, UiStyle.LIQUID_GLASS, false, "tabs4_glass")
    @Test fun tabs4_glass_dark() = shell(NavLayout.TABS_4, UiStyle.LIQUID_GLASS, true, "tabs4_glass")
    @Test fun tabs4_material_light() = shell(NavLayout.TABS_4, UiStyle.MATERIAL_YOU, false, "tabs4_material")
    @Test fun tabs4_material_dark() = shell(NavLayout.TABS_4, UiStyle.MATERIAL_YOU, true, "tabs4_material")
    @Test fun tabs4_mono_light() = shell(NavLayout.TABS_4, UiStyle.MINIMAL_MONO, false, "tabs4_mono")
    @Test fun tabs4_mono_dark() = shell(NavLayout.TABS_4, UiStyle.MINIMAL_MONO, true, "tabs4_mono")
    @Test fun tabs4_reach_light() = shell(NavLayout.TABS_4, UiStyle.REACH, false, "tabs4_reach")
    @Test fun tabs4_reach_dark() = shell(NavLayout.TABS_4, UiStyle.REACH, true, "tabs4_reach")
    @Test fun classic5_glass_dark() = shell(NavLayout.CLASSIC_5, UiStyle.LIQUID_GLASS, true, "classic5_glass")
    @Test fun classic5_material_light() = shell(NavLayout.CLASSIC_5, UiStyle.MATERIAL_YOU, false, "classic5_material")
    @Test fun hub_glass_light() = shell(NavLayout.HUB, UiStyle.LIQUID_GLASS, false, "hub_glass")
    @Test fun hub_glass_dark() = shell(NavLayout.HUB, UiStyle.LIQUID_GLASS, true, "hub_glass")
    @Test fun hub_material_light() = shell(NavLayout.HUB, UiStyle.MATERIAL_YOU, false, "hub_material")
    @Test fun hub_mono_dark() = shell(NavLayout.HUB, UiStyle.MINIMAL_MONO, true, "hub_mono")
}
