package com.droiddock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Injects taps / swipes / nav actions onto the phone for the Mac-side screen control.
 * Uses the already-enabled accessibility service's gesture dispatch — no ADB. Input
 * coordinates arrive as 0..1 fractions of the screen so they're resolution-independent.
 */
object AccessibilityControl {

    @Volatile var service: AccessibilityService? = null

    private fun realSize(svc: AccessibilityService): Pair<Int, Int> {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun frac(v: Double) = v.coerceIn(0.0, 1.0)

    fun tap(xFrac: Double, yFrac: Double) {
        val svc = service ?: return
        val (w, h) = realSize(svc)
        val x = (frac(xFrac) * w).toFloat()
        val y = (frac(yFrac) * h).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        runCatching { svc.dispatchGesture(g, null, null) }
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durMs: Int) {
        val svc = service ?: return
        val (w, h) = realSize(svc)
        val path = Path().apply {
            moveTo((frac(x1) * w).toFloat(), (frac(y1) * h).toFloat())
            lineTo((frac(x2) * w).toFloat(), (frac(y2) * h).toFloat())
        }
        val dur = durMs.toLong().coerceIn(20L, 3000L)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        runCatching { svc.dispatchGesture(g, null, null) }
    }

    /** Hardware-key equivalents via global accessibility actions. */
    fun key(name: String) {
        val svc = service ?: return
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return
        }
        runCatching { svc.performGlobalAction(action) }
    }
}
