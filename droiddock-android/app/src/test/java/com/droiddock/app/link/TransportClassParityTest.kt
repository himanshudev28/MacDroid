package com.droiddock.app.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Must agree with droiddock-native/core/src/transport_class.rs, or a message is dropped on one side over Bluetooth. */
class TransportClassParityTest {
    private fun cls(json: String, dir: TransportClassifier.Dir) = TransportClassifier.classOf(JSONObject(json), dir)

    @Test fun notifOpenRidesBluetoothBothWays() {
        assertEquals(TransportClass.ANY, cls("""{"type":"notif-open","key":"k"}""", TransportClassifier.Dir.MAC_TO_PHONE))
        assertEquals(TransportClass.ANY, cls("""{"type":"notif-open-result","ok":true}""", TransportClassifier.Dir.PHONE_TO_MAC))
        // Mac-dialled calls arm the phone's call pop-up; must work with only Bluetooth.
        assertEquals(TransportClass.ANY, cls("""{"type":"call-arm","number":"198"}""", TransportClassifier.Dir.MAC_TO_PHONE))
    }

    @Test fun clipboardTextOver16KiBWaitsForWifi() {
        val small = JSONObject().put("type", "clipboard").put("text", "a".repeat(16 * 1024)).toString()
        val big = JSONObject().put("type", "clipboard").put("text", "a".repeat(16 * 1024 + 1)).toString()
        assertEquals(TransportClass.ANY, cls(small, TransportClassifier.Dir.PHONE_TO_MAC))
        assertEquals(TransportClass.WIFI, cls(big, TransportClassifier.Dir.PHONE_TO_MAC))
        assertEquals(TransportClass.WIFI, cls(big, TransportClassifier.Dir.MAC_TO_PHONE))
    }
}
