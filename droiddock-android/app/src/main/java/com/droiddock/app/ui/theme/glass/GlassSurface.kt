package com.droiddock.app.ui.theme.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.LocalDockStyle
import com.droiddock.app.ui.theme.UiStyle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * What a glass surface is for. Only [CONTROL] and [PRIMARY] are ever glass;
 * [SHEET] is listed so callers say what they mean, and it always takes the
 * plain path until the Backdrop bottom-sheet crash is cleared on device.
 */
enum class GlassRole { CONTROL, PRIMARY, SHEET }

/**
 * At most [MAX] live glass surfaces on screen (perf budget, S21 FE class).
 * The third and later claimants silently fall back to [GlassTier.TINT].
 * Claims are taken in composition order, so the nav bar (composed first by the
 * shell) always wins.
 */
@Stable
class GlassBudget {
    private var active = 0
    fun claim(): Boolean = if (active < MAX) { active++; true } else false
    fun release() { if (active > 0) active-- }
    companion object { const val MAX = 2 }
}

/** The captured content layer, or null when no surface can use it. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalGlassBudget = compositionLocalOf { GlassBudget() }

/**
 * Hosts glass: [content] is the page (captured into a layer only when the tier
 * needs it), [overlay] is the floating control layer drawn above it. Glass
 * never captures itself (no feedback loop) because the overlay is a sibling,
 * not a child, of the captured layer.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val spec = LocalGlassSpec.current
    val live = spec.tier == GlassTier.BLUR || spec.tier == GlassTier.LENS
    val budget = remember { GlassBudget() }
    Box(modifier) {
        if (live) {
            val backdrop = rememberLayerBackdrop()
            val ctx = LocalContext.current
            Box(Modifier.matchParentSize().layerBackdrop(backdrop).drawWithContent {
                drawContent()
                GlassKillSwitch.markEngineUsed(ctx)
            }) { content() }
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalGlassBudget provides budget) {
                overlay()
            }
        } else {
            Box(Modifier.matchParentSize()) { content() }
            CompositionLocalProvider(LocalGlassBackdrop provides null, LocalGlassBudget provides budget) {
                overlay()
            }
        }
    }
}

/**
 * The only glass primitive. Picks its rendering from [LocalGlassSpec]:
 * Backdrop lens/blur on capable devices, translucent tint below, opaque tonal
 * when transparency is reduced or the style isn't Liquid Glass ([plain]).
 *
 * @param plain colour used when this is not glass at all (non-glass styles).
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    role: GlassRole = GlassRole.CONTROL,
    plain: Color = LocalGlassSpec.current.solid,
    plainBorder: Color = Color.Transparent,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = LocalGlassSpec.current
    val backdrop = LocalGlassBackdrop.current
    val budget = LocalGlassBudget.current
    val wantsLive = role != GlassRole.SHEET && backdrop != null &&
        (spec.tier == GlassTier.BLUR || spec.tier == GlassTier.LENS)
    val granted = if (wantsLive) {
        val ok = remember(budget) { budget.claim() }
        DisposableEffect(budget) { onDispose { if (ok) budget.release() } }
        ok
    } else false
    val tier = when {
        spec.tier == GlassTier.OFF -> GlassTier.OFF
        role == GlassRole.SHEET -> GlassTier.OFF
        granted -> spec.tier
        else -> GlassTier.TINT
    }
    val fill = if (role == GlassRole.PRIMARY) spec.primaryTint else spec.tint
    val rimBrush = Brush.verticalGradient(
        0f to spec.rim.copy(alpha = spec.rimAlpha),
        0.5f to spec.rim.copy(alpha = spec.rimAlpha * 0.25f),
        1f to spec.rim.copy(alpha = spec.rimAlpha * 0.5f),
    )
    val m = when (tier) {
        GlassTier.LENS, GlassTier.BLUR -> modifier.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                blur(spec.blurRadius.toPx())
                if (tier == GlassTier.LENS) lens(spec.lensHeight.toPx(), spec.lensAmount.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = spec.rimAlpha) },
            onDrawSurface = {
                drawRect(fill.copy(alpha = if (role == GlassRole.PRIMARY) fill.alpha else spec.scrimAlpha))
            },
        )
        GlassTier.TINT -> modifier
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(shape)
            .background(if (role == GlassRole.PRIMARY) fill.copy(alpha = 0.92f) else fill.copy(alpha = spec.tintAlpha))
            .border(spec.rimWidth, rimBrush, shape)
        GlassTier.OFF -> if (LocalDockStyle.current != UiStyle.LIQUID_GLASS) {
            // Not a glass style: the caller's own surface.
            modifier.clip(shape).background(plain).let {
                if (plainBorder.alpha > 0f) it.border(1.dp, plainBorder, shape) else it
            }
        } else {
            // Reduce transparency / sheet: opaque, with a higher-contrast rim.
            modifier.clip(shape)
                .background(if (role == GlassRole.PRIMARY) fill.copy(alpha = 1f) else spec.solid)
                .border(spec.rimWidth, MaterialTheme.colorScheme.outlineVariant, shape)
        }
    }
    Box(m, contentAlignment = contentAlignment, content = content)
}
