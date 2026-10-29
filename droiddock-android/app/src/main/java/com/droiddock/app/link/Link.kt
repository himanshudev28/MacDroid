package com.droiddock.app.link

import org.json.JSONObject

/** Which radio a [Link] rides on. */
enum class LinkKind { WIFI, BLE }

/** What the UI shows as "how am I connected right now". Null elsewhere = no link. */
enum class Transport { WIFI, BLE }

/**
 * One authenticated path to the Mac. [ConnectionManager] holds at most one per
 * [LinkKind]; message handlers reply on the link a request arrived on.
 */
interface Link {
    val id: Long
    val kind: LinkKind
    /** Queue [o] for the peer. False = not sent (closed, queue full, too large). */
    fun sendJson(o: JSONObject): Boolean
    /** Binary frames are Wi-Fi only; a BLE link always answers false. */
    fun sendBinary(b: ByteArray): Boolean
    fun close()
}

private val linkIds = java.util.concurrent.atomic.AtomicLong(1)
internal fun nextLinkId(): Long = linkIds.getAndIncrement()
