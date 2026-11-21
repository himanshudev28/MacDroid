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
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ImageReader
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
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
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
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
    private val videoGate = VideoPauseGate() // Mac viewer hidden (`mirror-pause`): video only, independent of [streaming]
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
    // Request builder, ImageReader and handler are fields (not locals) so a seamless zoom change
    // and a photo can reuse the live session instead of rebuilding it.
    private var camHandler: Handler? = null
    private var camRequest: CaptureRequest.Builder? = null
    private var jpegReader: ImageReader? = null
    private var sensorOrientation = 0
    private var curCameraId: String? = null
    @Volatile private var curZoom = 1.0
    @Volatile private var curLens = ""
    /** Discovered once per service instance; a lens whose camera won't open is dropped from it. */
    @Volatile private var camLenses: List<CameraLenses.Lens> = emptyList()
    /** False when the JPEG surface made session config fail: the Mac then grabs the decoded frame itself. */
    @Volatile private var photoSupported = false
    @Volatile private var photoPending = false
    /** Set while a lens switch that needs a camera restart is in flight, so an open failure can revert. */
    private var pendingLens: PendingLens? = null
    private var restartGen = 0
    @Volatile private var destroyed = false
    private var curPhysical: String? = null
    // Continuous zoom within the active lens: CONTROL_ZOOM_RATIO range (API 30+) or digital crop below that.
    private var zoomMin = 1.0
    private var zoomMaxR = 1.0
    private var activeArray: android.graphics.Rect? = null
    private var lastZoomReply = 0L
    private var zoomReplyPending = false

    private class PendingLens(
        val toLens: String, val fromCameraId: String?, val fromZoom: Double, val fromFacing: Int, val fromPhysical: String?,
    )

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
            // A second start while streaming: tear the current stream down first.
            if (encoder != null || projection != null || cameraDevice != null || virtualDisplay != null) {
                releaseStream()
                startedJson = null
            }
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
        startedAt = android.os.SystemClock.elapsedRealtime()
        streaming = true
        videoGate.reset()
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
                // A superseded projection (restart) stops without ending the service.
                if (projection === proj) stopSelf()
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

    /** `lenses` / `lens` / `photo` on camera `mirror-started`. Older Macs ignore them. */
    private fun putCameraFields(j: JSONObject) {
        j.put(
            "facing",
            if (curFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
        )
        val lenses = camLenses
        if (lenses.isNotEmpty()) {
            j.put("lenses", CameraLenses.toJson(lenses))
            j.put("lens", curLens)
        }
        j.put("rotation", OrientationWatcher.rotation())
        if (zoomMaxR > zoomMin) {
            j.put("zoomRange", JSONArray().put(zoomMin).put(zoomMaxR))
            j.put("zoom", curZoom)
        }
        j.put("photo", if (photoSupported) "phone" else "frame")
    }

    /** drain() announces once per encoder; a seamless zoom change or a photo fallback has
     *  no new encoder, so the updated fields are re-sent explicitly. */
    private fun reannounce() {
        val j = startedJson ?: return
        putCameraFields(j)
        if (streaming) { ConnectionManager.send(j); OrientationWatcher.sendCurrent() }
    }

    /** (sensor orientation, is-front) of the live camera stream, null for a screen stream. */
    fun cameraOrientInfo(): Pair<Int, Boolean>? =
        if (source == "camera" && cameraDevice != null) {
            sensorOrientation to (curFacing == CameraCharacteristics.LENS_FACING_FRONT)
        } else null

    private fun candOf(
        id: String, ch: CameraCharacteristics, listed: Boolean, physical: Boolean, parent: String? = null,
    ): CameraLenses.Cand? {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> return null // external cameras are not a phone lens
        }
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq = if (focal != null && size != null && size.width > 0f && size.height > 0f) {
            CameraLenses.eqFocal(focal.toDouble(), size.width.toDouble(), size.height.toDouble())
        } else 0.0
        return CameraLenses.Cand(id, facing, eq, listed, physical, parent)
    }

    /** Listed ids + hidden ids (Samsung keeps its ultra-wide / tele out of cameraIdList but often lets
     *  them open, so "0".."63" are probed) + the physical sub-cameras of logical ones. The result is
     *  cached per device build (Build.FINGERPRINT) so the probe runs once; lenses that later fail to
     *  open are removed from the cache for good. */
    private fun discoverLenses(mgr: CameraManager): List<CameraLenses.Lens> {
        camLenses.takeIf { it.isNotEmpty() }?.let { return it }
        val prefs = getSharedPreferences("ddcamlenses", Context.MODE_PRIVATE)
        if (prefs.getString("fp", null) == cacheKey()) {
            val cached = CameraLenses.fromStoreJson(prefs.getString("lenses", null))
            if (cached.isNotEmpty()) { camLenses = cached; return cached }
        }
        val listed = mgr.cameraIdList.toList()
        val seen = HashSet<String>()
        val cands = ArrayList<CameraLenses.Cand>()
        var mainId: String? = null
        var zMin: Double? = null
        var zMax: Double? = null
        var mainPhysical: Set<String> = emptySet()
        fun consider(id: String, listedId: Boolean, physical: Boolean, parent: String?) {
            if (!seen.add(id)) return
            val chr = runCatching { mgr.getCameraCharacteristics(id) }
            val ch = chr.getOrNull()
            if (ch == null) {
                // Most hidden ids simply don't exist; the class tells "no such id" from "permission/denied".
                Log.i(TAG, "probe $id listed=$listedId physical=$physical: throws ${chr.exceptionOrNull()?.javaClass?.simpleName}")
                return
            }
            val c = candOf(id, ch, listedId, physical, parent)
            val phys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ch.physicalCameraIds else emptySet()
            Log.i(
                TAG,
                "probe $id listed=$listedId physical=$physical parent=$parent: ok facing=${c?.facing ?: "other"} " +
                    "focal=${ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} " +
                    "sensor=${ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} eq35=${c?.eqFocalMm?.let { "%.1f".format(it) }} " +
                    "physicalIds=$phys zoomRange=" +
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null)
            )
            if (c == null) return
            cands += c
            if (c.facing == "back" && listedId && mainId == null) {
                mainId = id
                mainPhysical = phys
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                        zMin = it.lower.toDouble(); zMax = it.upper.toDouble()
                    }
                }
            }
            for (p in phys) consider(p, false, true, id)
        }
        for (id in listed) consider(id, true, false, null)
        for (i in 0..63) consider(i.toString(), false, false, null)
        val main = mainId ?: return emptyList()
        val lenses = CameraLenses.build(
            main, cands.filter { it.facing == "back" }, cands.filter { it.facing == "front" }, zMin, zMax, mainPhysical,
        )
        camLenses = lenses
        for (l in lenses) {
            Log.i(
                TAG,
                "offer ${l.id} label=${l.label} camera=${l.cameraId} physical=${l.physicalId} zoom=${l.zoom} " +
                    "strategy=${if (l.physicalId != null) "physical-via-logical" else if (l.zoom != 1.0) "zoom-ratio" else "direct-open"}"
            )
        }
        Log.i(TAG, "main=$main zoomRange=[$zMin,$zMax] mainPhysical=$mainPhysical candidates=${cands.map { it.id + "/" + it.facing + "/" + "%.1f".format(it.eqFocalMm) }}")
        saveLenses()
        return lenses
    }

    /** A new build, a new app version or a bumped discovery algorithm must never be masked by an old cache. */
    private fun cacheKey() = "${Build.FINGERPRINT}|${BuildConfig.VERSION_CODE}|$DISCOVERY_VERSION"

    private fun saveLenses() {
        runCatching {
            getSharedPreferences("ddcamlenses", Context.MODE_PRIVATE).edit()
                .putString("fp", cacheKey()).putString("lenses", CameraLenses.toStoreJson(camLenses)).apply()
        }
    }

    /** Largest JPEG the camera makes, bounded so a 200 MP sensor can't exhaust memory. */
    private fun pickJpegSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes.filter { it.width.toLong() * it.height <= 48_000_000L }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun applyZoom(b: CaptureRequest.Builder, zoom: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (zoom != 1.0 || zoomMaxR > 1.0) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.toFloat())
        } else {
            // Below API 30 there is no zoom ratio: crop the active array around its centre instead.
            val r = activeArray ?: return
            val z = zoom.coerceAtLeast(1.0)
            val w = (r.width() / z).toInt(); val h = (r.height() / z).toInt()
            val x = r.left + (r.width() - w) / 2; val y = r.top + (r.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(x, y, x + w, y + h))
        }
    }

    /** `camera-zoom {ratio}`: continuous zoom on the current lens, no restart and no re-announce. The
     *  state reply is rate-limited (~10/s, trailing value always sent) so a slider drag doesn't flood the link. */
    fun setZoom(ratio: Double) {
        Handler(Looper.getMainLooper()).post {
            val req = camRequest; val session = captureSession; val handler = camHandler
            if (source != "camera" || req == null || session == null || handler == null || zoomMaxR <= zoomMin) return@post
            val z = ratio.coerceIn(zoomMin, zoomMaxR)
            try {
                applyZoom(req, z)
                session.setRepeatingRequest(req.build(), null, handler)
                curZoom = z
            } catch (_: Exception) { return@post }
            val now = android.os.SystemClock.uptimeMillis()
            val wait = ZOOM_REPLY_MS - (now - lastZoomReply)
            if (wait <= 0) sendZoomState()
            else if (!zoomReplyPending) {
                zoomReplyPending = true
                Handler(Looper.getMainLooper()).postDelayed({ zoomReplyPending = false; sendZoomState() }, wait)
            }
        }
    }

    private fun sendZoomState() {
        lastZoomReply = android.os.SystemClock.uptimeMillis()
        ConnectionManager.send(JSONObject().put("type", "camera-zoom-state").put("ratio", curZoom))
    }

    private fun startCamera(facing: Int, cameraId: String? = null, zoom: Double? = null, physical: String? = null) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Grant the Camera permission to DroidDock")
        }
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = discoverLenses(mgr)
        val wanted = if (cameraId != null) {
            lenses.firstOrNull { it.cameraId == cameraId && it.zoom == (zoom ?: 1.0) && it.physicalId == physical }
        }
        else lenses.firstOrNull {
            it.mult == 1.0 && it.facing == (if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back")
        }
        val camId = cameraId ?: wanted?.cameraId ?: mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: mgr.cameraIdList.firstOrNull() ?: throw IllegalStateException("no camera")
        val camZoom = zoom ?: wanted?.zoom ?: 1.0
        curCameraId = camId
        curZoom = camZoom
        curPhysical = physical
        curLens = wanted?.id ?: CameraLenses.lensId(physical ?: camId, camZoom)

        val chars = mgr.getCameraCharacteristics(camId)
        curFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: facing
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        zoomMin = 1.0; zoomMaxR = 1.0
        val zr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        if (zr != null) { zoomMin = zr.lower.toDouble(); zoomMaxR = zr.upper.toDouble() }
        else chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { zoomMaxR = it.toDouble() }
        OrientationWatcher.enable(applicationContext, "stream")
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

        // A full-res JPEG target on the same session lets the Mac ask for a real photo. Set before
        // the encoder starts so the first mirror-started already says photo:"phone".
        val jpeg = runCatching { pickJpegSize(map?.getOutputSizes(ImageFormat.JPEG)) }.getOrNull()
            ?.let { runCatching { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }.getOrNull() }
        jpegReader = jpeg
        photoSupported = jpeg != null

        val surface = startEncoder(w, h, "camera")
        val ht = HandlerThread("ddcam").also { it.start() }
        camThread = ht
        val handler = Handler(ht.looper)
        camHandler = handler

        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                Log.i(TAG, "opened camera $camId physical=$physical")
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    .apply { addTarget(surface); applyZoom(this, camZoom) }
                camRequest = req
                configure(device, surface, req, handler, withPhoto = true, physical = physical)
            }

            override fun onDisconnected(device: CameraDevice) {
                onOpenProblem(null)
            }

            override fun onError(device: CameraDevice, error: Int) {
                onOpenProblem("camera error $error")
            }
        }, handler)
    }

    private fun configure(
        device: CameraDevice, surface: Surface, req: CaptureRequest.Builder, handler: Handler, withPhoto: Boolean,
        physical: String? = null,
    ) {
        val reader = jpegReader
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                Log.i(TAG, "session configured lens=$curLens")
                pendingLens = null // the lens opened and configured; nothing to revert to
                runCatching { session.setRepeatingRequest(req.build(), null, handler) }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                runCatching { session.close() }
                if (withPhoto && reader != null) {
                    // Some HALs refuse a video + full-res JPEG combination: stream without the still
                    // target and tell the Mac to grab frames itself.
                    runCatching { reader.close() }
                    jpegReader = null
                    photoSupported = false
                    runCatching { configure(device, surface, req, handler, withPhoto = false, physical = physical) }
                        .onFailure { onOpenProblem("session configure failed") }
                    reannounce()
                } else onOpenProblem("session configure failed")
            }
        }
        if (physical != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Stream from a hidden physical lens through the logical camera that owns it: apps can open
            // the logical id even when the physical one is not openable on its own.
            val outs = ArrayList<OutputConfiguration>()
            outs += OutputConfiguration(surface).also { it.setPhysicalCameraId(physical) }
            if (withPhoto && reader != null) outs += OutputConfiguration(reader.surface)
            @Suppress("DEPRECATION")
            device.createCaptureSessionByOutputConfigurations(outs, cb, handler)
            return
        }
        val targets = if (withPhoto && reader != null) listOf(surface, reader.surface) else listOf(surface)
        @Suppress("DEPRECATION")
        device.createCaptureSession(targets, cb, handler)
    }

    /** The camera failed to open or dropped. During a lens switch that means "that lens doesn't work":
     *  drop it, tell the Mac to revert, and go back to the previous lens rather than ending the stream. */
    private fun onOpenProblem(error: String?) {
        Log.i(TAG, "open/config problem: $error pending=${pendingLens?.toLens}")
        Handler(Looper.getMainLooper()).post {
            val p = pendingLens
            if (p == null) {
                if (error != null) {
                    ConnectionManager.send(JSONObject().put("type", "mirror-error").put("error", error))
                }
                stopSelf()
                return@post
            }
            failLens(p, error ?: "camera disconnected")
        }
    }

    private fun failLens(p: PendingLens, error: String) {
        Log.i(TAG, "lens ${p.toLens} FAILED, dropped: $error")
        pendingLens = null
        // Dropped for good on this phone (remembered in the cache): only lenses that truly open are offered.
        camLenses = camLenses.filterNot { it.id == p.toLens }
        saveLenses()
        ConnectionManager.send(
            JSONObject().put("type", "camera-lens-failed").put("lens", p.toLens).put("error", error)
        )
        restartCamera(p.fromCameraId, p.fromZoom, p.fromFacing, p.fromPhysical)
    }

    private fun drain(enc: MediaCodec, w: Int, h: Int, source: String, mime: String) {
        // Output drain feeds a live stream: don't let it queue behind background work.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
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
                if (videoGate.shouldDrop(streaming, isConfig)) {
                    enc.releaseOutputBuffer(idx, false)
                    continue
                }
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                if (isConfig) {
                    // Config is small and retained (re-sent with every
                    // keyframe), so it keeps its own array.
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
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
                        if (source == "camera") putCameraFields(started)
                        startedJson = started
                        if (streaming) {
                            ConnectionManager.send(started)
                            if (source == "camera") OrientationWatcher.sendCurrent()
                        }
                        announced = true
                    }
                } else if (streaming && !videoGate.paused) {
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
                            buf, // position/limit already set to this frame
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
    fun flip(facing: Int) = restartCamera(null, null, facing, null)

    /** Close camera + encoder and start them again on [cameraId]/[zoom] (null = that facing's 1x). No
     *  `mirror-stopped` is sent: the Mac keeps its window and just gets a fresh `mirror-started`. */
    private fun restartCamera(cameraId: String?, zoom: Double?, facing: Int = curFacing, physical: String? = null) {
        Handler(Looper.getMainLooper()).post {
            // A newer restart supersedes this one; only the latest generation goes on to open a camera.
            val gen = ++restartGen
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { jpegReader?.close() }
            val oldDrain = drainThread
            val oldEnc = encoder
            val old = camThread
            captureSession = null
            cameraDevice = null
            jpegReader = null
            camRequest = null
            camHandler = null
            photoPending = false
            running = false
            drainThread = null
            encoder = null
            configBytes = null
            camThread = null
            // The drain join and codec stop block (~250 ms+); keep them off the main thread. The camera is
            // already closed above, and the new encoder only starts after the old one is fully released,
            // because `running` is shared with the old drain loop.
            thread(name = "ddrestart") {
                runCatching { oldDrain?.join(250) }
                runCatching { oldEnc?.stop() }
                runCatching { oldEnc?.release() }
                runCatching { old?.quitSafely() }
                Handler(Looper.getMainLooper()).post {
                    if (gen != restartGen || destroyed) return@post
                    runCatching { startCamera(facing, cameraId, zoom, physical) }.onFailure {
                        val p = pendingLens
                        if (p != null) failLens(p, it.message ?: "camera failed to open")
                        else {
                            ConnectionManager.send(
                                JSONObject().put("type", "mirror-error")
                                    .put("error", it.message ?: "camera restart failed")
                            )
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    /** `camera-lens`: same camera + new zoom is seamless (Samsung's logical camera swaps the physical
     *  lens itself); a different camera id restarts camera and encoder like a flip. */
    fun selectLens(lensId: String) {
        Handler(Looper.getMainLooper()).post {
            val lens = camLenses.firstOrNull { it.id == lensId }
            if (source != "camera" || lens == null) {
                ConnectionManager.send(
                    JSONObject().put("type", "camera-lens-failed").put("lens", lensId)
                        .put("error", if (lens == null) "unknown lens" else "camera is not running")
                )
                return@post
            }
            if (lensId == curLens) { reannounce(); return@post }
            val req = camRequest
            val session = captureSession
            val handler = camHandler
            if (lens.cameraId == curCameraId && lens.physicalId == null && curPhysical == null && req != null && session != null && handler != null) {
                try {
                    applyZoom(req, lens.zoom)
                    if (lens.zoom == 1.0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        req.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                    }
                    session.setRepeatingRequest(req.build(), null, handler)
                    curZoom = lens.zoom
                    curLens = lens.id
                    reannounce()
                } catch (e: Exception) {
                    ConnectionManager.send(
                        JSONObject().put("type", "camera-lens-failed").put("lens", lensId)
                            .put("error", e.message ?: "zoom change failed")
                    )
                }
                return@post
            }
            val facing = if (lens.facing == "front") CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            Log.i(TAG, "verify-open lens=$lensId camera=${lens.cameraId} physical=${lens.physicalId} zoom=${lens.zoom}")
            pendingLens = PendingLens(lensId, curCameraId, curZoom, curFacing, curPhysical)
            restartCamera(lens.cameraId, lens.zoom, facing, lens.physicalId)
        }
    }

    private fun photoError(msg: String) {
        photoPending = false
        clearPhotoTimeout()
        ConnectionManager.send(JSONObject().put("type", "camera-photo-error").put("error", msg))
    }

    /** `camera-photo`: one full-resolution still on the live session, same zoom, sent as base64 JSON. */
    fun takePhoto() {
        // Main looper: the same thread restartCamera tears the session down on, so this never sees it half-torn-down.
        Handler(Looper.getMainLooper()).post { takePhotoOnMain() }
    }

    private var photoTimeout: Runnable? = null

    private fun clearPhotoTimeout() {
        photoTimeout?.let { camHandler?.removeCallbacks(it) }
        photoTimeout = null
    }

    private fun takePhotoOnMain() {
        val handler = camHandler
        val device = cameraDevice
        val session = captureSession
        val reader = jpegReader
        if (source != "camera" || handler == null || device == null || session == null || reader == null || !photoSupported) {
            photoError("phone photo unavailable")
            return
        }
        if (photoPending) { photoError("a photo is already being taken"); return }
        photoPending = true
        try {
            // Anything left in the reader is from an earlier, abandoned shot.
            runCatching { reader.acquireLatestImage()?.close() }
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val b = img.planes[0].buffer
                    ByteArray(b.remaining()).also { b.get(it) }
                } finally { img.close() }
                if (!photoPending) return@setOnImageAvailableListener
                photoPending = false
                clearPhotoTimeout()
                // Re-encoding and base64 are heavy; keep them off the camera thread so the preview doesn't stall.
                thread(name = "ddphoto") { sendPhoto(bytes) }
            }, handler)
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyZoom(this, curZoom)
                set(
                    CaptureRequest.JPEG_ORIENTATION,
                    CameraOrientation.rotation(
                        sensorOrientation, curFacing == CameraCharacteristics.LENS_FACING_FRONT, OrientationWatcher.device
                    )
                )
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            session.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    if (photoPending) photoError("capture failed")
                }
            }, handler)
            clearPhotoTimeout()
            // Kept in a field so a finished shot cancels it; otherwise it would fire during the next shot.
            val t = Runnable { if (photoPending) photoError("photo timed out") }
            photoTimeout = t
            handler.postDelayed(t, PHOTO_TIMEOUT_MS)
        } catch (e: Exception) {
            photoError(e.message ?: "photo failed")
        }
    }

    private fun sendPhoto(raw: ByteArray) {
        val jpeg = fitJpeg(raw)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        // OkHttp closes the socket if its outbound queue passes 16 MiB, and live video shares that
        // queue — wait for room instead of risking the whole link.
        var tries = 0
        while (ConnectionManager.outboundQueueBytes() + b64.length > PHOTO_QUEUE_LIMIT && tries++ < 50) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        val ok = ConnectionManager.send(
            JSONObject().put("type", "camera-photo-data")
                .put("name", CameraLenses.photoName(Date())).put("data", b64)
        )
        if (!ok) photoError("could not send photo")
    }

    /** A JPEG over [MAX_PHOTO_BYTES] would make a text frame near the 16 MiB socket queue cap, so it is
     *  re-encoded smaller (EXIF rotation baked in, since re-encoding drops the tag). Normal 12 MP stills pass untouched. */
    private fun fitJpeg(src: ByteArray): ByteArray {
        if (src.size <= MAX_PHOTO_BYTES) return src
        return runCatching {
            val rot = when (ExifInterface(ByteArrayInputStream(src))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            var sample = 1
            while (sample <= 4) {
                var bmp: Bitmap = BitmapFactory.decodeByteArray(
                    src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: break
                if (rot != 0f) {
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                }
                for (q in intArrayOf(90, 80, 70, 55)) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                    if (out.size() <= MAX_PHOTO_BYTES) return@runCatching out.toByteArray()
                }
                sample *= 2
            }
            src
        }.getOrDefault(src)
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
        videoGate.reset()
        audio?.resumeStreaming()
        startedJson?.let { ConnectionManager.send(it) }
        droppingVideo = false
        encoder?.let { requestSyncFrame(it) }
    }

    /**
     * Viewer hidden on the Mac (`mirror-pause`): stop sending *video* only. The
     * encoder and projection stay alive (no re-consent) and phone audio keeps
     * playing — unlike [pauseStreaming], which is the Auto-mode keep-alive and
     * silences both.
     */
    fun pauseVideo() {
        videoGate.pause(encoder != null)
    }

    /** Viewer visible again (`mirror-resume`): resume video and force a keyframe
     *  (config is prepended to it) so the Mac decoder can restart immediately. */
    fun resumeVideo() {
        if (!videoGate.resume(encoder != null)) return
        droppingVideo = false
        encoder?.let { requestSyncFrame(it) }
    }

    /** Release everything a running stream holds. Used by [onDestroy] and before a
     *  second start (camera-start / fresh consent while already streaming), so the old
     *  encoder, VirtualDisplay, camera and projection are never silently overwritten. */
    private fun releaseStream(async: Boolean = false) {
        running = false
        OrientationWatcher.disable("stream")
        // Joins (audio pump, drain) and the codec stop can block ~600 ms. onDestroy runs on the main
        // thread, so there they go to a short-lived thread; a second start needs them finished first.
        val oldAudio = audio
        val oldDrain = drainThread
        val oldEnc = encoder
        audio = null
        drainThread = null
        encoder = null
        // Camera and display go first (here, on the caller's thread) so the encoder is never
        // released while something is still writing to its surface.
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { camThread?.quitSafely() }
        runCatching { jpegReader?.close() }
        jpegReader = null
        camRequest = null
        camHandler = null
        pendingLens = null
        photoPending = false
        runCatching { virtualDisplay?.release() }
        val oldProj = projection
        // Clear first: the projection's onStop callback must not stopSelf() a service that is restarting.
        projection = null
        runCatching { oldProj?.stop() }
        captureSession = null
        cameraDevice = null
        camThread = null
        virtualDisplay = null
        configBytes = null
        val blocking = {
            runCatching { oldAudio?.stop() }
            runCatching { oldDrain?.join(300) }
            runCatching { oldEnc?.stop() }
            runCatching { oldEnc?.release() }
        }
        if (async) thread(name = "ddrelease") { blocking() } else blocking()
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; startedAt = 0L }
        DockWidget.refresh(applicationContext)
        startedJson = null
        destroyed = true
        releaseStream(async = true)
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
        /** Raw JPEG ceiling: base64 grows it by a third, and OkHttp's outbound queue caps at 16 MiB (shared with video). */
        private const val MAX_PHOTO_BYTES = 7_500_000
        private const val PHOTO_QUEUE_LIMIT = 15_000_000L
        private const val PHOTO_TIMEOUT_MS = 10_000L
        private const val ZOOM_REPLY_MS = 100L
        /** Bump when discovery logic changes so cached lens lists are rebuilt. */
        private const val DISCOVERY_VERSION = 2
        private const val TAG = "DDCam"

        @Volatile var instance: MirrorService? = null

        /** [android.os.SystemClock.elapsedRealtime] when the encoder started; 0 while no stream is running. UI read-only. */
        @Volatile var startedAt: Long = 0L

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
