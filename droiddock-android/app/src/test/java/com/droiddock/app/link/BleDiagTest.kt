package com.droiddock.app.link

import com.droiddock.app.link.ble.BleAdmission
import com.droiddock.app.link.ble.BleLogLimiter
import org.junit.Assert.*
import org.junit.Test

class BleDiagTest {
    @Test fun limiterAllowsOneLinePerWindowAndCountsRepeats() {
        val l = BleLogLimiter(30_000)
        assertEquals(0, l.admit("a", 1_000))
        assertNull(l.admit("a", 2_000))
        assertNull(l.admit("a", 3_000))
        assertEquals(0, l.admit("b", 3_000))
        assertEquals(2, l.admit("a", 31_000))
        assertNull(l.admit("a", 31_500))
    }

    @Test fun admissionNeedsReadyServiceTokenAndNoCurrentSession() {
        assertTrue(BleAdmission.accept(true, true, false))
        assertFalse(BleAdmission.accept(false, true, false))
        assertFalse(BleAdmission.accept(true, false, false))
        assertFalse(BleAdmission.accept(true, true, true))
    }
}
