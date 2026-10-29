package com.droiddock.app.link

import com.droiddock.app.link.ble.BleCrypto
import com.droiddock.app.link.ble.BleFraming
import com.droiddock.app.link.ble.BleSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val TOKEN = "acad86c1-6041-408c-bce7-2495dd338711"
private val NONCE_P = ByteArray(16) { it.toByte() }
private val NONCE_M = ByteArray(16) { (0x10 + it).toByte() }
private fun hex(b: ByteArray) = BleCrypto.hex(b)

/** BLE-DESIGN §3 and §5 vectors, bit for bit. */
class BleVectorsTest {
    @Test fun ridKey() = assertEquals("292f553951c2c10d9c06bcfb2eb473809e51542ef7d76db90057d3ddd2a5e8ec", hex(BleCrypto.kRid(TOKEN)))

    @Test fun ridEpochs() {
        assertEquals(2111111L, BleCrypto.epoch(1_900_000_000_000))
        assertEquals("a6d762b56cb65216", hex(BleCrypto.rid(TOKEN, 2111110)))
        assertEquals("df638fe5722213b6", hex(BleCrypto.rid(TOKEN, 2111111)))
        assertEquals("a1b3e70a3219af3b", hex(BleCrypto.rid(TOKEN, 2111112)))
    }

    @Test fun authKeyProofsAndSessionKey() {
        assertEquals("bd1d063d94603644f48f268ef4bfcf44dabfd3e7aac48533e2c2f13611435f99", hex(BleCrypto.kAuth(TOKEN)))
        assertEquals("09f3919abd55e8f7c51baf05c68c0d8a7982486b5b8eccc932984ad5ae0600d6", hex(BleCrypto.proofM(TOKEN, NONCE_P, NONCE_M)))
        assertEquals("35adeacc175006673cffba01e5aaafde866951ffc0b908ae93d45d79f0e4c915", hex(BleCrypto.proofP(TOKEN, NONCE_P, NONCE_M)))
        assertEquals("7c7e1b811fdf9464357a83402df82cd83a4e9b4779820957ab437ca225cf3ad1", hex(BleCrypto.sessionKey(TOKEN, NONCE_P, NONCE_M)))
    }
}

class BleFramingTest {
    @Test fun fragmentsAndReassembles600Bytes() {
        val frame = ByteArray(600) { (it * 7).toByte() }
        val frags = BleFraming.Fragmenter().fragment(frame, 182)
        assertTrue(frags.all { it.size <= 182 })
        assertEquals(0x80, frags.first()[0].toInt() and 0xC0)         // START only
        assertEquals(0x40, frags.last()[0].toInt() and 0xC0)          // END only
        val len = ((frags[0][1].toInt() and 0xFF) shl 16) or ((frags[0][2].toInt() and 0xFF) shl 8) or (frags[0][3].toInt() and 0xFF)
        assertEquals(600, len)
        val r = BleFraming.Reassembler()
        var out: ByteArray? = null
        frags.forEach { out = r.push(it) ?: out }
        assertArrayEquals(frame, out)
    }

    @Test fun sequenceContinuesAcrossFramesAndWraps() {
        val f = BleFraming.Fragmenter(); val r = BleFraming.Reassembler()
        repeat(40) { n ->
            val frame = ByteArray(50) { n.toByte() }
            var got: ByteArray? = null
            f.fragment(frame, 20).forEach { got = r.push(it) ?: got }
            assertArrayEquals(frame, got)
        }
    }

    @Test fun gapOversizeAndOrphanDataAreErrors() {
        val frags = BleFraming.Fragmenter().fragment(ByteArray(100), 30)
        val r = BleFraming.Reassembler()
        r.push(frags[0])
        assertThrows(BleFraming.ProtocolError::class.java) { r.push(frags[2]) }
        assertThrows(BleFraming.ProtocolError::class.java) {
            BleFraming.Reassembler().push(byteArrayOf(0x80.toByte(), 0x02, 0x00, 0x01, 1)) // 131073 bytes
        }
        assertThrows(BleFraming.ProtocolError::class.java) { BleFraming.Reassembler().push(byteArrayOf(0x00, 1, 2)) }
    }
}

class BleSessionTest {
    private class Rec : BleSession.Listener {
        val msgs = ArrayList<JSONObject>(); var authed = false; var authFailed = false
        var fatal: String? = null; var bye = -1; var wakes = 0
        override fun onWake() { wakes++ }
        override fun onAuthenticated() { authed = true }
        override fun onMessage(msg: JSONObject) { msgs += msg }
        override fun onBye(reason: Int) { bye = reason }
        override fun onAuthFailed() { authFailed = true }
        override fun onFatal(why: String) { fatal = why }
    }

