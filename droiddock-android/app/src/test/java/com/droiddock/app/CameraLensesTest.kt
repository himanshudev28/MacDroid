package com.droiddock.app

import com.droiddock.app.CameraLenses.Cand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CameraLensesTest {
    private fun back(id: String, eq: Double, listed: Boolean = true, physical: Boolean = false, parent: String? = null) =
        Cand(id, "back", eq, listed, physical, parent)

    @Test fun snapsToNearestOnLogScale() {
        assertEquals(0.5, CameraLenses.snap(0.47), 0.0)
        assertEquals(0.5, CameraLenses.snap(0.54), 0.0)
        assertEquals(1.0, CameraLenses.snap(1.08), 0.0)
        assertEquals(3.0, CameraLenses.snap(2.8), 0.0)
        assertEquals(10.0, CameraLenses.snap(40.0), 0.0)
    }

    @Test fun equivalentFocalLength() {
        // 5.4 mm on a 7.0 x 5.3 mm sensor is ~26.6 mm full-frame.
        assertEquals(26.6, CameraLenses.eqFocal(5.4, 7.0, 5.3), 0.1)
    }

    @Test fun idsAndLabels() {
        assertEquals("0@0.5", CameraLenses.lensId("0", 0.5))
        assertEquals("1@1", CameraLenses.lensId("1", 1.0))
        assertEquals("0" to 3.0, CameraLenses.parseId("0@3"))
        assertEquals("12" to 0.5, CameraLenses.parseId("12@0.5"))
        assertNull(CameraLenses.parseId("nonsense"))
        assertNull(CameraLenses.parseId("0@abc"))
        assertEquals("0.5×", CameraLenses.label("back", 0.5))
        assertEquals("Front", CameraLenses.label("front", 1.0))
        assertEquals("Front wide", CameraLenses.label("front", 0.5))
    }

    @Test fun zoomRatioStrategyWhenLogicalRangeCoversIt() {
        // S21 FE style: logical main 0 with zoom 0.5..30, hidden ultra-wide and tele ids.
        val lenses = CameraLenses.build(
            "0",
            listOf(back("0", 26.0), back("2", 13.0, listed = false), back("3", 78.0, listed = false, physical = true)),
            listOf(Cand("1", "front", 26.0, true, false)),
            0.5, 30.0, setOf("2", "3"),
        )
        assertEquals(listOf("0@0.5", "0@1", "0@3", "1@1"), lenses.map { it.id })
        assertEquals(listOf("0.5×", "1×", "3×", "Front"), lenses.map { it.label })
    }

    @Test fun teleViaZoomOnlyWhenTelePhysicalBelongsToLogicalMain() {
        // tele is a separate hidden camera, main is not logical: no zoom-ratio tele, direct open instead.
        val l = CameraLenses.build("0", listOf(back("0", 26.0), back("3", 78.0, listed = false)), emptyList(), 1.0, 30.0)
        assertEquals(listOf("0@1", "3@1"), l.map { it.id })
    }

    @Test fun directOpenWhenNoZoomRange() {
        val lenses = CameraLenses.build(
            "0",
            listOf(back("0", 26.0), back("2", 13.0, listed = false)),
            emptyList(), null, null,
        )
        assertEquals(listOf("2@1", "0@1"), lenses.map { it.id })
        assertEquals(listOf("0.5×", "1×"), lenses.map { it.label })
    }

    @Test fun zoomRangeThatDoesNotReachFallsBackToDirectOpen() {
        val lenses = CameraLenses.build("0", listOf(back("0", 26.0), back("4", 80.0, listed = false)), emptyList(), 1.0, 2.0)
        assertEquals(listOf("0@1", "4@1"), lenses.map { it.id })
    }

    @Test fun dedupePrefersListedThenLogical() {
        val lenses = CameraLenses.build(
            "0",
            listOf(
                back("0", 26.0),
                back("5", 13.0, listed = false, physical = true),
                back("6", 13.5, listed = false),
                back("2", 13.2, listed = true),
            ),
            emptyList(), null, null,
        )
        assertEquals(listOf("2@1", "0@1"), lenses.map { it.id })
    }

    @Test fun synthesisesUltraWideFromZoomRangeAlone() {
        val lenses = CameraLenses.build("0", listOf(back("0", 26.0)), emptyList(), 0.6, 10.0)
        assertEquals(listOf("0@0.6", "0@1"), lenses.map { it.id })
    }

    @Test fun singleCameraPhoneJustHasMainAndFront() {
        val lenses = CameraLenses.build("0", listOf(back("0", 26.0)), listOf(Cand("1", "front", 24.0, true, false)), 1.0, 8.0)
        assertEquals(listOf("0@1", "1@1"), lenses.map { it.id })
        assertTrue(lenses.filter { it.mult == 1.0 }.all { it.zoom == 1.0 })
    }

    @Test fun extraFrontOnlyIfWider() {
        val lenses = CameraLenses.build(
            "0", listOf(back("0", 26.0)),
            listOf(Cand("1", "front", 26.0, true, false), Cand("9", "front", 13.0, false, false), Cand("8", "front", 26.5, false, false)),
            null, null,
        )
        assertEquals(listOf("0@1", "1@1", "9@1"), lenses.map { it.id })
        assertEquals("Front wide", lenses.last().label)
    }

    @Test fun jsonShape() {
        val arr = CameraLenses.toJson(CameraLenses.build("0", listOf(back("0", 26.0)), emptyList(), null, null))
        val o = arr.getJSONObject(0)
        assertEquals("0@1", o.getString("id")); assertEquals("back", o.getString("facing")); assertEquals("1×", o.getString("label"))
    }

    @Test fun photoName() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 9, 5, 7) }
        assertEquals("IMG_20261121_090507.jpg", CameraLenses.photoName(c.time))
    }

    // Real S21 FE: ids 0 (main), 2 (ultra-wide) listed; hidden logicals 20/23 (same optics as main) and 21 (tele),
    // 52 a physical tele inside 21. Zoom range [1, 8]: no sub-1 zoom, plain (non-logical) main.
    private val s21Back = listOf(
        back("0", 26.0), back("2", 13.4),
        back("20", 26.0, listed = false), back("23", 26.0, listed = false),
        back("21", 75.5, listed = false), back("52", 75.5, listed = false, physical = true, parent = "21"),
    )

    @Test fun s21UltraWideDirectTelePendingVerifyAndLogicalsDedupe() {
        val l = CameraLenses.build("0", s21Back, listOf(Cand("1", "front", 26.0, true, false)), 1.0, 8.0)
        // 21@1 is the tele candidate: shown, but only opened (and possibly dropped) when first selected.
        assertEquals(listOf("2@1", "0@1", "21@1", "1@1"), l.map { it.id })
        assertEquals(listOf("0.5×", "1×", "3×", "Front"), l.map { it.label })
        assertEquals(1, l.count { it.mult == 1.0 && it.facing == "back" })
    }

    @Test fun physicalTeleOnlyStreamsThroughItsLogicalParent() {
        val l = CameraLenses.build(
            "0", listOf(back("0", 26.0), back("52", 75.5, listed = false, physical = true, parent = "21")), emptyList(), 1.0, 8.0,
        )
        val tele = l.first { it.mult == 3.0 }
        assertEquals("52@1", tele.id); assertEquals("21", tele.cameraId); assertEquals("52", tele.physicalId)
    }

    @Test fun noRealTeleMeansNoTeleChip() {
        val l = CameraLenses.build("0", listOf(back("0", 26.0), back("2", 13.4)), emptyList(), 1.0, 8.0)
        assertEquals(listOf("2@1", "0@1"), l.map { it.id })
    }

    @Test fun ambiguousFocalRatioIsNotOffered() {
        val l = CameraLenses.build("0", listOf(back("0", 26.0), back("7", 40.0, listed = false)), emptyList(), null, null)
        assertEquals(listOf("0@1"), l.map { it.id })
    }

    @Test fun storeRoundTrip() {
        val l = CameraLenses.build("0", s21Back + back("60", 260.0, listed = false), emptyList(), 1.0, 8.0)
        assertEquals(l, CameraLenses.fromStoreJson(CameraLenses.toStoreJson(l)))
        assertTrue(l.any { it.label == "10×" })
        assertEquals(emptyList<CameraLenses.Lens>(), CameraLenses.fromStoreJson("garbage"))
    }
}
