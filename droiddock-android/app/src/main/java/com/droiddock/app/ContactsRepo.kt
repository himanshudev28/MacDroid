package com.droiddock.app

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

object ContactsRepo {
    /** One row per contact (first number), name-sorted, with starred flag. */
    fun list(ctx: Context, max: Int = 3000): JSONArray {
        val arr = JSONArray()
        val seen = HashSet<Long>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            proj, null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext() && arr.length() < max) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue // collapse multiple numbers to one card
                val name = c.getString(1) ?: continue
                arr.put(
                    JSONObject()
                        .put("name", name)
                        .put("number", c.getString(2) ?: "")
                        .put("starred", c.getInt(3) == 1)
                )
            }
        }
        return arr
    }
}