    private fun ctl(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun mAuth(proof: ByteArray) = byteArrayOf(0x80.toByte(), 2) + NONCE_M + proof

    private fun handshake(rec: Rec): Pair<BleSession, ByteArray> {
        val s = BleSession(TOKEN, rec, nonce = { NONCE_P })
        s.onSubscribed()
        val hello = s.pollFrame()!!
        assertEquals("80" + "01" + "01" + hex(NONCE_P), hex(hello))      // P_HELLO [1][nonceP]
        s.onFrame(mAuth(BleCrypto.proofM(TOKEN, NONCE_P, NONCE_M)))
        val pauth = s.pollFrame()!!
        assertEquals("8003" + hex(BleCrypto.proofP(TOKEN, NONCE_P, NONCE_M)), hex(pauth))
        assertTrue(rec.authed)
        assertArrayEquals(BleCrypto.sessionKey(TOKEN, NONCE_P, NONCE_M), s.sessionKeyForTest)
        return s to BleCrypto.sessionKey(TOKEN, NONCE_P, NONCE_M)
    }

    /** What the Mac would send: a sealed frame in direction M. */
    private fun macFrame(key: ByteArray, ctr: Long, flags: Int, plain: ByteArray) =
        byteArrayOf(flags.toByte()) + BleCrypto.seal(key, BleCrypto.DIR_MAC, ctr, flags, plain)

    @Test fun handshakeThenSealedTrafficBothWays() {
        val rec = Rec(); val (s, key) = handshake(rec)
        assertTrue(s.send(JSONObject().put("type", "hello").put("token", TOKEN)))
        val f = s.pollFrame()!!
        val plain = BleCrypto.open(key, BleCrypto.DIR_PHONE, f[0].toInt() and 0xFF, f.copyOfRange(1, f.size))!!
        assertEquals("hello", JSONObject(String(plain)).getString("type"))
        assertEquals(0L, BleCrypto.ctrOf(f.copyOfRange(1, f.size)))

        s.onFrame(macFrame(key, 0, 0, """{"type":"welcome","name":"Mac"}""".toByteArray()))
        assertEquals("welcome", rec.msgs.single().getString("type"))
    }

    @Test fun wrongProofFailsAndQueuesFail() {
        val rec = Rec()
        val s = BleSession(TOKEN, rec, nonce = { NONCE_P }); s.onSubscribed(); s.pollFrame()
        s.onFrame(mAuth(ByteArray(32)))
        assertTrue(rec.authFailed)
        assertEquals("807f01", hex(s.pollFrame()!!))
        assertFalse(rec.authed)
    }

    @Test fun replayAndCounterGapsKillTheSession() {
        val rec = Rec(); val (s, key) = handshake(rec)
        s.onFrame(macFrame(key, 0, 0, """{"type":"ping"}""".toByteArray()))
        assertNull(rec.fatal)
        s.onFrame(macFrame(key, 0, 0, """{"type":"ping"}""".toByteArray())) // replay
        assertEquals("counter mismatch", rec.fatal)

        val rec2 = Rec(); val (s2, key2) = handshake(rec2)
        s2.onFrame(macFrame(key2, 1, 0, """{"type":"ping"}""".toByteArray())) // gap
        assertEquals("counter mismatch", rec2.fatal)
    }

    @Test fun tamperedAndUnknownFlagFramesAreFatal() {
        val rec = Rec(); val (s, key) = handshake(rec)
        val f = macFrame(key, 0, 0, """{"type":"ping"}""".toByteArray()); f[f.size - 1] = (f.last() + 1).toByte()
        s.onFrame(f)
        assertEquals("bad tag", rec.fatal)
        val rec2 = Rec(); val (s2, _) = handshake(rec2)
        s2.onFrame(byteArrayOf(0x02, 0, 0, 0, 0))
        assertEquals("unknown flags", rec2.fatal)
    }

    @Test fun pingIsAnsweredWithSealedPongAndByeSurfaces() {
        val rec = Rec(); val (s, key) = handshake(rec)
        s.onFrame(macFrame(key, 0, BleSession.CTL, ctl(0x10, 0, 0, 0x03, 0xE8)))
        val pong = s.pollFrame()!!
        assertEquals(0x80, pong[0].toInt() and 0xFF)
        val plain = BleCrypto.open(key, BleCrypto.DIR_PHONE, 0x80, pong.copyOfRange(1, pong.size))!!
        assertEquals("11000003e8", hex(plain)) // PONG echoes the PING payload
        s.onFrame(macFrame(key, 1, BleSession.CTL, ctl(0x12, 1)))
        assertEquals(1, rec.bye)
    }

    @Test fun compressesLargeJsonButNeverClipboard() {
        val rec = Rec(); val (s, key) = handshake(rec)
        val big = "abcdefgh".repeat(100)
        s.send(JSONObject().put("type", "sms-threads").put("x", big))
        s.send(JSONObject().put("type", "clipboard").put("text", big))
        val a = s.pollFrame()!!; val b = s.pollFrame()!!
        // clipboard is P1 (first), sms-threads is P3
        assertEquals(0, a[0].toInt() and BleSession.DEFLATE)       // clipboard: not compressed
        assertEquals(BleSession.DEFLATE, b[0].toInt() and BleSession.DEFLATE)
        val inflated = BleCrypto.inflate(BleCrypto.open(key, BleCrypto.DIR_PHONE, b[0].toInt() and 0xFF, b.copyOfRange(1, b.size))!!)!!
        assertEquals("sms-threads", JSONObject(String(inflated)).getString("type"))
    }

    @Test fun outboxIsBoundedAndMediaIsLatestWins() {
        val rec = Rec(); val (s, _) = handshake(rec)
        repeat(5) { s.send(JSONObject().put("type", "media").put("n", it)) }
        // only one media frame can be queued at a time
        assertNotNull(s.pollFrame()); assertNull(s.pollFrame())
        repeat(BleSession.MAX_QUEUE) { assertTrue(s.send(JSONObject().put("type", "notification").put("n", it))) }
        assertFalse(s.send(JSONObject().put("type", "notification"))) // full, no P2 to evict
    }

    @Test fun oversizeMessageIsRefused() {
        val rec = Rec(); val (s, _) = handshake(rec)
        val noise = java.util.Random(1).let { r -> String(CharArray(70_000) { ('a' + r.nextInt(26)) }) }
        assertFalse(s.send(JSONObject().put("type", "clipboard").put("text", noise)))
    }
}
