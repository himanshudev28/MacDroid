package com.droiddock.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.droiddock.app.Prefs
import com.droiddock.app.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every Settings › Appearance choice, as one immutable value.
 *
 * The enums are stored by [Enum.name], so renaming a constant is a migration;
 * reordering is free. Defaults follow `ANDROID-REDESIGN-PLAN.md` exactly.
 */
@Immutable
data class Appearance(
    val style: UiStyle = UiStyle.LIQUID_GLASS,
    val layout: NavLayout = NavLayout.TABS_4,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Palette modifier. Ignored (and disabled in the picker) for Liquid Glass:
     *  glass needs content behind it, and #000 is no content. */
    val amoled: Boolean = false,
    val motion: MotionPersonality = MotionPersonality.STANDARD,
    val glow: Boolean = true,
    val glassIntensity: GlassIntensity = GlassIntensity.STANDARD,
    /** Where accent colours come from. Default: the DroidDock brand. */
    val colorSource: ColorSource = ColorSource.BRAND,
    /** In-app equivalent of Apple's Reduce Transparency: glass becomes opaque. */
    val reduceTransparency: Boolean = false,
    /** "Live blur" toggle. Off is the user-facing half of the glass kill switch:
     *  translucent tint, no Backdrop/RenderEffect at all. */
    val liveBlur: Boolean = true,
    val haptics: Boolean = true,
) {
    /** AMOLED as it actually applies. */
    val effectiveAmoled: Boolean get() = amoled && style.allowsAmoled
}

enum class UiStyle(val allowsAmoled: Boolean) {
    LIQUID_GLASS(false),
    MATERIAL_YOU(true),
    MINIMAL_MONO(true),
    REACH(true);
}

/** Compact-width navigation. Width >= 600 dp always gets the rail. */
enum class NavLayout { TABS_4, CLASSIC_5, HUB }

enum class MotionPersonality { CALM, STANDARD, BOUNCY }

enum class GlassIntensity { SUBTLE, STANDARD, STRONG }

/** App colours: the fixed brand violet-indigo, or the phone's Material You palette (API 31+). */
enum class ColorSource { BRAND, PHONE }

private inline fun <reified E : Enum<E>> enumOr(raw: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == raw } ?: fallback

/**
 * Persists [Appearance] in the app's existing SharedPreferences file
 * (`droiddock`, the same one [Prefs] uses), and publishes it as a [StateFlow]
 * so a change is a recomposition, never an activity recreate.
 *
 * Keys are namespaced `ui.*` so they cannot collide with [Prefs]. Theme mode
 * keeps living under [Prefs.themeMode]'s key, and the legacy `pitchBlack`
 * switch is read once as the AMOLED default, so nobody's choice is lost.
 */
object AppearanceStore {
    private const val FILE = "droiddock"
    private const val K_STYLE = "ui.style"
    private const val K_LAYOUT = "ui.layout"
    private const val K_AMOLED = "ui.amoled"
    private const val K_MOTION = "ui.motion"
    private const val K_GLOW = "ui.glow"
    private const val K_INTENSITY = "ui.glassIntensity"
    private const val K_COLORS = "ui.colorSource"
    private const val K_REDUCE = "ui.reduceTransparency"
    private const val K_BLUR = "ui.liveBlur"
    private const val K_HAPTICS = "ui.haptics"

    private val _state = MutableStateFlow(Appearance())
    val state: StateFlow<Appearance> = _state.asStateFlow()

    @Volatile private var loaded = false

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Idempotent. Call before the first composition so frame one is right. */
    fun load(ctx: Context): Appearance {
        if (loaded) return _state.value
        val p = sp(ctx)
        val a = Appearance(
            style = enumOr(p.getString(K_STYLE, null), UiStyle.LIQUID_GLASS),
            layout = enumOr(p.getString(K_LAYOUT, null), NavLayout.TABS_4),
            themeMode = Prefs.themeMode(ctx),
            amoled = if (p.contains(K_AMOLED)) p.getBoolean(K_AMOLED, false) else Prefs.pitchBlack(ctx),
            motion = enumOr(p.getString(K_MOTION, null), MotionPersonality.STANDARD),
            glow = p.getBoolean(K_GLOW, true),
            glassIntensity = enumOr(p.getString(K_INTENSITY, null), GlassIntensity.STANDARD),
            colorSource = enumOr(p.getString(K_COLORS, null), ColorSource.BRAND),
            reduceTransparency = p.getBoolean(K_REDUCE, false),
            liveBlur = p.getBoolean(K_BLUR, true),
            haptics = p.getBoolean(K_HAPTICS, true),
        )
        _state.value = a
        loaded = true
        return a
    }

    /** Applies [transform], publishes the result at once, persists async. */
    fun update(ctx: Context, transform: (Appearance) -> Appearance) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        sp(ctx).edit()
            .putString(K_STYLE, next.style.name)
            .putString(K_LAYOUT, next.layout.name)
            .putBoolean(K_AMOLED, next.amoled)
            .putString(K_MOTION, next.motion.name)
            .putBoolean(K_GLOW, next.glow)
            .putString(K_INTENSITY, next.glassIntensity.name)
            .putString(K_COLORS, next.colorSource.name)
            .putBoolean(K_REDUCE, next.reduceTransparency)
            .putBoolean(K_BLUR, next.liveBlur)
            .putBoolean(K_HAPTICS, next.haptics)
            .apply()
        // Theme mode and the legacy pitch-black flag stay in Prefs' own keys so
        // anything still reading them (old Settings rows) agrees with us.
        Prefs.setThemeMode(ctx, next.themeMode)
        Prefs.setPitchBlack(ctx, next.amoled)
    }

    /** Test/preview hook: publish a value without touching disk. */
    internal fun setForPreview(a: Appearance) { _state.value = a; loaded = true }
}
