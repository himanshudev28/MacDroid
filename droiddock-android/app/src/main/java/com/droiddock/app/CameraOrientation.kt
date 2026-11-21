package com.droiddock.app

import android.content.Context
import android.view.OrientationEventListener
import org.json.JSONObject

/** Pure maths for "which way up is the phone", JVM-testable. */
object CameraOrientation {
    /** Switch quadrant only once the raw angle is this far from the current one (45 would be the exact
     *  boundary; 75 = 30 degrees past it) so holding the phone near a diagonal doesn't flicker. */
    private const val SWITCH_DEG = 75

    private fun dist(a: Int, b: Int): Int {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /** [raw] from OrientationEventListener (0..359, or -1 = unknown/flat). Returns 0/90/180/270. */
    fun quantize(raw: Int, current: Int): Int {
        if (raw < 0 || raw > 359) return current
        if (dist(raw, current) <= SWITCH_DEG) return current
        return ((raw + 45) / 90 * 90) % 360
    }

    /**
     * Degrees CLOCKWISE the Mac must rotate the received frame (raw sensor orientation, as encoded) so it
     * is upright for the way the phone is held. This is Camera2's documented JPEG_ORIENTATION formula
     * (back = sensor + device, front = sensor - device; [device] is the raw OrientationEventListener angle,
     * clockwise device rotation, quantized), so the same number also orients saved photos. Front camera: the Mac applies its horizontal mirror
     * AFTER this rotation (a selfie preview); JPEGs from the front camera are not mirrored.
     */
    fun rotation(sensorOrientation: Int, front: Boolean, device: Int): Int {
        val d = if (front) -device else device
        return ((sensorOrientation + d) % 360 + 360) % 360
    }
}

/** Accelerometer-based device orientation, independent of the auto-rotate lock. Reference-counted by
 *  reason ("stream" while the app streams the camera, "watch" when the Mac asks during a USB camera). */
object OrientationWatcher {
    private var listener: OrientationEventListener? = null
    private val reasons = HashSet<String>()
    @Volatile var device = 0
        private set

    @Synchronized
    fun enable(ctx: Context, reason: String) {
        reasons += reason
        if (listener != null) return
        val l = object : OrientationEventListener(ctx.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                val n = CameraOrientation.quantize(orientation, device)
                if (n != device) { device = n; sendCurrent() }
            }
        }
        if (l.canDetectOrientation()) { l.enable(); listener = l }
    }

    @Synchronized
    fun disable(reason: String) {
        reasons -= reason
        if (reasons.isEmpty()) { listener?.disable(); listener = null }
    }

    /** Sensor orientation / facing of the live camera; a USB camera (no app stream) is assumed to be a back camera at 90. */
    fun rotation(): Int {
        val info = MirrorService.instance?.cameraOrientInfo()
        return CameraOrientation.rotation(info?.first ?: 90, info?.second ?: false, device)
    }

    fun sendCurrent() {
        ConnectionManager.send(
            JSONObject().put("type", "camera-orientation").put("rotation", rotation()).put("device", device)
        )
    }
}
