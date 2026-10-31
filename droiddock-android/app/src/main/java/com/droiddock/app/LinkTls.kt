package com.droiddock.app

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for the Wi-Fi link: pinning, the bootstrap `tls-pin` message, and QR parsing.
 *
 * Pure JVM on purpose (no `Context`), so every rule here is unit-tested without
 * a device. Wire contract: fingerprint `fp` is base64url (no padding) of the
 * SHA-256 of the Mac certificate's DER SubjectPublicKeyInfo, always 43 chars.
 * The pin is the *key*, not the certificate, so the Mac can re-issue its
 * certificate without breaking a pairing.
 */
object LinkTls {
    /** Advertised in `hello.caps` on every link, TLS or plain. */
    const val CAP = "tls"
    const val PIN_TYPE = "tls-pin"
    /** Close reason used when the phone drops the plain link to reconnect over TLS. */
    const val UPGRADE_REASON = "tls-upgrade"
    private const val SIG_PREFIX = "droiddock-tls-pin-v1|"
    const val FP_LEN = 43

    /** Shown when a pinned Mac presents a different key. */
    const val IDENTITY_CHANGED = "This Mac's identity changed. Scan its QR code again to reconnect."

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun fingerprint(spkiDer: ByteArray): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

    /** Exactly 43 base64url characters that decode to 32 bytes (canonical form). */
    fun isValidFp(fp: String?): Boolean {
        if (fp == null || fp.length != FP_LEN) return false
        for (c in fp) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
            if (!ok) return false
        }
        return runCatching { b64.encodeToString(b64d.decode(fp)) == fp }.getOrDefault(false)
    }

    private fun hmac(token: String, fp: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((SIG_PREFIX + fp).toByteArray(Charsets.UTF_8))
    }

    fun pinSig(token: String, fp: String): String = b64.encodeToString(hmac(token, fp))

    /** Constant-time check of a `tls-pin` signature. False on any malformed input. */
    fun verifyPin(token: String, fp: String?, sig: String?): Boolean {
        if (sig == null || !isValidFp(fp) || token.isEmpty()) return false
        val got = runCatching { b64d.decode(sig) }.getOrNull() ?: return false
        return MessageDigest.isEqual(hmac(token, fp!!), got)
    }

    /**
     * Should a `tls-pin` message be acted on? Only when this Mac has no pin yet,
     * the link is plain, and the signature (keyed by the pairing token) checks
     * out. A pin is never replaced over a plain link; scanning a QR is the only
     * way to change one.
     */
    fun shouldAcceptPin(existingFp: String?, overTls: Boolean, fp: String?, sig: String?, token: String): Boolean =
        existingFp == null && !overTls && verifyPin(token, fp, sig)

    /** For the message handler: returns the fp to store, or null to ignore the message. */
    fun acceptPin(existingFp: String?, overTls: Boolean, msg: JSONObject, token: String): String? {
        val fp = msg.optString("fp").ifEmpty { null }
        val sig = msg.optString("sig").ifEmpty { null }
        return if (shouldAcceptPin(existingFp, overTls, fp, sig, token)) fp else null
    }

    fun urlFor(ip: String, port: Int, fp: String?): String =
        (if (fp != null) "wss://" else "ws://") + ip + ":" + port

    /** A non-empty stored pin that is not a valid fingerprint. Never silently becomes "unpinned". */
    fun isUnusablePin(fp: String?): Boolean = fp != null && !isValidFp(fp)

    /** Stand-in pin when a token is known to have been pinned but its entry can no longer be read. */
    const val PIN_UNREADABLE = "?"

    /**
     * The pin -> store -> close decision, pure. [store] persists the pin and returns
     * true only if it was actually written. The caller closes the socket and
     * reconnects only when this returns true.
     */
    fun decideUpgrade(existingFp: String?, overTls: Boolean, msg: JSONObject, token: String, store: (String) -> Boolean): Boolean {
        val fp = acceptPin(existingFp, overTls, msg, token) ?: return false
        return store(fp)
    }

    /** The Mac refuses a plain link from a device that used TLS before (it sends tls-pin first, then this close). */
    const val TLS_REQUIRED_CODE = 4003
    const val TLS_REQUIRED_REASON = "tls-required"
    const val TLS_REQUIRED_MSG = "This Mac only accepts secure connections. Scan its QR code again."
    const val NOT_TLS_MSG = "This Mac isn't using a secure connection. Update DroidDock on the Mac or scan its QR code again."

    fun isTlsRequiredClose(code: Int, reason: String?): Boolean =
        code == TLS_REQUIRED_CODE || reason == TLS_REQUIRED_REASON

    /** If the pin was just stored the reconnect (over wss) is already queued; otherwise tell the user. */
    fun onTlsRequiredClose(pinJustStored: Boolean): TlsRequiredAction =
        if (pinJustStored) TlsRequiredAction.RECONNECT_NOW else TlsRequiredAction.SHOW_ERROR

    /**
     * A welcome that follows a tls-upgrade reconnect shortly after an earlier welcome repeats the
     * connect-time side effects (media/notification/device-info pushes). [upgradeAt] is 0 when the
     * plain socket never got a welcome, in which case nothing was done yet and nothing is suppressed.
     */
    fun isDuplicateWelcome(upgradeAt: Long, now: Long, windowMs: Long = 15_000L): Boolean =
        upgradeAt > 0L && now - upgradeAt in 0..windowMs

    /**
     * Best-effort: the server answered our TLS ClientHello with something that is not TLS
     * (a plain-WebSocket Mac). Matches the usual Conscrypt / JSSE wording; false when unsure.
     */
    fun looksLikeNonTlsServer(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is javax.net.ssl.SSLException && e !is javax.net.ssl.SSLPeerUnverifiedException && !isPinMismatch(e)) {
                val m = e.message.orEmpty()
                if (m.contains("WRONG_VERSION_NUMBER", true) ||
                    m.contains("unrecognized SSL message", true) ||
                    m.contains("Unable to parse TLS packet header", true) ||
                    m.contains("plaintext connection", true) ||
                    m.contains("not an SSL/TLS record", true)
                ) return true
            }
            e = e.cause
        }
        return false
    }

    /** Drop the cached client for a fingerprint nothing uses any more. */
    fun dropClient(fp: String) { clients.remove(fp) }

    /** Walks the cause chain: OkHttp wraps the handshake failure in SSLHandshakeException. */
    fun isPinMismatch(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is PinMismatchException) return true
            e = e.cause
        }
        return false
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * A client that trusts exactly the key with fingerprint [fp]. Derived with
     * `newBuilder()` so the dispatcher and connection pool are shared with
     * [base]. Cached per fingerprint (the address book holds only a handful).
     * Hostname verification is off because we connect to bare IPs; the trust
     * manager pinning the key is what makes that safe.
     */
    fun clientFor(base: OkHttpClient, fp: String): OkHttpClient =
        clients.computeIfAbsent(fp) {
            val tm = PinnedTrustManager(fp)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            base.newBuilder()
                .sslSocketFactory(ctx.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .build()
        }

    /** Parse a scanned QR (`droiddock://pair?...` or the JSON form). Throws on anything invalid. */
    fun parsePairingQr(raw: String): Pairing {
        if (raw.startsWith("droiddock://pair")) {
            val q = HashMap<String, String>()
            raw.substringAfter('?', "").substringBefore('#').split('&').forEach { kv ->
                if (kv.isEmpty()) return@forEach
                val k = kv.substringBefore('=')
                // '+' is literal in these URLs (android.net.Uri never turned it into a space).
                val v = URLDecoder.decode(kv.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
                q.putIfAbsent(URLDecoder.decode(k, "UTF-8"), v)
            }
            val ips = (q["ips"] ?: "").split(",").filter { it.isNotBlank() }
            val token = q["token"] ?: ""
            require(ips.isNotEmpty() && token.isNotEmpty())
            val fp = q["fp"]
            require(fp == null || isValidFp(fp))
            return Pairing(ips, q["port"]?.toIntOrNull() ?: 48484, token, q["name"] ?: "Mac", fp)
        }
        val o = JSONObject(raw)
        val ips = mutableListOf<String>()
        val arr = o.optJSONArray("ips")
        if (arr != null) for (i in 0 until arr.length()) ips.add(arr.getString(i))
        require(ips.isNotEmpty() && o.has("token"))
        val fp = if (o.has("fp") && !o.isNull("fp")) o.getString("fp") else null
        require(fp == null || isValidFp(fp))
        return Pairing(ips, o.optInt("port", 48484), o.getString("token"), o.optString("name", "Mac"), fp)
    }
}

enum class TlsRequiredAction { RECONNECT_NOW, SHOW_ERROR }

/** The pinned Mac presented a different key than the one this phone paired with. */
class PinMismatchException(message: String) : CertificateException(message)

/**
 * Trusts one key and nothing else. Always throws on a mismatch, so this is not a
 * trust-all manager; it replaces chain validation (a self-signed cert never
 * validates) with a constant-time comparison of the SPKI fingerprint.
 */
class PinnedTrustManager(fp: String) : X509TrustManager {
    private val expected = fp.toByteArray(Charsets.US_ASCII)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        val got = LinkTls.fingerprint(chain[0].publicKey.encoded).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, got)) throw PinMismatchException("Mac identity changed")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Live link security for the UI. Written by ConnectionManager, read by the Home card. */
object LinkSecurity {
    /** null = no Wi-Fi link; true = TLS; false = plain (or legacy AES). */
    val encrypted = MutableStateFlow<Boolean?>(null)
    /** True after a pinned Mac presented the wrong key; cleared on the next good link or re-pair. */
    val identityChanged = MutableStateFlow(false)

    enum class Problem { TLS_REQUIRED, NOT_TLS }
    /** Set when the Mac demands TLS but the phone has no pin, or a pinned Mac is not speaking TLS. */
    val problem = MutableStateFlow<Problem?>(null)

    fun clearProblems() {
        identityChanged.value = false
        problem.value = null
    }
}
