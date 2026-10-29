package com.droiddock.app

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameTest {
    private val data = ByteArray(10) { it.toByte() }

    /** The reference: what the old array-copy path produced. */
    private fun legacy(flags: Int, prefix: ByteArray?, payload: ByteArray): ByteArray {
        val pre = prefix?.size ?: 0
        val out = ByteArray(2 + pre + payload.size)
        out[0] = 3; out[1] = flags.toByte()
        if (prefix != null) System.arraycopy(prefix, 0, out, 2, pre)
        System.arraycopy(payload, 0, out, 2 + pre, payload.size)
        return out
    }

    @Test fun slicedBufferMatchesLegacyBytes() {
        val buf = ByteBuffer.wrap(data)
        buf.position(3); buf.limit(8) // like info.offset / info.size
        val got = ConnectionManager.buildVideoFrame(0, null, buf)
        assertArrayEquals(legacy(0, null, data.copyOfRange(3, 8)), got)
        assertEquals(3, buf.position()) // caller's buffer untouched
    }

    @Test fun keyframeWithPrefixMatchesLegacy() {
        val prefix = byteArrayOf(9, 9, 9)
        val buf = ByteBuffer.allocateDirect(10).also { it.put(data); it.position(2); it.limit(10) }
        val got = ConnectionManager.buildVideoFrame(1, prefix, buf)
        assertArrayEquals(legacy(1, prefix, data.copyOfRange(2, 10)), got)
    }
}
