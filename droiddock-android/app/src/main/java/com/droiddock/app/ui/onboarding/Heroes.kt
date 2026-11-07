package com.droiddock.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.ui.setup.PHONE_PERMS
import com.droiddock.app.ui.setup.notifAccessGranted
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockPageBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import com.droiddock.app.ui.theme.DockMotion

/**
 * An [Animatable] that runs once from 0 to [target] when first composed, after [delayMs], and holds.
 * With animations off it starts at [target] so the hero is simply shown finished.
 */
@Composable
internal fun rememberPlayOnce(
    motion: DockMotion,
    ms: Int,
    target: Float = 1f,
    delayMs: Int = 0,
    easing: Easing = LinearEasing,
): Animatable<Float, AnimationVector1D> {
    val a = remember { Animatable(if (motion.enabled) 0f else target) }
    LaunchedEffect(Unit) {
        if (!motion.enabled || a.value >= target) return@LaunchedEffect
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(target, tween(ms, easing = easing))
    }
    return a
}

/** Hero timeline in ms. Shared with the pills so they land in step with the chips. */
internal object HeroTimeline {
    const val DEVICES_IN = 350
    const val ARC_START = 80
    const val ARC_MS = 380
    const val FIRST_LEAVE = 330
    const val STAGGER = 150
    const val TRAVEL = 720
    const val POP_MS = 300
    const val GLOW_MS = 520
    fun leave(i: Int) = FIRST_LEAVE + i * STAGGER
    fun land(i: Int) = leave(i) + TRAVEL - 110
    val TOTAL = land(2) + POP_MS + GLOW_MS
}

/**
 * A phone and a Mac. A dashed arc draws between them, then three chips
 * (a notification, a file, a clipboard item) fly along it and settle on the
 * Mac's screen, which keeps them. Plays once (~1.9 s), then holds.
 * Drawn on one Canvas from a single clock: one Animatable, no recomposition per frame.
 */
