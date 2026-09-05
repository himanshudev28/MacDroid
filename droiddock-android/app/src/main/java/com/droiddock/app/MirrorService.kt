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
import android.media.MediaCodecList
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
    /** True while [drain] is skipping predicted frames because the socket is
     *  backed up. Latched so the "give me a keyframe" request is made once per
     *  gap rather than once per dropped frame. */
    @Volatile private var droppingVideo = false
    private var configBytes: ByteArray? = null
    private var startedJson: JSONObject? = null
    private var source = "screen"
    /** Non-null only while phone audio is being captured (screen source, opt-in). */
    @Volatile private var audio: AudioCapture? = null

    // camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var camThread: HandlerThread? = null
    private var curFacing = CameraCharacteristics.LENS_FACING_BACK

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // The home-screen widget's Mirror button doubles as Stop, and `instance`
        // is the only signal that a stream is up.
        DockWidget.refresh(applicationContext)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "screen"
        try {
            // Inside the try on purpose: startForeground throws for real reasons
            // (ForegroundServiceStartNotAllowedException when the start races the
            // app going to background, SecurityException when the OS decides the
            // projection consent no longer backs a mediaProjection service), and
            // it used to throw straight out of onStartCommand and kill the app.
            startForegroundNotif(source)
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
            runCatching {
                ConnectionManager.send(
                    JSONObject().put("type", "mirror-error").put("error", e.message ?: "mirror failed")
                )
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Configure + start the encoder, begin draining, and return its input surface. */
    private fun startEncoder(w: Int, h: Int, source: String): Surface {
        // HEVC is opt-in and *degrades* rather than failing. An HEVC encoder is
        // not guaranteed to exist on every device, and a phone without one
        // should still mirror in H.264 rather than show the Mac a black window.
        val mime = if (reqCodec == "h265" && hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, reqBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, reqFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            // Realtime priority. Without it the encoder is scheduled as a
            // best-effort transcode, which is the wrong trade for a stream
            // somebody is watching and tapping on.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Tell the encoder the rate we actually want, so it doesn't pace
            // itself for offline encoding.
            setInteger(MediaFormat.KEY_OPERATING_RATE, reqFps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // B-frames are encoded out of order, so each one adds a frame
                // of latency at both ends before anything can be displayed.
                // Off is the default on most encoders — but only most.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // "Emit each frame as soon as it is encoded" — the encoder
                // otherwise holds a small queue of frames for rate control,
                // which is latency you can see when you drag something.
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        val enc = MediaCodec.createEncoderByType(mime)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        running = true
        streaming = true
        this.source = source
        drainThread = thread(name = "mirror-drain") { drain(enc, w, h, source, mime) }
        return surface
    }

    /** Is there an encoder for this MIME type at all? Asked before requesting
     *  HEVC, because `createEncoderByType` throws rather than degrading. */
    private fun hasEncoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

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

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi: Int
        var w: Int
        var h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
            dpi = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            dpi = metrics.densityDpi
            w = metrics.widthPixels
            h = metrics.heightPixels
        }
        val cap = if (reqMaxSize > 0) reqMaxSize else 1280
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
        startAudio(proj)
    }

    /**
     * Begin capturing playback audio on the same projection, when the Mac asked
     * for it. Deliberately never fatal to the mirror: audio that cannot start
     * reports itself and the screen keeps streaming.
     */
    private fun startAudio(proj: MediaProjection) {
        if (!reqAudio) return
        if (Build.VERSION.SDK_INT < 29) {
            audioErr("phone audio needs Android 10 or newer")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The consent activity asks for this before the projection; landing
            // here means it was denied, and silence with no explanation would
            // read to the user as a broken feature.
            audioErr("microphone permission denied — needed to capture playback")
            return
        }
        val cap = AudioCapture { audio = null }
        audio = if (cap.start(proj)) cap else null
    }

    private fun audioErr(msg: String) {
        runCatching {
            ConnectionManager.send(JSONObject().put("type", "audio-error").put("error", msg))
        }
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
        val cap = if (reqMaxSize > 0) reqMaxSize else 1280
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
        val ht = HandlerThread("ddcam").also { it.start() }
        camThread = ht
        val handler = Handler(ht.looper)

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

    private fun drain(enc: MediaCodec, w: Int, h: Int, source: String, mime: String) {
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
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                // Paused (`pauseStreaming`) keeps the projection and encoder
                // alive so resuming needs no fresh consent — but there is no
                // reason to copy a frame out of the codec buffer just to drop
                // it. Config packets are still read: they are what the Mac
                // needs to reconfigure its decoder on resume.
                if (!streaming && !isConfig) {
                    enc.releaseOutputBuffer(idx, false)
                    continue
                }
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                if (isConfig) {
                    configBytes = bytes
                    if (!announced) {
                        val started = JSONObject().put("type", "mirror-started")
                            .put("width", w).put("height", h)
                            .put("codec", codecString(mime, bytes))
                            .put("source", source)
                            .put(
                                "facing",
                                if (curFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
                            )
                        startedJson = started
                        if (streaming) ConnectionManager.send(started)
                        announced = true
                    }
                } else if (streaming) {
                    // Backpressure. The video path used to push into OkHttp's
                    // outbound queue unconditionally, so a Wi-Fi dip meant
                    // frames piling up in RAM and the Mac rendering seconds
                    // behind reality — with the queue never draining, because
                    // the encoder keeps producing. Past a threshold we drop
                    // predicted frames instead, then ask for a keyframe so the
                    // Mac has a clean resync point: an inter-frame stream can't
                    // survive a gap, so dropping without that would show
                    // smeared garbage until the next scheduled IDR.
                    val backlogged =
                        ConnectionManager.outboundQueueBytes() > VIDEO_BACKLOG_BYTES
                    if (backlogged && !isKey) {
                        if (!droppingVideo) {
                            droppingVideo = true
                            requestSyncFrame(enc)
                        }
                    } else {
                        // Cleared only once the queue has actually drained —
                        // NOT merely because this frame was a keyframe and got
                        // sent. Clearing on the keyframe would re-arm the
                        // latch for the very next predicted frame, so a link
                        // that stays congested would be asked for a fresh IDR
                        // every few frames — the most expensive possible
                        // response to not having enough bandwidth.
                        if (!backlogged) droppingVideo = false
                        // One allocation, not three. This used to be
                        // `configBytes!! + bytes` (a whole new array on every
                        // keyframe) handed to `sendVideo`, which allocated
                        // another to prepend its two header bytes.
                        ConnectionManager.sendVideoParts(
                            if (isKey) 1 else 0,
                            if (isKey) configBytes else null,
                            bytes,
                        )
                    }
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

    /** The RFC 6381 codec string WebCodecs needs for this stream's config bytes. */
    private fun codecString(mime: String, config: ByteArray): String =
        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) HevcCodec.codecString(config)
        else avcCodecString(config)

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

    /** Ask the encoder for an IDR now. Used to resume after a dropped-frame
     *  gap and when unpausing — both are cases where the Mac's decoder has no
     *  usable reference and every predicted frame would decode to garbage. */
    private fun requestSyncFrame(enc: MediaCodec) {
        runCatching {
            enc.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    /** Auto-mode keep-alive: a live screen session whose projection we can reuse. */
    fun isScreenAlive(): Boolean = source == "screen" && projection != null && encoder != null

    /** Stop transmitting but keep the projection + encoder alive (no re-consent later). */
    fun pauseStreaming() {
        streaming = false
        audio?.pauseStreaming()
    }

    /** Resume transmitting: re-announce so the Mac reconfigures, and force a keyframe. */
    fun resumeStreaming() {
        streaming = true
        audio?.resumeStreaming()
        startedJson?.let { ConnectionManager.send(it) }
        droppingVideo = false
        encoder?.let { requestSyncFrame(it) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        DockWidget.refresh(applicationContext)
        startedJson = null
        running = false
        runCatching { audio?.stop() }
        audio = null
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
            // Playback capture goes through AudioRecord, so Android 14+ wants
            // the microphone type declared even though no microphone is opened.
            // Asking for it without RECORD_AUDIO actually granted throws, hence
            // the permission check *and* the fallback: a service that refuses to
            // start would take the whole mirror down over an optional extra.
            val wantsAudio = source != "camera" && reqAudio &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            try {
                startForeground(
                    NOTIF_ID, n,
                    if (wantsAudio) type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else type
                )
            } catch (e: Exception) {
                startForeground(NOTIF_ID, n, type)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        /**
         * Seconds between forced keyframes.
         *
         * This was 1 — a full IDR every single second, forever, even on a
         * completely static screen. Keyframes are many times the size of a
         * predicted frame, so at the default bitrate they dominated both the
         * encode cost and the bytes on the wire, and each one arrived as a
         * transmission spike you could see as a hitch.
         *
         * A longer interval is safe here in a way it would not be over UDP:
         * this is a WebSocket over TCP, so frames are never simply lost, and
         * the two cases that genuinely need a fresh keyframe — a decoder
         * joining mid-stream, and resyncing after [droppingVideo] — both ask
         * for one explicitly via [requestSyncFrame].
         */
        private const val I_FRAME_SECONDS = 3

        /**
         * How many bytes may sit unsent in the socket's outbound queue before
         * the video path starts dropping predicted frames. Roughly a second of
         * video at the default bitrate: past that, the Mac is showing the past
         * and catching up matters more than completeness.
         */
        private const val VIDEO_BACKLOG_BYTES = 1_500_000L

        @Volatile var instance: MirrorService? = null

        /**
         * Encoder settings the Mac asked for on `mirror-start` / `camera-start`.
         *
         * Statics rather than Intent extras on purpose: the request arrives in
         * `ConnectionManager` and the encoder is configured three hops later
         * (permission activity → service → `startEncoder`), so threading them
         * through would mean changing four signatures for three integers that
         * are read exactly once, at encoder configure time.
         *
         * The defaults are the values that used to be hardcoded here, so a Mac
         * that never sends them behaves precisely as before.
         */
        @Volatile var reqBitrate = 6_000_000
        @Volatile var reqFps = 30
        /** Longest-edge cap in px; 0 = use the built-in 1280 ceiling. */
        @Volatile var reqMaxSize = 0

        /** "h264" (always safe) or "h265". Honoured only if the device has an
         *  HEVC encoder; [startEncoder] falls back rather than failing. */
        @Volatile var reqCodec = "h264"

        /** Stream phone playback audio alongside the screen. Screen source only —
         *  playback capture rides the MediaProjection, which the camera path has
         *  no reason to hold. */
        @Volatile var reqAudio = false

        /** Unknown values pin to h264: an unrecognised codec must degrade to the
         *  one every decoder handles, never to a stream nothing can play. */
        fun setCodec(codec: String?) {
            reqCodec = if (codec == "h265" || codec == "hevc") "h265" else "h264"
        }

        fun setAudio(on: Boolean) {
            reqAudio = on
        }

        /** Clamped at the edge, so nothing downstream has to re-check. */
        fun setQuality(bitrate: Int, fps: Int, maxSize: Int) {
            if (bitrate > 0) reqBitrate = bitrate.coerceIn(1_000_000, 50_000_000)
            if (fps > 0) reqFps = fps.coerceIn(15, 120)
            reqMaxSize = maxSize.coerceIn(0, 4096)
        }
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "com.droiddock.app.MIRROR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_FACING = "facing"

        fun stop(ctx: Context) {
            // startService is illegal from the background on Android 8+, which is
            // exactly where a Mac-side "stop mirroring" arrives. Falling back to
            // stopService reaches the same place — onDestroy — so the phone still
            // drops its screen-share notification instead of casting forever.
            runCatching {
                ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                runCatching { ctx.stopService(Intent(ctx, MirrorService::class.java)) }
            }
        }
    }
}
