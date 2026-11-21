package com.droiddock.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Pure logic behind the Mac's camera lens picker, kept free of android.hardware so it is
 * JVM-testable: multiplier maths, dedupe, ids and labels. [MirrorService] does the Camera2
 * reads and feeds [Cand]s in; this decides what the Mac is offered.
 */
object CameraLenses {
    /** What Camera.app shows; a computed ratio is snapped to the nearest (log scale) so 0.52 and 0.47 both read "0.5×". */
    val SNAP = doubleArrayOf(0.5, 0.6, 1.0, 2.0, 3.0, 5.0, 10.0)

    /** Diagonal of a 35mm frame; focal * this / sensor diagonal = 35mm-equivalent focal length. */
    private const val FULL_FRAME_DIAG_MM = 43.27

    /** Tolerance when asking "does the logical camera's zoom range reach this multiplier". */
    private const val ZOOM_EPS = 0.02

    /** One camera Camera2 knows about (listed, hidden-probed, or a physical sub-camera). */
    data class Cand(
        val id: String,
        val facing: String,            // "back" | "front"
        val eqFocalMm: Double,         // 35mm-equivalent
        val listed: Boolean,           // in cameraIdList (vs hidden-probed)
        val physical: Boolean,         // a physical sub-camera of a logical one
        val parent: String? = null,    // for a physical camera: the logical camera that contains it
    )

    /** What the Mac is offered. [cameraId] + [zoom] is how the phone realises it. */
    data class Lens(
        val cameraId: String,
        val zoom: Double,
        val facing: String,
        val mult: Double,              // snapped multiplier, drives the label
        val label: String,
        /** Set when the lens is a physical camera streamed through logical [cameraId] (OutputConfiguration.setPhysicalCameraId). */
        val physicalId: String? = null,
    ) {
        val id: String get() = lensId(physicalId ?: cameraId, zoom)
    }

    /** Ratios between ~1.25x and ~1.8x are neither "the same lens" nor a real tele: not offered. */
    private const val AMBIGUOUS_LO = 1.25
    private const val AMBIGUOUS_HI = 1.8

    fun eqFocal(focalMm: Double, sensorW: Double, sensorH: Double): Double =
        focalMm * FULL_FRAME_DIAG_MM / hypot(sensorW, sensorH)

    fun snap(m: Double): Double = SNAP.minByOrNull { abs(ln(m) - ln(it)) } ?: 1.0

    fun fmt(d: Double): String = if (d == Math.rint(d)) d.toInt().toString() else d.toString()

    /** Stable across runs: "<cameraId>@<zoom>", e.g. "0@0.5". */
    fun lensId(cameraId: String, zoom: Double) = "$cameraId@${fmt(zoom)}"

    fun parseId(id: String): Pair<String, Double>? {
        val at = id.lastIndexOf('@')
        if (at <= 0) return null
        val z = id.substring(at + 1).toDoubleOrNull() ?: return null
        if (z <= 0.0) return null
        return id.substring(0, at) to z
    }

    fun label(facing: String, mult: Double): String = when {
        facing == "front" && mult < 1.0 -> "Front wide"
        facing == "front" -> "Front"
        else -> "${fmt(mult)}×"
    }

