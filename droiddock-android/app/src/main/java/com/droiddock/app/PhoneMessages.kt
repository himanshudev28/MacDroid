package com.droiddock.app

import org.json.JSONObject

/**
 * Pure builders and policies for a few capability-gated messages, split out of
 * [ConnectionManager] / [AccessibilityControl] so they can run in JVM tests.
 */
object ScreenshotReply {
    /**
     * Largest PNG we put in one text frame. Base64 inflates by 4/3, and OkHttp's
     * WebSocket refuses a send once its 16 MiB outbound queue would overflow
     * (it returns false, nothing is sent, the Mac just times out). 6 MiB of PNG
     * is ~8 MiB of text: comfortably under the cap, with room for queued video.
     */
    const val MAX_PNG_BYTES = 6 * 1024 * 1024

    /** Scale (0..1] to apply to both dimensions so a PNG of [pngBytes] is likely
     *  to fit [MAX_PNG_BYTES]; 1f when it already fits. PNG size tracks pixel
     *  count roughly linearly, hence the square root. */
    fun shrinkFactor(pngBytes: Int): Float {
        if (pngBytes <= MAX_PNG_BYTES) return 1f
        val f = Math.sqrt(MAX_PNG_BYTES.toDouble() / pngBytes).toFloat() * 0.9f
        return f.coerceIn(0.1f, 0.95f)
    }

    /** `{type:"screenshot",reqId,data}` on success, `{...,error}` otherwise. */
    fun build(reqId: Long, png: ByteArray?, err: String?): JSONObject {
        val out = JSONObject().put("type", "screenshot").put("reqId", reqId)
        if (png != null && png.isNotEmpty()) {
            out.put("data", java.util.Base64.getEncoder().encodeToString(png))
        } else {
            out.put("error", err ?: "Screenshot failed")
        }
        return out
    }
}

/** Phone -> Mac messages for the `macbrightness` and `remotedrag` caps. */
object RemoteMessages {
    /** Slider level clamped to 0..1; null for NaN/infinite, which JSONObject
     *  refuses to serialise (it would throw out of the slider callback). */
    fun macBrightness(level: Float): JSONObject? {
        if (level.isNaN() || level.isInfinite()) return null
        return JSONObject().put("type", "mac-brightness-set")
            .put("level", level.coerceIn(0f, 1f).toDouble())
    }

    fun remoteButton(down: Boolean, button: String = "left"): JSONObject =
        JSONObject().put("type", "remote-button").put("button", button)
            .put("state", if (down) "down" else "up")
}

/**
 * Mac viewer hidden (`mirror-pause`) / visible (`mirror-resume`). Video only; the
 * encoder, projection and phone audio stay alive. Mirrors the flag logic that
 * used to live inline in MirrorService.
 */
class VideoPauseGate {
    @Volatile var paused = false
        private set

    /** Pausing needs a live encoder; with none there is nothing to quiet. */
    fun pause(encoderAlive: Boolean) { if (encoderAlive) paused = true }

    /** True when the caller must now force a keyframe (resume was effective). */
    fun resume(encoderAlive: Boolean): Boolean {
        if (!encoderAlive) return false
        paused = false
        return true
    }

    /** New encoder / explicit resume of the whole stream. */
    fun reset() { paused = false }

    /** Whether the drain loop should drop this output buffer without copying. */
    fun shouldDrop(streaming: Boolean, isConfig: Boolean): Boolean =
        (!streaming || paused) && !isConfig
}
