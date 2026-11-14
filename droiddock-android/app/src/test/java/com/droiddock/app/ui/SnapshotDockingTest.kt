package com.droiddock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockHero
import com.droiddock.app.ui.components.DockNavBar
import com.droiddock.app.ui.components.DockNavItem
import com.droiddock.app.ui.components.DockNumeral
import com.droiddock.app.ui.components.DockProgress
import com.droiddock.app.ui.components.DockTether
import com.droiddock.app.ui.components.HeroState
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.components.DockChip
import com.droiddock.app.ui.components.DockCircleToggle
import com.droiddock.app.ui.components.DockNowPlaying
import com.droiddock.app.ui.components.DockSegmented
import com.droiddock.app.ui.components.DockSwitch
import androidx.compose.material.icons.rounded.Lock
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.LocalDockMotion
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.junit.Rule
import org.junit.Test

/**
 * "Docking" design-system gallery: the signature components in every style, light and dark, so the four styles can be
 * compared side by side (they must not read as copies). Motion is snapped (entrances render at rest).
 */
class SnapshotDockingTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    @Composable
    private fun Gallery(style: UiStyle, mode: ThemeMode) {
        DroidDockTheme(appearance = Appearance(style = style, themeMode = mode), dynamicColor = false) {
            CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                val sp = DockTheme.spacing
                Box(Modifier.fillMaxSize().dockPageBackground()) {
                    Column(
                        Modifier.fillMaxWidth().padding(sp.gutter),
                        verticalArrangement = Arrangement.spacedBy(sp.l),
                    ) {
                        DockHero(
                            title = "MacBook Pro", status = "Connected · Wi-Fi · 12 ms", state = HeroState.CONNECTED,
                            battery = 87, primaryLabel = "Send file", onPrimary = {}, secondaryLabel = "Lock", onSecondary = {},
                        )
                        DockCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Transfers", style = DockTheme.type.overline)
                                    DockNumeral(42, unit = "MB/s")
                                }
                                DockNumeral(null, unit = "%")
                            }
                            DockProgress(0.62f, Modifier.padding(top = sp.m))
                        }
                        DockTether(TetherState.LINKED, progress = 0.4f)
                        DockTether(TetherState.CONNECTING)
                        DockTether(TetherState.OFFLINE)
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                            DockButton("Primary", {}, kind = ButtonKind.PRIMARY)
                            DockButton("Tonal", {}, kind = ButtonKind.TONAL)
                            DockButton("Ghost", {}, kind = ButtonKind.GHOST)
                        }
                    }
                    DockNavBar(
                        items = listOf(
                            DockNavItem("home", "Home", Icons.Rounded.Home),
                            DockNavItem("files", "Files", Icons.Rounded.Folder),
                            DockNavItem("ctrl", "Control", Icons.Rounded.TouchApp),
                            DockNavItem("clip", "Clipboard", Icons.Rounded.ContentPaste),
                            DockNavItem("set", "Settings", Icons.Rounded.Settings),
                        ),
                        selected = "files", onSelect = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Composable
    private fun Controls(style: UiStyle, mode: ThemeMode) {
        DroidDockTheme(appearance = Appearance(style = style, themeMode = mode), dynamicColor = false) {
            CompositionLocalProvider(LocalDockMotion provides LocalDockMotion.current.snapped()) {
                val sp = DockTheme.spacing
                Column(
                    Modifier.fillMaxSize().dockPageBackground().padding(sp.gutter),
                    verticalArrangement = Arrangement.spacedBy(sp.l),
                ) {
                    DockSegmented(listOf("a" to "Photos", "b" to "Files", "c" to "Apps"), selected = "b", onSelect = {})
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        DockChip("Recent", {}, selected = true)
                        DockChip("Large", {})
                        DockChip("Shared", {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.l), verticalAlignment = Alignment.CenterVertically) {
                        DockSwitch(true, {})
                        DockSwitch(false, {})
                        DockCircleToggle(Icons.Rounded.Lock, "Lock", checked = true, onToggle = {})
                        DockCircleToggle(Icons.Rounded.Lock, "Lock", checked = false, onToggle = {})
                    }
                    DockNowPlaying("Midnight City", "M83 · Spotify", playing = true, onPrevious = {}, onPlayPause = {}, onNext = {}, progress = 0.35f)
                }
            }
        }
    }

    @Test fun controlsGlass() = paparazzi.snapshot { Controls(UiStyle.LIQUID_GLASS, ThemeMode.LIGHT) }
    @Test fun controlsMaterial() = paparazzi.snapshot { Controls(UiStyle.MATERIAL_YOU, ThemeMode.LIGHT) }
    @Test fun controlsMono() = paparazzi.snapshot { Controls(UiStyle.MINIMAL_MONO, ThemeMode.DARK) }
    @Test fun controlsReach() = paparazzi.snapshot { Controls(UiStyle.REACH, ThemeMode.LIGHT) }

    @Test fun glassLight() = paparazzi.snapshot { Gallery(UiStyle.LIQUID_GLASS, ThemeMode.LIGHT) }
    @Test fun glassDark() = paparazzi.snapshot { Gallery(UiStyle.LIQUID_GLASS, ThemeMode.DARK) }
    @Test fun materialLight() = paparazzi.snapshot { Gallery(UiStyle.MATERIAL_YOU, ThemeMode.LIGHT) }
    @Test fun materialDark() = paparazzi.snapshot { Gallery(UiStyle.MATERIAL_YOU, ThemeMode.DARK) }
    @Test fun monoLight() = paparazzi.snapshot { Gallery(UiStyle.MINIMAL_MONO, ThemeMode.LIGHT) }
    @Test fun monoDark() = paparazzi.snapshot { Gallery(UiStyle.MINIMAL_MONO, ThemeMode.DARK) }
    @Test fun reachLight() = paparazzi.snapshot { Gallery(UiStyle.REACH, ThemeMode.LIGHT) }
    @Test fun reachDark() = paparazzi.snapshot { Gallery(UiStyle.REACH, ThemeMode.DARK) }
}
