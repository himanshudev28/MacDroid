package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test fun quantizeIgnoresUnknown() {
        assertEquals(90, CameraOrientation.quantize(-1, 90))
    }

    @Test fun hysteresisHoldsNearBoundary() {
        assertEquals(0, CameraOrientation.quantize(50, 0))   // past 45 but inside the dead-band
        assertEquals(0, CameraOrientation.quantize(75, 0))
        assertEquals(90, CameraOrientation.quantize(80, 0))  // 30+ degrees past the boundary
        assertEquals(0, CameraOrientation.quantize(355, 0))  // wraps around zero
        assertEquals(270, CameraOrientation.quantize(280, 0))
        assertEquals(180, CameraOrientation.quantize(170, 90))
        assertEquals(90, CameraOrientation.quantize(120, 90))
    }

    // Camera2 JPEG_ORIENTATION doc sample: back = sensor + device, front = sensor - device.
    @Test fun backCameraRotation() {
        assertEquals(90, CameraOrientation.rotation(90, false, 0))
        assertEquals(180, CameraOrientation.rotation(90, false, 90))
        assertEquals(270, CameraOrientation.rotation(90, false, 180))
        assertEquals(0, CameraOrientation.rotation(90, false, 270))
    }

    @Test fun frontCameraRotation() {
        assertEquals(270, CameraOrientation.rotation(270, true, 0))
        assertEquals(180, CameraOrientation.rotation(270, true, 90))
        assertEquals(90, CameraOrientation.rotation(270, true, 180))
        assertEquals(0, CameraOrientation.rotation(270, true, 270))
    }

    @Test fun allAnglesBothFacingsAgainstDocFormula() {
        for (sensor in intArrayOf(90, 270)) for (dev in intArrayOf(0, 90, 180, 270)) {
            assertEquals((sensor + dev) % 360, CameraOrientation.rotation(sensor, false, dev))
            assertEquals((sensor - dev + 360) % 360, CameraOrientation.rotation(sensor, true, dev))
        }
    }
}
