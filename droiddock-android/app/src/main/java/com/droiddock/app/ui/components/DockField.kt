package com.droiddock.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.glass.frostActive

/**
 * Colours for every OutlinedTextField in the app, so fields match the
 * surfaces around them instead of staying stock Material.
 *
 * Liquid Glass: a faint frosted fill with a hairline edge (a bare grey outline
 * on a transparent fill looked unfinished next to glass buttons and cards).
 * Other styles keep the Material defaults, with the style's hairline at rest.
 */
@Composable
fun dockFieldColors(): TextFieldColors {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    return if (frostActive()) {
        val fill = s.onSurface.copy(alpha = if (d.isDark) 0.07f else 0.045f)
        OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = fill,
            focusedContainerColor = fill,
            disabledContainerColor = fill,
            unfocusedBorderColor = s.onSurface.copy(alpha = if (d.isDark) 0.16f else 0.12f),
            focusedBorderColor = s.primary,
            cursorColor = s.primary,
        )
    } else {
        OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = if (d.hairline.alpha > 0f) d.hairline else s.outlineVariant,
            unfocusedContainerColor = Color.Transparent,
        )
    }
}
