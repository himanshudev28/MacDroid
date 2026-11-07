package com.droiddock.app.ui

import com.droiddock.app.ui.remote.KeyRepeat
import com.droiddock.app.ui.remote.gesturesEnabled
import com.droiddock.app.ui.remote.pinchGesture
import com.droiddock.app.ui.remote.swipeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGesturesTest {
    @Test fun `key repeat timing`() {
        assertEquals(350L, KeyRepeat.DELAY_MS)
        assertEquals(70L, KeyRepeat.INTERVAL_MS)
        assertEquals(0, KeyRepeat.sendsAfter(-1))
        assertEquals(1, KeyRepeat.sendsAfter(0))
        assertEquals(1, KeyRepeat.sendsAfter(349))
        assertEquals(2, KeyRepeat.sendsAfter(350))
        assertEquals(2, KeyRepeat.sendsAfter(419))
        assertEquals(3, KeyRepeat.sendsAfter(420))
    }

    @Test fun `swipe direction maps to gesture`() {
        assertEquals("mission_control", swipeGesture(5f, -100f, 80f))
        assertEquals("app_expose", swipeGesture(-5f, 100f, 80f))
        assertEquals("space_right", swipeGesture(-100f, 10f, 80f))
        assertEquals("space_left", swipeGesture(100f, -10f, 80f))
        assertNull(swipeGesture(60f, 60f, 80f))
    }

    @Test fun `pinch thresholds`() {
        assertEquals("zoom_in", pinchGesture(1.25f))
        assertEquals("zoom_out", pinchGesture(0.8f))
        assertNull(pinchGesture(1.1f))
        assertNull(pinchGesture(0.9f))
    }

    @Test fun `gesture cap gating`() {
        assertTrue(gesturesEnabled(listOf("remote", "remotegestures")))
        assertFalse(gesturesEnabled(listOf("remote")))
        assertFalse(gesturesEnabled(listOf("remotegestures")))
    }
}

class RemoteScrollTest {
    @Test fun `legacy line scroll keeps the remainder`() {
        val l = com.droiddock.app.ui.remote.LineScroller(20f)
        assertEquals(0 to 0, l.add(0f, 8f))
        assertEquals(0 to 0, l.add(0f, 8f))
        assertEquals(0 to 1, l.add(0f, 8f)) // 24 px total -> one line, 4 px kept
        assertEquals(0 to -1, l.add(0f, -24f)) // 4 - 24 = -20
    }

    @Test fun `pinch that is really a scroll`() {
        assertTrue(com.droiddock.app.ui.remote.pinchIsReallyScroll(100f, 20f, 30f))
        assertFalse(com.droiddock.app.ui.remote.pinchIsReallyScroll(25f, 5f, 30f))
        assertFalse(com.droiddock.app.ui.remote.pinchIsReallyScroll(60f, 50f, 30f))
    }
}
