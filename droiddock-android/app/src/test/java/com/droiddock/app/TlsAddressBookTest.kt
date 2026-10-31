package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device JSON and merge rules that carry the TLS pin (`fp`). */
class TlsAddressBookTest {
    private val fpA = "Xyk_wGH8yYL1D7E_YjfBy2aaODRCsf-jBA4-20BdAdU"
    private val fpB = "3Pu3mbBlEGFdgMWI7ZswzoGXl5HFwoOtDpSzY9IndsQ"

    private fun dev(fp: String? = null, seen: Long = 5L) =
        KnownDevice("Mac", listOf("10.0.0.2", "100.64.0.9"), 48484, "tok", seen, fp)

    @Test
    fun `device JSON round-trips with fp`() {
        val back = decodeDevices(encodeDevices(listOf(dev(fpA))))
        assertEquals(listOf(dev(fpA)), back)
    }

    @Test
    fun `device JSON round-trips without fp`() {
        val raw = encodeDevices(listOf(dev(null)))
        assertEquals(false, raw.contains("\"fp\""))
        assertEquals(listOf(dev(null)), decodeDevices(raw))
    }

    @Test
    fun `old JSON written before fp existed loads as fp null`() {
        val old = """[{"macName":"Studio","ips":["10.0.0.2"],"port":48484,"token":"t1","lastSeenAt":9}]"""
        val d = decodeDevices(old).single()
        assertNull(d.fp)
        assertEquals("t1", d.token)
        assertNull(d.toPairing().fp)
    }

    @Test
    fun `a malformed stored fp is kept raw and reported unusable, never unpinned`() {
        val raw = """[{"macName":"M","ips":["10.0.0.2"],"port":1,"token":"t","lastSeenAt":1,"fp":"junk"}]"""
        val d = decodeDevices(raw).single()
        assertEquals("junk", d.fp)
        assertTrue(LinkTls.isUnusablePin(d.fp))
        assertEquals("junk", d.toPairing().fp)
        // and it survives a round trip instead of being laundered into null
        assertEquals("junk", decodeDevices(encodeDevices(listOf(d))).single().fp)
    }

    @Test
    fun `an unreadable device list decodes to null so callers can fail closed`() {
        assertNull(decodeDevicesOrNull("{not json"))
        assertEquals(emptyList<KnownDevice>(), decodeDevicesOrNull(null))
        assertTrue(LinkTls.isUnusablePin(LinkTls.PIN_UNREADABLE))
    }

    @Test
    fun `toPairing passes the pin through`() {
        assertEquals(fpA, dev(fpA).toPairing().fp)
    }

