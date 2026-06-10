package com.droiddock.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Screen mirroring AND phone-camera streaming over the app link — no ADB / scrcpy.
 *
 * Encodes H.264 with MediaCodec and streams each access unit to the Mac as a binary
 * frame (kind=3) over the existing WebSocket; the Mac decodes with WebCodecs. The encoder
 * input surface is fed either by a MediaProjection VirtualDisplay (source="screen") or by
 * a Camera2 capture session (source="camera"). Foreground service of type
 * mediaProjection | camera as Android 14+ requires.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var streaming = true // gated off while paused (Auto-mode keep-alive)
    private var configBytes: ByteArray? = null
    private var startedJson: JSONObject? = null
    private var source = "screen"

    // camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var camThread: HandlerThread? = null
    private var curFacing = CameraCharacteristics.LENS_FACING_BACK

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "screen"
        startForegroundNotif(source)
        try {
            if (source == "camera") {
                startCamera(intent?.getIntExtra(EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK)
                    ?: CameraCharacteristics.LENS_FACING_BACK)
            } else {
                val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
                @Suppress("DEPRECATION")
                val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
                    ?: throw IllegalStateException("no projection data")
                startScreen(resultCode, data)
            }
        } catch (e: Exception) {
            ConnectionManager.send(
                JSONObject().put("type", "mirror-error").put("error", e.message ?: "mirror failed")
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Configure + start the encoder, begin draining, and return its input surface. */
    private fun startEncoder(w: Int, h: Int, source: String): Surface {
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
        running = true
        streaming = true
        this.source = source
        drainThread = thread(name = "mirror-drain") { drain(enc, w, h, source) }
        return surface
    }

    private fun startScreen(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("no projection")
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

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
        w = w and 1.inv()
        h = h and 1.inv()

        val surface = startEncoder(w, h, "screen")
        virtualDisplay = proj.createVirtualDisplay(
            "DroidDockMirror", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface, null, null
        )
    }

    private fun startCamera(facing: Int) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Grant the Camera permission to DroidDock")
        }
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: mgr.cameraIdList.firstOrNull() ?: throw IllegalStateException("no camera")
        curFacing = mgr.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.LENS_FACING) ?: facing

        val chars = mgr.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = (map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()).toList()
        val cap = 1280
        // Webcam-style: closest to 16:9, capped, prefer larger — not a weird square.
        val target = 16.0 / 9.0
        val pick = sizes
            .filter { maxOf(it.width, it.height) <= cap && minOf(it.width, it.height) >= 360 }
            .sortedWith(
                compareBy(
                    { kotlin.math.abs(maxOf(it.width, it.height).toDouble() / minOf(it.width, it.height) - target) },
                    { -(it.width.toLong() * it.height) }
                )
            )
            .firstOrNull()
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
        val w = pick.width and 1.inv()
        val h = pick.height and 1.inv()

        val surface = startEncoder(w, h, "camera")
        camThread = HandlerThread("ddcam").also { it.start() }
        val handler = Handler(camThread!!.looper)

        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    .apply { addTarget(surface) }
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            runCatching { session.setRepeatingRequest(req.build(), null, handler) }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            stopSelf()
                        }
                    },
                    handler
                )
            }

            override fun onDisconnected(device: CameraDevice) {
                stopSelf()
            }

            override fun onError(device: CameraDevice, error: Int) {
                ConnectionManager.send(
                    JSONObject().put("type", "mirror-error").put("error", "camera error $error")
                )
                stopSelf()
            }
        }, handler)
    }

    private fun drain(enc: MediaCodec, w: Int, h: Int, source: String) {
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
                        val started = JSONObject().put("type", "mirror-started")
                            .put("width", w).put("height", h)
                            .put("codec", avcCodecString(bytes))
                            .put("source", source)
                            .put(
                                "facing",
                                if (curFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
                            )
                        startedJson = started
                        if (streaming) ConnectionManager.send(started)
                        announced = true
                    }
                } else {
                    val payload =
                        if (isKey && configBytes != null) configBytes!! + bytes else bytes
                    if (streaming) ConnectionManager.sendVideo(if (isKey) 1 else 0, payload)
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

    /** Switch front/back camera in place — no re-consent (permission already granted). */
    fun flip(facing: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            captureSession = null
            cameraDevice = null
            running = false
            runCatching { drainThread?.join(250) }
            drainThread = null
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            configBytes = null
            val old = camThread
            camThread = null
            runCatching { old?.quitSafely() }
            runCatching { startCamera(facing) }.onFailure {
                ConnectionManager.send(
                    JSONObject().put("type", "mirror-error")
                        .put("error", it.message ?: "camera flip failed")
                )
                stopSelf()
            }
        }
    }

    /** Auto-mode keep-alive: a live screen session whose projection we can reuse. */
    fun isScreenAlive(): Boolean = source == "screen" && projection != null && encoder != null

    /** Stop transmitting but keep the projection + encoder alive (no re-consent later). */
    fun pauseStreaming() {
        streaming = false
    }

    /** Resume transmitting: re-announce so the Mac reconfigures, and force a keyframe. */
    fun resumeStreaming() {
        streaming = true
        startedJson?.let { ConnectionManager.send(it) }
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        startedJson = null
        running = false
        runCatching { drainThread?.join(300) }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { camThread?.quitSafely() }
        runCatching { virtualDisplay?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { projection?.stop() }
        captureSession = null
        cameraDevice = null
        camThread = null
        virtualDisplay = null
        encoder = null
        projection = null
        runCatching { ConnectionManager.send(JSONObject().put("type", "mirror-stopped")) }
        super.onDestroy()
    }

    private fun startForegroundNotif(source: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (source == "camera") "Streaming camera to Mac" else "Mirroring your screen to Mac"
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("DroidDock")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (source == "camera") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        @Volatile var instance: MirrorService? = null
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "com.droiddock.app.MIRROR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_FACING = "facing"

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
