package com.droiddock.app

import org.junit.Assert.*
import org.junit.Test

class ScreenshotReplyTest {
    @Test fun `success carries base64 data and reqId, no error`() {
        val o = ScreenshotReply.build(7L, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), null)
        assertEquals("screenshot", o.getString("type"))
        assertEquals(7L, o.getLong("reqId"))
        assertEquals("iVBORw==", o.getString("data"))
        assertFalse(o.has("error"))
    }

    @Test fun `failure carries the reason and no data`() {
        val o = ScreenshotReply.build(3L, null, "Needs Android 11 or newer")
        assertEquals("Needs Android 11 or newer", o.getString("error"))
        assertFalse(o.has("data"))
        assertEquals(3L, o.getLong("reqId"))
    }

    @Test fun `missing reason and empty png still produce an error`() {
        assertEquals("Screenshot failed", ScreenshotReply.build(1, null, null).getString("error"))
        assertTrue(ScreenshotReply.build(1, ByteArray(0), null).has("error"))
    }

    @Test fun `base64 of the largest allowed png stays under the 16 MiB socket queue`() {
        val b64 = ScreenshotReply.MAX_PNG_BYTES / 3 * 4 + 4
        assertTrue(b64 <= 16 * 1024 * 1024 * 6 / 10)
    }

    @Test fun `shrink factor is 1 when it fits and shrinks big images`() {
        assertEquals(1f, ScreenshotReply.shrinkFactor(1_000_000), 0f)
        assertEquals(1f, ScreenshotReply.shrinkFactor(ScreenshotReply.MAX_PNG_BYTES), 0f)
        val f = ScreenshotReply.shrinkFactor(ScreenshotReply.MAX_PNG_BYTES * 4)
        assertTrue(f > 0.1f && f < 0.5f)
        // Area scales with f^2, so the estimated size lands under the budget.
        assertTrue(ScreenshotReply.MAX_PNG_BYTES * 4 * f * f < ScreenshotReply.MAX_PNG_BYTES)
        assertEquals(0.1f, ScreenshotReply.shrinkFactor(Int.MAX_VALUE), 0f)
    }
}

class RemoteMessagesTest {
    @Test fun `brightness is clamped to 0 to 1`() {
        assertEquals(1.0, RemoteMessages.macBrightness(5f)!!.getDouble("level"), 0.0)
        assertEquals(0.0, RemoteMessages.macBrightness(-2f)!!.getDouble("level"), 0.0)
        assertEquals(0.5, RemoteMessages.macBrightness(0.5f)!!.getDouble("level"), 1e-6)
        assertEquals("mac-brightness-set", RemoteMessages.macBrightness(0.1f)!!.getString("type"))
    }

    @Test fun `non-finite brightness is dropped instead of throwing`() {
        assertNull(RemoteMessages.macBrightness(Float.NaN))
        assertNull(RemoteMessages.macBrightness(Float.POSITIVE_INFINITY))
    }

    @Test fun `remote button down and up`() {
        val d = RemoteMessages.remoteButton(true)
        assertEquals("remote-button", d.getString("type"))
        assertEquals("down", d.getString("state"))
        assertEquals("left", d.getString("button"))
        assertEquals("up", RemoteMessages.remoteButton(false, "right").getString("state"))
        assertEquals("right", RemoteMessages.remoteButton(false, "right").getString("button"))
    }
}

class VideoPauseGateTest {
    @Test fun `pause then resume`() {
        val g = VideoPauseGate()
        g.pause(true); assertTrue(g.paused)
        assertTrue(g.resume(true)); assertFalse(g.paused)
    }

    @Test fun `double pause is idempotent and one resume clears it`() {
        val g = VideoPauseGate()
        g.pause(true); g.pause(true)
        assertTrue(g.paused)
        g.resume(true)
        assertFalse(g.paused)
    }

    @Test fun `resume without pause still asks for a keyframe but stays unpaused`() {
        val g = VideoPauseGate()
        assertTrue(g.resume(true))
        assertFalse(g.paused)
    }

    @Test fun `pause and resume without an encoder do nothing`() {
        val g = VideoPauseGate()
        g.pause(false); assertFalse(g.paused)
        g.pause(true)
        assertFalse(g.resume(false))
        assertTrue(g.paused) // untouched until an encoder exists
    }

    @Test fun `reset on new encoder or full resume clears a stale pause`() {
        val g = VideoPauseGate()
        g.pause(true); g.reset()
        assertFalse(g.paused)
    }

    @Test fun `drain drops frames when paused or not streaming but keeps config`() {
        val g = VideoPauseGate()
        assertFalse(g.shouldDrop(streaming = true, isConfig = false))
        assertTrue(g.shouldDrop(streaming = false, isConfig = false))
        assertFalse(g.shouldDrop(streaming = false, isConfig = true))
        g.pause(true)
        assertTrue(g.shouldDrop(streaming = true, isConfig = false))
        assertFalse(g.shouldDrop(streaming = true, isConfig = true))
    }
}

class FileRepoMkdirTest {
    private lateinit var root: java.io.File

    @org.junit.Before fun setUp() { root = java.nio.file.Files.createTempDirectory("mk").toFile() }
    @org.junit.After fun tearDown() { root.deleteRecursively() }

    private fun rejects(name: String) {
        val e = runCatching { FileRepo.mkdir(root.path, name) }.exceptionOrNull()
        assertTrue("'$name' should be rejected, got $e", e is IllegalArgumentException)
        assertEquals("nothing may be created for '$name'", 0, root.listFiles()!!.size)
    }

    @Test fun `creates a single directory and returns its path`() {
        val p = FileRepo.mkdir(root.path, "  New Folder ")
        assertEquals(java.io.File(root, "New Folder").absolutePath, p)
        assertTrue(java.io.File(p).isDirectory)
    }

    @Test fun `empty blank dot and dotdot names are rejected`() {
        rejects(""); rejects("   "); rejects("."); rejects("..")
    }

    @Test fun `names containing a slash cannot escape the parent`() {
        rejects("a/b"); rejects("../x"); rejects("/abs"); rejects("x/")
    }

    @Test fun `existing name and missing parent fail without creating a chain`() {
        FileRepo.mkdir(root.path, "dup")
        assertTrue(runCatching { FileRepo.mkdir(root.path, "dup") }.exceptionOrNull() is java.io.IOException)
        val missing = java.io.File(root, "no/such")
        assertTrue(runCatching { FileRepo.mkdir(missing.path, "x") }.exceptionOrNull() is java.io.FileNotFoundException)
        assertFalse(java.io.File(root, "no").exists())
    }
}
