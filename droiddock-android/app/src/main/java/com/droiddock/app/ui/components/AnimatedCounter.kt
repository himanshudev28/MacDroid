package com.droiddock.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.droiddock.app.ui.theme.DockTheme

/**
 * Digit-roll number (iOS `.numericText`): only the digits that changed roll;
 * the others stay put. Rolls up when the value rises, down when it falls.
 *
 * Spec (slide is 35% of the line height + fade, clipped to the line):
 * - Calm: 200 ms tween.   - Standard: 160 ms tween.
 * - Bouncy: 160 ms fade + a 0.8/600 spring on the slide (a tiny settle).
 * - Animations off: snaps.
 * Digits are keyed from the right, so 99 -> 100 grows a digit without
 * re-rolling the units. Tabular figures keep the width steady.
 *
 * @param suffix drawn after the number at [suffixSize] (e.g. "%"), not animated.
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    suffix: String? = null,
    suffixSize: TextUnit = TextUnit.Unspecified,
) {
    val motion = DockTheme.motion
    val last = remember { intArrayOf(value) }
    val up = value >= last[0]
    last[0] = value
    val text = value.toString()
    val tabular = style.copy(fontFeatureSettings = "tnum")
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text + (suffix ?: "") }) {
        text.forEachIndexed { i, ch ->
            val fromRight = text.length - 1 - i
            // Key by position from the right via the Row child index: AnimatedContent
            // targetState is the glyph, so equal glyphs do nothing.
            key(fromRight) { AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dir = if (up) 1 else -1
                    val slideIn = if (!motion.enabled) tween<androidx.compose.ui.unit.IntOffset>(0)
                    else if (motion.stretchy) spring(0.8f, 600f) else tween(if (motion.pageMs > 300) 200 else 160, easing = motion.easing)
                    val fade = if (!motion.enabled) tween<Float>(0) else tween(if (motion.pageMs > 300) 200 else 160)
                    (slideInVertically(slideIn) { (it * 0.35f).toInt() * dir } + fadeIn(fade)) togetherWith
                        (slideOutVertically(tween(if (motion.enabled) 140 else 0)) { -(it * 0.35f).toInt() * dir } + fadeOut(tween(if (motion.enabled) 100 else 0)))
                },
                label = "digit$fromRight",
            ) { c -> Text(c.toString(), style = tabular, color = color, maxLines = 1) } }
        }
        if (suffix != null) {
            Text(suffix, style = tabular.copy(fontSize = if (suffixSize != TextUnit.Unspecified) suffixSize else tabular.fontSize), color = color, maxLines = 1)
        }
    }
}
