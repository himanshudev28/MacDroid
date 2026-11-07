package com.droiddock.app.link.ble

import android.util.Log

/** Pure limiter: one line per key per window; repeats are counted and reported with the next line. */
class BleLogLimiter(private val windowMs: Long = 30_000L) {
    private val seen = HashMap<String, Pair<Long, Int>>()

    /** Number of suppressed lines since the last printed one, or null when this line must be dropped. */
    @Synchronized fun admit(key: String, now: Long): Int? {
        val e = seen[key]
        if (e != null && now - e.first < windowMs) {
            seen[key] = e.first to (e.second + 1)
            return null
        }
        seen[key] = now to 0
        return e?.second ?: 0
    }
}

/**
 * Release-safe Bluetooth diagnostics. Tag `DDBle`, level INFO: R8 here has no
 * `-assumenosideeffects` rule, so these survive in release builds.
 * Read with `adb logcat -s DDBle`. No tokens, rids or full addresses are logged.
 */
internal object BleLog {
    const val TAG = "DDBle"
    private val limiter = BleLogLimiter()

    fun i(key: String, msg: String) {
        val n = limiter.admit(key, System.currentTimeMillis()) ?: return
        val extra = if (n > 0) " (+$n similar suppressed)" else ""
        runCatching { Log.i(TAG, "$key: $msg$extra") }
    }

    /** Last two octets only. */
    fun who(address: String?): String = address?.takeLast(5) ?: "?"
}
