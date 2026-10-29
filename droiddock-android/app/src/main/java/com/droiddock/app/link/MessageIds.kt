package com.droiddock.app.link

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * `mid` support (BLE-DESIGN §7): ids for the few messages that must never be
 * shown twice when a handover makes the phone resend them, a receiver-side LRU
 * that swallows repeats, and a 30 s ring of what went out on which link.
 */
object MessageIds {
    /** Types that carry a `mid` (notification, removal, call, clipboard). */
    val STAMPED = setOf("notification", "notification-removed", "call", "clipboard")

    private val prefix: String = run {
        val r = java.security.SecureRandom()
        buildString { repeat(6) { append("0123456789abcdefghijklmnopqrstuvwxyz"[r.nextInt(36)]) } }
    }
    private val seq = AtomicLong(0)
    fun next(): String = "$prefix-${seq.incrementAndGet()}"

    /** Last [cap] ids seen; `seenBefore` records and reports in one step. */
    class Lru(private val cap: Int = 512) {
        private val map = object : LinkedHashMap<String, Unit>(cap + 1, 0.75f, false) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Unit>?) = size > cap
        }
        @Synchronized fun seenBefore(mid: String): Boolean = map.put(mid, Unit) != null
    }

    /** Messages with a `mid` sent in the last [windowMs], tagged by link id. */
    class ReplayRing(private val windowMs: Long = 30_000, private val clock: () -> Long = System::currentTimeMillis) {
        private class Entry(val at: Long, val linkId: Long, val msg: JSONObject)
        private val entries = ArrayDeque<Entry>()

        @Synchronized fun record(linkId: Long, msg: JSONObject) {
            val now = clock()
            prune(now)
            entries.addLast(Entry(now, linkId, msg))
            while (entries.size > 128) entries.removeFirst()
        }

        /** Remove and return [linkId]'s still-fresh entries, oldest first. */
        @Synchronized fun drain(linkId: Long): List<JSONObject> {
            prune(clock())
            val out = entries.filter { it.linkId == linkId }.map { it.msg }
            entries.removeAll { it.linkId == linkId }
            return out
        }

        private fun prune(now: Long) {
            while (entries.isNotEmpty() && now - entries.first().at > windowMs) entries.removeFirst()
        }
    }
}
