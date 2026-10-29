package com.droiddock.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min

/*
 * DroidDock brand scheme: the default "App colours" choice, and the fallback on
 * API 26-30 and in every snapshot test.
 *
 * Generated with Material Color Utilities tonal palettes (`materialyoucolor`,
 * PyPI), tones per the M3 baseline mapping. Seeded from the logo's violet to
 * indigo (#6C63FF to #4A44D8):
 *   primary    hue 289, chroma 66   (violet-indigo; tone 40 = #5549D0)
 *   secondary  hue 285, chroma 16
 *   tertiary   hue 325, chroma 30   (soft orchid, the second glass glow)
 *   neutral    hue 262, chroma 5    (COOL greys, never warm)
 *   neutral-variant hue 262, chroma 9
 * Nothing here descends from the retired amber/brown palette.
 */
internal val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF5549D0), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF130067), inversePrimary = Color(0xFFC5C0FF), secondary = Color(0xFF5D5C72),
    onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFE2E0F9), onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF6B5582), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFF0DBFF),
    onTertiaryContainer = Color(0xFF26123A), background = Color(0xFFF9F9FE), onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF9F9FE), onSurface = Color(0xFF1A1C1F), surfaceVariant = Color(0xFFDFE2ED),
    onSurfaceVariant = Color(0xFF434750), surfaceTint = Color(0xFF5549D0), inverseSurface = Color(0xFF2F3034),
    inverseOnSurface = Color(0xFFF1F0F5), error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002), outline = Color(0xFF737781),
    outlineVariant = Color(0xFFC3C6D1), surfaceBright = Color(0xFFF9F9FE), surfaceDim = Color(0xFFDAD9DF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F3F8), surfaceContainer = Color(0xFFEEEDF3),
    surfaceContainerHigh = Color(0xFFE8E8ED), surfaceContainerHighest = Color(0xFFE2E2E7),
)

internal val BrandDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFC5C0FF), onPrimary = Color(0xFF2400A2), primaryContainer = Color(0xFF3D2CB7),
    onPrimaryContainer = Color(0xFFE3DFFF), inversePrimary = Color(0xFF5549D0), secondary = Color(0xFFC6C4DD),
    onSecondary = Color(0xFF2F2F42), secondaryContainer = Color(0xFF454559), onSecondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFFD7BCEF), onTertiary = Color(0xFF3B2750), tertiaryContainer = Color(0xFF533E68),
    onTertiaryContainer = Color(0xFFF0DBFF), background = Color(0xFF121317), onBackground = Color(0xFFE2E2E7),
    surface = Color(0xFF121317), onSurface = Color(0xFFE2E2E7), surfaceVariant = Color(0xFF434750),
    onSurfaceVariant = Color(0xFFC3C6D1), surfaceTint = Color(0xFFC5C0FF), inverseSurface = Color(0xFFE2E2E7),
    inverseOnSurface = Color(0xFF2F3034), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6), outline = Color(0xFF8D919B),
    outlineVariant = Color(0xFF434750), surfaceBright = Color(0xFF38393D), surfaceDim = Color(0xFF121317),
    surfaceContainerLowest = Color(0xFF0C0E12), surfaceContainerLow = Color(0xFF1A1C1F), surfaceContainer = Color(0xFF1E2023),
    surfaceContainerHigh = Color(0xFF282A2E), surfaceContainerHighest = Color(0xFF333539),
)

/*
 * Minimal Mono: an ink/paper ramp and exactly one signal accent. The accent is
 * not the primary: primary is ink (buttons are ink-on-paper), the signal colour
 * only marks live state, errors and the single glow.
 */
private val MonoSignalLight = Color(0xFFD12A1C)
private val MonoSignalDark = Color(0xFFFF5442)

