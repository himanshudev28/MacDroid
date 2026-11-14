package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeRearmTest {
    @Test fun `delays grow and then stop`() {
        val d = (0 until BridgeRearm.MAX_ATTEMPTS).map { BridgeRearm.delayMs(it)!! }
        assertEquals(d.sorted(), d)
        assertNotNull(BridgeRearm.delayMs(0))
        assertNull(BridgeRearm.delayMs(BridgeRearm.MAX_ATTEMPTS))
        assertNull(BridgeRearm.delayMs(-1))
    }

    @Test fun `old attempt counts are forgotten`() {
        val hour = 3_600_000L
        assertEquals(3, BridgeRearm.effectiveAttempts(3, 0L + 100 * hour, 101 * hour))
        assertEquals(0, BridgeRearm.effectiveAttempts(3, 0L, 13 * hour))
        assertEquals(0, BridgeRearm.effectiveAttempts(0, 5L, 6L))
    }
}
