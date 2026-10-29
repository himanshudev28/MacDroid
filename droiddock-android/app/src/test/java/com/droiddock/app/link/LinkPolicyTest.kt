package com.droiddock.app.link

import com.droiddock.app.link.FallbackController.Inputs
import com.droiddock.app.link.FallbackController.Prefer
import com.droiddock.app.link.FallbackController.WifiAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransportClassTest {
    private fun c(s: String, d: TransportClassifier.Dir = TransportClassifier.Dir.PHONE_TO_MAC) =
        TransportClassifier.classOf(JSONObject(s), d)

    @Test fun phoneToMac() {
        for (t in listOf("hello", "notification", "notification-removed", "reply-result", "call", "call-action-result",
            "media", "device-info", "sms-changed", "ring", "health", "health-fix", "sms-threads", "sms-messages",
            "sms-send", "ping", "pong", "mac-brightness-set", "pause", "resume", "link-mode", "link-prefer")) {
            assertEquals(t, TransportClass.ANY, c("""{"type":"$t"}"""))
        }
        assertEquals(TransportClass.ANY, c("""{"type":"clipboard","text":"x"}"""))
        assertEquals(TransportClass.WIFI, c("""{"type":"clipboard","kind":"image"}"""))
        for (a in listOf("key", "text", "media", "lock", "screensaver", "brightness", "volume_set")) {
            assertEquals(a, TransportClass.ANY, c("""{"type":"remote","action":"$a"}"""))
        }
        assertEquals(TransportClass.WIFI, c("""{"type":"remote","action":"mouse_move"}"""))
        for (t in listOf("photos-changed", "remote-button", "fs-list", "phone-push", "photos-list", "photo-thumb",
            "app-icon", "apps-list", "wallpaper", "screenshot", "mirror-start", "audio-x", "camera-start",
            "control-unavailable", "mac-fs-list", "mac-apps-list", "mac-app-launch", "contacts", "never-heard-of-it")) {
            assertEquals(t, TransportClass.WIFI, c("""{"type":"$t"}"""))
        }
    }

    @Test fun macToPhone() {
        val d = TransportClassifier.Dir.MAC_TO_PHONE
        for (t in listOf("welcome", "caps", "ping", "pong", "mac-info", "mac-media", "reply", "dismiss", "notif-action",
            "action-call", "action-sms", "call-action", "ring", "health", "health-fix", "media-cmd", "sms-threads",
            "sms-messages", "sms-send", "app-launch", "link-mode", "link-prefer")) {
            assertEquals(t, TransportClass.ANY, c("""{"type":"$t"}""", d))
        }
        assertEquals(TransportClass.WIFI, c("""{"type":"clipboard","kind":"image"}""", d))
        for (t in listOf("contacts", "fs-list", "photos-list", "photo-thumb", "app-icon", "apps-list", "wallpaper",
            "screenshot", "mirror-key", "camera-flip", "mac-fs-pull")) assertEquals(t, TransportClass.WIFI, c("""{"type":"$t"}""", d))
    }
}

class MessageIdsTest {
    @Test fun midFormatAndLru() {
        val m = MessageIds.next()
        assertTrue(Regex("[0-9a-z]{6}-\\d+").matches(m))
        assertNotEquals(m, MessageIds.next())
        val lru = MessageIds.Lru(3)
        assertFalse(lru.seenBefore("a")); assertTrue(lru.seenBefore("a"))
        lru.seenBefore("b"); lru.seenBefore("c"); lru.seenBefore("d")  // evicts a
        assertFalse(lru.seenBefore("a"))
    }

    @Test fun replayRingKeepsThirtySecondsPerLink() {
        var now = 0L
        val ring = MessageIds.ReplayRing(30_000) { now }
        ring.record(1, JSONObject().put("mid", "x-1")); now = 10_000
        ring.record(2, JSONObject().put("mid", "x-2")); now = 31_000
        ring.record(1, JSONObject().put("mid", "x-3"))
        assertEquals(listOf("x-3"), ring.drain(1).map { it.getString("mid") }) // x-1 expired
        assertEquals(listOf("x-2"), ring.drain(2).map { it.getString("mid") })
        assertTrue(ring.drain(1).isEmpty())
    }
}

class FallbackControllerTest {
    private var now = 1_000_000L
    private val c = FallbackController { now }

    private fun inp(
        fallback: Boolean = true, online: Boolean = true, wifiUp: Boolean = false, upSince: Long = 0, downSince: Long = now,
        roundFailed: Boolean = false, probeOk: Boolean = true, lastInbound: Long = now, probeSent: Long = 0,
        ble: Boolean = false, auto: Boolean = true, prefer: Prefer = Prefer.AUTO, blocked: Boolean = false, radio: Boolean = true,
    ) = Inputs(fallback, blocked, radio, online, wifiUp, upSince, downSince, roundFailed, probeOk, lastInbound, probe_(probeSent), ble, auto, prefer)
    private fun probe_(v: Long) = v

