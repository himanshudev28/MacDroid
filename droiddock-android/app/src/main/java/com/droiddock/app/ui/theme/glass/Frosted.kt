package com.droiddock.app.ui.theme.glass

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.LocalDockColors
import com.droiddock.app.ui.theme.LocalDockStyle
import com.droiddock.app.ui.theme.UiStyle
import kotlin.math.max

/*
 * Frosted glass for everything that is not a lens.
 *
 * Only two floating surfaces ever run the Backdrop engine (nav pill, top bar).
 * Every other surface (cards, groups, tiles, hero, sheets, buttons, switches,
 * chips, badges) is "frosted": a translucent tonal fill over the static colour
 * backdrop of the page, a 1 px rim that is bright at the top edge, and a soft
 * inner glow. No offscreen buffer, no live blur, no per-frame work: it is three
 * draw calls cached per size, so it costs the same as a plain rounded box.
 *
 * It switches off (solid tonal surfaces) whenever [GlassSpec.frostAlpha] is 0:
 * Reduce transparency, battery saver, thermal throttling, high contrast.
 */

/** True when content surfaces should be drawn as frosted glass. */
@Composable
fun frostActive(): Boolean =
    LocalDockStyle.current == UiStyle.LIQUID_GLASS && LocalGlassSpec.current.frostAlpha > 0f

/** Rim: bright specular edge on top, fading, with a faint contour at the bottom. */
@Composable
private fun rimBrush(spec: GlassSpec, dark: Boolean, strength: Float = 1f): Brush {
    val edge = if (dark) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    return Brush.verticalGradient(
        0f to spec.rim.copy(alpha = (spec.rimAlpha * strength).coerceIn(0f, 1f)),
        0.5f to spec.rim.copy(alpha = (spec.rimAlpha * 0.22f * strength).coerceIn(0f, 1f)),
        1f to edge,
    )
}

/**
 * A frosted content surface: [color] at the glass opacity, inner glow, rim.
 *
 * @param floating adds a soft drop shadow (sheets, menus); in-flow cards get none.
 * @param accent tints the inner glow (hero, now-playing); defaults to primary.
 * @param minAlpha opacity floor for surfaces that sit over moving content (sheets).
 */
@Composable
fun Modifier.frosted(
    shape: Shape,
    color: Color,
    floating: Boolean = false,
    accent: Color = Color.Unspecified,
    minAlpha: Float = 0f,
): Modifier {
    val spec = LocalGlassSpec.current
    val dark = LocalDockColors.current.isDark
    val glowTint = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    val a = max(spec.frostAlpha, minAlpha)
    val rim = rimBrush(spec, dark)
    val topLight = spec.rim.copy(alpha = (if (dark) 0.10f else 0.55f) * spec.rimAlpha / 0.55f)
    var m = this
    if (floating) {
        m = m.shadow(
            14.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.20f),
        )
    }
    return m
        .clip(shape)
        .drawWithCache {
            val glow = Brush.verticalGradient(
                0f to topLight, 0.45f to Color.Transparent, 1f to Color.Transparent,
            )
            val tint = Brush.radialGradient(
                listOf(glowTint.copy(alpha = if (dark) 0.16f else 0.10f), Color.Transparent),
                center = Offset(size.width * 0.12f, 0f), radius = size.maxDimension * 0.9f,
            )
            onDrawBehind {
                drawRect(color.copy(alpha = color.alpha * a))
                drawRect(tint)
                drawRect(glow)
            }
        }
        .border(spec.rimWidth, rim, shape)
}

/**
 * A glass control (pill button, toggle track, chip, badge disc): tinted fill,
 * a specular highlight across the upper half, and the rim. [fill] should
 * carry its own alpha.
 */
@Composable
fun Modifier.glassControl(shape: Shape, fill: Color, strength: Float = 1f): Modifier {
    val spec = LocalGlassSpec.current
    val dark = LocalDockColors.current.isDark
    val rim = rimBrush(spec, dark, strength)
    val hl = Color.White.copy(alpha = (if (dark) 0.14f else 0.45f) * spec.rimAlpha / 0.55f * strength)
    return this
        .clip(shape)
        .drawWithCache {
            val highlight = Brush.verticalGradient(0f to hl, 0.55f to Color.Transparent, 1f to Color.Transparent)
            onDrawBehind {
                drawRect(fill)
                drawRect(highlight)
            }
        }
        .border(spec.rimWidth, rim, shape)
}