@Composable
internal fun HandoffHero(modifier: Modifier = Modifier) {
    val motion = DockTheme.motion
    val total = HeroTimeline.TOTAL.toFloat()
    val clock = rememberPlayOnce(motion, HeroTimeline.TOTAL, target = total)
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val icons = listOf(
        rememberVectorPainter(Icons.Rounded.NotificationsActive),
        rememberVectorPainter(Icons.Rounded.Description),
        rememberVectorPainter(Icons.Rounded.ContentPaste),
    )
    val tints = listOf(NotifRed, FileBlue, ClipOrange)
    val body = d.raised
    val rim = s.outlineVariant
    val screen = d.card
    val phoneA = s.primary
    val phoneB = s.tertiary
    val arcColor = s.outline

    Canvas(modifier.clearAndSetSemantics { }) {
        val ms = clock.value
        val k = size.width / 300f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)

        val inP = ease(ms / HeroTimeline.DEVICES_IN)
        translate(top = (1f - inP) * 10.dp.toPx()) {
            val a = inP
            // Phone.
            val phoneTl = p(46f - 22f, 84f - 40f)
            val phoneSz = Size(44f * k, 80f * k)
            drawRoundRect(body.copy(alpha = body.alpha * a), phoneTl, phoneSz, CornerRadius(11f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), phoneTl, phoneSz, CornerRadius(11f * k), style = Stroke(1.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(phoneA.copy(alpha = 0.95f * a), phoneB.copy(alpha = 0.7f * a)), phoneTl.y, phoneTl.y + phoneSz.height),
                phoneTl + Offset(3f * k, 3f * k), Size(phoneSz.width - 6f * k, phoneSz.height - 6f * k), CornerRadius(8f * k),
            )
            drawCircle(Color.Black.copy(alpha = 0.35f * a), 2f * k, p(46f, 84f - 40f + 7f))

            // Mac: screen, bezel and base.
            val macTl = p(232f - 58f, 80f - 37f)
            val macSz = Size(116f * k, 74f * k)
            drawRoundRect(body.copy(alpha = body.alpha * a), macTl, macSz, CornerRadius(7f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), macTl, macSz, CornerRadius(7f * k), style = Stroke(1.dp.toPx()))
            drawRoundRect(screen.copy(alpha = screen.alpha * a), macTl + Offset(4f * k, 4f * k),
                Size(macSz.width - 8f * k, macSz.height - 8f * k), CornerRadius(4f * k))
            val baseTl = p(232f - 70f, 80f + 37f)
            drawRoundRect(body.copy(alpha = body.alpha * a), baseTl, Size(140f * k, 6f * k), CornerRadius(3f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), baseTl, Size(140f * k, 6f * k), CornerRadius(3f * k), style = Stroke(1.dp.toPx()))

            // Glow ring once everything has landed.
            val g = ((ms - HeroTimeline.land(2) - HeroTimeline.POP_MS * 0.5f) / HeroTimeline.GLOW_MS).coerceIn(0f, 1f)
            if (g > 0f && g < 1f) {
                val grow = ease(g) * 12.dp.toPx()
                drawRoundRect(
                    phoneA.copy(alpha = 0.45f * (1f - g)),
                    macTl - Offset(grow, grow), Size(macSz.width + grow * 2, macSz.height + grow * 2),
                    CornerRadius(7f * k + grow), style = Stroke(2.dp.toPx()),
                )
            }
        }

        // Arc, drawn on with a dashed stroke.
        val arcP = ease((ms - HeroTimeline.ARC_START) / HeroTimeline.ARC_MS)
        if (arcP > 0f) {
            val arc = Path().apply {
                moveTo(46f * k, 84f * k)
                quadraticTo(150f * k, 16f * k, 232f * k, 74f * k)
            }
            val pm = PathMeasure().apply { setPath(arc, false) }
            val seg = Path()
            pm.getSegment(0f, pm.length * arcP, seg, true)
            drawPath(
                seg, arcColor.copy(alpha = 0.6f),
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * k, 6f * k))),
            )
        }

        // Landed tiles on the Mac's screen: fixed slots, popping in on arrival.
        val slotY = 80f
        for (i in 0..2) {
            val slotX = 232f + (i - 1) * 28f
            val lp = ((ms - HeroTimeline.land(i)) / HeroTimeline.POP_MS).coerceIn(0f, 1f)
            if (lp <= 0f) continue
            val sc = 0.7f + 0.3f * easeOutBack(lp)
            drawTile(icons[i], tints[i], p(slotX, slotY), 22f * k * sc, lp)
        }

        // Chips in flight.
        for (i in 0..2) {
            val f = (ms - HeroTimeline.leave(i)) / HeroTimeline.TRAVEL
            if (f <= 0f || f >= 1f) continue
            val tt = FastOutSlowInEasing.transform(f)
            val from = Offset(46f, 84f)
            val to = Offset(232f + (i - 1) * 28f, slotY)
            val ctrl = Offset((from.x + to.x) / 2f + (i - 1) * 8f, 10f + i * 6f)
            val u = 1f - tt
            val x = u * u * from.x + 2 * u * tt * ctrl.x + tt * tt * to.x
            val y = u * u * from.y + 2 * u * tt * ctrl.y + tt * tt * to.y
            val alpha = when {
                f < 0.18f -> f / 0.18f
                f > 0.84f -> (1f - f) / 0.16f
                else -> 1f
            }
            val sc = if (f < 0.4f) 0.9f + 0.18f * (f / 0.4f) else 1.08f - 0.25f * ((f - 0.4f) / 0.6f)
            drawTile(icons[i], tints[i], p(x, y), 26f * k * sc, alpha, shadow = true)
        }
    }
}

private fun DrawScope.drawTile(icon: VectorPainter, tint: Color, center: Offset, side: Float, alpha: Float, shadow: Boolean = false) {
    val tl = Offset(center.x - side / 2f, center.y - side / 2f)
    val r = CornerRadius(side * 0.28f)
    if (shadow) drawRoundRect(Color.Black.copy(alpha = 0.16f * alpha), tl + Offset(0f, side * 0.08f), Size(side, side), r)
    drawRoundRect(tint.copy(alpha = alpha), tl, Size(side, side), r)
    val ic = side * 0.52f
    translate(center.x - ic / 2f, center.y - ic / 2f) {
        with(icon) { draw(Size(ic, ic), alpha = alpha, colorFilter = ColorFilter.tint(Color.White)) }
    }
}

