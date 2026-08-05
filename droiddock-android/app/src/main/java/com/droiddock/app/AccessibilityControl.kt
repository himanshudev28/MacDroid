package com.droiddock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects taps / swipes / typing / nav actions onto the phone for the Mac-side screen
 * control. Uses the already-enabled accessibility service — no ADB. Coordinates arrive as
 * 0..1 fractions so they're resolution-independent.
 *
 * Everything runs on the main thread: `dispatchGesture` and node actions (ACTION_SET_TEXT)
 * silently do nothing when called off the main thread, and the control messages arrive on
 * the WebSocket reader thread.
 */
object AccessibilityControl {

    @Volatile var service: AccessibilityService? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Whether Mac-side screen control can actually do anything right now.
     *
     * Every method below begins `service ?: return@post`, so with the
     * accessibility service switched off each tap, swipe and nav press is
     * discarded in silence — the mirror keeps streaming video, which makes it
     * look like the Mac isn't sending anything. Android also turns an
     * accessibility service off whenever its app is reinstalled, so this goes
     * from working to not working with no user action at all. Callers use this
     * to say so instead of dropping the message.
     */
    fun available(): Boolean = service != null && enabled

    /**
     * The user's "let the Mac control this screen" choice, mirrored here so the
     * dispatch path is a field read rather than a SharedPreferences hit on
     * every tap of a drag gesture.
     *
     * Defaults true so a phone that never opens the setting behaves as it
     * always has. [available] gates on it, so switching it off makes the Mac
     * report control as unavailable — the same, already-handled path as the
     * service being off entirely — while auto-clipboard keeps working.
     */
    @Volatile var enabled: Boolean = true

    /**
     * Turn the accessibility service off from inside the app.
     *
     * `disableSelf()` is the only half of this Android gives us — a service can
     * switch itself off, but nothing can switch one *on* except the user in
     * Settings. That asymmetry is fine for the case this exists for: banking
     * apps refuse to run while any accessibility service is enabled, so "off,
     * right now, without digging through Settings" is the direction that needs
     * to be quick. Re-enabling is a deliberate act and can afford the trip.
     */
    fun disableSelf(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.disableSelf(); true }.getOrDefault(false)
    }

    private fun realSize(svc: AccessibilityService): Pair<Int, Int> {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun frac(v: Double) = v.coerceIn(0.0, 1.0)

    fun tap(xFrac: Double, yFrac: Double) = main.post {
        val svc = service ?: return@post
        val (w, h) = realSize(svc)
        val x = (frac(xFrac) * w).toFloat()
        val y = (frac(yFrac) * h).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        runCatching { svc.dispatchGesture(g, null, null) }
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durMs: Int) = main.post {
        val svc = service ?: return@post
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

    // We track what we've typed rather than trusting the node's text: some apps (WhatsApp)
    // report their placeholder ("Message") as the field's text, which would get prepended.
    private var typed = StringBuilder()
    private var lastSet: String? = null

    /** If the field's text isn't what we last wrote, it's a fresh field (new focus,
     *  placeholder, or edited on the phone) — start empty so the next write replaces it. */
    private fun syncField(node: AccessibilityNodeInfo) {
        if (node.text?.toString() != lastSet) typed = StringBuilder()
    }

    /** Type [text] into the currently-focused input field. */
    fun typeText(text: String) = main.post {
        val node = service?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
        syncField(node)
        typed.append(text)
        setNodeText(node, typed.toString())
        lastSet = typed.toString()
    }

    /** Delete the last character we typed in the focused input field. */
    fun backspace() = main.post {
        val node = service?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
        syncField(node)
        if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1)
        setNodeText(node, typed.toString())
        lastSet = typed.toString()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) }
    }

    /** Hardware-key equivalents via global accessibility actions. */
    fun key(name: String) = main.post {
        val svc = service ?: return@post
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            // Locks the screen the same way the power button does. Added on
            // Android 9 (API 28); on anything older this falls through and the
            // Mac's button is a no-op rather than a crash.
            //
            // There is deliberately no "unlock": Android has no API for it at
            // any privilege level a sideloaded app can reach, and it shouldn't
            // — a Mac that can unlock the phone defeats the lock screen. The
            // Mac's button says "Lock phone" and means exactly that.
            "lock" -> if (Build.VERSION.SDK_INT >= 28) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else return@post
            else -> return@post
        }
        runCatching { svc.performGlobalAction(action) }
    }
}
