package com.droiddock.app.link.ble

import com.droiddock.app.link.Link
import com.droiddock.app.link.LinkKind
import com.droiddock.app.link.nextLinkId
import org.json.JSONObject

/** The authenticated BLE session as a [Link]. Binary frames never ride it. */
internal class BleLink(val conn: BleGattServer.Conn) : Link {
    override val id = nextLinkId()
    override val kind = LinkKind.BLE
    override fun sendJson(o: JSONObject): Boolean = conn.session.send(o)
    override fun sendBinary(b: ByteArray): Boolean = false
    override fun close() { conn.session.sendBye(1); conn.disconnect("closed by app") }
    /** Courtesy BYE, then drop after the Mac had time to drain (caller schedules). */
    fun dropNow() = conn.disconnect("dropped")
}