/** Green disc whose checkmark draws itself. */
@Composable
internal fun GrantedCheck() {
    val motion = DockTheme.motion
    val ok = DockTheme.colors.success
    val draw = rememberPlayOnce(motion, 320, delayMs = 90, easing = FastOutSlowInEasing)
    val label = t("Allowed")
    Canvas(Modifier.size(36.dp).semantics { contentDescription = label }) {
        drawCircle(ok)
        drawCheck(draw.value, Color.White, 2.6.dp.toPx())
    }
}

private fun DrawScope.drawCheck(progress: Float, color: Color, width: Float) {
    if (progress <= 0f) return
    val path = Path().apply {
        moveTo(size.width * 0.30f, size.height * 0.52f)
        lineTo(size.width * 0.45f, size.height * 0.66f)
        lineTo(size.width * 0.71f, size.height * 0.36f)
    }
    val pm = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A green tile settles, its checkmark draws, and one ring of dots bursts out once. */
@Composable
internal fun DoneMark() {
    val motion = DockTheme.motion
    val s = MaterialTheme.colorScheme
    val ok = DockTheme.colors.success
    val land = remember { Animatable(if (motion.enabled) 0f else 1f) }
    val draw = remember { Animatable(if (motion.enabled) 0f else 1f) }
    val burst = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion.enabled) return@LaunchedEffect
        launch { land.animateTo(1f, spring(0.55f, 380f)) }
        launch { delay(140); draw.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
        launch { delay(200); burst.animateTo(1f, tween(700, easing = LinearEasing)) }
    }
    val colors = listOf(ok, s.primary, ClipOrange, Color(0xFFFF4F9A), Color(0xFF2EC4D6))
    Canvas(Modifier.size(180.dp).clearAndSetSemantics { }) {
        val c = center
        // Burst: dots ride out on an ease-out, fully visible early, fading by the end.
        val b = burst.value
        if (b > 0f && b < 1f) {
            val r = (46f + 40f * ease(b)).dp.toPx()
            val a = if (b < 0.15f) b / 0.15f else (1f - b) / 0.85f
            for (i in 0 until 12) {
                val ang = i / 12f * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                val dot = (if (i % 2 == 0) 3.6f else 2.6f).dp.toPx()
                drawCircle(colors[i % colors.size].copy(alpha = a), dot, Offset(c.x + cos(ang) * r, c.y + sin(ang) * r))
            }
            // A soft ring under the dots.
            drawCircle(ok.copy(alpha = 0.25f * (1f - b)), r * 0.92f, c, style = Stroke(2.dp.toPx()))
        }
        val l = land.value
        val side = 88.dp.toPx() * (0.86f + 0.14f * l)
        val tl = Offset(c.x - side / 2f, c.y - side / 2f)
        drawRoundRect(
            Brush.verticalGradient(listOf(ok, ok.copy(alpha = 0.72f)), tl.y, tl.y + side),
            tl, Size(side, side), CornerRadius(side * 0.3f), alpha = l.coerceIn(0f, 1f),
        )
        // drawCheck works in the current draw size, so shrink the area to the tile.
        translate(tl.x, tl.y) { drawIntoTile(Size(side, side)) { drawCheck(draw.value, Color.White, 7.dp.toPx()) } }
    }
}

/** Runs [block] with the draw size set to [sz] (top-left already translated). */
private fun DrawScope.drawIntoTile(sz: Size, block: DrawScope.() -> Unit) {
    val dx = size.width - sz.width
    val dy = size.height - sz.height
    // inset(right, bottom) shrinks the drawing area to the tile.
    inset(0f, 0f, dx, dy) { block() }
}

internal fun ease(x: Float): Float = FastOutSlowInEasing.transform(x.coerceIn(0f, 1f))

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val v = x - 1f
    return 1f + c3 * v.pow(3) + c1 * v.pow(2)
}
