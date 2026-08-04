package com.droiddock.app

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the link's JSON control messages (Tier C).
 *
 * The counterpart of the Mac's `crypto.rs` — same HKDF-SHA256 derivation over
 * the shared pairing token, same `{"type":"enc","n":…,"d":…}` envelope, same
 * 12-byte random nonce per message. Anything that changes here has to change
 * there too, which is what [INFO]'s version suffix is for.
 *
 * Scope, stated plainly: this covers JSON messages only. Binary frames — file
 * chunks, thumbnails, app icons, mirror video — stay in the clear, because they
 * ride a separate framing path on the hot transfer loops. It is not end-to-end
 * encryption of everything on this link.
 */
object LinkCrypto {
    /** Advertised in `hello.caps`; the Mac echoes it in `welcome.caps` if it engaged. */
    const val CAP = "enc"

    private const val INFO = "droiddock-link-v1"
    private const val SALT = "droiddock-hkdf-salt-v1"
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    private val rng = SecureRandom()

    /** HKDF-SHA256 (RFC 5869) — extract then expand. One 32-byte block, so the
     *  expand loop runs exactly once. */
    fun derive(token: String): SecretKeySpec {
        val hmac = Mac.getInstance("HmacSHA256")

        // extract
        hmac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
        val prk = hmac.doFinal(token.toByteArray())

        // expand
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update(INFO.toByteArray())
        hmac.update(1.toByte())
        return SecretKeySpec(hmac.doFinal(), "AES")
    }

    /** Wrap a plaintext message in the `enc` envelope. */
    fun seal(key: SecretKeySpec, plaintext: JSONObject): JSONObject {
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(plaintext.toString().toByteArray())
        return JSONObject()
            .put("type", "enc")
            .put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("d", Base64.encodeToString(sealed, Base64.NO_WRAP))
    }

    /** Unwrap an envelope, or null if it can't be authenticated. Callers drop
     *  nulls rather than falling back to plaintext — a failed tag means
     *  tampering or a key mismatch, and neither is safe to act on. */
    fun open(key: SecretKeySpec, envelope: JSONObject): JSONObject? = runCatching {
        val nonce = Base64.decode(envelope.optString("n"), Base64.NO_WRAP)
        if (nonce.size != NONCE_LEN) return null
        val sealed = Base64.decode(envelope.optString("d"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        JSONObject(String(cipher.doFinal(sealed)))
    }.getOrNull()
}
