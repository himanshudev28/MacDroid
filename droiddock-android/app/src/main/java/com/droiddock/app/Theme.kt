package com.droiddock.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DroidDock's colour layer.
 *
 * The app was written against a dozen top-level `val`s (`Ink`, `Fg`, `Dim`, …)
 * used directly at ~200 call sites. Rather than rewrite all of them, those names
 * survive in `MainActivity.kt` as `@Composable get()` accessors onto this
 * holder — so a screen keeps saying `color = Fg` and simply reads whichever
 * palette is in scope. The compiler enforces the one rule that comes with that:
 * a colour can only be read from a composable.
 *
 * Dark is byte-identical to the palette that shipped, so switching to it is a
 * no-op for anyone who never opens the new setting.
 */
data class DroidColors(
    /** Page background — behind every card. */
    val ink: Color,
    /** Card surface. */
    val surface1: Color,
    /** Raised surface: chips, quick-action buttons, dialogs. */
    val surface2: Color,
    /** Inset surface: progress tracks, key caps, info strips. */
    val surface3: Color,
    /** Brand accent. Used both as a fill and as text/icon tint, which is why
     *  light mode darkens it rather than reusing the dark-mode value. */
    val amber: Color,
    val amberDim: Color,
    /** Text/icon colour that sits *on* an amber fill. */
    val onAmber: Color,
    val ok: Color,
    /** Text/icon colour that sits *on* an ok-green fill. */
    val onOk: Color,
    val bad: Color,
    /** Primary text. */
    val fg: Color,
    /** Secondary text. */
    val dim: Color,
    /** Hairline dividers and outlines. */
    val line: Color,
    val purple: Color,
    val blue: Color,
    val orange: Color,
    /** True when this palette is a dark one — drives system-bar icon polarity. */
    val isDark: Boolean,
)

/** The palette DroidDock has always shipped. Unchanged, deliberately. */
val DarkPalette = DroidColors(
    ink      = Color(0xFF0D0D12),
    surface1 = Color(0xFF14141B),
    surface2 = Color(0xFF1C1C26),
    surface3 = Color(0xFF222230),
    amber    = Color(0xFFF5A623),
    amberDim = Color(0xFFCC7B0E),
    onAmber  = Color(0xFF1A0E00),
    ok       = Color(0xFF34C759),
    onOk     = Color(0xFF002210),
    bad      = Color(0xFFFF453A),
    fg       = Color(0xFFF0EFE9),
    dim      = Color(0xFF72728A),
    line     = Color(0xFF22222F),
    purple   = Color(0xFFAA84FF),
    blue     = Color(0xFF5B8FFF),
    orange   = Color(0xFFF0934C),
    isDark   = true,
)

/**
 * Light mode.
 *
 * Warm rather than grey — the neutrals carry a little orange so the amber
 * accent looks native to the surface instead of stamped onto it, which is what
 * the reference screenshots do with their browns.
 *
 * The accent is *darkened* here (`#9A5A05`) instead of reusing `#F5A623`. That
 * value is doing two jobs: as a button fill it only has to carry its own label,
 * but the app also uses it as 12sp text and as icon tint directly on a card,
 * where `#F5A623` on near-white is about 1.9:1 — invisible. The fill's own text
 * colour flips to white to match.
 *
 * Every foreground/background pair this app actually renders was measured
 * against its real surface; the weakest is 4.58:1 (`dim` on a `surface3` chip)
 * and the rest clear 4.9:1. That matters more here than in dark mode because
 * the UI leans on 11–12sp secondary text, which is exactly what a merely
 * "large-text AA" 3:1 value fails.
 */
val LightPalette = DroidColors(
    ink      = Color(0xFFFAF0E9),
    surface1 = Color(0xFFFFF9F5),
    surface2 = Color(0xFFF6EAE1),
    surface3 = Color(0xFFEBDCD1),
    amber    = Color(0xFF9A5A05),
    amberDim = Color(0xFF7A4500),
    onAmber  = Color(0xFFFFFFFF),
    ok       = Color(0xFF15702F),
    onOk     = Color(0xFFFFFFFF),
    bad      = Color(0xFFB3201D),
    fg       = Color(0xFF241C14),
    dim      = Color(0xFF6B6055),
    line     = Color(0xFFE6D5C8),
    purple   = Color(0xFF6B44C4),
    blue     = Color(0xFF2A61C4),
    orange   = Color(0xFFA85417),
    isDark   = false,
)

/** User's theme preference. Stored by [name], so don't reorder casually. */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    companion object {
        fun from(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * OLED variant of [DarkPalette]: true black page, near-black cards.
 *
 * Accents are left exactly as they are — they were already chosen against a
 * dark surface, and darkening the background only widens that gap.
 *
 * `dim` is the one exception. Inherited unchanged it measures 3.62:1 on a
 * `surface3` chip, and this palette leans on 11sp secondary text; lightening it
 * clears 4.79:1 on every surface here. The shipped [DarkPalette] has the same
 * weakness and is deliberately *not* touched — changing it would alter the look
 * of every existing install, which is a separate decision from adding a new
 * theme.
 */
val PitchBlackPalette = DarkPalette.copy(
    ink      = Color(0xFF000000),
    surface1 = Color(0xFF0B0B0F),
    surface2 = Color(0xFF141419),
    surface3 = Color(0xFF1C1C22),
    dim      = Color(0xFF8686A0),
    line     = Color(0xFF1E1E26),
)

val LocalDroidColors = staticCompositionLocalOf { DarkPalette }

/** Resolves [mode] against the OS setting and publishes the result.
 *  [pitchBlack] only applies once the result is a dark one. */
@Composable
fun DroidDockTheme(
    mode: ThemeMode,
    pitchBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = when {
        !dark -> LightPalette
        pitchBlack -> PitchBlackPalette
        else -> DarkPalette
    }

    // Material's own scheme is kept in step so the components the app doesn't
    // style by hand — Switch, OutlinedTextField, AlertDialog — follow along
    // instead of staying dark on a light page.
    val scheme = if (dark) {
        darkColorScheme(
            background     = colors.ink,
            surface        = colors.surface1,
            surfaceVariant = colors.surface2,
            primary        = colors.amber,
            onPrimary      = colors.onAmber,
            secondary      = colors.ok,
            onSecondary    = colors.onOk,
            onBackground   = colors.fg,
            onSurface      = colors.fg,
            outline        = colors.line,
            outlineVariant = colors.line.copy(alpha = 0.6f),
        )
    } else {
        lightColorScheme(
            background     = colors.ink,
            surface        = colors.surface1,
            surfaceVariant = colors.surface2,
            primary        = colors.amber,
            onPrimary      = colors.onAmber,
            secondary      = colors.ok,
            onSecondary    = colors.onOk,
            onBackground   = colors.fg,
            onSurface      = colors.fg,
            outline        = colors.line,
            outlineVariant = colors.line.copy(alpha = 0.6f),
        )
    }

    CompositionLocalProvider(LocalDroidColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
