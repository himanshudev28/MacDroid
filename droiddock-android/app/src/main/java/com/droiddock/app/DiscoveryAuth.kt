package com.droiddock.app

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LAN discovery probe that never puts the pairing token on the wire.
 *
 * Payload: `DROIDDOCK:DISCOVER2:<hex>` where `<hex>` is the lowercase hex of the first 16 bytes of
 * HMAC-SHA256(key = token UTF-8 bytes, message = "droiddock-discover:" + w) and
 * `w = floor(unixSeconds / 300)` as a decimal string. The Mac answers `DROIDDOCK:HERE`.
 */
object DiscoveryAuth {
    const val PREFIX = "DROIDDOCK:DISCOVER2:"
    const val WINDOW_SECONDS = 300L

    fun window(nowMs: Long): Long = nowMs / 1000 / WINDOW_SECONDS

    fun tag(token: String, w: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val full = mac.doFinal("droiddock-discover:$w".toByteArray(Charsets.UTF_8))
        return full.copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun payload(token: String, nowMs: Long = System.currentTimeMillis()): ByteArray =
        (PREFIX + tag(token, window(nowMs))).toByteArray(Charsets.UTF_8)
}

/** Reconnect backoff: doubles to 15 s as before, then to 60 s while the Mac stays unreachable. */
internal fun nextBackoff(current: Long): Long =
    if (current < 15_000L) minOf(current * 2, 15_000L) else minOf(current * 2, 60_000L)
