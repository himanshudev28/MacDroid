package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        ConnectionManager.ensureLoop(ctx.applicationContext)

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // on API 28+ this broadcast fires twice: first without the number,
                // then (with READ_CALL_LOG) again with it — announce each improvement once
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                if (number.isEmpty() && announced) return
                if (number.isNotEmpty() && number == lastNumber) return
                announced = true
                if (number.isNotEmpty()) lastNumber = number
                ConnectionManager.send(
                    JSONObject()
                        .put("type", "call")
                        .put("state", "ringing")
                        .put("number", number)
                        .put("name", if (number.isNotEmpty()) SmsRepo.name(ctx, number) ?: "" else "")
                )
            }
            TelephonyManager.EXTRA_STATE_IDLE,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (!announced) return
                announced = false
                lastNumber = ""
                ConnectionManager.send(JSONObject().put("type", "call").put("state", "idle"))
            }
        }
    }

    companion object {
        @Volatile private var announced = false
        @Volatile private var lastNumber = ""
    }
}
