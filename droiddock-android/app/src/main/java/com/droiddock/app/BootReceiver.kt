package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the Mac link back after a reboot or an app update, but only for a phone that is paired and
 * not paused. An update (in-app or `adb install -r`) kills the process, and without this the link stayed
 * down until the user opened the app. MY_PACKAGE_REPLACED is exempt from background FGS-start limits.
 *
 * Android 15 forbids starting dataSync, camera, mediaPlayback, phoneCall, mediaProjection and
 * microphone foreground services from BOOT_COMPLETED. [BridgeService] runs as connectedDevice,
 * which is allowed; its dataSync fallback simply fails here and the service stops itself.
 * Mirroring (mediaProjection/camera/microphone) is never started from boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        if (!shouldStartOnBoot(Prefs.load(app) != null, Prefs.pausedUntil(app), System.currentTimeMillis())) return
        if (runCatching { BridgeService.start(app) }.isFailure) BridgeRearm.schedule(app)
    }

    companion object {
        /** Paired, and no pause in force (0 = none; Long.MAX_VALUE = until resumed; else a deadline). */
        fun shouldStartOnBoot(paired: Boolean, pausedUntil: Long, now: Long): Boolean =
            paired && (pausedUntil == 0L || pausedUntil <= now)
    }
}
