package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The codec string has to be exactly right or WebCodecs rejects the decoder
 * config and the mirror window goes black with nothing in the log, so these
 * pin the parse against hand-built bitstreams with known field values.
 */
class HevcCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Main profile, level 3.1 — the canonical `hvc1.1.6.L93.B0` from RFC 6381.
     *  profile_space 0, tier L, profile_idc 1, compat 0x60000000, constraint B0,
     *  level 93. Written unescaped; `unescape` is a no-op without 0x03 markers. */
    private val cleanSps = bytes(
        0x00, 0x00, 0x00, 0x01,             // Annex-B start code
        0x42, 0x01,                         // NAL header: type 33 (SPS) = 0x42
        0x01,                               // sps ids
        0x01,                               // profile_space 0 | tier 0 | idc 1
        0x60, 0x00, 0x00, 0x00,             // compatibility flags
        0xB0, 0x00, 0x00, 0x00, 0x00, 0x00, // constraint bytes
        0x5D                                // level_idc 93
    )

    /** The same SPS as a real encoder would emit it, with emulation-prevention
     *  0x03 bytes broken into every `00 00 00`. */
    private val escapedSps = bytes(
        0x00, 0x00, 0x00, 0x01,
        0x42, 0x01,
        0x01,
        0x01,
        0x60, 0x00, 0x00, 0x03, 0x00,
        0xB0, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
        0x5D
    )

    @Test
    fun `derives the canonical main-profile string`() {
        assertEquals("hvc1.1.6.L93.B0", HevcCodec.codecString(cleanSps))
    }

    @Test
    fun `emulation-prevention bytes do not shift the fields`() {
        // The whole point of unescape: without it the constraint bytes and level
        // read from the wrong offsets and produce a plausible-looking wrong
        // string, which is the failure mode that is hardest to notice.
        assertEquals("hvc1.1.6.L93.B0", HevcCodec.codecString(escapedSps))
    }

    @Test
    fun `unescape removes only markers and leaves payload intact`() {
        assertEquals(
            listOf(0x00, 0x00, 0x00, 0xB0),
            HevcCodec.unescape(bytes(0x00, 0x00, 0x03, 0x00, 0xB0)).map { it.toInt() and 0xff }
        )
        // A lone 0x03 not preceded by two zeroes is real payload, not a marker.
        assertEquals(
            listOf(0x01, 0x03, 0x04),
            HevcCodec.unescape(bytes(0x01, 0x03, 0x04)).map { it.toInt() and 0xff }
        )
    }

    @Test
    fun `main10 profile and high tier`() {
        // profile_space 0, tier H (bit 5), idc 2; compat 0x24000000 reverses to
        // 0x00000024 = "24"; constraint bytes 90 00.. ; level 120 (4.0).
        val sps = bytes(
            0x00, 0x00, 0x01,               // 3-byte start code is equally legal
            0x42, 0x01,
            0x01,
            0x22,                           // 0b001_00010 → tier 1, idc 2
            0x24, 0x00, 0x00, 0x00,
            0x90, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x78
        )
        assertEquals("hvc1.2.24.H120.90", HevcCodec.codecString(sps))
    }

    @Test
    fun `trailing zero constraint bytes are omitted but interior ones kept`() {
        val sps = bytes(
            0x00, 0x00, 0x00, 0x01,
            0x42, 0x01,
            0x01,
            0x01,
            0x60, 0x00, 0x00, 0x00,
            0xB0, 0x00, 0x0C, 0x00, 0x00, 0x00, // interior 0x0C must survive
            0x5D
        )
        assertEquals("hvc1.1.6.L93.B0.00.0C", HevcCodec.codecString(sps))
    }

    @Test
    fun `falls back rather than throwing when there is no SPS`() {
        // An H.264 config would land here if the codec fields ever disagreed;
        // a mid-stream exception on the encoder thread would kill the mirror.
        val pps = bytes(0x00, 0x00, 0x00, 0x01, 0x44, 0x01, 0xC0)
        assertEquals(HevcCodec.FALLBACK, HevcCodec.codecString(pps))
        assertEquals(HevcCodec.FALLBACK, HevcCodec.codecString(ByteArray(0)))
    }

    @Test
    fun `truncated SPS falls back instead of reading past the end`() {
        val short = bytes(0x00, 0x00, 0x00, 0x01, 0x42, 0x01, 0x01, 0x01, 0x60)
        assertEquals(HevcCodec.FALLBACK, HevcCodec.codecString(short))
    }

    @Test
    fun `picks the SPS out of a full VPS-SPS-PPS config`() {
        val full = bytes(
            0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0C, 0x01, // VPS (type 32)
            0x00, 0x00, 0x00, 0x01,
            0x42, 0x01, 0x01, 0x01,
            0x60, 0x00, 0x00, 0x00,
            0xB0, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x5D,
            0x00, 0x00, 0x00, 0x01, 0x44, 0x01, 0xC0        // PPS (type 34)
        )
        assertEquals("hvc1.1.6.L93.B0", HevcCodec.codecString(full))
    }

    /**
     * Real bytes, not hand-built: the SPS a VideoToolbox HEVC encoder actually
     * emits, captured from `ffmpeg -c:v hevc_videotoolbox`. `ffprobe` reports
     * the same stream as "Main, level 123", which is what these strings encode.
     * Hand-written vectors can share a wrong assumption with the parser; these
     * cannot.
     */
    @Test
    fun `matches a real VideoToolbox Main SPS`() {
        val sps = bytes(
            0x00, 0x00, 0x00, 0x01,
            0x42, 0x01, 0x01, 0x01,
            0x60, 0x00, 0x00, 0x03, 0x00, 0xB0,
            0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
            0x7B, 0xA0, 0x05
        )
        assertEquals("hvc1.1.6.L123.B0", HevcCodec.codecString(sps))
    }

    @Test
    fun `matches a real VideoToolbox Main10 SPS`() {
        // Same encoder at 10-bit: profile_idc 2, compatibility 0x20000000,
        // which reverses to 4 — a different code path through the bit reversal
        // than Main's 6.
        val sps = bytes(
            0x00, 0x00, 0x00, 0x01,
            0x42, 0x01, 0x01, 0x02,
            0x20, 0x00, 0x00, 0x03, 0x00, 0xB0,
            0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
            0x7B, 0xA0, 0x05
        )
        assertEquals("hvc1.2.4.L123.B0", HevcCodec.codecString(sps))
    }

    @Test
    fun `nal type comes from bits 6 to 1, not H264's low five`() {
        // 0x42 is SPS in HEVC (33) but would read as type 2 the H.264 way, and
        // an H.264-shaped parse finds nothing here at all.
        assertEquals(33, (0x42 shr 1) and 0x3f)
        assertNull(HevcCodec.findNal(cleanSps, 34))
    }
}
