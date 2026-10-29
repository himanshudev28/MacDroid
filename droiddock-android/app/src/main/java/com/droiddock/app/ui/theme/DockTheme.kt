package com.droiddock.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.droiddock.app.ThemeMode
import com.droiddock.app.ui.theme.glass.GlassSignals
import com.droiddock.app.ui.theme.glass.GlassSpec
import com.droiddock.app.ui.theme.glass.GlassTier
import com.droiddock.app.ui.theme.glass.LocalGlassSpec
import com.droiddock.app.ui.theme.glass.rememberGlassSignals
import com.droiddock.app.ui.theme.glass.resolveGlass

/**
 * The one theme entry point. Every argument is an Appearance setting; changing
 * any of them is a recomposition (no activity recreate).
 *
 * Provides `MaterialTheme` (colour scheme, typography, shapes) plus the Dock
 * tokens: [LocalDockColors], [LocalDockShapes], [LocalDockSpacing],
 * [LocalDockType], [LocalDockSurfaces], [LocalDockMotion], [LocalDockHaptics],
 * [LocalGlassSpec], [LocalDockStyle], [LocalAppearance]. Read them through [DockTheme].
 *
 * @param dynamicColor wallpaper colour on API 31+ (Material You / Liquid Glass /
 *   Reach). Tests pass false for deterministic pixels.
 * @param glassTierOverride pin the glass tier (tests; Paparazzi cannot render
 *   RenderEffect, so it gets TINT automatically in inspection mode anyway).
 */
@Composable
fun DroidDockTheme(
    style: UiStyle,
    layout: NavLayout,
    darkMode: ThemeMode,
    amoled: Boolean,
    motion: MotionPersonality,
    glow: Boolean,
    glassIntensity: GlassIntensity,
    reduceTransparency: Boolean,
    liveBlur: Boolean = true,
    haptics: Boolean = true,
    dynamicColor: Boolean = true,
    glassTierOverride: GlassTier? = null,
    content: @Composable () -> Unit,
) = DroidDockTheme(
    appearance = Appearance(
        style = style, layout = layout, themeMode = darkMode, amoled = amoled, motion = motion, glow = glow,
        glassIntensity = glassIntensity, reduceTransparency = reduceTransparency, liveBlur = liveBlur, haptics = haptics,
    ),
    dynamicColor = dynamicColor,
    glassTierOverride = glassTierOverride,
    content = content,
)

@Composable
fun DroidDockTheme(
    appearance: Appearance,
    dynamicColor: Boolean = true,
    glassTierOverride: GlassTier? = null,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val inspection = LocalInspectionMode.current
    val dark = when (appearance.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val style = appearance.style
    val amoled = appearance.effectiveAmoled && dark

    val scheme: ColorScheme = remember(style, dark, amoled, dynamicColor, inspection, appearance.colorSource) {
        val base = when (style) {
            UiStyle.MINIMAL_MONO -> monoScheme(dark, appearance.colorSource)
            else -> baseScheme(ctx, dark, appearance.colorSource, dynamicColor && !inspection)
        }
        if (amoled) base.amoled() else base
    }
    val colors = remember(scheme, style, dark, amoled) { dockColors(scheme, style, dark, amoled) }
    val typography: Typography = remember(style) { typographyFor(style) }
    val dockType = remember(style, typography) { dockTypeFor(style, typography) }
    val (dockShapes, m3Shapes) = remember(style) { shapesFor(style) }
    val spacing = remember(style) { spacingFor(style) }
    val surfaces = remember(style, colors, amoled) { surfacesFor(style, colors, amoled) }

    val signals: GlassSignals by rememberGlassSignals()
    val animationsOn = !signals.animationsOff
    // Battery saver / thermal throttling: Calm personality, no ambient loops.
    val lowPower = signals.powerSave || signals.thermalThrottled
    val motion = remember(style, appearance.motion, animationsOn, lowPower) {
        DockMotion.of(
            style, if (lowPower) MotionPersonality.CALM else appearance.motion,
            animationsOn, ambient = !lowPower,
        )
    }
    val glass: GlassSpec = remember(appearance, signals, dark, scheme, inspection, glassTierOverride) {
        resolveGlass(
            appearance, signals, dark, scheme.primary, scheme.surfaceContainerHigh, scheme.onSurface,
            inspection = inspection,
        ).let { if (glassTierOverride != null && appearance.style == UiStyle.LIQUID_GLASS) it.copy(tier = glassTierOverride) else it }
    }
    val hapticFeedback = LocalHapticFeedback.current
    val haptics = remember(hapticFeedback, appearance.haptics, style) {
        DockHaptics(hapticFeedback, appearance.haptics, style)
    }

    SystemBarsFollow(dark)

    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalDockStyle provides style,
        LocalDockColors provides colors,
        LocalDockShapes provides dockShapes,
        LocalDockSpacing provides spacing,
        LocalDockType provides dockType,
        LocalDockSurfaces provides surfaces,
        LocalDockMotion provides motion,
        LocalDockHaptics provides haptics,
        LocalGlassSpec provides glass,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = m3Shapes, content = content)
    }
}

/** Status/nav bar icon polarity follows the *resolved* theme, not the OS one. */
@Composable
private fun SystemBarsFollow(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Token accessors: `DockTheme.colors.success`, `DockTheme.spacing.gutter`, ... */
object DockTheme {
    val style: UiStyle @Composable get() = LocalDockStyle.current
    val appearance: Appearance @Composable get() = LocalAppearance.current
    val scheme: ColorScheme @Composable get() = MaterialTheme.colorScheme
    val colors: DockColors @Composable get() = LocalDockColors.current
    val shapes: DockShapes @Composable get() = LocalDockShapes.current
    val spacing: DockSpacing @Composable get() = LocalDockSpacing.current
    val type: DockType @Composable get() = LocalDockType.current
    val typography: Typography @Composable get() = MaterialTheme.typography
    val surfaces: DockSurfaces @Composable get() = LocalDockSurfaces.current
    val motion: DockMotion @Composable get() = LocalDockMotion.current
    val haptics: DockHaptics @Composable get() = LocalDockHaptics.current
    val glass: GlassSpec @Composable get() = LocalGlassSpec.current
}
