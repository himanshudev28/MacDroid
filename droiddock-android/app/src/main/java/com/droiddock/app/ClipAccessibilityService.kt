package com.droiddock.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Auto-clipboard capture (phone → Mac).
 *
 * Android 13+ (and especially Samsung One UI) blocks background clipboard reads for
 * EVERY app when it isn't the focused window — so we can't just read the clipboard
 * when you copy in WhatsApp/Chrome/etc. Two capture paths cover that:
 *
 *  Path 1 — real clipboard read: works when DroidDock itself has focus, or on OSes
 *           that still allow background reads.
 *  Path 2 — event capture: read the selected / long-pressed text straight from
 *           accessibility events and send it only when a "copied" toast confirms an
 *           actual copy. This is what makes cross-app copies work without clipboard access.
 *
 * Honors the Auto/Manual toggle, dedupes, and never reads window content (only the text
 * carried in events). Mac→phone clipboard and the manual paths are untouched.
 */
class ClipAccessibilityService : AccessibilityService() {

    private var clipboard: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var candidate: String = ""   // last selected / long-pressed text
    @Volatile private var candidateAt: Long = 0     // when it was captured (uptimeMillis)
    @Volatile private var lastSent: String = ""     // dedupe

    override fun onServiceConnected() {
        super.onServiceConnected()
        ConnectionManager.ensureLoop(applicationContext)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val l = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged(cm, 0) }
        clipboard?.let { old -> listener?.let { old.removePrimaryClipChangedListener(it) } }
        cm.addPrimaryClipChangedListener(l)
        clipboard = cm
        listener = l
        AccessibilityControl.service = this // enable Mac-side screen control (gestures)
        AccessibilityControl.enabled = Prefs.screenControl(applicationContext)
    }

    /** Path 1 — direct clipboard read (works while DroidDock is focused). */
    private fun onClipChanged(cm: ClipboardManager, attempt: Int) {
        if (!Prefs.clipboardAuto(applicationContext)) return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            if (attempt < 2) handler.postDelayed({ onClipChanged(cm, attempt + 1) }, 150)
            return
        }
        deliver(text)
    }

    /** Path 2 — capture text from events; fire on the "copied" toast. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!Prefs.clipboardAuto(applicationContext)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                selectedText(event)?.let { setCandidate(it) }      // exact substring
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val txt = event.text.joinToString(" ").trim()
                if (txt.length > 1) setCandidate(stripTrailingTime(txt))
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (isCopyToast(event)) onCopyDetected()
        }
    }

    private fun setCandidate(s: String) {
        val v = s.trim()
        if (v.isEmpty()) return
        candidate = v
        candidateAt = SystemClock.uptimeMillis()
    }

    private fun onCopyDetected() {
        val c = candidate
        // Only act on a fresh capture so an unrelated copy never sends stale text.
        if (c.isNotEmpty() && SystemClock.uptimeMillis() - candidateAt < 12_000) {
            deliver(c)
            candidate = "" // consume — second toast (systemui) won't resend it
        }
    }

    /** The selected substring from a text-selection event, or null if it's just a cursor. */
    private fun selectedText(e: AccessibilityEvent): String? {
        val full = e.text.joinToString("")
        val from = e.fromIndex
        val to = e.toIndex
        return if (full.isNotEmpty() && from in 0..full.length && to in (from + 1)..full.length)
            full.substring(from, to) else null
    }

    private fun isCopyToast(e: AccessibilityEvent): Boolean {
        if (e.className?.contains("Toast") != true) return false
        if (e.packageName == "com.android.systemui") return true // Samsung "Copied to clipboard"
        val txt = e.text.joinToString(" ").lowercase()
        return txt.contains("copied") || txt.contains("copy to clip")
    }

    // A trailing "… 12:48 pm" / "…, 12:56 pm" timestamp WhatsApp appends in a11y text.
    // Spaces may be regular, NBSP ( ) or narrow-NBSP ( ); the comma is optional.
    private val trailingTime = Regex(
        ",?[\\s\\u00a0\\u202f]*\\d{1,2}:\\d{2}[\\s\\u00a0\\u202f]*([ap]\\.?[\\s\\u00a0\\u202f]*m\\.?)?[\\s\\u00a0\\u202f]*$",
        RegexOption.IGNORE_CASE
    )

    /** Strip the trailing timestamp and normalise odd unicode spaces. */
    private fun stripTrailingTime(s: String): String {
        val noTime = s.replace(trailingTime, "")
        // normalise NBSP (\u00a0) and narrow NBSP (\u202f) to plain spaces
        val sb = StringBuilder(noTime.length)
        for (ch in noTime) sb.append(if (ch == '\u00a0' || ch == '\u202f') ' ' else ch)
        return sb.toString().trim()
    }

    private fun deliver(text: String) {
        if (text.isEmpty() || text == lastSent) return
        lastSent = text
        if (ConnectionManager.sendClipboardText(text)) {
            ConnectionManager.lastEvent.value = "clipboard → Mac"
        }
    }

    override fun onInterrupt() { /* unused */ }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { clipboard?.removePrimaryClipChangedListener(it) }
        listener = null
        clipboard = null
        if (AccessibilityControl.service === this) AccessibilityControl.service = null
        super.onDestroy()
    }

}
