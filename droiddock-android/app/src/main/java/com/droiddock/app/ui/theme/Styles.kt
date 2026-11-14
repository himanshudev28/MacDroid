package com.droiddock.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Per-style shape, type and spacing tokens. Colour lives in Colors.kt, motion
 * in Motion.kt. Fonts are system only (Roboto / the OEM sans, the platform
 * monospace): nothing bundled, so no licence to track and no APK cost.
 */

private val M3 = Typography()

private fun TextStyle.w(weight: FontWeight, tracking: Float? = null) =
    copy(fontWeight = weight, letterSpacing = tracking?.sp ?: letterSpacing)

internal fun typographyFor(style: UiStyle): Typography = when (style) {
    UiStyle.MATERIAL_YOU -> M3.copy(
        // M3 Expressive "emphasized" headlines: same scale, heavier weight.
        displaySmall = M3.displaySmall.w(FontWeight.Medium),
        headlineLarge = M3.headlineLarge.w(FontWeight.SemiBold),
        headlineMedium = M3.headlineMedium.w(FontWeight.SemiBold),
        headlineSmall = M3.headlineSmall.w(FontWeight.SemiBold),
        titleLarge = M3.titleLarge.w(FontWeight.SemiBold),
        titleMedium = M3.titleMedium.w(FontWeight.SemiBold),
        labelLarge = M3.labelLarge.w(FontWeight.SemiBold),
    )
    UiStyle.LIQUID_GLASS -> M3.copy(
        // Tighter tracking and medium weights: calm, optical, not shouty.
        displaySmall = M3.displaySmall.w(FontWeight.Medium, -0.6f),
        // Large page titles in the iOS manner: bigger, bolder, tighter.
        headlineLarge = M3.headlineLarge.w(FontWeight.Bold, -0.9f).copy(fontSize = 34.sp, lineHeight = 40.sp),
        headlineMedium = M3.headlineMedium.w(FontWeight.SemiBold, -0.4f),
        headlineSmall = M3.headlineSmall.w(FontWeight.SemiBold, -0.3f),
        titleLarge = M3.titleLarge.w(FontWeight.SemiBold, -0.2f),
        titleMedium = M3.titleMedium.w(FontWeight.SemiBold, -0.1f),
        labelLarge = M3.labelLarge.w(FontWeight.SemiBold, 0f),
    )
    UiStyle.MINIMAL_MONO -> {
        val sans = FontFamily.SansSerif
        val mono = FontFamily.Monospace
        fun TextStyle.sans(weight: FontWeight, tracking: Float? = null) =
            copy(fontFamily = sans, fontWeight = weight, letterSpacing = tracking?.sp ?: letterSpacing)
        fun TextStyle.mono(size: Int, tracking: Float) =
            copy(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = size.sp, letterSpacing = tracking.sp)
        M3.copy(
            displayLarge = M3.displayLarge.sans(FontWeight.Light, -1.5f),
            displayMedium = M3.displayMedium.sans(FontWeight.Light, -1f),
            displaySmall = M3.displaySmall.sans(FontWeight.Light, -0.5f),
            headlineLarge = M3.headlineLarge.sans(FontWeight.Normal, -0.5f),
            headlineMedium = M3.headlineMedium.sans(FontWeight.Normal, -0.4f),
            headlineSmall = M3.headlineSmall.sans(FontWeight.Normal, -0.2f),
            titleLarge = M3.titleLarge.sans(FontWeight.Medium, -0.2f),
            titleMedium = M3.titleMedium.sans(FontWeight.Medium),
            titleSmall = M3.titleSmall.sans(FontWeight.Medium),
            bodyLarge = M3.bodyLarge.sans(FontWeight.Normal).copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = M3.bodyMedium.sans(FontWeight.Normal),
            bodySmall = M3.bodySmall.sans(FontWeight.Normal),
            labelLarge = M3.labelLarge.mono(13, 0.6f),
            labelMedium = M3.labelMedium.mono(11, 0.8f),
            labelSmall = M3.labelSmall.mono(10, 0.8f),
        )
    }
    UiStyle.REACH -> M3.copy(
        displaySmall = M3.displaySmall.w(FontWeight.Normal),
        headlineLarge = M3.headlineLarge.w(FontWeight.Normal),
        headlineMedium = M3.headlineMedium.w(FontWeight.Normal),
        headlineSmall = M3.headlineSmall.w(FontWeight.Medium),
        titleLarge = M3.titleLarge.w(FontWeight.Medium),
        titleMedium = M3.titleMedium.w(FontWeight.Medium),
        bodyLarge = M3.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        labelLarge = M3.labelLarge.w(FontWeight.SemiBold),
    )
}

