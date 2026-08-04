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
        val ips = (sp.getString("ips", "") ?: "").split(",").filter { it.isNotBlank() }
        if (ips.isEmpty()) return null
        return Pairing(ips, sp.getInt("port", 48484), token, sp.getString("macName", "Mac") ?: "Mac")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Pause state: 0 = active, Long.MAX_VALUE = until the user resumes, else the
     *  epoch-millis deadline after which the link auto-resumes. */
    /** A stable per-install id, generated once. The Mac keys its photo-sync
     *  ledger on this rather than on "MANUFACTURER MODEL", which two identical
     *  phones share — and which made each of them skip the other's photos. */
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.getString("deviceId", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        sp.edit().putString("deviceId", fresh).apply()
        return fresh
    }

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

    /** When true, the Mac can start mirroring/camera without a per-time prompt on the
     *  phone (needs the "Display over other apps" permission to launch in the background). */
    fun setAutoMirror(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("autoMirror", v).apply()
    }

    fun autoMirror(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("autoMirror", false)
}
