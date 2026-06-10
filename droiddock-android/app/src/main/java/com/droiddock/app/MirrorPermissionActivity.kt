package com.droiddock.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * Transparent helper that obtains consent for streaming to the Mac:
 *  - source="screen": the system "Start screen recording?" dialog (MediaProjection)
 *  - source="camera": the runtime Camera permission
 * then hands off to [MirrorService]. Launched via a tappable notification (Android blocks
 * background activity launches), so it works whether or not the phone app is foreground.
 */
class MirrorPermissionActivity : ComponentActivity() {

    private var facing = CameraCharacteristics.LENS_FACING_BACK

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            startFgs(
                Intent(this, MirrorService::class.java)
                    .putExtra(MirrorService.EXTRA_CODE, res.resultCode)
                    .putExtra(MirrorService.EXTRA_DATA, res.data)
            )
        } else {
            err("screen capture not allowed")
        }
        finish()
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraService() else err("camera permission denied")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(MirrorService.EXTRA_SOURCE) ?: "screen"
        facing = intent.getIntExtra(MirrorService.EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK)
        if (source == "camera") {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraService()
                finish()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
                .onFailure { finish() }
        }
    }

    private fun startCameraService() {
        startFgs(
            Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_SOURCE, "camera")
                .putExtra(MirrorService.EXTRA_FACING, facing)
        )
    }

    private fun startFgs(i: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun err(msg: String) {
        ConnectionManager.send(JSONObject().put("type", "mirror-error").put("error", msg))
    }

    companion object {
        private const val CHANNEL = "mirror-req"
        private const val NOTIF_ID = 8

        /** Launch the consent flow directly (allowed in the background only with the
         *  "Display over other apps" permission — used by Auto mode). */
        fun start(ctx: Context, source: String, facing: Int = CameraCharacteristics.LENS_FACING_BACK) {
            val i = Intent(ctx, MirrorPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MirrorService.EXTRA_SOURCE, source)
                .putExtra(MirrorService.EXTRA_FACING, facing)
            runCatching { ctx.startActivity(i) }
        }

        /**
         * Mac asked to stream. Android blocks launching an activity from the background, so
         * post a tappable heads-up prompt; tapping it opens the consent flow. [source] is
         * "screen" or "camera".
         */
        fun request(ctx: Context, source: String, facing: Int = CameraCharacteristics.LENS_FACING_BACK) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mirror requests", NotificationManager.IMPORTANCE_HIGH)
            )
            val target = Intent(ctx, MirrorPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MirrorService.EXTRA_SOURCE, source)
                .putExtra(MirrorService.EXTRA_FACING, facing)
            val pi = PendingIntent.getActivity(
                ctx, if (source == "camera") 2 else 1, target,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val title = if (source == "camera") "Stream camera to Mac?" else "Mirror your screen to Mac?"
            val body = if (source == "camera") "Tap to allow the camera" else "Tap to allow screen mirroring"
            val n = Notification.Builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}