internal val MonoLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A), onPrimary = Color(0xFFF4F4F2),
    primaryContainer = Color(0xFFE4E4E1), onPrimaryContainer = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFFF4F4F2),
    secondary = Color(0xFF3A3A38), onSecondary = Color(0xFFF4F4F2),
    secondaryContainer = Color(0xFFE4E4E1), onSecondaryContainer = Color(0xFF0A0A0A),
    tertiary = MonoSignalLight, onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2DE), onTertiaryContainer = Color(0xFF6B0E06),
    background = Color(0xFFF4F4F2), onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF4F4F2), onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFE9E9E6), onSurfaceVariant = Color(0xFF5C5C59),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF141414), inverseOnSurface = Color(0xFFF4F4F2),
    error = MonoSignalLight, onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE2DE), onErrorContainer = Color(0xFF6B0E06),
    outline = Color(0xFF8A8A86), outlineVariant = Color(0xFFD6D6D2), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFE4E4E1),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9F9F7),
    surfaceContainer = Color(0xFFF9F9F7), surfaceContainerHigh = Color(0xFFEFEFEC),
    surfaceContainerHighest = Color(0xFFE9E9E6),
)

internal val MonoDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF4F4F2), onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF242424), onPrimaryContainer = Color(0xFFF4F4F2),
    inversePrimary = Color(0xFF0A0A0A),
    secondary = Color(0xFFBDBDB9), onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color(0xFFF4F4F2),
    tertiary = MonoSignalDark, onTertiary = Color(0xFF1A0200),
    tertiaryContainer = Color(0xFF3A0D08), onTertiaryContainer = Color(0xFFFFDAD5),
    background = Color(0xFF000000), onBackground = Color(0xFFF4F4F2),
    surface = Color(0xFF000000), onSurface = Color(0xFFF4F4F2),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF9A9A96),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF4F4F2), inverseOnSurface = Color(0xFF0A0A0A),
    error = MonoSignalDark, onError = Color(0xFF1A0200),
    errorContainer = Color(0xFF3A0D08), onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF6E6E6B), outlineVariant = Color(0xFF262626), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1C1C1C), surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0E0E0E), surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1C1C1C),
)

/**
 * The scheme for [source]: wallpaper colours only when the user chose
 * [ColorSource.PHONE] and the OS can supply them (31+), the brand otherwise.
 */
internal fun baseScheme(ctx: Context, dark: Boolean, source: ColorSource, allowDynamic: Boolean = true): ColorScheme =
    if (allowDynamic && source == ColorSource.PHONE && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) BrandDark else BrandLight
    }

/**
 * Mono with the brand's indigo as its one signal accent instead of red. Indigo
 * reads calmer next to ink and paper and ties Mono to the logo; error stays
 * red (it is a meaning, not a brand colour).
 */
internal fun monoScheme(dark: Boolean, source: ColorSource): ColorScheme {
    val base = if (dark) MonoDark else MonoLight
    if (source != ColorSource.BRAND) return base
    return if (dark) base.copy(
        tertiary = Color(0xFFB4ACFF), onTertiary = Color(0xFF1B0F78),
        tertiaryContainer = Color(0xFF2A2470), onTertiaryContainer = Color(0xFFE3DFFF),
    ) else base.copy(
        tertiary = Color(0xFF4A44D8), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3DFFF), onTertiaryContainer = Color(0xFF130067),
    )
}

private fun Color.desaturate(fraction: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = hsl[1] * (1f - fraction)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = alpha)
}

/**
 * Rotate [this] hue toward [toward] by half the difference, at most 15 degrees:
 * the Material "harmonize" rule, done in HSL (close enough for status colours,
 * and avoids pulling in material-color-utilities for one function).
 */
internal fun Color.harmonize(toward: Color): Color {
    val a = FloatArray(3); val b = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), a)
    ColorUtils.colorToHSL(toward.toArgb(), b)
    if (b[1] < 0.08f) return this // neutral source: nothing to harmonise with
    var diff = b[0] - a[0]
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    val rot = min(abs(diff) * 0.5f, 15f) * (if (diff >= 0) 1f else -1f)
    a[0] = (a[0] + rot + 360f) % 360f
    return Color(ColorUtils.HSLToColor(a)).copy(alpha = alpha)
}

