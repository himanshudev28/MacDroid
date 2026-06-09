package com.droiddock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * Transparent helper that shows the system "Start screen recording?" consent dialog and,
 * on approval, hands the projection token to [MirrorService]. Launched when the Mac asks
 * to mirror (or from a phone-side action). No ADB / Developer Options involved.
 */
class MirrorPermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, res.resultCode)
                .putExtra(MirrorService.EXTRA_DATA, res.data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            ConnectionManager.send(
                JSONObject().put("type", "mirror-error").put("error", "screen capture not allowed")
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { launcher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { finish() }
    }

    companion object {
        private const val CHANNEL = "mirror-req"
        private const val NOTIF_ID = 8

        /** Launch the consent flow directly (only allowed when our app is foreground). */
        fun start(ctx: Context) {
            val i = Intent(ctx, MirrorPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }

        /**
         * Mac asked to mirror. Android blocks launching an activity from the background,
         * so post a tappable heads-up prompt; tapping it (a user gesture) opens the
         * consent dialog. Works whether or not the phone app is in the foreground.
         */
        fun request(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mirror requests", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MirrorPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Mirror your screen to Mac?")
                .setContentText("Tap to allow screen mirroring")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}
