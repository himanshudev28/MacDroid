package com.droiddock.app.link.ble

/**
 * L1 (BLE-DESIGN §4): one L2 frame split into ATT values.
 *
 *   ATT value = [hdr][START? len u24 BE][data...]
 *   hdr: bit7 START, bit6 END, bits0-5 seq (mod 64, per direction, reset per GATT connection)
 *
 * The fragment size passed in is the largest ATT *value* (header included).
 */
object BleFraming {
    const val MAX_FRAME = 65536
    private const val START = 0x80
    private const val END = 0x40

    class ProtocolError(msg: String) : Exception(msg)

    /** Sender half; holds the running sequence number for one direction. */
    class Fragmenter {
        private var seq = 0

        fun fragment(frame: ByteArray, maxValue: Int): List<ByteArray> {
            require(maxValue >= 5) { "fragment size too small" }
            require(frame.size in 1..MAX_FRAME)
            val out = ArrayList<ByteArray>()
            var off = 0
            while (off < frame.size) {
                val first = off == 0
                val head = if (first) 4 else 1
                val n = minOf(maxValue - head, frame.size - off)
                val last = off + n >= frame.size
                val b = ByteArray(head + n)
                b[0] = ((if (first) START else 0) or (if (last) END else 0) or (seq and 0x3F)).toByte()
                if (first) {
                    b[1] = (frame.size ushr 16).toByte()
                    b[2] = (frame.size ushr 8).toByte()
                    b[3] = frame.size.toByte()
                }
                System.arraycopy(frame, off, b, head, n)
                seq = (seq + 1) and 0x3F
                off += n
                out += b
            }
            return out
        }
    }

    /** Receiver half. Any [ProtocolError] means: drop the GATT connection. */
    class Reassembler {
        private var expectSeq = 0
        private var buf: ByteArray? = null
        private var total = 0
        private var have = 0

        /** The completed L2 frame, or null while more fragments are due. */
        fun push(att: ByteArray): ByteArray? {
            if (att.isEmpty()) throw ProtocolError("empty fragment")
            val h = att[0].toInt() and 0xFF
            if ((h and 0x3F) != expectSeq) throw ProtocolError("sequence gap")
            expectSeq = (expectSeq + 1) and 0x3F
            var off = 1
            if (h and START != 0) {
                if (att.size < 4) throw ProtocolError("short start")
                total = ((att[1].toInt() and 0xFF) shl 16) or ((att[2].toInt() and 0xFF) shl 8) or (att[3].toInt() and 0xFF)
                if (total <= 0 || total > MAX_FRAME) throw ProtocolError("bad length")
                buf = ByteArray(total)
                have = 0
                off = 4
            }
            val b = buf ?: throw ProtocolError("data without start")
            val n = att.size - off
            if (have + n > total) throw ProtocolError("overrun")
            System.arraycopy(att, off, b, have, n)
            have += n
            val end = h and END != 0
            if (end != (have == total)) throw ProtocolError("length mismatch")
            if (!end) return null
            buf = null
            return b
        }
    }
}
