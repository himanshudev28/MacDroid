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

internal const val USB_LOOPBACK = "127.0.0.1"
/** The phone-side port of the Mac's `adb reverse tcp:28484 tcp:<mac port>`. It must sit below Android's
 *  ephemeral range (32768–60999): a port inside it, like 48484, is randomly taken by other apps' outgoing
 *  connections, and then adbd can't bind the reverse ("Address already in use"). Must match the Mac's
 *  `adb::USB_PHONE_PORT`. */
internal const val USB_LOOPBACK_PORT = 28484
internal const val USB_CONNECT_TIMEOUT_MS = 500L

/** The USB candidate: loopback only for a pinned (usable pin) pairing. An unpinned one would send the
 *  token in plain ws:// to whatever app listens on 127.0.0.1. */
internal fun loopbackCandidate(p: Pairing): String? =
    if (p.fp != null && !LinkTls.isUnusablePin(p.fp)) USB_LOOPBACK else null
