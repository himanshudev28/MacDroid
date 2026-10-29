package com.droiddock.app.link

import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * The OkHttp socket as a [Link]. Sealing stays in ConnectionManager (`wire` /
 * `sealFrame`) because the session keys are negotiated in `welcome`, which that
 * object still owns; this class only binds one socket to the shared send path.
 */
internal class WsLink(
    private val socket: WebSocket,
    private val wire: (JSONObject) -> String,
    private val frame: (ByteArray) -> ByteArray,
) : Link {
    override val id = nextLinkId()
    override val kind = LinkKind.WIFI
    override fun sendJson(o: JSONObject): Boolean {
        val p = wire(o)
        return p.isNotEmpty() && socket.send(p)
    }
    override fun sendBinary(b: ByteArray): Boolean = socket.send(frame(b).toByteString())
    override fun close() { runCatching { socket.cancel() } }
    fun queueSize(): Long = socket.queueSize()
}