/**
 * AMOLED modifier: #000 page, near-black ramp, accents desaturated 15% so they
 * don't smear on OLED, text at 87% white (no halation). Dark only.
 */
internal fun ColorScheme.amoled(): ColorScheme {
    val text = Color(0xFFDEDEDE)
    val dim = Color(0xFFA3A3A8)
    return copy(
        primary = primary.desaturate(0.15f),
        primaryContainer = primaryContainer.desaturate(0.15f),
        secondary = secondary.desaturate(0.15f),
        secondaryContainer = secondaryContainer.desaturate(0.15f),
        tertiary = tertiary.desaturate(0.15f),
        tertiaryContainer = tertiaryContainer.desaturate(0.15f),
        background = Color.Black, surface = Color.Black,
        surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF09090A), surfaceContainer = Color(0xFF0E0E10),
        surfaceContainerHigh = Color(0xFF151517), surfaceContainerHighest = Color(0xFF1C1C1F),
        surfaceBright = Color(0xFF232326), surfaceVariant = Color(0xFF1C1C1F),
        onBackground = text, onSurface = text, onSurfaceVariant = dim,
        outlineVariant = Color(0xFF26262A),
    )
}

private val SuccessLight = listOf(Color(0xFF1A6C31), Color(0xFFFFFFFF), Color(0xFFA3F5AA), Color(0xFF00531F))
private val SuccessDark = listOf(Color(0xFF88D990), Color(0xFF003913), Color(0xFF00531F), Color(0xFFA3F5AA))
private val WarningLight = listOf(Color(0xFF825500), Color(0xFFFFFFFF), Color(0xFFFFDDB2), Color(0xFF624000))
private val WarningDark = listOf(Color(0xFFFFB94E), Color(0xFF452B00), Color(0xFF624000), Color(0xFFFFDDB2))

