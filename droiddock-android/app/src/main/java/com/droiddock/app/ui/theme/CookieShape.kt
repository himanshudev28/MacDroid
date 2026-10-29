package com.droiddock.app.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A soft scalloped circle (Material 3 Expressive's "cookie"), drawn as a polar
 * curve so it needs no experimental `MaterialShapes` API.
 *
 * r(θ) = R · (1 − depth · (1 − cos(lobes·θ)) / 2). Sampled at 4 points per
 * degree of the smallest lobe, which is smooth at any badge size.
 */
class CookieShape(private val lobes: Int = 9, private val depth: Float = 0.075f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(cx, cy)
        val steps = lobes * 24
        val path = Path()
        for (i in 0..steps) {
            val th = (2.0 * PI * i / steps)
            val k = 1f - depth * (1f - cos(lobes * th).toFloat()) / 2f
            val x = cx + (r * k * cos(th - PI / 2)).toFloat()
            val y = cy + (r * k * sin(th - PI / 2)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is CookieShape && other.lobes == lobes && other.depth == depth
    override fun hashCode() = lobes * 31 + depth.hashCode()
}
