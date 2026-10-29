package com.droiddock.app.ui.theme.glass

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.GlassIntensity
import com.droiddock.app.ui.theme.UiStyle

/**
 * Which glass we can afford right now.
 *
 * - [OFF]: opaque tonal surface (Reduce transparency, or a non-glass style).
 * - [TINT]: translucent tint + rim + soft shadow. No offscreen buffer. API 26-30,
 *   battery saver, thermal >= moderate, low-RAM, animations off, kill switch,
 *   over budget, and every Paparazzi snapshot.
 * - [BLUR]: Backdrop blur + vibrancy + tint + rim. API 31-32.
 * - [LENS]: BLUR plus the AGSL lens (edge refraction). API 33+.
 */
enum class GlassTier { OFF, TINT, BLUR, LENS }

/** Live device conditions that force glass down a tier. */
@Immutable
data class GlassSignals(
    val powerSave: Boolean = false,
    val thermalThrottled: Boolean = false,
    val lowRam: Boolean = false,
    val animationsOff: Boolean = false,
    val highContrast: Boolean = false,
    val killSwitch: Boolean = false,
)

/** Everything a [GlassSurface] needs, resolved once per theme. */
@Immutable
data class GlassSpec(
    val tier: GlassTier,
    /** Fill alpha over the blurred backdrop (BLUR/LENS). */
    val scrimAlpha: Float,
    /** Fill alpha of the translucent fallback (TINT). */
    val tintAlpha: Float,
    val blurRadius: Dp,
    val lensHeight: Dp,
    val lensAmount: Dp,
    /** Specular rim strength 0..1. */
    val rimAlpha: Float,
    /** Rim width; 1.5 dp under high contrast. */
    val rimWidth: Dp,
    /** Neutral glass tint: white / near-black plus ~8% primary. */
    val tint: Color,
    /** Primary-action glass tint. */
    val primaryTint: Color,
    /** Opaque fallback for OFF. */
    val solid: Color,
    val rim: Color,
    /**
     * Opacity of frosted content surfaces (cards, tiles, hero, sheets, controls).
     * 0 means solid tonal: Reduce transparency, battery saver, thermal, high contrast.
     * Scaled by Glass intensity: Subtle is most opaque, Strong most see-through.
     */
    val frostAlpha: Float = 0f,
)

/**
 * Kill switch for Backdrop's known RenderThread SIGSEGV (FAQ: glass bottom
 * sheet). Any one source drops every surface to [GlassTier.TINT]:
 *
 * 1. [ENGINE_ENABLED]: compile-time; flip to false to ship without Backdrop paths.
 * 2. [remoteOff]: runtime flag for a future server/Mac-pushed "glass off".
 * 3. The user's "Live blur" toggle ([Appearance.liveBlur]).
 * 4. A crash sentinel: two consecutive foreground deaths while the glass engine
 *    was drawing trips it until the next app update (see [onStart]/[onStop]).
 */
object GlassKillSwitch {
    const val ENGINE_ENABLED = true

    @Volatile var remoteOff: Boolean = false

    private const val FILE = "droiddock"
    private const val K_FOREGROUND = "ui.glass.fg"
    private const val K_ENGINE_USED = "ui.glass.used"
    private const val K_CRASHES = "ui.glass.crashes"
    private const val TRIP_AT = 2

    @Volatile private var tripped: Boolean? = null

    fun isTripped(ctx: Context): Boolean = tripped ?: run {
        val n = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(K_CRASHES, 0)
        (n >= TRIP_AT).also { tripped = it }
    }

    /** Activity onStart. A foreground flag still set means the last session died in front of the user. */
    fun onStart(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val crashed = sp.getBoolean(K_FOREGROUND, false) && sp.getBoolean(K_ENGINE_USED, false)
        val crashes = if (crashed) sp.getInt(K_CRASHES, 0) + 1 else sp.getInt(K_CRASHES, 0)
        sp.edit().putBoolean(K_FOREGROUND, true).putBoolean(K_ENGINE_USED, false)
            .putInt(K_CRASHES, crashes).apply()
        tripped = crashes >= TRIP_AT
    }

    /** Activity onStop: a clean exit resets the consecutive-crash count (unless already tripped). */
    fun onStop(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val crashes = sp.getInt(K_CRASHES, 0)
        sp.edit().putBoolean(K_FOREGROUND, false)
            .putInt(K_CRASHES, if (crashes >= TRIP_AT) crashes else 0).apply()
    }

    /** Called once per session by the host the first time Backdrop actually draws. */
    @Volatile private var markedThisSession = false
    internal fun markEngineUsed(ctx: Context) {
        if (markedThisSession) return
        markedThisSession = true
        runCatching { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_ENGINE_USED, true).apply() }
    }

    /** Clears a tripped sentinel (Settings toggling Live blur back on). */
    fun reset(ctx: Context) {
        tripped = false
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(K_CRASHES, 0).apply()
    }
}

