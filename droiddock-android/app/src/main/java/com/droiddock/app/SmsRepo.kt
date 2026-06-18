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
    private val nameCache = HashMap<String, String?>()

    fun threads(ctx: Context, max: Int = 30): JSONArray {
        val arr = JSONArray()
        val seen = HashSet<Long>()
        var rows = 0
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "body", "date"),
            null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext() && seen.size < max && rows++ < 1500) {
                val tid = c.getLong(0)
                if (!seen.add(tid)) continue
                val addr = c.getString(1) ?: continue
                arr.put(
                    JSONObject()
                        .put("threadId", tid)
                        .put("address", addr)
                        .put("name", name(ctx, addr) ?: addr)
                        .put("snippet", (c.getString(2) ?: "").take(80))
                        .put("date", c.getLong(3))
                )
            }
        }
        return arr
    }

    /** Returns (address, messages oldest→newest). */
    fun messages(ctx: Context, threadId: Long, max: Int = 60): Pair<String, JSONArray> {
        val list = ArrayList<JSONObject>()
        var addr: String? = null
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC"
        )?.use { c ->
            while (c.moveToNext() && list.size < max) {
                if (addr == null) addr = c.getString(1)
                list.add(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("body", c.getString(2) ?: "")
                        .put("date", c.getLong(3))
                        .put("out", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                )
            }
        }
        list.reverse()
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return (addr ?: "") to arr
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
        nameCache[address]?.let { return it }
        if (nameCache.containsKey(address)) return null
        var result: String? = null
        runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) result = c.getString(0) }
        }
        if (nameCache.size > 200) nameCache.clear()
        nameCache[address] = result
        return result
    }
}
