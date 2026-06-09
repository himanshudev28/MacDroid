package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/** Pushes basic device + battery info to the Mac so it can render a device card
 *  when no ADB connection exists (Phase 6a, caps:"info"). */
object DeviceInfo {
    private var receiver: BroadcastReceiver? = null
    private var lastPush = 0L

    private fun snapshot(ctx: Context): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (level < 0) {
            val raw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (raw >= 0 && scale > 0) level = raw * 100 / scale
        }
        return JSONObject()
            .put("type", "device-info")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("battery", if (level in 0..100) level else null)
            .put("charging", plugged != 0)
    }

    /** Push now; called on link-up. Also lazily registers the battery watcher. */
    fun push(ctx: Context) {
        lastPush = System.currentTimeMillis()
        ConnectionManager.send(snapshot(ctx))
        ensureReceiver(ctx.applicationContext)
    }

    private fun ensureReceiver(app: Context) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val now = System.currentTimeMillis()
                if (now - lastPush < 30_000) return // debounce per spec
                lastPush = now
                runCatching { ConnectionManager.send(snapshot(app)) }
            }
        }
        runCatching { app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    }
}
