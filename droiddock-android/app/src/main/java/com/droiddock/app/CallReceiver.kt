package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * Turns the system's `PHONE_STATE` broadcast into the `call` messages the Mac
 * renders as its call overlay.
 *
 * # Why OFFHOOK became its own state
 *
 * This used to fold `OFFHOOK` in with `IDLE` and report both as `"idle"`, back
 * when the Mac could only *watch* a call over Wi-Fi: an answered call and an
 * ended one were equally "nothing more to show". Now that [CallControl] can
 * hang up, mute and change the route, the Mac needs an overlay for the call it
 * is holding — so `OFFHOOK` reports `"active"` and only `IDLE` clears.
 *
 * # Why calls the Mac knows nothing about stay silent
 *
 * The `announced` gate is deliberately kept. Reporting every `OFFHOOK` would
 * pop an overlay on the Mac each time the phone is used as a phone, in the
 * user's hand, which is noise rather than a feature. A call is announced when
 * it rings, or when the Mac itself dialled it ([armOutgoing]) — the two cases
 * where the Mac is already showing something and needs it to stay truthful.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = ctx.applicationContext
        ConnectionManager.ensureLoop(app)

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // on API 28+ this broadcast fires twice: first without the number,
                // then (with READ_CALL_LOG) again with it — announce each improvement once
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                if (number.isEmpty() && announced) return
                if (number.isNotEmpty() && number == lastNumber) return
                announced = true
                lastState = TelephonyManager.EXTRA_STATE_RINGING
                if (number.isNotEmpty()) {
                    lastNumber = number
                    lastName = SmsRepo.name(app, number) ?: ""
                }
                ConnectionManager.send(payload(app, "ringing"))
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Dual-SIM devices and some OEM stacks repeat OFFHOOK; the Mac
                // would restart its call timer on every repeat.
                if (!announced || lastState == TelephonyManager.EXTRA_STATE_OFFHOOK) return
                lastState = TelephonyManager.EXTRA_STATE_OFFHOOK
                ConnectionManager.send(payload(app, "active"))
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (!announced) return
                announced = false
                lastState = TelephonyManager.EXTRA_STATE_IDLE
                lastNumber = ""
                lastName = ""
                ConnectionManager.send(JSONObject().put("type", "call").put("state", "idle"))
            }
        }
    }

    companion object {
        @Volatile private var announced = false
        @Volatile private var lastNumber = ""
        @Volatile private var lastName = ""
        @Volatile private var lastState = ""

        /**
         * Tell this receiver that the Mac just placed a call, so the `OFFHOOK`
         * and `IDLE` that follow are reported rather than dropped as "a call
         * the Mac never knew about".
         *
         * Without this the Mac's outbound-dial overlay had no way to learn the
         * call had connected or ended, and sat on "Calling…" until dismissed
         * by hand.
         */
        fun armOutgoing(ctx: Context, number: String) {
            announced = true
            lastState = ""
            lastNumber = number
            lastName = runCatching { SmsRepo.name(ctx, number) }.getOrNull().orEmpty()
        }

        /**
         * The `call` message, carrying what the Mac needs to draw the overlay
         * *and* decide which buttons on it are real.
         *
         * The capability flags are recomputed on every push rather than read
         * from the handshake's `caps`. Granting the Calls permission is a thing
         * that happens mid-session — often *because* the Mac just said a button
         * needed it — and a handshake-time answer would stay wrong until the
         * link happened to drop.
         */
        private fun payload(ctx: Context, state: String): JSONObject =
            JSONObject()
                .put("type", "call")
                .put("state", state)
                .put("number", lastNumber)
                .put("name", lastName)
                .put("canAnswer", CallControl.canAnswer(ctx))
                .put("canEnd", CallControl.canEnd(ctx))
                .put("canAudio", CallControl.canAudio(ctx))
                .apply {
                    // Only meaningful once there is a call to route; on a ringing
                    // call these read whatever the idle device happens to say.
                    if (state == "active") {
                        CallControl.isMuted(ctx)?.let { put("muted", it) }
                        CallControl.isSpeaker(ctx)?.let { put("speaker", it) }
                    }
                }
    }
}
