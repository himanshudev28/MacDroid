package com.droiddock.app.link

import org.json.JSONObject

/** BLE-DESIGN §2. ANY may ride Bluetooth; WIFI never does. Unknown types are WIFI. */
enum class TransportClass { ANY, WIFI }

object TransportClassifier {
    enum class Dir { PHONE_TO_MAC, MAC_TO_PHONE }

    /** Largest clipboard text (UTF-8 bytes) that goes over Bluetooth. */
    const val MAX_BLE_CLIPBOARD = 16 * 1024

    private val PHONE_ANY = setOf(
        "hello", "notification", "notification-removed", "reply-result", "call",
        "call-action-result", "media", "device-info", "clipboard", "sms-changed", "ring",
        "health", "health-fix", "sms-threads", "sms-messages", "sms-send", "ping", "pong",
        "remote", "mac-brightness-set", "pause", "resume", "link-mode", "link-prefer",
    )
    private val REMOTE_ANY = setOf("key", "text", "media", "lock", "screensaver", "brightness", "volume_set")

    private val MAC_ANY = setOf(
        "welcome", "caps", "ping", "pong", "clipboard", "mac-info", "mac-media", "reply", "dismiss",
        "notif-action", "action-call", "action-sms", "call-action", "ring", "health", "health-fix",
        "media-cmd", "sms-threads", "sms-messages", "sms-send", "app-launch", "link-mode", "link-prefer",
    )

    fun classOf(msg: JSONObject, dir: Dir = Dir.PHONE_TO_MAC): TransportClass {
        val type = msg.optString("type")
        val set = if (dir == Dir.PHONE_TO_MAC) PHONE_ANY else MAC_ANY
        if (type !in set) return TransportClass.WIFI
        if (type == "clipboard" && msg.optString("kind") == "image") return TransportClass.WIFI
        if (type == "remote" && dir == Dir.PHONE_TO_MAC && msg.optString("action") !in REMOTE_ANY) {
            return TransportClass.WIFI
        }
        return TransportClass.ANY
    }
}
