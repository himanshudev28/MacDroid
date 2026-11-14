package com.droiddock.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

object SmsRepo {
    // "" = looked up, no contact. ConcurrentHashMap: hit from IO handlers and CallReceiver threads.
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** One page of threads, newest first. [offset] counts threads (not rows) already sent; [more] = another page exists. */
    class ThreadPage(val threads: JSONArray, val more: Boolean)

    /** Cap on rows scanned for one page; the old fixed 30-thread call never needed more than 1500. */
    private const val SCAN_ROWS = 40_000


    fun threads(ctx: Context, max: Int = 30, offset: Int = 0): ThreadPage {
        val arr = JSONArray()
        val seen = HashSet<Long>()
        val wanted = ArrayList<Triple<Long, String, Long>>()
        val snippets = HashMap<Long, Long>() // threadId -> newest message _id
        var rows = 0
        var more = false
        // Scan without the body (the heavy column); bodies are fetched only for the threads we emit.
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "date", "_id"),
            null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext() && rows++ < SCAN_ROWS) {
                val tid = c.getLong(0)
                val addr = c.getString(1) ?: continue   // never counted, so it can't skew the offset
                if (!seen.add(tid)) continue
                if (seen.size <= offset) continue
                if (wanted.size >= max) { more = true; break }
                wanted.add(Triple(tid, addr, c.getLong(2)))
                snippets[tid] = c.getLong(3)
            }
        }
        for ((tid, addr, date) in wanted) {
            var body = ""
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("body"), "_id = ?", arrayOf(snippets.remove(tid).toString()), null
            )?.use { c -> if (c.moveToFirst()) body = c.getString(0) ?: "" }
            arr.put(
                JSONObject().put("threadId", tid).put("address", addr).put("name", name(ctx, addr) ?: addr)
                    .put("snippet", body.take(80)).put("date", date)
            )
        }
        return ThreadPage(arr, more)
    }

    /** A window of one thread, oldest→newest. [moreOlder]/[moreNewer]: messages exist beyond either edge. */
    class MessagePage(val address: String, val messages: JSONArray, val moreOlder: Boolean, val moreNewer: Boolean)

    private fun row(c: android.database.Cursor) = JSONObject()
        .put("id", c.getLong(0))
        .put("body", c.getString(2) ?: "")
        .put("date", c.getLong(3))
        .put("out", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)

    private val MSG_COLS = arrayOf("_id", "address", "body", "date", "type")

    /** Up to [max] rows of the thread matching [sel], in [order]; returns (rows, moreExist, address). */
    private fun page(ctx: Context, threadId: Long, sel: String, args: List<String>, order: String, max: Int):
        Triple<List<JSONObject>, Boolean, String?> {
        val list = ArrayList<JSONObject>()
        var addr: String? = null
        var more = false
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, MSG_COLS,
            "thread_id = ?" + sel, (listOf(threadId.toString()) + args).toTypedArray(), order
        )?.use { c ->
            while (c.moveToNext()) {
                if (list.size >= max) { more = true; break }
                if (addr == null) addr = c.getString(1)
                list.add(row(c))
            }
        }
        return Triple(list, more, addr)
    }

    private const val OLDER = " AND (date < ? OR (date = ? AND _id < ?))"
    private const val NEWER = " AND (date > ? OR (date = ? AND _id > ?))"

    /**
     * No cursor → the newest [max]. [beforeDate]/[beforeId] → the [max] just older than that message.
     * [afterDate]/[afterId] → the [max] just newer. [around] (a message id) → [max]/2 either side of it.
     * Returns (address, messages oldest→newest).
     */
    fun messages(
        ctx: Context, threadId: Long, max: Int = 60,
        beforeDate: Long? = null, beforeId: Long = 0,
        afterDate: Long? = null, afterId: Long = 0,
        around: Long? = null,
    ): MessagePage {
        val desc = "date DESC, _id DESC"
        val asc = "date ASC, _id ASC"
        fun cur(d: Long, id: Long) = listOf(d.toString(), d.toString(), id.toString())
        if (around != null) {
            var d = -1L
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, MSG_COLS, "_id = ? AND thread_id = ?",
                arrayOf(around.toString(), threadId.toString()), null
            )?.use { c -> if (c.moveToFirst()) d = c.getLong(3) }
            // Message gone (deleted since the search): fall back to the newest page.
            if (d < 0) return messages(ctx, threadId, max)
            val half = (max / 2).coerceAtLeast(1)
            val (older, moreOlder, a1) = page(ctx, threadId, OLDER, cur(d, around), desc, half)
            val (newer, moreNewer, a2) = page(ctx, threadId, NEWER, cur(d, around), asc, half)
            val self = page(ctx, threadId, " AND _id = ?", listOf(around.toString()), desc, 1).first
            val arr = JSONArray()
            (older.reversed() + self + newer).forEach { arr.put(it) }
            return MessagePage(a1 ?: a2 ?: "", arr, moreOlder, moreNewer)
        }
        if (afterDate != null) {
            val (rows, more, addr) = page(ctx, threadId, NEWER, cur(afterDate, afterId), asc, max)
            val arr = JSONArray(); rows.forEach { arr.put(it) }
            return MessagePage(addr ?: "", arr, true, more)
        }
        val (rows, more, addr) = if (beforeDate != null)
            page(ctx, threadId, OLDER, cur(beforeDate, beforeId), desc, max)
        else page(ctx, threadId, "", emptyList(), desc, max)
        val arr = JSONArray(); rows.reversed().forEach { arr.put(it) }
        return MessagePage(addr ?: "", arr, more, beforeDate != null)
    }

    /** Escape LIKE wildcards so "100%" matches literally. */
    private fun likeEscape(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    class SearchPage(val results: JSONArray, val more: Boolean)

    /** Message-body search across every conversation, newest first. [offset] counts hits already sent. */
    fun search(ctx: Context, query: String, max: Int = 30, offset: Int = 0): SearchPage {
        val q = query.trim()
        val arr = JSONArray()
        if (q.length < 2) return SearchPage(arr, false)
        var n = 0
        var more = false
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "thread_id", "address", "body", "date", "type"),
            "body LIKE ? ESCAPE '\\'", arrayOf("%" + likeEscape(q) + "%"), "date DESC, _id DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                if (n++ < offset) continue
                if (arr.length() >= max) { more = true; break }
                val addr = c.getString(2) ?: ""
                arr.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("threadId", c.getLong(1))
                        .put("address", addr)
                        .put("name", name(ctx, addr) ?: addr)
                        .put("body", (c.getString(3) ?: "").take(400))
                        .put("date", c.getLong(4))
                        .put("out", c.getInt(5) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                )
            }
        }
        return SearchPage(arr, more)
    }

    fun send(ctx: Context, address: String, text: String) {
        require(address.isNotBlank() && text.isNotBlank()) { "empty message" }
        val sm = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
                ?: throw IllegalStateException("SmsManager unavailable")
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val parts = sm.divideMessage(text)
        sm.sendMultipartTextMessage(address, null, parts, null, null)
    }

    fun name(ctx: Context, address: String): String? {
        if (address.isBlank()) return null
        nameCache[address]?.let { return it.ifEmpty { null } }
        var result: String? = null
        runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) result = c.getString(0) }
        }
        if (nameCache.size > 200) nameCache.clear() // a racing put just re-fills; harmless
        nameCache[address] = result ?: ""
        return result
    }
}
