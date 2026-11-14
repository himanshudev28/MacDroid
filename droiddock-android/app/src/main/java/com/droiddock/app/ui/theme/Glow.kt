package com.droiddock.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Glow accent: a luminous contour on the hero and the one primary action
 * (ColorOS 16/17 "light instead of shadow"). Drawn as three nested strokes of
 * a diagonal gradient, so no blur and no offscreen buffer: static, cheap.
 * No-op when the user turned Glow off.
 */
@Composable
fun Modifier.dockGlow(shape: Shape, strength: Float = 1f, visible: Boolean = true): Modifier {
    if (!LocalAppearance.current.glow) return this
    // Fades in/out when [visible] flips; read in the draw phase only.
    val fade = animateFloatAsState(if (visible) 1f else 0f, LocalDockMotion.current.fade(), label = "glow")
    val color = LocalDockColors.current.glow
    val dark = LocalDockColors.current.isDark
    val mono = LocalDockStyle.current == UiStyle.MINIMAL_MONO
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val k = strength * (if (dark) 1f else 0.75f)
        // Mono keeps it to a single crisp signal line.
        val brush = Brush.linearGradient(
            0f to color.copy(alpha = 0.95f * k),
            0.45f to color.copy(alpha = 0.10f * k),
            0.7f to color.copy(alpha = 0.05f * k),
            1f to color.copy(alpha = 0.65f * k),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val w1 = 1.25.dp.toPx()
        val w2 = 5.dp.toPx()
        val w3 = 10.dp.toPx()
        onDrawWithContent {
            val a = fade.value
            if (a > 0.01f && !mono) {
                drawPath(path, brush, alpha = 0.10f * a, style = Stroke(w3))
                drawPath(path, brush, alpha = 0.18f * a, style = Stroke(w2))
            }
            drawContent()
            if (a > 0.01f) drawPath(path, brush, alpha = a, style = Stroke(max(w1, 1f)))
        }
    }
}

/**
 * The page behind content. Liquid Glass (DEPTH) gets soft colour fields that drift very slowly (an aurora; glass over a
 * flat colour reads as a flat blob); every other style a flat page colour.
 *
 * The drift is ambient decoration, so it is cheap and gated: it runs only while [rememberAmbientActive] (foreground,
 * animations on, not battery saver / thermal) and updates about 15 times a second (the fields move a few dp per second,
 * so steps are invisible). That caps the full-screen redraw (and the Backdrop re-capture behind the glass) at 15 fps
 * instead of the display rate. Otherwise the fields are static.
 */
@Composable
fun Modifier.dockPageBackground(): Modifier {
    val c = LocalDockColors.current
    if (c.backdropA.alpha == 0f) return this.drawBehind { drawRect(c.page) }
    val ambient = rememberAmbientActive()
    val t = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(ambient) {
        if (!ambient) return@LaunchedEffect
        val start = android.os.SystemClock.uptimeMillis() - (t.floatValue * 1000f).toLong()
        while (true) {
            kotlinx.coroutines.delay(66)
            t.floatValue = (android.os.SystemClock.uptimeMillis() - start) / 1000f
        }
    }
    return this.drawBehind {
        // Slow Lissajous drift, at most ~6% of the width; one full cycle is minutes long.
        val k = t.floatValue
        fun dx(f: Float, ph: Float): Float = (kotlin.math.sin((k * f + ph).toDouble()) * 0.06).toFloat()
        fun dy(f: Float, ph: Float): Float = (kotlin.math.cos((k * f * 0.8f + ph).toDouble()) * 0.04).toFloat()
        drawRect(c.page)
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy),
        )
        blob(c.backdropA, w * (0.05f + dx(0.031f, 0f)), h * (0.08f + dy(0.027f, 1f)), w * 1.15f)
        blob(c.backdropB, w * (1.0f + dx(0.023f, 2f)), h * (0.36f + dy(0.035f, 0.5f)), w * 1.00f)
        blob(c.backdropC, w * (0.10f + dx(0.029f, 4f)), h * (0.70f + dy(0.021f, 3f)), w * 1.00f)
        blob(c.backdropB, w * (0.95f + dx(0.019f, 5f)), h * (0.98f + dy(0.025f, 2f)), w * 0.90f)
    }
}