/**
 * Collects [GlassSignals], live: power-save broadcasts, the thermal listener
 * (API 29+), and a re-read of animator scale / contrast on every resume. Static
 * defaults in inspection mode (Paparazzi), where none of these services exist.
 */
@Composable
fun rememberGlassSignals(): State<GlassSignals> {
    val inspection = LocalInspectionMode.current
    val ctx = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(if (inspection) GlassSignals() else readSignals(ctx)) }
    if (inspection) return state
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ctx, lifecycle) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val refresh = { state.value = readSignals(ctx) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            PowerManager.OnThermalStatusChangedListener { refresh() }.takeIf {
                // Can throw (layoutlib, some OEM builds): then we just never hear about throttling.
                runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(ctx), it) }.isSuccess
            }
        } else null
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose {
            runCatching { ctx.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= 29 && thermal != null) pm.removeThermalStatusListener(thermal)
            lifecycle.removeObserver(observer)
        }
    }
    return state
}

private fun readSignals(ctx: Context): GlassSignals {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val thermal = Build.VERSION.SDK_INT >= 29 &&
        pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    val contrast = if (Build.VERSION.SDK_INT >= 34) {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).contrast > 0.25f
    } else {
        runCatching {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "high_text_contrast_enabled", 0) == 1
        }.getOrDefault(false)
    }
    return GlassSignals(
        powerSave = pm.isPowerSaveMode,
        thermalThrottled = thermal,
        lowRam = am.isLowRamDevice,
        animationsOff = !com.droiddock.app.ui.theme.DockMotion.systemAnimationsEnabled(),
        highContrast = contrast,
        killSwitch = !GlassKillSwitch.ENGINE_ENABLED || GlassKillSwitch.remoteOff || GlassKillSwitch.isTripped(ctx),
    )
}

/**
 * The single decision point: style, user prefs, API level and live signals in,
 * one [GlassSpec] out.
 */
fun resolveGlass(
    a: Appearance,
    signals: GlassSignals,
    dark: Boolean,
    primary: Color,
    surfaceHigh: Color,
    onSurface: Color,
    sdk: Int = Build.VERSION.SDK_INT,
    inspection: Boolean = false,
): GlassSpec {
    val tier = when {
        a.style != UiStyle.LIQUID_GLASS || a.reduceTransparency -> GlassTier.OFF
        inspection || !a.liveBlur || signals.killSwitch || signals.powerSave ||
            signals.thermalThrottled || signals.lowRam || signals.animationsOff -> GlassTier.TINT
        sdk >= 33 -> GlassTier.LENS
        sdk >= 31 -> GlassTier.BLUR
        else -> GlassTier.TINT
    }
    val (scrim, blur, lensH, lensA, rim, tintA) = when (a.glassIntensity) {
        GlassIntensity.SUBTLE -> Sextuple(0.55f, 12.dp, 12.dp, 16.dp, 0.35f, 0.92f)
        GlassIntensity.STANDARD -> Sextuple(0.40f, 20.dp, 16.dp, 24.dp, 0.55f, 0.88f)
        GlassIntensity.STRONG -> Sextuple(0.28f, 28.dp, 20.dp, 32.dp, 0.75f, 0.84f)
    }
    // Light glass reads washed-out over light content: heavier scrim there.
    val lightBoost = if (dark) 0f else 0.10f
    val hc = signals.highContrast
    val frost = when (a.glassIntensity) {
        GlassIntensity.SUBTLE -> 0.80f
        GlassIntensity.STANDARD -> 0.66f
        GlassIntensity.STRONG -> 0.54f
    }
    val frosted = tier != GlassTier.OFF && !signals.powerSave && !signals.thermalThrottled && !hc
    val neutral = if (dark) Color(0xFF16181C) else Color(0xFFFFFFFF)
    return GlassSpec(
        tier = tier,
        scrimAlpha = if (hc) maxOf(0.85f, scrim + lightBoost) else scrim + lightBoost,
        tintAlpha = if (hc) maxOf(0.94f, tintA) else tintA,
        blurRadius = blur,
        lensHeight = lensH,
        lensAmount = lensA,
        rimAlpha = rim,
        rimWidth = if (hc) 1.5.dp else 1.dp,
        tint = lerp(neutral, primary, 0.08f),
        primaryTint = primary.copy(alpha = 0.74f),
        solid = surfaceHigh,
        rim = if (hc) onSurface.copy(alpha = 0.6f) else Color.White,
        frostAlpha = if (frosted) frost else 0f,
    )
}

private data class Sextuple<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

val LocalGlassSpec = staticCompositionLocalOf {
    GlassSpec(
        GlassTier.OFF, 0.4f, 0.88f, 20.dp, 16.dp, 24.dp, 0.5f, 1.dp,
        Color.White, Color.Blue, Color.LightGray, Color.White,
    )
}
