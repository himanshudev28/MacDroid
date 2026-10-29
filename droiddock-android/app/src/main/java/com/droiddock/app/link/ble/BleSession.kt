package com.droiddock.app.link.ble

import org.json.JSONObject
import java.security.SecureRandom

/**
 * L2 (BLE-DESIGN §4/§5), phone side: mutual challenge/response on the pairing
 * token, then every frame sealed with AES-256-GCM under a per-session key and a
 * strictly sequential counter. Pure logic: no Android classes and no timers
 * (the phone runs none; the Mac sends the keepalives), so it is unit-testable.
 *
 * Frames leave through [pollFrame], one at a time, in the order the GATT pump
 * pulls them. Sealing happens at poll time so counters follow transmit order
 * even though the outbox reorders by priority. Listener callbacks are always
 * invoked after the session lock is released.
 */
class BleSession(
    private val token: String,
    private val listener: Listener,
    private val nonce: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
) {
    interface Listener {
        /** Something is queued: pump it. */
        fun onWake()
        /** Handshake passed; the caller should now send `hello`. */
        fun onAuthenticated()
        fun onMessage(msg: JSONObject)
        fun onBye(reason: Int)
        /** The Mac's proof was wrong. FAIL is already queued; disconnect once it drains. */
        fun onAuthFailed()
        /** Unrecoverable (replay, gap, bad frame): drop the GATT connection now. */
        fun onFatal(why: String)
    }

    enum class State { IDLE, AWAIT_M_AUTH, LIVE, DEAD }

    companion object {
        const val CTL = 0x80
        const val DEFLATE = 0x01
        const val OP_P_HELLO = 0x01
        const val OP_M_AUTH = 0x02
        const val OP_P_AUTH = 0x03
        const val OP_PING = 0x10
        const val OP_PONG = 0x11
        const val OP_BYE = 0x12
        const val OP_FAIL = 0x7F
        const val MAX_QUEUE = 32
        const val DEFLATE_MIN = 200

        private val P2 = setOf("media", "device-info", "mac-info", "mac-media")

        /** Outbox priority (BLE-DESIGN §4): 1 interactive, 2 latest-wins state, 3 bulk replies. */
        fun priorityOf(type: String): Int = when {
            type in P2 -> 2
            type == "sms-threads" || type == "sms-messages" -> 3
            else -> 1
        }
    }

    /** [raw] is a finished unsealed frame; otherwise [plain] is sealed at poll time. */
    private class Out(val prio: Int, val type: String?, val flags: Int, val plain: ByteArray?, val raw: ByteArray?)

    @get:Synchronized var state = State.IDLE
        private set

    private val queue = ArrayList<Out>()
    private val events = ArrayList<() -> Unit>()
    private var nonceP = ByteArray(0)
    private var key: ByteArray? = null
    private var sendCtr = 0L
    private var recvCtr = 0L

    internal val sessionKeyForTest: ByteArray? @Synchronized get() = key

    private fun fire() {
        val todo = synchronized(this) { events.toList().also { events.clear() } }
        todo.forEach { it() }
    }

    private fun ctlRaw(vararg b: Int) = ByteArray(b.size + 1).also {
        it[0] = CTL.toByte(); b.forEachIndexed { i, v -> it[i + 1] = v.toByte() }
    }

    /** Front of the line, behind other P0 frames. */
    private fun addP0(o: Out) {
        val at = queue.indexOfFirst { it.prio > 0 }.let { if (it < 0) queue.size else it }
        queue.add(at, o)
    }

    /** CCCD enabled on P2M: begin the handshake with P_HELLO. */
    fun onSubscribed() {
        synchronized(this) {
            if (state != State.IDLE) return
            nonceP = nonce()
            state = State.AWAIT_M_AUTH
            addP0(Out(0, null, CTL, null, ctlRaw(OP_P_HELLO, 1) + nonceP))
            events += { listener.onWake() }
        }
        fire()
    }

    fun onFrame(frame: ByteArray) {
        synchronized(this) { process(frame) }
        fire()
    }

    private fun die(why: String) {
        state = State.DEAD
        events += { listener.onFatal(why) }
    }

    private fun process(frame: ByteArray) {
        if (state == State.DEAD || state == State.IDLE || frame.isEmpty()) return
        val flags = frame[0].toInt() and 0xFF
        if (flags and 0x7E != 0) return die("unknown flags")
        val body = frame.copyOfRange(1, frame.size)
        if (state == State.AWAIT_M_AUTH) {
            if (flags != CTL) return die("expected M_AUTH")
            return handleMAuth(body)
        }
        val ctr = BleCrypto.ctrOf(body)
        if (ctr != recvCtr) return die("counter mismatch")
        val opened = BleCrypto.open(key!!, BleCrypto.DIR_MAC, flags, body) ?: return die("bad tag")
        recvCtr++
        val plain = if (flags and DEFLATE != 0) BleCrypto.inflate(opened) ?: return die("bad deflate") else opened
        if (flags and CTL != 0) return handleCtl(plain)
        val msg = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        events += { listener.onMessage(msg) }
    }

    private fun handleMAuth(body: ByteArray) {
        if (body.size != 1 + 16 + 32 || (body[0].toInt() and 0xFF) != OP_M_AUTH) return die("bad M_AUTH")
        val nonceM = body.copyOfRange(1, 17)
        val proofM = body.copyOfRange(17, 49)
        if (!BleCrypto.constEq(proofM, BleCrypto.proofM(token, nonceP, nonceM))) {
            state = State.DEAD
            addP0(Out(0, null, CTL, null, ctlRaw(OP_FAIL, 1)))
            events += { listener.onWake() }
            events += { listener.onAuthFailed() }
            return
        }
        addP0(Out(0, null, CTL, null, ctlRaw(OP_P_AUTH) + BleCrypto.proofP(token, nonceP, nonceM)))
        key = BleCrypto.sessionKey(token, nonceP, nonceM)
        state = State.LIVE
        events += { listener.onWake() }
        events += { listener.onAuthenticated() }
    }

    private fun handleCtl(p: ByteArray) {
        if (p.isEmpty()) return
        when (p[0].toInt() and 0xFF) {
            OP_PING -> {
                addP0(Out(0, null, CTL, p.copyOf().also { it[0] = OP_PONG.toByte() }, null))
                events += { listener.onWake() }
            }
            OP_BYE -> {
                val r = if (p.size > 1) p[1].toInt() and 0xFF else 0
                events += { listener.onBye(r) }
            }
        }
    }

    /** Queue one app message. False = not live, too big for one frame, or queue full. */
    fun send(msg: JSONObject): Boolean {
        val type = msg.optString("type")
        val json = msg.toString().toByteArray(Charsets.UTF_8)
        var flags = 0
        var plain = json
        if (json.size >= DEFLATE_MIN && type != "clipboard") {
            val d = BleCrypto.deflate(json)
            if (d.size < json.size) { plain = d; flags = DEFLATE }
        }
        // flags + ctr + ciphertext + tag
        if (1 + 4 + plain.size + 16 > BleFraming.MAX_FRAME) return false
        val prio = priorityOf(type)
        synchronized(this) {
            if (state != State.LIVE) return false
            if (prio == 2) queue.removeAll { it.prio == 2 && it.type == type }
            if (queue.size >= MAX_QUEUE) {
                val victim = queue.indexOfFirst { it.prio == 2 }
                if (victim < 0) return false
                queue.removeAt(victim)
            }
            queue.add(queue.indexOfLast { it.prio <= prio } + 1, Out(prio, type, flags, plain, null))
        }
        listener.onWake()
        return true
    }

    /** Ask the Mac to close (sealed CTL BYE). */
    fun sendBye(reason: Int) {
        synchronized(this) {
            if (state != State.LIVE) return
            addP0(Out(0, null, CTL, byteArrayOf(OP_BYE.toByte(), reason.toByte()), null))
        }
        listener.onWake()
    }

    /** Next finished L2 frame to transmit, or null. */
    @Synchronized fun pollFrame(): ByteArray? {
        if (queue.isEmpty()) return null
        val o = queue.removeAt(0)
        o.raw?.let { return it }
        val k = key ?: return null
        val body = BleCrypto.seal(k, BleCrypto.DIR_PHONE, sendCtr++, o.flags, o.plain!!)
        return byteArrayOf(o.flags.toByte()) + body
    }

    @Synchronized fun hasPending() = queue.isNotEmpty()
    @get:Synchronized val isLive get() = state == State.LIVE

    @Synchronized fun kill() { state = State.DEAD; queue.clear() }
}