    /**
     * Only real hardware is offered: no synthesized digital "2x/3x". A tele (>= ~1.8x main) appears when some
     * camera with that focal length exists; whether it actually opens is only known when selected (the
     * service drops it then, and remembers).
     *
     * @param back back-facing candidates, [mainId] among them (the default back camera = first back id in cameraIdList).
     * @param zoomMin/zoomMax CONTROL_ZOOM_RATIO_RANGE of the main camera, or null below API 30 / when absent.
     * @param mainPhysical physical camera ids of the main camera when it is a logical multi-camera.
     */
    fun build(
        mainId: String,
        back: List<Cand>,
        front: List<Cand>,
        zoomMin: Double?,
        zoomMax: Double?,
        mainPhysical: Set<String> = emptySet(),
    ): List<Lens> {
        val out = ArrayList<Lens>()
        val main = back.firstOrNull { it.id == mainId }
        if (main != null && main.eqFocalMm > 0) {
            // Dedupe by snapped multiplier. Listed beats hidden, logical beats physical: the listed
            // logical id is the one Samsung keeps working across lens switches.
            val byMult = LinkedHashMap<Double, Cand>()
            for (c in back.sortedWith(compareBy({ !it.listed }, { it.physical }))) {
                if (c.eqFocalMm <= 0) continue
                val r = c.eqFocalMm / main.eqFocalMm
                if (r > AMBIGUOUS_LO && r < AMBIGUOUS_HI) continue
                val m = snap(r)
                if (m !in byMult) byMult[m] = c
            }
            for ((m, c) in byMult.toSortedMap()) {
                val viaZoom = when {
                    m == 1.0 -> false
                    // A wide lens inside the logical camera: zoom ratio below 1 is a real lens switch.
                    m < 1.0 -> zoomMin != null && m >= zoomMin - ZOOM_EPS
                    // The one zoom-ratio tele allowed: the HAL switches to a real tele physical lens.
                    else -> c.physical && c.id in mainPhysical && zoomMax != null && zoomMax >= m
                }
                out += when {
                    m == 1.0 -> Lens(mainId, 1.0, "back", 1.0, label("back", 1.0))
                    // Seamless: same camera, only CONTROL_ZOOM_RATIO changes.
                    viaZoom -> Lens(mainId, m, "back", m, label("back", m))
                    // A physical camera is streamed through its logical parent; physical ids rarely open alone.
                    c.physical && c.parent != null -> Lens(c.parent, 1.0, "back", m, label("back", m), physicalId = c.id)
                    else -> Lens(c.id, 1.0, "back", m, label("back", m))
                }
            }
            // An ultra-wide that no camera id exposes but the logical zoom range reaches.
            if (out.none { it.mult < 1.0 } && zoomMin != null && zoomMin < 0.9) {
                val m = snap(zoomMin)
                if (m < 1.0) {
                    out += Lens(mainId, m, "back", m, label("back", m))
                    out.sortBy { it.mult }
                }
            }
        } else {
            // No usable focal data: offer the default camera only rather than guessing multipliers.
            (main ?: back.firstOrNull())?.let { out += Lens(it.id, 1.0, "back", 1.0, label("back", 1.0)) }
        }
        val frontMain = front.firstOrNull()
        if (frontMain != null) {
            out += Lens(frontMain.id, 1.0, "front", 1.0, label("front", 1.0))
            for (c in front.drop(1)) {
                if (frontMain.eqFocalMm <= 0 || c.eqFocalMm <= 0) continue
                val m = snap(c.eqFocalMm / frontMain.eqFocalMm)
                if (m < 1.0 && out.none { it.facing == "front" && it.mult == m }) {
                    out += Lens(c.id, 1.0, "front", m, label("front", m))
                }
            }
        }
        return out
    }

    /** Cache format (SharedPreferences, keyed by Build.FINGERPRINT in the service). */
    fun toStoreJson(lenses: List<Lens>): String = JSONArray().also { arr ->
        for (l in lenses) {
            arr.put(
                JSONObject().put("c", l.cameraId).put("z", l.zoom).put("f", l.facing).put("m", l.mult)
                    .put("l", l.label).put("p", l.physicalId ?: JSONObject.NULL)
            )
        }
    }.toString()

    fun fromStoreJson(json: String?): List<Lens> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Lens(
                    o.getString("c"), o.getDouble("z"), o.getString("f"), o.getDouble("m"), o.getString("l"),
                    if (o.isNull("p")) null else o.getString("p"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The `lenses` array of `mirror-started`. */
    fun toJson(lenses: List<Lens>): JSONArray = JSONArray().also { arr ->
        for (l in lenses) {
            arr.put(JSONObject().put("id", l.id).put("facing", l.facing).put("label", l.label).put("zoom", l.zoom))
        }
    }

    /** Camera.app-style name for a phone photo; the Mac saves it as-is. */
    fun photoName(now: Date): String =
        "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".jpg"
}
