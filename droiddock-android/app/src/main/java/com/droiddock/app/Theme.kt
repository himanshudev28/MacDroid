package com.droiddock.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.LocalDockColors
import com.droiddock.app.ui.theme.UiStyle

/*
 * The theme engine moved to `ui/theme/` (DroidDockTheme, DockTheme tokens,
 * glass). What stays here is what other files still name:
 *
 * - [ThemeMode]: persisted by Prefs, used by the Appearance picker.
 * - [DroidColors] / [LocalDroidColors]: a read-only COMPAT view for screens the
 *   sweep has not reached yet. It holds no colours of its own (the old
 *   amber/brown palettes are gone); every field is a new token. Delete once no
 *   screen reads it.
 */

/** User's theme preference. Stored by [name], so don't reorder casually. */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    companion object {
        fun from(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** COMPAT: old palette field names mapped onto the new semantic tokens. */
@Deprecated("Use MaterialTheme.colorScheme / DockTheme.colors")
data class DroidColors(
    val ink: Color, val surface1: Color, val surface2: Color, val surface3: Color,
    val amber: Color, val amberDim: Color, val onAmber: Color,
    val ok: Color, val onOk: Color, val bad: Color,
    val fg: Color, val dim: Color, val line: Color,
    val purple: Color, val blue: Color, val orange: Color,
    val isDark: Boolean,
)

/** COMPAT: `LocalDroidColors.current.isDark` keeps compiling. */
@Suppress("DEPRECATION")
object LocalDroidColors {
    val current: DroidColors
        @Composable get() {
            val d = LocalDockColors.current
            val s = MaterialTheme.colorScheme
            return DroidColors(
                ink = d.page, surface1 = d.card, surface2 = d.raised, surface3 = d.inset,
                amber = s.primary, amberDim = s.primaryContainer, onAmber = s.onPrimary,
                ok = d.success, onOk = d.onSuccess, bad = s.error,
                fg = s.onSurface, dim = s.onSurfaceVariant, line = d.divider,
                purple = s.tertiary, blue = s.secondary, orange = d.warning,
                isDark = d.isDark,
            )
        }
}

/** COMPAT overload for the old call shape: Material You, everything else default. */
@Composable
fun DroidDockTheme(mode: ThemeMode, pitchBlack: Boolean = false, content: @Composable () -> Unit) =
    com.droiddock.app.ui.theme.DroidDockTheme(
        appearance = Appearance(style = UiStyle.MATERIAL_YOU, themeMode = mode, amoled = pitchBlack),
        content = content,
    )
