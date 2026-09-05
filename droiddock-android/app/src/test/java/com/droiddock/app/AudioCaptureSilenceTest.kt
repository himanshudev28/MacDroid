package com.droiddock.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silence suppression decides whether ~192 KB/s keeps flowing next to the video
 * stream, so the threshold behaviour is worth pinning: too strict and the link
 * carries silence forever, too loose and it clips quiet passages.
 */
class AudioCaptureSilenceTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `all-zero chunk is silent`() {
        assertTrue(AudioCapture.isSilent(ByteArray(64), 64))
    }

    @Test
    fun `dither at plus or minus one LSB still counts as silent`() {
        // An exact-zero test would fail here, and mixers that emit steady dither
        // would defeat suppression completely.
        assertTrue(AudioCapture.isSilent(pcm(1, -1, 0, 1, -1, 2, -2), 14))
    }

    @Test
    fun `a real sample is not silent`() {
        assertFalse(AudioCapture.isSilent(pcm(0, 0, 0, 900), 8))
        // Negative-going audio must not be mistaken for silence: the sign bit
        // lives in the high byte, so a naive unsigned read would see a huge
        // positive value or, worse, compare the wrong byte.
        assertFalse(AudioCapture.isSilent(pcm(0, 0, -900, 0), 8))
    }

    @Test
    fun `only the first len bytes are examined`() {
        val buf = pcm(0, 0, 5000, 5000)
        assertTrue(AudioCapture.isSilent(buf, 4))
        assertFalse(AudioCapture.isSilent(buf, 8))
    }
}