internal fun dockTypeFor(style: UiStyle, t: Typography): DockType {
    val tnum = "tnum"
    return when (style) {
        UiStyle.MINIMAL_MONO -> DockType(
            heroTitle = t.headlineMedium,
            numeral = t.displayMedium.copy(fontFeatureSettings = tnum, fontWeight = FontWeight.Thin),
            overline = t.labelMedium,
            upperCaseLabels = true,
            largeTitle = t.headlineLarge,
        )
        // SHELF: big, light titles up top (they collapse away); calm medium weights below.
        UiStyle.REACH -> DockType(
            heroTitle = t.headlineMedium.copy(fontWeight = FontWeight.Medium),
            numeral = t.displayMedium.copy(fontFeatureSettings = tnum, fontWeight = FontWeight.Light),
            overline = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            upperCaseLabels = false,
            largeTitle = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp),
        )
        UiStyle.LIQUID_GLASS -> DockType(
            heroTitle = t.headlineSmall.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            numeral = t.displaySmall.copy(fontFeatureSettings = tnum, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
            overline = t.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.02.em),
            upperCaseLabels = false,
            largeTitle = t.headlineLarge,
        )
        // BLOCKS: heavy, compact headlines that sit on the colour slabs like labels on a block.
        UiStyle.MATERIAL_YOU -> DockType(
            heroTitle = t.headlineSmall.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
            numeral = t.displaySmall.copy(fontFeatureSettings = tnum, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
            overline = t.labelLarge,
            upperCaseLabels = false,
            largeTitle = t.headlineLarge,
        )
    }
}

/** Material You "Blocks": three generous corners and one tight "docked" corner at the bottom-start (RTL-aware). */
private fun docked(big: Int, tight: Int) = RoundedCornerShape(
    topStart = big.dp, topEnd = big.dp, bottomEnd = big.dp, bottomStart = tight.dp,
)

internal fun shapesFor(style: UiStyle): Pair<DockShapes, Shapes> = when (style) {
    // BLOCKS: colour-blocked slabs that each "dock" on one tight corner.
    UiStyle.MATERIAL_YOU -> DockShapes(
        hero = docked(32, 10), card = docked(28, 8), tile = docked(28, 8),
        control = CircleShape, chip = RoundedCornerShape(12.dp), sheet = RoundedCornerShape(28.dp),
        badge = RoundedCornerShape(16.dp), deviceBadge = CookieShape(), field = docked(18, 6),
        cardRadius = 28.dp,
    ) to Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp), large = docked(28, 8), extraLarge = docked(32, 10),
    )
    // DEPTH: soft continuous lenses.
    UiStyle.LIQUID_GLASS -> DockShapes(
        hero = RoundedCornerShape(32.dp), card = RoundedCornerShape(28.dp), tile = RoundedCornerShape(28.dp),
        control = CircleShape, chip = CircleShape, sheet = RoundedCornerShape(34.dp),
        badge = CircleShape, deviceBadge = RoundedCornerShape(22.dp), field = RoundedCornerShape(20.dp),
        cardRadius = 28.dp,
    ) to Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp),
    )
    // INSTRUMENT: machined panels, near-square, corner ticks drawn by DockCard.
    UiStyle.MINIMAL_MONO -> DockShapes(
        hero = RoundedCornerShape(3.dp), card = RoundedCornerShape(2.dp), tile = RoundedCornerShape(2.dp),
        control = RoundedCornerShape(2.dp), chip = RoundedCornerShape(2.dp), sheet = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
        badge = RoundedCornerShape(2.dp), deviceBadge = RoundedCornerShape(2.dp), field = RoundedCornerShape(2.dp),
        pill = RoundedCornerShape(2.dp), cardRadius = 2.dp,
    ) to Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(2.dp),
        medium = RoundedCornerShape(2.dp), large = RoundedCornerShape(3.dp), extraLarge = RoundedCornerShape(4.dp),
    )
    // SHELF: soft pebbles that stack up from the bottom.
    UiStyle.REACH -> DockShapes(
        hero = RoundedCornerShape(36.dp), card = RoundedCornerShape(32.dp), tile = RoundedCornerShape(32.dp),
        control = CircleShape, chip = CircleShape, sheet = RoundedCornerShape(32.dp),
        badge = CircleShape, deviceBadge = CircleShape, field = RoundedCornerShape(24.dp),
        cardRadius = 32.dp,
    ) to Shapes(
        extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp), extraLarge = RoundedCornerShape(36.dp),
    )
}

internal fun spacingFor(style: UiStyle): DockSpacing = when (style) {
    UiStyle.MATERIAL_YOU -> DockSpacing(gutter = 16.dp, listGap = 2.dp, sectionGap = 24.dp, tileHeight = 112.dp, navHeight = 80.dp)
    UiStyle.LIQUID_GLASS -> DockSpacing(gutter = 16.dp, cardPadding = 18.dp, listGap = 2.dp, sectionGap = 22.dp, tileHeight = 108.dp, navHeight = 64.dp)
    UiStyle.MINIMAL_MONO -> DockSpacing(gutter = 20.dp, cardPadding = 16.dp, listGap = 0.dp, sectionGap = 28.dp, tileHeight = 104.dp, navHeight = 64.dp)
    UiStyle.REACH -> DockSpacing(gutter = 16.dp, cardPadding = 20.dp, listGap = 2.dp, sectionGap = 20.dp, tileHeight = 120.dp, navHeight = 72.dp)
}

internal fun surfacesFor(style: UiStyle, colors: DockColors, amoled: Boolean): DockSurfaces =
    if (style == UiStyle.MINIMAL_MONO || amoled) {
        DockSurfaces(SurfaceMode.HAIRLINE, BorderStroke(1.dp, colors.hairline))
    } else if (style == UiStyle.LIQUID_GLASS) {
        // Content cards keep a faint rim so they sit on the colour field.
        DockSurfaces(SurfaceMode.TONAL, BorderStroke(0.5.dp, colors.hairline))
    } else {
        DockSurfaces(SurfaceMode.TONAL, null)
    }
