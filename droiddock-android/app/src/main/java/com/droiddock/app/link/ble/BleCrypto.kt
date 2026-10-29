package com.droiddock.app.link.ble

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** BLE-DESIGN §3/§5 key schedule, rotating id, and the L2 AEAD + DEFLATE. Pure JVM. */
object BleCrypto {
    private val SALT = "droiddock-ble-v1".toByteArray()
    const val DIR_PHONE: Byte = 0x50
    const val DIR_MAC: Byte = 0x4D
    const val EPOCH_MS = 900_000L
    const val MAX_INFLATED = 256 * 1024

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    /** HKDF-SHA256, single output block (L <= 32), as the doc uses it. */
    fun hkdf(ikm: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        require(len in 1..32)
        val prk = hmac(SALT, ikm)
        return hmac(prk, info, byteArrayOf(1)).copyOf(len)
    }

    private fun tok(token: String) = token.toByteArray(Charsets.UTF_8)
    fun kRid(token: String) = hkdf(tok(token), "rid".toByteArray())
    fun kAuth(token: String) = hkdf(tok(token), "auth".toByteArray())

    fun epoch(unixMs: Long) = Math.floorDiv(unixMs, EPOCH_MS)

    fun u64(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

    fun rid(token: String, epoch: Long): ByteArray =
        hmac(kRid(token), "rid".toByteArray(), u64(epoch)).copyOf(8)

    fun proofM(token: String, nonceP: ByteArray, nonceM: ByteArray) =
        hmac(kAuth(token), "DDB1-M".toByteArray(), nonceP, nonceM)

    fun proofP(token: String, nonceP: ByteArray, nonceM: ByteArray) =
        hmac(kAuth(token), "DDB1-P".toByteArray(), nonceM, nonceP)

    fun sessionKey(token: String, nonceP: ByteArray, nonceM: ByteArray) =
        hkdf(tok(token), "session".toByteArray() + nonceP + nonceM)

    fun constEq(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun nonce(dir: Byte, ctr: Long): ByteArray =
        byteArrayOf(dir, 0, 0, 0) + u64(ctr)

    private fun aad(flags: Int, ctr: Long, dir: Byte): ByteArray =
        byteArrayOf(
            flags.toByte(), (ctr ushr 24).toByte(), (ctr ushr 16).toByte(), (ctr ushr 8).toByte(), ctr.toByte(), dir,
        )

    /** body = [ctr u32 BE][ciphertext || tag16] */
    fun seal(key: ByteArray, dir: Byte, ctr: Long, flags: Int, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, ctr)))
        c.updateAAD(aad(flags, ctr, dir))
        val ct = c.doFinal(plain)
        return byteArrayOf((ctr ushr 24).toByte(), (ctr ushr 16).toByte(), (ctr ushr 8).toByte(), ctr.toByte()) + ct
    }

    /** Counter field of a sealed body, or -1 when too short. */
    fun ctrOf(body: ByteArray): Long =
        if (body.size < 4 + 16) -1 else
            ((body[0].toLong() and 0xFF) shl 24) or ((body[1].toLong() and 0xFF) shl 16) or
                ((body[2].toLong() and 0xFF) shl 8) or (body[3].toLong() and 0xFF)

    /** Plaintext, or null if authentication fails. [dir] is the SENDER's direction. */
    fun open(key: ByteArray, dir: Byte, flags: Int, body: ByteArray): ByteArray? = runCatching {
        val ctr = ctrOf(body)
        if (ctr < 0) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, ctr)))
        c.updateAAD(aad(flags, ctr, dir))
        c.doFinal(body, 4, body.size - 4)
    }.getOrNull()

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(6, true)
        try {
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 16)
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally { d.end() }
    }

    /** Inflate raw DEFLATE; null if malformed or larger than [MAX_INFLATED]. */
    fun inflate(data: ByteArray): ByteArray? {
        val i = Inflater(true)
        try {
            i.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            return out.toByteArray()
        } catch (_: Exception) { return null } finally { i.end() }
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
