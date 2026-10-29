package com.droiddock.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic colours that Material's [androidx.compose.material3.ColorScheme] has
 * no role for. Everything else comes from `MaterialTheme.colorScheme`.
 *
 * Surfaces are named by *job*, not tone, because each style maps them to
 * different scheme roles (Reach puts white cards on a darker page; Material You
 * the reverse; Mono and AMOLED use hairlines instead of tonal steps).
 */
@Immutable
data class DockColors(
    /** Page behind everything. */
    val page: Color,
    /** Default card / grouped-list surface. */
    val card: Color,
    /** One step up: chips, key caps, quick tiles, dialogs. */
    val raised: Color,
    /** Recessed: progress tracks, text fields, info strips. */
    val inset: Color,
    /** Card outline. Transparent where the style separates by tone instead. */
    val hairline: Color,
    /** Divider between rows inside a card. */
    val divider: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** The one signal accent (status dot, glow). `primary` except in Mono. */
    val signal: Color,
    /** Contour highlight colour for the glow accent. */
    val glow: Color,
    /** Soft colour fields behind content, so glass has something to refract.
     *  Liquid Glass only; transparent elsewhere. */
    val backdropA: Color,
    val backdropB: Color,
    val backdropC: Color,
    val isDark: Boolean,
)

/** Shape scale per style. Concentric rule: inner = outer - padding. */
@Immutable
data class DockShapes(
    val hero: Shape,
    val card: Shape,
    val tile: Shape,
    /** Buttons and other tap targets. */
    val control: Shape,
    val chip: Shape,
    val sheet: Shape,
    /** Icon container (the old `IconBadge`). */
    val badge: Shape,
    /** The hero's device badge: a cookie in Material You, a square in Mono. */
    val deviceBadge: Shape,
    val field: Shape,
    /** Nav pill / segmented track. */
    val pill: Shape = CircleShape,
    /** Corner radius of [card] in dp, for grouped lists whose inner corners shrink. */
    val cardRadius: Dp,
)

/** 4-dp grid. Screens never write literal dp for layout spacing. */
@Immutable
data class DockSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Page side margin. */
    val gutter: Dp = 16.dp,
    /** Inside a card. */
    val cardPadding: Dp = 16.dp,
    /** Between stacked rows of a grouped list (2 dp segmented look; 0 for hairline). */
    val listGap: Dp = 2.dp,
    /** Between page sections. */
    val sectionGap: Dp = 24.dp,
    /** Hub / quick tiles. */
    val tileHeight: Dp = 112.dp,
    /** Minimum touch target. */
    val touch: Dp = 48.dp,
    /** Floating nav pill height (Liquid Glass) / bar height. */
    val navHeight: Dp = 64.dp,
)

/** Extra text styles on top of `MaterialTheme.typography`. */
@Immutable
data class DockType(
    /** Device name in the hero. */
    val heroTitle: TextStyle,
    /** Big confident numerals (battery %, counts). Tabular figures. */
    val numeral: TextStyle,
    /** Section header / overline. */
    val overline: TextStyle,
    /** Mono-only: whether overlines and chips are upper-cased by components. */
    val upperCaseLabels: Boolean,
    /** Reach's big collapsing page title. */
    val largeTitle: TextStyle,
)

enum class SurfaceMode {
    /** Hierarchy by tonal steps (Material You, Reach, Liquid Glass content). */
    TONAL,
    /** Hierarchy by 1 dp outlines on a flat page (Minimal Mono, AMOLED). */
    HAIRLINE,
}

@Immutable
data class DockSurfaces(
    val mode: SurfaceMode,
    val cardBorder: BorderStroke?,
)

val LocalDockStyle = staticCompositionLocalOf { UiStyle.LIQUID_GLASS }
val LocalAppearance = staticCompositionLocalOf { Appearance() }
val LocalDockColors = staticCompositionLocalOf<DockColors> { error("DockColors not provided: wrap in DroidDockTheme") }
val LocalDockShapes = staticCompositionLocalOf {
    DockShapes(
        hero = RoundedCornerShape(28.dp), card = RoundedCornerShape(24.dp), tile = RoundedCornerShape(24.dp),
        control = CircleShape, chip = RoundedCornerShape(12.dp), sheet = RoundedCornerShape(28.dp),
        badge = RoundedCornerShape(14.dp), deviceBadge = RoundedCornerShape(20.dp),
        field = RoundedCornerShape(16.dp), cardRadius = 24.dp,
    )
}
val LocalDockSpacing = staticCompositionLocalOf { DockSpacing() }
val LocalDockType = staticCompositionLocalOf<DockType> { error("DockType not provided: wrap in DroidDockTheme") }
val LocalDockSurfaces = staticCompositionLocalOf { DockSurfaces(SurfaceMode.TONAL, null) }
