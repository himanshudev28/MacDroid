package com.droiddock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var observing = false
    private var observingPhotos = false

    private val notifySms = Runnable {
        ConnectionManager.send(JSONObject().put("type", "sms-changed"))
    }

    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(notifySms)
            handler.postDelayed(notifySms, 800)
        }
    }

    // Phase 18 — no item IDs are sent; the phone tracks no sync cursor at all.
    // This is just a "something changed" doorbell. The Mac re-lists photos via
    // the existing photos-list request and diffs against its own ledger, so a
    // burst of writes (burst-mode shot, video finalize + thumbnail write, etc.)
    // only needs to be debounced down to one wake-up, not deduplicated by ID.
    private val notifyPhotos = Runnable {
        ConnectionManager.send(JSONObject().put("type", "photos-changed"))
    }

    private val photosObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(notifyPhotos)
            handler.postDelayed(notifyPhotos, 800)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(buildNotification("Waiting for Mac…"))

        scope.launch {
            combine(ConnectionManager.connected, ConnectionManager.pausedUntil) { linked, until ->
                linked to until
            }.collect { (linked, until) ->
                val mac = ConnectionManager.macName.value ?: "Mac"
                val text = when {
                    until != 0L -> "Paused"
                    linked -> "Linked to $mac"
                    else -> "Waiting for Mac…"
                }
                notify(buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ConnectionManager.ensureLoop(applicationContext)
        if (!observing &&
            checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
                observing = true
            }
        }
        if (!observingPhotos) {
            runCatching {
                contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, photosObserver
                )
                contentResolver.registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, photosObserver
                )
                observingPhotos = true
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (observing) runCatching { contentResolver.unregisterContentObserver(smsObserver) }
        if (observingPhotos) runCatching { contentResolver.unregisterContentObserver(photosObserver) }
        handler.removeCallbacks(notifySms)
        handler.removeCallbacks(notifyPhotos)
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("DroidDock")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mac link", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }
    }
}
