package com.droiddock.app

import android.content.Context

data class Pairing(val ips: List<String>, val port: Int, val token: String, val macName: String)

object Prefs {
    private const val FILE = "droiddock"

    fun save(ctx: Context, p: Pairing) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("ips", p.ips.joinToString(","))
            .putInt("port", p.port)
            .putString("token", p.token)
            .putString("macName", p.macName)
            .apply()
    }

    fun load(ctx: Context): Pairing? {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val token = sp.getString("token", null) ?: return null
        val ips = sp.getString("ips", "")!!.split(",").filter { it.isNotBlank() }
        if (ips.isEmpty()) return null
        return Pairing(ips, sp.getInt("port", 48484), token, sp.getString("macName", "Mac")!!)
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Pause state: 0 = active, Long.MAX_VALUE = until the user resumes, else the
     *  epoch-millis deadline after which the link auto-resumes. */
    fun setPausedUntil(ctx: Context, until: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("pausedUntil", until).apply()
    }

    fun pausedUntil(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("pausedUntil", 0L)

    /** When true (default), the Accessibility service auto-sends copies to the Mac.
     *  When false, phone→Mac clipboard is manual only (share sheet / "Send" button). */
    fun setClipboardAuto(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("clipAuto", v).apply()
    }

    fun clipboardAuto(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("clipAuto", true)
}