    @Test fun networkModeIsInert() {
        val d = c.evaluate(inp(fallback = false, online = false))
        assertFalse(d.advertise); assertEquals(WifiAction.NONE, d.wifiAction); assertEquals(Long.MAX_VALUE, d.nextCheckMs)
    }

    @Test fun advertisesImmediatelyWithoutNetwork() = assertTrue(c.evaluate(inp(online = false)).advertise)

    @Test fun advertisesOnlyAfterFiveSecondsAndAFailedRound() {
        val down = now
        assertFalse(c.evaluate(inp(downSince = down)).advertise)
        now += 6_000
        assertFalse(c.evaluate(inp(downSince = down)).advertise)               // round has not failed yet
        assertTrue(c.evaluate(inp(downSince = down, roundFailed = true)).advertise)
        now += 1_000
        assertTrue(c.evaluate(inp(downSince = down, roundFailed = true)).advertise)  // sticky
    }

    @Test fun neverWhenPausedBlockedOrRadioOff() {
        assertFalse(c.evaluate(inp(online = false, blocked = true)).advertise)
        assertFalse(c.evaluate(inp(online = false, radio = false)).advertise)
    }

    @Test fun stopsAdvertisingWhenBleConnects() {
        assertTrue(c.evaluate(inp(online = false)).advertise)
        assertFalse(c.evaluate(inp(online = false, ble = true)).advertise)
    }

    @Test fun wifiBecomesPrimaryOnlyAfterTenStableSeconds() {
        val up = now
        var d = c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now))
        assertEquals(LinkKind.BLE, d.primary)
        now += 9_000
        d = c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now))
        assertEquals(LinkKind.BLE, d.primary)
        now += 1_500
        d = c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now))
        assertEquals(LinkKind.WIFI, d.primary); assertTrue(d.wifiStable)
        assertFalse(d.advertise)
    }

    @Test fun noProbeMeansNotStable() {
        val up = now; now += 20_000
        assertEquals(LinkKind.BLE, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, probeOk = false, lastInbound = now)).primary)
    }

    @Test fun phoneDropsForgottenBleThirtySecondsAfterSwitchBack() {
        val up = now
        c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now))
        now += 11_000
        assertFalse(c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now)).dropBle)
        now += 31_000
        assertTrue(c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now)).dropBle)
    }

    @Test fun stallProbePingsThenKills() {
        val up = now
        assertEquals(WifiAction.NONE, c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = up)).wifiAction)
        now += 8_100
        assertEquals(WifiAction.PING, c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = up)).wifiAction)
        val sent = now
        now += 8_100
        assertEquals(WifiAction.KILL, c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = up, probeSent = sent)).wifiAction)
    }

    @Test fun autoReturnOffStaysOnBluetooth() {
        val up = now; now += 30_000
        val d = c.evaluate(inp(wifiUp = true, upSince = up, ble = true, auto = false, lastInbound = now))
        assertEquals(LinkKind.BLE, d.primary)
        assertTrue(d.wifiAvailableWhileBle)
        assertFalse(d.dropBle)
    }

    @Test fun manualChoiceWinsAndThenAutoReturns() {
        val up = now
        // Wi-Fi not yet stable, user taps Use Wi-Fi now
        assertEquals(LinkKind.WIFI, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now, prefer = Prefer.WIFI)).primary)
        // user taps Use Bluetooth now while Wi-Fi is stable
        now += 30_000
        assertEquals(LinkKind.BLE, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now, prefer = Prefer.BLE)).primary)
        assertEquals(LinkKind.WIFI, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now, prefer = Prefer.AUTO)).primary)
    }

    @Test fun flapGuardDoublesTheWindow() {
        var up = now
        repeat(5) {                                                              // 5 quick BLE<->Wi-Fi flips
            c.evaluate(inp(wifiUp = false, ble = true)); now += 1_000
            up = now
            c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now, prefer = Prefer.WIFI)); now += 1_000
        }
        up = now
        c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now))
        now += 12_000
        // 12 s > 10 s normal window, < 20 s flap window
        assertEquals(LinkKind.BLE, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now)).primary)
        now += 9_000
        assertEquals(LinkKind.WIFI, c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now)).primary)
    }

    @Test fun useBluetoothNowAdvertisesWhileWifiIsFine() {
        val up = now; now += 30_000
        assertFalse(c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = now)).advertise)
        assertTrue(c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = now, prefer = Prefer.BLE)).advertise)
        assertTrue(c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = now, prefer = Prefer.BLE)).advertise) // sticky
        assertFalse(c.evaluate(inp(wifiUp = true, upSince = up, lastInbound = now)).advertise)                    // back to auto
    }

    @Test fun keptBluetoothIsNeverDropped() {
        val up = now; now += 120_000
        assertFalse(c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now, prefer = Prefer.BLE)).dropBle)
        assertFalse(c.evaluate(inp(wifiUp = true, upSince = up, ble = true, auto = false, lastInbound = now)).dropBle)
        assertTrue(c.evaluate(inp(wifiUp = true, upSince = up, ble = true, lastInbound = now)).dropBle)
    }
}
