package com.droiddock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Screen mirroring over the app link — no ADB / scrcpy / Developer Options.
 *
 * Captures the screen with MediaProjection, encodes H.264 with MediaCodec, and streams
 * each access unit to the Mac as a binary frame (kind=3) over the existing WebSocket.
 * The Mac decodes with WebCodecs and renders to a canvas. Foreground service of type
 * mediaProjection, as Android 14+ requires.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    private var configBytes: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotif()
        try {
            startCapture(resultCode, data)
        } catch (e: Exception) {
            ConnectionManager.send(
                JSONObject().put("type", "mirror-error").put("error", e.message ?: "mirror failed")
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("no projection")
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        // Real screen size, scaled so the long edge is at most CAP (keeps bandwidth sane).
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val dpi = metrics.densityDpi
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val cap = 1280
        val longEdge = maxOf(w, h)
        if (longEdge > cap) {
            val s = cap.toFloat() / longEdge
            w = (w * s).toInt()
            h = (h * s).toInt()
        }
        w = w and 1.inv() // encoders need even dimensions
        h = h and 1.inv()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc

        virtualDisplay = proj.createVirtualDisplay(
            "DroidDockMirror", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface, null, null
        )

        running = true
        drainThread = thread(name = "mirror-drain") { drain(enc, w, h) }
    }

    private fun drain(enc: MediaCodec, w: Int, h: Int) {
        val info = MediaCodec.BufferInfo()
        var announced = false
        while (running) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 100_000)
            } catch (e: Exception) {
                break
            }
            if (idx >= 0) {
                val buf: ByteBuffer? = enc.getOutputBuffer(idx)
                if (buf == null) {
                    enc.releaseOutputBuffer(idx, false)
                    continue
                }
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (isConfig) {
                    configBytes = bytes
                    if (!announced) {
                        ConnectionManager.send(
                            JSONObject().put("type", "mirror-started")
                                .put("width", w).put("height", h)
                                .put("codec", avcCodecString(bytes))
                        )
                        announced = true
                    }
                } else {
                    // Prepend SPS/PPS to keyframes so the decoder always has them.
                    val payload =
                        if (isKey && configBytes != null) configBytes!! + bytes else bytes
                    ConnectionManager.sendVideo(if (isKey) 1 else 0, payload)
                }
                enc.releaseOutputBuffer(idx, false)
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val csd0 = enc.outputFormat.getByteBuffer("csd-0")
                if (csd0 != null && configBytes == null) {
                    val arr = ByteArray(csd0.remaining())
                    csd0.get(arr)
                    configBytes = arr
                }
            }
        }
    }

    /** Build the `avc1.PPCCLL` codec string from the SPS in the codec-config bytes. */
    private fun avcCodecString(config: ByteArray): String {
        var i = 0
        while (i + 4 < config.size) {
            val z = 0.toByte()
            val one = 1.toByte()
            val sc4 = config[i] == z && config[i + 1] == z && config[i + 2] == z && config[i + 3] == one
            val sc3 = config[i] == z && config[i + 1] == z && config[i + 2] == one
            if (sc4 || sc3) {
                val nal = i + if (sc4) 4 else 3
                if ((config[nal].toInt() and 0x1f) == 7 && nal + 3 < config.size) {
                    val profile = config[nal + 1].toInt() and 0xff
                    val constraint = config[nal + 2].toInt() and 0xff
                    val level = config[nal + 3].toInt() and 0xff
                    return "avc1.%02X%02X%02X".format(profile, constraint, level)
                }
                i = nal
            } else i++
        }
        return "avc1.42E01E"
    }

    override fun onDestroy() {
        running = false
        runCatching { drainThread?.join(300) }
        runCatching { virtualDisplay?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        encoder = null
        projection = null
        runCatching { ConnectionManager.send(JSONObject().put("type", "mirror-stopped")) }
        super.onDestroy()
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("DroidDock")
            .setContentText("Mirroring your screen to Mac")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "com.droiddock.app.MIRROR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
