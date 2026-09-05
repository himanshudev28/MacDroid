package com.droiddock.app

/**
 * Derives the RFC 6381 codec string for an HEVC stream from its own bitstream,
 * e.g. `hvc1.1.6.L93.B0`.
 *
 * Its own file, and pure, because it has to be unit-testable. WebCodecs refuses
 * to configure a decoder whose codec string disagrees with the bitstream, and a
 * wrong string fails *silently*: the decoder errors, transitions to `closed`,
 * and every later frame is dropped on the pop-out's `state !== "configured"`
 * guard. That is a black window with nothing in the log — the exact failure
 * that already cost a debugging session on the H.264 path.
 */
object HevcCodec {

    /** Used when the SPS cannot be found or parsed: Main profile, level 3.1 —
     *  the baseline every HEVC decoder supports. Wrong-but-plausible beats
     *  throwing, since the caller is a codec thread mid-stream. */
    const val FALLBACK = "hvc1.1.6.L93.B0"

    private const val NAL_SPS = 33

    /**
     * Parse `profile_tier_level` out of the SPS in these Annex-B config bytes.
     *
     * The fields are byte-aligned once emulation-prevention bytes are stripped,
     * so no bit reader is needed. After the 2-byte NAL header:
     * `[2]` sps ids, `[3]` `profile_space:2 | tier:1 | profile_idc:5`,
     * `[4..7]` 32 compatibility flags, `[8..13]` 48 constraint bits,
     * `[14]` `general_level_idc`.
     */
    fun codecString(config: ByteArray): String {
        val nal = findNal(config, NAL_SPS) ?: return FALLBACK
        val r = unescape(nal)
        if (r.size < 15) return FALLBACK

        val profileSpace = (r[3].toInt() shr 6) and 0x3
        val tier = (r[3].toInt() shr 5) and 0x1
        val profileIdc = r[3].toInt() and 0x1f

        var compat = 0L
        for (i in 4..7) compat = (compat shl 8) or (r[i].toLong() and 0xff)
        // The string carries the compatibility flags in *reverse* bit order,
        // which is why Main profile reads as "6" rather than "60000000".
        var reversed = 0L
        for (bit in 0 until 32) {
            if ((compat shr bit) and 1L == 1L) reversed = reversed or (1L shl (31 - bit))
        }

        val sb = StringBuilder("hvc1.")
        // profile_space 0 is written as a bare number; 1..3 take an A/B/C prefix.
        if (profileSpace > 0) sb.append('A' + profileSpace - 1)
        sb.append(profileIdc).append('.')
        sb.append(java.lang.Long.toHexString(reversed).uppercase()).append('.')
        sb.append(if (tier == 1) 'H' else 'L').append(r[14].toInt() and 0xff)
        // Constraint bytes, with trailing all-zero bytes omitted per the spec.
        var last = 13
        while (last >= 8 && r[last].toInt() == 0) last--
        for (i in 8..last) sb.append('.').append("%02X".format(r[i].toInt() and 0xff))
        return sb.toString()
    }

    /**
     * The payload of the first Annex-B NAL of [type], header byte included.
     * HEVC puts the type in bits 6..1 of the first header byte, unlike H.264's
     * low 5 bits — reading it the H.264 way finds nothing at all.
     */
    fun findNal(buf: ByteArray, type: Int): ByteArray? {
        // (start-code index, payload index) so the previous NAL can be ended
        // at the *start code*, not after it.
        val nals = ArrayList<IntArray>()
        var i = 0
        while (i + 2 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (buf[i + 2].toInt() == 1) {
                    nals.add(intArrayOf(i, i + 3)); i += 3; continue
                }
                if (i + 3 < buf.size && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1) {
                    nals.add(intArrayOf(i, i + 4)); i += 4; continue
                }
            }
            i++
        }
        for ((n, nal) in nals.withIndex()) {
            val s = nal[1]
            if (s >= buf.size) continue
            if (((buf[s].toInt() shr 1) and 0x3f) != type) continue
            val end = if (n + 1 < nals.size) nals[n + 1][0] else buf.size
            if (end <= s) continue
            return buf.copyOfRange(s, end)
        }
        return null
    }

    /**
     * Strip emulation-prevention bytes: an encoder inserts `0x03` after any
     * `00 00` that would otherwise look like a start code. Leaving them in
     * shifts every field after the first occurrence, and they genuinely do
     * occur inside profile_tier_level — the constraint bytes are mostly zero.
     */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size)
        var j = 0
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out[j++] = b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.copyOf(j)
    }
}
