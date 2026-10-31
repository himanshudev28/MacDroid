package com.droiddock.app

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Pinning, the `tls-pin` bootstrap message and URL choice. JVM-only, no device. */
class LinkTlsTest {

    // Wire-contract test vector, shared with the Mac test suite.
    private val token = "acad86c1-6041-408c-bce7-2495dd338711"
    private val fp = "7iF8Sz-mw4_5-QGyqC_ptXO0NB7toSxHHpHmz7EXwfs"
    private val sig = "BR0ExWmPcRuTGOn21e-372ynCHAArP6SZdxUzbFbK4k"

    // Two P-256 self-signed certs generated once with openssl.
    private val certA = """
-----BEGIN CERTIFICATE-----
MIIBXDCCAQKgAwIBAgIURePkAUeZT38pTjCQVmTZ+vuiAvwwCgYIKoZIzj0EAwIw
FDESMBAGA1UEAwwJRHJvaWREb2NrMB4XDTI2MTAzMTEyMDQyMVoXDTM2MTAyODEy
MDQyMVowFDESMBAGA1UEAwwJRHJvaWREb2NrMFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEMjIaP84RvcsMb8TedX7vDO7vWa5xA4ji5x5s+vhH9X5LPLZTS59Tsfax
/O21l5TY81Ck7IlIJfO2oYbgdCkbvaMyMDAwHQYDVR0OBBYEFAcfEDiROCDC0c/C
xPRjPP8yUmwBMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAObR
togf/EMDeJDjR3mVEqWIe3aVZ9a+mXqrAMh3OpsmAiBLNaCHxLGfxqM1mHwEzxHL
a+Hr2KWi0Xe8hVWaHiBRrg==
-----END CERTIFICATE-----
"""
    private val certAFp = "Xyk_wGH8yYL1D7E_YjfBy2aaODRCsf-jBA4-20BdAdU"

    private val certB = """
-----BEGIN CERTIFICATE-----
MIIBXDCCAQKgAwIBAgIUJsKqibK1Opx0AYY3tZ2SYZS4WSEwCgYIKoZIzj0EAwIw
FDESMBAGA1UEAwwJRHJvaWREb2NrMB4XDTI2MTAzMTEyMDQyMVoXDTM2MTAyODEy
MDQyMVowFDESMBAGA1UEAwwJRHJvaWREb2NrMFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEYrW3KnRVevPDEFXTEXX8XHmaMa+ydD358c/I1rU9B37rBvRVBAm1q4Ue
sLOSqmeSxA25en1S3Rvx3FCV5rXW8KMyMDAwHQYDVR0OBBYEFFdbeWvWXpYeOLHd
1CFJGwIlhKxQMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgAPFE
SuyRdJRiZd3/6NwkJnbP9eHWWVnyiVg5RfViuxQCIQDNQF02s/RXAhr1xdidrNjq
+GqPodXtUdThH/bPFGyviQ==
-----END CERTIFICATE-----
"""

