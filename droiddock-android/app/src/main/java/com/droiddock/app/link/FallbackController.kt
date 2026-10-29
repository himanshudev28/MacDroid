package com.droiddock.app.link

/**
 * BLE-DESIGN §3/§6 as pure decision logic, fed state and a clock, returning what
 * to do. Owns no timers and touches no Android classes: ConnectionManager polls
 * it from events and from the single wake-up time it returns in
 * [Decision.nextCheckMs].
 *
 * Nothing here can run unless the user chose the fallback mode: with
 * `fallback == false` every output is inert.
 */
class FallbackController(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        const val T_BACK_MS = 10_000L
        const val T_BACK_FLAP_MS = 20_000L
        const val DOWN_BEFORE_ADV_MS = 5_000L
        const val SILENCE_PING_MS = 8_000L
        const val PROBE_REPLY_MS = 8_000L
        const val DROP_BLE_AFTER_MS = 30_000L
        const val FLAP_WINDOW_MS = 120_000L
        const val FLAP_CALM_MS = 300_000L
        const val FLAP_MAX_CHANGES = 3
    }

    data class Inputs(
        val fallback: Boolean,
        /** Paused, manually disconnected, auth lock-out, or BLE unavailable. */
        val blocked: Boolean,
        /** Bluetooth adapter is on. */
        val radioOn: Boolean,
        /** NetworkWatch says a default network exists. */
        val online: Boolean,
        val wifiUp: Boolean,
        val wifiUpSince: Long,
        val wifiDownSince: Long,
        /** A full connectLoop round failed since Wi-Fi went down. */
        val roundFailed: Boolean,
        /** Wi-Fi: any inbound frame after the welcome (proof of a round trip). */
        val probeOk: Boolean,
        val lastInbound: Long,
        /** When we sent our own stall `ping`, 0 if none outstanding. */
        val probeSentAt: Long,
        val bleAuthed: Boolean,
        /** "Switch back automatically" (pref `bleAutoReturn`). Off = stay on Bluetooth until the user acts. */
        val autoReturn: Boolean = true,
        /** The user's one-shot choice from "Use Wi-Fi now" / "Use Bluetooth now" (or the Mac's link-prefer). */
        val prefer: Prefer = Prefer.AUTO,
    )

    enum class Prefer { AUTO, WIFI, BLE }

    enum class WifiAction { NONE, PING, KILL }

    data class Decision(
        val advertise: Boolean,
        /** Where ANY-class traffic goes; null = no link at all. */
        val primary: LinkKind?,
        val wifiStable: Boolean,
        val wifiAction: WifiAction,
        /** Phone cancels a BLE connection the Mac forgot to close. */
        val dropBle: Boolean,
        /** Ms until something time-based could change the answer (Long.MAX_VALUE = nothing). */
        val nextCheckMs: Long,
        /** Bluetooth is primary while a stable Wi-Fi link also exists: offer "Wi-Fi available, switch now". */
        val wifiAvailableWhileBle: Boolean = false,
    )

    private var advertising = false
    private var lastPrimary: LinkKind? = null
    private val changes = ArrayDeque<Long>()
    private var flapping = false
    private var lastChangeAt = 0L
    private var wifiPrimarySince = 0L

    private fun tBack() = if (flapping) T_BACK_FLAP_MS else T_BACK_MS

    fun evaluate(i: Inputs): Decision {
        val now = clock()
        if (!i.fallback) {
            advertising = false
            return Decision(false, if (i.wifiUp) LinkKind.WIFI else null, i.wifiUp, WifiAction.NONE, false, Long.MAX_VALUE)
        }
        var next = Long.MAX_VALUE
        fun due(at: Long) { if (at > now) next = minOf(next, at - now) }

        // Stall probe (fallback mode only): silence -> ping -> silence = lost.
        var action = WifiAction.NONE
        var stalled = false
        if (i.wifiUp) {
            val idle = now - i.lastInbound
            val outstanding = i.probeSentAt > i.lastInbound
            if (outstanding) {
                if (now - i.probeSentAt >= PROBE_REPLY_MS) { action = WifiAction.KILL; stalled = true }
                else due(i.probeSentAt + PROBE_REPLY_MS)
            } else if (idle >= SILENCE_PING_MS) action = WifiAction.PING
            else due(i.lastInbound + SILENCE_PING_MS)
        }

        val stableAt = i.wifiUpSince + tBack()
        val stable = i.wifiUp && !stalled && i.probeOk && now >= stableAt
        if (i.wifiUp && !stalled && i.probeOk && !stable) due(stableAt)

        // Priority (USB/ADB arrives as a Wi-Fi-kind WebSocket) > Wi-Fi > Bluetooth. A manual choice wins
        // while its link exists; with auto-return off a live Bluetooth link is kept even when Wi-Fi is stable.
        val primary = when {
            i.prefer == Prefer.WIFI && i.wifiUp -> LinkKind.WIFI
            i.prefer == Prefer.BLE && i.bleAuthed -> LinkKind.BLE
            stable && !(i.bleAuthed && !i.autoReturn) -> LinkKind.WIFI
            i.bleAuthed -> LinkKind.BLE
            i.wifiUp -> LinkKind.WIFI
            else -> null
        }
        if (primary != lastPrimary) {
            if (lastPrimary != null && primary != null) {
                changes.addLast(now); lastChangeAt = now
                while (changes.isNotEmpty() && now - changes.first() > FLAP_WINDOW_MS) changes.removeFirst()
                if (changes.size > FLAP_MAX_CHANGES) flapping = true
            }
            lastPrimary = primary
            wifiPrimarySince = if (primary == LinkKind.WIFI) now else 0L
        }
        if (flapping) {
            if (now - lastChangeAt >= FLAP_CALM_MS) { flapping = false; changes.clear() }
            else due(lastChangeAt + FLAP_CALM_MS)
        }

        val canRun = !i.blocked && i.radioOn
        // "Use Bluetooth now" (here or on the Mac) needs the beacon even while Wi-Fi is fine.
        val forcedBle = i.prefer == Prefer.BLE
        val startCond = forcedBle || !i.online || (!i.wifiUp && now - i.wifiDownSince >= DOWN_BEFORE_ADV_MS && i.roundFailed)
        if (!i.wifiUp && i.online && !i.roundFailed) due(i.wifiDownSince + DOWN_BEFORE_ADV_MS)
        if (!i.wifiUp && i.online && i.roundFailed && now - i.wifiDownSince < DOWN_BEFORE_ADV_MS) {
            due(i.wifiDownSince + DOWN_BEFORE_ADV_MS)
        }
        advertising = when {
            !canRun || i.bleAuthed -> false
            advertising -> !stable || forcedBle
            else -> startCond
        }

        var drop = false
        // Only drop a Bluetooth link that is no longer carrying traffic: never one the user
        // forced, or one kept because "Switch back automatically" is off.
        if (i.bleAuthed && stable && primary != LinkKind.BLE) {
            val at = stableAt + DROP_BLE_AFTER_MS
            if (now >= at) drop = true else due(at)
        }
        return Decision(advertising, primary, stable, action, drop, next, wifiAvailableWhileBle = primary == LinkKind.BLE && stable)
    }
}
