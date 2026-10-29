package com.droiddock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.components.AnimatedCounter
import com.droiddock.app.ui.components.DockCircleToggle
import com.droiddock.app.ui.components.DockHero
import com.droiddock.app.ui.components.DockNowPlaying
import com.droiddock.app.ui.components.DockProgress
import com.droiddock.app.ui.components.DockSegmented
import com.droiddock.app.ui.components.DockSwitch
import com.droiddock.app.ui.components.HeroState
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.MotionPersonality
import com.droiddock.app.ui.theme.UiStyle
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.junit.Rule
import org.junit.Test

/**
 * Mid-animation frames. Each component starts in its "from" state and flips on the
 * first frame; Paparazzi's gif() advances the frame clock, so the frames show the
 * spring in flight. Output: build/reports/paparazzi (one gif per personality) (extract frames with
 * `ffmpeg -i x.gif f%02d.png`). Also asserts nothing crashes for every personality.
 */
class SnapshotMotionTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 100.0,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    @Composable
    private fun Stage(personality: MotionPersonality) {
        var on by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { on = true }
        DroidDockTheme(
            appearance = Appearance(style = UiStyle.MATERIAL_YOU, themeMode = ThemeMode.LIGHT, motion = personality),
            dynamicColor = false,
        ) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DockHero(
                    title = "MacBook Pro", status = if (on) "Connected" else "Connecting",
                    state = if (on) HeroState.CONNECTED else HeroState.CONNECTING,
                    battery = if (on) 101 - 3 else 98, detail = if (on) "Same Wi-Fi" else null,
                    primaryLabel = if (on) "Disconnect" else "Cancel", onPrimary = {},
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    DockSwitch(checked = on, onCheckedChange = {})
                    DockCircleToggle(Icons.Rounded.LockOpen, "Lock", checked = on, onToggle = {}, checkedIcon = Icons.Rounded.Lock)
                    AnimatedCounter(if (on) 100 else 99, style = DockTheme.typography.displaySmall)
                }
                DockSegmented(listOf("a" to "Calm", "b" to "Standard", "c" to "Bouncy"), if (on) "c" else "a", {})
                DockProgress(if (on) 0.8f else 0.1f)
                DockNowPlaying(
                    title = if (on) "Track B" else "Track A", subtitle = "Artist", playing = on,
                    onPrevious = {}, onPlayPause = {}, onNext = {}, progress = if (on) 0.7f else 0.2f,
                )
            }
        }
    }

    private fun frames(name: String, p: MotionPersonality) {
        val owner = Owners()
        val view = ComposeView(paparazzi.context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            setContent { Stage(p) }
        }
        owner.resume()
        paparazzi.gif(view, name = name, start = 0L, end = 600L, fps = 10)
    }

    @Test fun motion_calm() = frames("motion_calm", MotionPersonality.CALM)
    @Test fun motion_standard() = frames("motion_standard", MotionPersonality.STANDARD)
    @Test fun motion_bouncy() = frames("motion_bouncy", MotionPersonality.BOUNCY)

    /** One object that is every ViewTree owner Compose looks for (Paparazzi's own are internal). */
    private class Owners : LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        init { controller.performRestore(null) }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    }
}