    private fun cert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.trim().toByteArray())) as X509Certificate

    // ── fingerprint ──────────────────────────────────────────────────────────

    @Test
    fun `fingerprint of a certificate key matches the precomputed value`() {
        assertEquals(certAFp, LinkTls.fingerprint(cert(certA).publicKey.encoded))
        assertEquals(43, certAFp.length)
    }

    @Test
    fun `isValidFp accepts 43 base64url characters and nothing else`() {
        assertTrue(LinkTls.isValidFp(fp))
        assertTrue(LinkTls.isValidFp(certAFp))
        assertFalse(LinkTls.isValidFp(null))
        assertFalse(LinkTls.isValidFp(""))
        assertFalse(LinkTls.isValidFp(fp.dropLast(1)))
        assertFalse(LinkTls.isValidFp(fp + "A"))
        assertFalse(LinkTls.isValidFp(fp.dropLast(1) + "+"))
        assertFalse(LinkTls.isValidFp(fp.dropLast(1) + "="))
    }

    // ── PinnedTrustManager ───────────────────────────────────────────────────

    @Test
    fun `pinned trust manager accepts the pinned certificate`() {
        PinnedTrustManager(certAFp).checkServerTrusted(arrayOf(cert(certA)), "ECDHE_ECDSA")
    }

    @Test
    fun `pinned trust manager rejects another key with PinMismatchException`() {
        try {
            PinnedTrustManager(certAFp).checkServerTrusted(arrayOf(cert(certB)), "ECDHE_ECDSA")
            fail("expected PinMismatchException")
        } catch (e: PinMismatchException) {
            assertTrue(LinkTls.isPinMismatch(e))
        }
    }

    @Test
    fun `pinned trust manager rejects an empty or null chain`() {
        val tm = PinnedTrustManager(certAFp)
        try { tm.checkServerTrusted(emptyArray(), "x"); fail() } catch (_: CertificateException) {}
        try { tm.checkServerTrusted(null, "x"); fail() } catch (_: CertificateException) {}
    }

    @Test
    fun `pinned trust manager refuses client certs and has no issuers`() {
        val tm = PinnedTrustManager(certAFp)
        try { tm.checkClientTrusted(arrayOf(cert(certA)), "x"); fail() } catch (_: CertificateException) {}
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun `isPinMismatch finds the exception wrapped in a handshake failure`() {
        val wrapped = javax.net.ssl.SSLHandshakeException("x").apply { initCause(PinMismatchException("y")) }
        assertTrue(LinkTls.isPinMismatch(wrapped))
        assertFalse(LinkTls.isPinMismatch(java.io.IOException("timeout")))
        assertFalse(LinkTls.isPinMismatch(null))
    }

    // ── tls-pin signature ────────────────────────────────────────────────────

    @Test
    fun `pinSig matches the shared test vector`() {
        assertEquals(sig, LinkTls.pinSig(token, fp))
    }

    @Test
    fun `verifyPin accepts the vector`() {
        assertTrue(LinkTls.verifyPin(token, fp, sig))
    }

    @Test
    fun `verifyPin rejects tampered fp, tampered sig, wrong token and bad base64`() {
        assertFalse(LinkTls.verifyPin(token, certAFp, sig))
        assertFalse(LinkTls.verifyPin(token, fp, sig.dropLast(1) + "A"))
        assertFalse(LinkTls.verifyPin("another-token", fp, sig))
        assertFalse(LinkTls.verifyPin(token, fp, "!!!not base64!!!"))
        assertFalse(LinkTls.verifyPin(token, fp, ""))
        assertFalse(LinkTls.verifyPin(token, fp, null))
        assertFalse(LinkTls.verifyPin(token, null, sig))
        assertFalse(LinkTls.verifyPin(token, "short", sig))
        assertFalse(LinkTls.verifyPin("", fp, sig))
    }

    // ── shouldAcceptPin ──────────────────────────────────────────────────────

    @Test
    fun `no pin yet on a plain link with a valid signature is accepted`() {
        assertTrue(LinkTls.shouldAcceptPin(null, false, fp, sig, token))
    }

    @Test
    fun `an existing pin is never replaced`() {
        assertFalse(LinkTls.shouldAcceptPin(certAFp, false, fp, sig, token))
        assertFalse(LinkTls.shouldAcceptPin(fp, false, fp, sig, token))
    }

    @Test
    fun `tls-pin over TLS is ignored`() {
        assertFalse(LinkTls.shouldAcceptPin(null, true, fp, sig, token))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFalse(LinkTls.shouldAcceptPin(null, false, fp, "bad", token))
        assertFalse(LinkTls.shouldAcceptPin(null, false, "bad", sig, token))
        assertFalse(LinkTls.shouldAcceptPin(null, false, null, null, token))
        assertFalse(LinkTls.shouldAcceptPin(null, false, fp, sig, "wrong"))
    }

    @Test
    fun `acceptPin reads fp and sig from the message and returns the fp to store`() {
        val msg = JSONObject().put("type", "tls-pin").put("fp", fp).put("sig", sig)
        assertEquals(fp, LinkTls.acceptPin(null, false, msg, token))
        assertNull(LinkTls.acceptPin(null, true, msg, token))
        assertNull(LinkTls.acceptPin(null, false, JSONObject().put("type", "tls-pin"), token))
    }

    // ── URL and client ───────────────────────────────────────────────────────

    @Test
    fun `urlFor picks wss when pinned and ws otherwise, including tailnet IPs`() {
        assertEquals("wss://192.168.1.5:48484", LinkTls.urlFor("192.168.1.5", 48484, fp))
        assertEquals("ws://192.168.1.5:48484", LinkTls.urlFor("192.168.1.5", 48484, null))
        assertEquals("wss://100.101.102.103:48600", LinkTls.urlFor("100.101.102.103", 48600, fp))
        assertEquals("ws://100.101.102.103:48600", LinkTls.urlFor("100.101.102.103", 48600, null))
    }

    @Test
    fun `clientFor caches per fingerprint and shares the base dispatcher and pool`() {
        val base = OkHttpClient.Builder().build()
        val a1 = LinkTls.clientFor(base, certAFp)
        val a2 = LinkTls.clientFor(base, certAFp)
        val b = LinkTls.clientFor(base, "3Pu3mbBlEGFdgMWI7ZswzoGXl5HFwoOtDpSzY9IndsQ")
        assertSame(a1, a2)
        assertNotSame(a1, b)
        assertSame(base.dispatcher, a1.dispatcher)
        assertSame(base.connectionPool, a1.connectionPool)
    }

    // ── decideUpgrade: pin -> store -> close ─────────────────────────────────

    private fun pinMsg() = JSONObject().put("type", "tls-pin").put("fp", fp).put("sig", sig)

    @Test
    fun `decideUpgrade upgrades only when the pin was actually stored`() {
        var stored: String? = null
        assertTrue(LinkTls.decideUpgrade(null, false, pinMsg(), token) { stored = it; true })
        assertEquals(fp, stored)
        // store refused (unknown token / pin already there): no upgrade, so no reconnect loop
        assertFalse(LinkTls.decideUpgrade(null, false, pinMsg(), token) { false })
    }

    @Test
    fun `decideUpgrade never calls store for a message it would ignore`() {
        var called = false
        val store: (String) -> Boolean = { called = true; true }
        assertFalse(LinkTls.decideUpgrade(certAFp, false, pinMsg(), token, store))
        assertFalse(LinkTls.decideUpgrade(null, true, pinMsg(), token, store))
        assertFalse(LinkTls.decideUpgrade(null, false, pinMsg(), "wrong", store))
        assertFalse(called)
    }

    @Test
    fun `unusable pins are recognised`() {
        assertTrue(LinkTls.isUnusablePin("junk"))
        assertTrue(LinkTls.isUnusablePin("?"))
        assertFalse(LinkTls.isUnusablePin(null))
        assertFalse(LinkTls.isUnusablePin(fp))
    }

    @Test
    fun `dropClient evicts the cached client`() {
        val base = OkHttpClient.Builder().build()
        val c1 = LinkTls.clientFor(base, fp)
        LinkTls.dropClient(fp)
        assertNotSame(c1, LinkTls.clientFor(base, fp))
    }

    // ── tls-pin before any welcome, 4003, duplicate welcome ──────────────────

    @Test
    fun `a tls-pin with no prior welcome is accepted and stored`() {
        // The Mac refusing plain sends tls-pin first; nothing gates it on a welcome.
        var stored: String? = null
        assertTrue(LinkTls.decideUpgrade(null, false, pinMsg(), token) { stored = it; true })
        assertEquals(fp, stored)
    }

    @Test
    fun `4003 tls-required with a pin just stored reconnects at once`() {
        assertEquals(TlsRequiredAction.RECONNECT_NOW, LinkTls.onTlsRequiredClose(true))
    }

    @Test
    fun `4003 tls-required without a stored pin shows an error`() {
        assertEquals(TlsRequiredAction.SHOW_ERROR, LinkTls.onTlsRequiredClose(false))
    }

    @Test
    fun `tls-required close is recognised by code or reason only`() {
        assertTrue(LinkTls.isTlsRequiredClose(4003, ""))
        assertTrue(LinkTls.isTlsRequiredClose(1008, "tls-required"))
        assertFalse(LinkTls.isTlsRequiredClose(1000, "tls-upgrade"))
        assertFalse(LinkTls.isTlsRequiredClose(1001, null))
    }

    @Test
    fun `duplicate welcome is suppressed only shortly after a welcomed upgrade`() {
        assertTrue(LinkTls.isDuplicateWelcome(1_000L, 2_000L))
        assertTrue(LinkTls.isDuplicateWelcome(1_000L, 16_000L))
        assertFalse(LinkTls.isDuplicateWelcome(1_000L, 16_001L))
        assertFalse(LinkTls.isDuplicateWelcome(0L, 2_000L)) // plain socket never got a welcome: do the pushes
        assertFalse(LinkTls.isDuplicateWelcome(5_000L, 4_000L)) // clock went backwards
    }

    @Test
    fun `non-TLS server is recognised from the handshake error text`() {
        assertTrue(LinkTls.looksLikeNonTlsServer(javax.net.ssl.SSLException("Unsupported or unrecognized SSL message")))
        assertTrue(LinkTls.looksLikeNonTlsServer(java.io.IOException("x", javax.net.ssl.SSLHandshakeException("error:100000f7:SSL routines:OPENSSL_internal:WRONG_VERSION_NUMBER"))))
        assertFalse(LinkTls.looksLikeNonTlsServer(javax.net.ssl.SSLHandshakeException("Mac identity changed").apply { initCause(PinMismatchException("x")) }))
        assertFalse(LinkTls.looksLikeNonTlsServer(java.io.IOException("timeout")))
        assertFalse(LinkTls.looksLikeNonTlsServer(null))
    }

    @Test
    fun `LinkSecurity clearProblems resets both states`() {
        LinkSecurity.identityChanged.value = true
        LinkSecurity.problem.value = LinkSecurity.Problem.TLS_REQUIRED
        LinkSecurity.clearProblems()
        assertEquals(false, LinkSecurity.identityChanged.value)
        assertNull(LinkSecurity.problem.value)
    }
}
