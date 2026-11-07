package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the Mac link back after a reboot, but only for a phone that is paired and not paused.
 *
 * Android 15 forbids starting dataSync, camera, mediaPlayback, phoneCall, mediaProjection and
 * microphone foreground services from BOOT_COMPLETED. [BridgeService] runs as connectedDevice,
 * which is allowed; its dataSync fallback simply fails here and the service stops itself.
 * Mirroring (mediaProjection/camera/microphone) is never started from boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = ctx.applicationContext
        if (!shouldStartOnBoot(Prefs.load(app) != null, Prefs.pausedUntil(app), System.currentTimeMillis())) return
        runCatching { BridgeService.start(app) }
    }

    companion object {
        /** Paired, and no pause in force (0 = none; Long.MAX_VALUE = until resumed; else a deadline). */
        fun shouldStartOnBoot(paired: Boolean, pausedUntil: Long, now: Long): Boolean =
            paired && (pausedUntil == 0L || pausedUntil <= now)
    }
}