/** Builds the semantic layer for [style] on top of a finished [scheme]. */
internal fun dockColors(scheme: ColorScheme, style: UiStyle, dark: Boolean, amoled: Boolean): DockColors {
    val s = (if (dark) SuccessDark else SuccessLight).map { it.harmonize(scheme.primary) }
    val w = (if (dark) WarningDark else WarningLight).map { it.harmonize(scheme.primary) }
    val hairlineStyle = style == UiStyle.MINIMAL_MONO || amoled
    val clear = Color.Transparent
    return when (style) {
        UiStyle.REACH -> DockColors(
            // One UI's inset-grouped look: white cards on a slightly darker page.
            page = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainer,
            card = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
            raised = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
            inset = scheme.surfaceContainerHighest,
            hairline = if (amoled) scheme.outlineVariant else clear,
            divider = scheme.outlineVariant.copy(alpha = 0.6f),
            success = s[0], onSuccess = s[1], successContainer = s[2], onSuccessContainer = s[3],
            warning = w[0], onWarning = w[1], warningContainer = w[2], onWarningContainer = w[3],
            signal = scheme.primary, glow = scheme.primary,
            backdropA = clear, backdropB = clear, backdropC = clear, isDark = dark,
        )
        UiStyle.MINIMAL_MONO -> DockColors(
            page = scheme.background,
            card = scheme.background,
            raised = scheme.surfaceContainerHigh,
            inset = scheme.surfaceContainerHighest,
            hairline = scheme.onSurface.copy(alpha = if (dark) 0.16f else 0.12f),
            divider = scheme.onSurface.copy(alpha = if (dark) 0.12f else 0.09f),
            success = scheme.onSurface, onSuccess = scheme.background,
            successContainer = scheme.surfaceContainerHigh, onSuccessContainer = scheme.onSurface,
            warning = scheme.tertiary, onWarning = scheme.onTertiary,
            warningContainer = scheme.tertiaryContainer, onWarningContainer = scheme.onTertiaryContainer,
            signal = scheme.tertiary, glow = scheme.tertiary,
            backdropA = clear, backdropB = clear, backdropC = clear, isDark = dark,
        )
        UiStyle.LIQUID_GLASS -> DockColors(
            page = scheme.surface,
            // Raw container tones: components draw them frosted (translucent) or,
            // when transparency is off, as-is.
            card = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
            raised = scheme.surfaceContainerHigh,
            inset = scheme.surfaceContainerHighest,
            hairline = scheme.outlineVariant.copy(alpha = if (dark) 0.35f else 0.5f),
            divider = scheme.outlineVariant.copy(alpha = 0.5f),
            success = s[0], onSuccess = s[1], successContainer = s[2], onSuccessContainer = s[3],
            warning = w[0], onWarning = w[1], warningContainer = w[2], onWarningContainer = w[3],
            signal = scheme.primary,
            glow = lerp(scheme.primary, scheme.tertiary, 0.35f),
            // Soft colour fields: what the glass layer refracts. Kept low-alpha
            // so content stays legible and the page still reads as "surface".
            // Dark: the deep container tones (saturated, not the pastel accents).
            // Light: the accents themselves at low alpha. Static, so it costs nothing.
            backdropA = if (dark) scheme.primaryContainer.copy(alpha = 0.85f) else scheme.primary.copy(alpha = 0.42f),
            backdropB = if (dark) scheme.tertiaryContainer.copy(alpha = 0.70f) else scheme.tertiary.copy(alpha = 0.34f),
            backdropC = if (dark) scheme.primary.copy(alpha = 0.22f) else scheme.primary.copy(alpha = 0.22f),
            isDark = dark,
        )
        UiStyle.MATERIAL_YOU -> DockColors(
            page = scheme.surface,
            card = scheme.surfaceContainer,
            raised = scheme.surfaceContainerHigh,
            inset = scheme.surfaceContainerHighest,
            hairline = if (hairlineStyle) scheme.outlineVariant else clear,
            divider = scheme.outlineVariant.copy(alpha = 0.6f),
            success = s[0], onSuccess = s[1], successContainer = s[2], onSuccessContainer = s[3],
            warning = w[0], onWarning = w[1], warningContainer = w[2], onWarningContainer = w[3],
            signal = scheme.primary, glow = scheme.primary,
            backdropA = clear, backdropB = clear, backdropC = clear, isDark = dark,
        )
    }
}

/**
 * File-type colour, from roles (no rainbow). Replaces the old `extColor` hex map.
 * Returns container + content pair so it works as a badge.
 */
enum class FileKind { DOCUMENT, IMAGE, VIDEO, AUDIO, ARCHIVE, CODE, APP, OTHER;
    companion object {
        fun of(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf", "doc", "docx", "txt", "rtf", "md", "pages", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "key", "numbers" -> DOCUMENT
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "raw", "dng" -> IMAGE
            "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp" -> VIDEO
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> AUDIO
            "zip", "rar", "7z", "tar", "gz", "dmg", "iso", "xz", "bz2" -> ARCHIVE
            "kt", "java", "swift", "js", "ts", "py", "json", "xml", "html", "css", "sh", "rs", "c", "cpp", "h", "go" -> CODE
            "apk", "aab", "app", "exe", "pkg" -> APP
            else -> OTHER
        }
    }
}

data class ToneColors(val container: Color, val content: Color)

/** Three roles only: primary (documents/code), tertiary (media), secondary (the rest). */
fun fileKindColors(kind: FileKind, scheme: ColorScheme): ToneColors = when (kind) {
    FileKind.DOCUMENT, FileKind.CODE -> ToneColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO -> ToneColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    FileKind.ARCHIVE, FileKind.APP, FileKind.OTHER -> ToneColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
}