    @Test
    fun `re-pairing without a QR pin keeps the existing fp and lastSeenAt`() {
        val merged = mergePairing(listOf(dev(fpA, seen = 77)), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", null))
        val d = merged.single()
        assertEquals(fpA, d.fp)
        assertEquals(77L, d.lastSeenAt)
        assertEquals(listOf("10.0.0.9"), d.ips)
    }

    @Test
    fun `re-pairing with a QR carrying a new fp replaces the pin`() {
        val merged = mergePairing(listOf(dev(fpA)), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", fpB))
        assertEquals(fpB, merged.single().fp)
    }

    @Test
    fun `a different token does not inherit another Macs pin`() {
        val merged = mergePairing(listOf(dev(fpA)), Pairing(listOf("10.0.0.9"), 48484, "other", "Mac2", null))
        assertNull(merged.first { it.token == "other" }.fp)
        assertEquals(fpA, merged.first { it.token == "tok" }.fp)
    }

    @Test
    fun `markSeen keeps fp`() {
        val out = applySeen(listOf(dev(fpA)), "tok", "100.64.0.9", "Renamed", 1234L).single()
        assertEquals(fpA, out.fp)
        assertEquals(1234L, out.lastSeenAt)
        assertEquals("100.64.0.9", out.ips.first())
        assertEquals("Renamed", out.macName)
    }

    @Test
    fun `setPin stores the pin on the matching device only`() {
        val other = dev(null).copy(token = "x")
        val out = applyPin(listOf(dev(null), other), "tok", fpA)
        assertEquals(fpA, out.first { it.token == "tok" }.fp)
        assertNull(out.first { it.token == "x" }.fp)
    }

    @Test
    fun `setPin on an unknown token changes nothing`() {
        assertEquals(listOf(dev(null)), applyPin(listOf(dev(null)), "nope", fpA))
    }

    @Test
    fun `applyPin over an existing pin is a no-op`() {
        val existing = listOf(dev(fpA))
        assertEquals(existing, applyPin(existing, "tok", fpB))
        assertFalse(canPin(existing, "tok"))
        assertTrue(canPin(listOf(dev(null)), "tok"))
        assertFalse(canPin(listOf(dev(null)), "unknown"))
    }

    @Test
    fun `applyPin does not overwrite an unusable stored pin either`() {
        val existing = listOf(dev("junk"))
        assertEquals(existing, applyPin(existing, "tok", fpB))
    }

    private fun many(n: Int, pinned: Boolean = false) =
        (1..n).map { KnownDevice("M$it", listOf("10.0.0.$it"), 48484, "t$it", it * 100L, if (pinned) fpA else null) }

    @Test
    fun `a newly paired device is never evicted by the 8-device cap`() {
        val merged = mergePairing(many(8), Pairing(listOf("10.9.9.9"), 48484, "fresh", "New", null))
        assertEquals(8, merged.size)
        assertTrue(merged.any { it.token == "fresh" })
        // and it survives the encode/decode round trip too
        assertTrue(decodeDevices(encodeDevices(merged)).any { it.token == "fresh" })
    }

    @Test
    fun `a pinned device is not evicted in favour of an unpinned one`() {
        val pinnedOld = KnownDevice("Old", listOf("10.0.0.99"), 48484, "pinned-old", 1L, fpA)
        val list = listOf(pinnedOld) + many(8)
        val capped = capDevices(list)
        assertEquals(8, capped.size)
        assertTrue(capped.any { it.token == "pinned-old" })
    }

    @Test
    fun `cap evicts the least recently seen among equals`() {
        val capped = capDevices(many(9))
        assertEquals(8, capped.size)
        assertFalse(capped.any { it.token == "t1" })
    }

    @Test
    fun `a QR scan without fp clears the stored pin`() {
        val out = mergePairing(listOf(dev(fpA, seen = 9)), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", null), authoritative = true).single()
        assertNull(out.fp)
        assertEquals(9L, out.lastSeenAt)
    }

    @Test
    fun `a manual or re-save pairing without fp keeps the stored pin`() {
        val out = mergePairing(listOf(dev(fpA)), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", null), authoritative = false).single()
        assertEquals(fpA, out.fp)
    }

    @Test
    fun `a QR scan with a new fp replaces the pin`() {
        val out = mergePairing(listOf(dev(fpA)), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", fpB), authoritative = true).single()
        assertEquals(fpB, out.fp)
    }

    @Test
    fun `manual pair after forgetting the Mac starts unpinned`() {
        val out = mergePairing(emptyList(), Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", null)).single()
        assertNull(out.fp)
    }

    @Test fun `removing a Mac drops only its entry and its pin`() {
        val a = KnownDevice(ips = listOf("10.0.0.2"), port = 48484, token = "tok-a", macName = "A", lastSeenAt = 5L, fp = "x".repeat(43))
        val b = KnownDevice(ips = listOf("10.0.0.3"), port = 48484, token = "tok-b", macName = "B", lastSeenAt = 7L, fp = null)
        val rest = removeDevice(listOf(a, b), "tok-a")
        assertEquals(listOf(b), rest)
        assertTrue(rest.none { it.fp != null })
        assertEquals(listOf(a, b), removeDevice(listOf(a, b), "tok-unknown"))
    }
}
