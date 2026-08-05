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
        if (!startInForeground(buildNotification("Waiting for Mac…"))) {
            // The system refused the foreground start (background-start restriction,
            // FGS quota). Going on would leave a service that never called
            // startForeground, which Android kills with an ANR — so bow out. The
            // next time the app is opened, MainActivity starts it again from the
            // foreground, where the start is always allowed.
            stopSelf()
            return
        }

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

    /**
     * Enter the foreground as a **connectedDevice** service.
     *
     * Not dataSync: since Android 15 a dataSync foreground service may only run
     * 6 hours in any 24, after which the system calls [onTimeout] and then kills
     * the process with ForegroundServiceDidNotStopInTimeException — fatal for a
     * link that is meant to stay up all day. connectedDevice ("interacting with
     * an external device") is both untimed and the honest description of what
     * this service does.
     *
     * Every step is fallible on a real phone (OEM policy, background-start
     * restrictions, FGS quota), and an uncaught failure here takes the whole app
     * down, so each tier degrades to the next instead of throwing.
     */
    private fun startInForeground(n: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            return runCatching { startForeground(NOTIF_ID, n) }.isSuccess
        }
        val types = intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        for (type in types) {
            if (runCatching { startForeground(NOTIF_ID, n, type) }.isSuccess) return true
        }
        return runCatching { startForeground(NOTIF_ID, n) }.isSuccess
    }

    /**
     * Android 15+ calls this if the service ever does run under the timed
     * dataSync fallback above. The contract is unforgiving: stop the foreground
     * service now, or the system throws. Dropping the link until the app is
     * next opened beats being killed.
     *
     * Both overloads are overridden deliberately. Android 15 calls the one-arg
     * form; Android 16 calls the two-arg one, whose base implementation does
     * *not* fall through to the other — overriding only one leaves the phone
     * with no handler on half the versions that can time out.
     */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, n) }
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
