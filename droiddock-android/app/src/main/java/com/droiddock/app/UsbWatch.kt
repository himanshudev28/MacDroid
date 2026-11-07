package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Wakes the connect loop when a USB cable goes in, so the loopback (USB) route is tried within seconds.
 *  Registered at runtime: these broadcasts cannot be declared in the manifest on modern Android. */
object UsbWatch {
    private const val USB_STATE = "android.hardware.usb.action.USB_STATE"
    @Volatile private var started = false

    /** True for a plug-in event: POWER_CONNECTED, or USB_STATE with connected=true. */
    internal fun isPlugIn(action: String?, usbConnectedExtra: Boolean): Boolean = when (action) {
        Intent.ACTION_POWER_CONNECTED -> true
        USB_STATE -> usbConnectedExtra
        else -> false
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(USB_STATE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (isPlugIn(i.action, i.getBooleanExtra("connected", false))) ConnectionManager.onUsbPlugged()
            }
        }
        // USB_STATE is sticky: the returned intent says whether a cable is already in. Both actions are
        // protected system broadcasts, so NOT_EXPORTED still receives them while other apps can't fake them.
        val sticky = runCatching {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { started = false }.getOrNull()
        if (sticky != null && isPlugIn(sticky.action, sticky.getBooleanExtra("connected", false))) {
            ConnectionManager.onUsbPlugged()
        }
    }
}
