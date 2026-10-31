package com.droiddock.app.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.onboarding.OnboardingFlow
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.LocalDockMotion
import com.droiddock.app.ui.theme.UiStyle
import org.junit.Rule
import org.junit.Test

/** Launchers need a registry; layoutlib has no Activity to provide one. */
private object NoResults : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
    }
}

/** First-run onboarding, each page at its final frame (motion snapped). */
class SnapshotONBTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private val styles = listOf("glass" to UiStyle.LIQUID_GLASS, "you" to UiStyle.MATERIAL_YOU, "mono" to UiStyle.MINIMAL_MONO)

    private fun shoot(page: String, step: Int) {
        for ((sn, style) in styles) for (dark in listOf(false, true)) {
            paparazzi.snapshot(name = "ONB_${page}_${sn}_${if (dark) "dark" else "light"}") {
                DroidDockTheme(
                    Appearance(style = style, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                    dynamicColor = false,
                ) {
                    CompositionLocalProvider(
                        LocalDockMotion provides LocalDockMotion.current.snapped(),
                        LocalActivityResultRegistryOwner provides NoResults,
                    ) {
                        OnboardingFlow(onFinish = {}, initialStep = step)
                    }
                }
            }
        }
    }

    @Test fun welcome() = shoot("welcome", 0)
    @Test fun permissions() = shoot("permissions", 1)
    @Test fun done() = shoot("done", 2)
}
