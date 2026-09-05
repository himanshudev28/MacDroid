package com.droiddock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Find the phone: make it loud, from the Mac.
 *
 * # Why the alarm stream, and not the notification one
 *
 * The one time you need this is the one time the phone is on silent and down
 * the back of a sofa. `USAGE_ALARM` on `STREAM_ALARM` is the only route that
 * survives that: Android's silent and vibrate ringer modes deliberately do not
 * silence alarms, which is exactly the property being borrowed here. A
 * notification sound would be muted precisely when it mattered.
 *
 * The stream volume is raised to maximum for the duration and **restored on
 * stop**, including when the timeout stops it, so this never quietly leaves the
 * user's alarm volume pinned at full.
 *
 * # Why it stops on its own
 *
 * [DEFAULT_SECONDS] is a hard ceiling. A remote control that can start an
 * unstoppable noise on a device in someone else's bag is a worse thing to own
 * than the feature is a good one — and the Mac that started it may well have
 * walked out of Wi-Fi range before you find the phone. Three independent stops
 * exist: the Mac, the notification's own action, and the timer.
 */
object Ringer {

    private const val CHANNEL = "ring"
    private const val NOTIF_ID = 44
    const val ACTION_STOP = "com.droiddock.app.RING_STOP"

    /** How long a ring lasts if nothing stops it. Long enough to search a room. */
    const val DEFAULT_SECONDS = 60

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var priorVolume: Int? = null
    private var stopAt: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val ringing: Boolean get() = player != null

    @Synchronized
    fun start(ctx: Context, seconds: Int = DEFAULT_SECONDS): Result<Unit> {
        if (ringing) return Result.success(Unit) // already loud; not an error
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Result.failure(IllegalStateException("No audio service on this device"))

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return Result.failure(IllegalStateException("This phone has no alarm or ringtone sound set"))

        return runCatching {
            priorVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0, // no UI: this is not a volume change the user asked to see
            )

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }

            vibrate(app)
            notify(app)

            stopAt = Runnable { stop(app) }.also {
                handler.postDelayed(it, seconds.coerceIn(5, 300) * 1000L)
            }
        }.onFailure {
            // Partial start: put the volume back rather than leaving it at max
            // with nothing playing.
            restoreVolume(am)
            player?.runCatching { release() }
            player = null
        }.map { }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        stopAt?.let { handler.removeCallbacks(it) }
        stopAt = null

        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null

        (app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { restoreVolume(it) }
        runCatching { vibrator(app)?.cancel() }
        runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
    }

    private fun restoreVolume(am: AudioManager) {
        priorVolume?.let { runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        priorVolume = null
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun vibrate(ctx: Context) = runCatching {
        val pattern = longArrayOf(0, 600, 400)
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    /**
     * A notification with a Stop button, so the person holding the phone can end
     * it without unlocking anything or finding the Mac. Ongoing, so it can't be
     * swiped away while the noise continues.
     */
    private fun notify(ctx: Context) = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH)
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_STOP).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle("DroidDock is ringing this phone")
                .setContentText("Started from your Mac.")
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setContentIntent(stop)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        )
    }
}

/** The notification's Stop button, and the only reason this receiver exists. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Ringer.ACTION_STOP) Ringer.stop(ctx)
    }
}
