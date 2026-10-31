package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PairingQrTest {
    private val fp = "7iF8Sz-mw4_5-QGyqC_ptXO0NB7toSxHHpHmz7EXwfs"
    private val base = "droiddock://pair?v=1&name=My%20Mac&ips=192.168.1.5,100.64.0.9&port=48600&token=abc"

    private fun rejects(raw: String) {
        try { LinkTls.parsePairingQr(raw); fail("should reject: $raw") } catch (_: Exception) {}
    }

    @Test
    fun `URL QR with fp`() {
        val p = LinkTls.parsePairingQr("$base&fp=$fp")
        assertEquals(listOf("192.168.1.5", "100.64.0.9"), p.ips)
        assertEquals(48600, p.port)
        assertEquals("abc", p.token)
        assertEquals("My Mac", p.macName)
        assertEquals(fp, p.fp)
    }

    @Test
    fun `URL QR without fp stays unpinned`() {
        val p = LinkTls.parsePairingQr(base)
        assertNull(p.fp)
        assertEquals("abc", p.token)
    }

    @Test
    fun `URL QR with a malformed fp is rejected`() {
        rejects("$base&fp=tooshort")
        rejects("$base&fp=")
        rejects("$base&fp=${fp.dropLast(1)}+")
        rejects("$base&fp=${fp}A")
    }

    @Test
    fun `URL QR defaults and required fields`() {
        val p = LinkTls.parsePairingQr("droiddock://pair?ips=10.0.0.1&token=t")
        assertEquals(48484, p.port)
        assertEquals("Mac", p.macName)
        rejects("droiddock://pair?token=t")
        rejects("droiddock://pair?ips=10.0.0.1")
    }

    @Test
    fun `plus signs in the name stay literal like android Uri`() {
        assertEquals("A+B", LinkTls.parsePairingQr("droiddock://pair?ips=10.0.0.1&token=t&name=A+B").macName)
        assertEquals("A B", LinkTls.parsePairingQr("droiddock://pair?ips=10.0.0.1&token=t&name=A%20B").macName)
    }

    @Test
    fun `JSON QR with and without fp`() {
        val withFp = LinkTls.parsePairingQr("""{"ips":["10.0.0.1"],"port":48484,"token":"t","name":"M","fp":"$fp"}""")
        assertEquals(fp, withFp.fp)
        val without = LinkTls.parsePairingQr("""{"ips":["10.0.0.1"],"token":"t"}""")
        assertNull(without.fp)
        assertEquals(48484, without.port)
    }

    @Test
    fun `JSON QR with a malformed fp or missing fields is rejected`() {
        rejects("""{"ips":["10.0.0.1"],"token":"t","fp":"bad"}""")
        rejects("""{"ips":[],"token":"t"}""")
        rejects("""{"ips":["10.0.0.1"]}""")
        rejects("not a qr")
    }
}
