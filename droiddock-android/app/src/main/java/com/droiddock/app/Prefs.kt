package com.droiddock.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** [fp] is the Mac's pinned TLS key fingerprint (see [LinkTls]); null = not pinned yet, plain `ws://`. */
data class Pairing(val ips: List<String>, val port: Int, val token: String, val macName: String, val fp: String? = null)

/** A Mac this phone has paired with before, plus when it was last linked.
 *  [lastSeenAt] is 0 for a device that has been paired but never reached. */
data class KnownDevice(
    val macName: String,
    val ips: List<String>,
    val port: Int,
    val token: String,
    val lastSeenAt: Long,
    val fp: String? = null,
) {
    fun toPairing() = Pairing(ips, port, token, macName, fp)
    /** Identity is the token: IPs move between networks and two Macs can share
     *  a name, but the token is minted per pairing and never collides. */
    val id: String get() = token
}

/**
 * Addresses in Tailscale's CGNAT range, `100.64.0.0/10`.
 *
 * These get special treatment when the stored address list is trimmed. A
 * tailnet address is the *only* one that keeps working when you leave the
 * house, and it is also the one nothing can rediscover: the UDP broadcast probe
 * and mDNS are both link-local, so once a `100.x` address falls off the list
 * there is no path back to it short of re-pairing. LAN addresses are cheap by
 * comparison — they get rediscovered the moment you're on the network.
 */
fun isTailnetAddress(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}

/**
 * Trim an address list to [max] without ever dropping the last tailnet address.
 *
 * The plain `take(n)` this replaces would silently evict a `100.x` address as
 * soon as a few LAN addresses churned through the front of the list — which
 * broke off-LAN reconnect in exactly the situation it exists for, and did it
 * invisibly.
 */
fun trimAddresses(ips: List<String>, max: Int): List<String> {
    val head = ips.distinct().take(max)
    if (head.any(::isTailnetAddress)) return head
    val tailnet = ips.firstOrNull(::isTailnetAddress) ?: return head
    // Displace the least-recently-useful entry rather than growing past `max`.
    return (head.dropLast(1) + tailnet).distinct()
}

// ── Address-book pure functions (JVM-testable) ──────────────────────────────

/** Decode the stored device list. JSON without `fp` loads as `fp = null`. */
fun decodeDevices(raw: String?): List<KnownDevice> = decodeDevicesOrNull(raw) ?: emptyList()

/** Like [decodeDevices] but null when the stored text is present and unreadable. */
fun decodeDevicesOrNull(raw: String?): List<KnownDevice>? {
    if (raw == null) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val token = o.optString("token").ifEmpty { return@mapNotNull null }
            val ips = o.optJSONArray("ips")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            if (ips.isEmpty()) return@mapNotNull null
            KnownDevice(
                macName = o.optString("macName", "Mac"),
                ips = ips,
                port = o.optInt("port", 48484),
                token = token,
                lastSeenAt = o.optLong("lastSeenAt", 0L),
                // Kept raw: a non-empty but invalid pin means "pinned to something unusable",
                // which must fail closed (see LinkTls.isUnusablePin), not become "unpinned".
                fp = o.optString("fp").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrNull()?.sortedByDescending { it.lastSeenAt }
}

/**
 * Keep at most [max] devices. [protectToken] (the device just paired, whose
 * lastSeenAt is still 0) is never evicted, and a pinned device is never evicted
 * in favour of an unpinned one; ties go to the most recently seen.
 */
fun capDevices(list: List<KnownDevice>, protectToken: String? = null, max: Int = 8): List<KnownDevice> =
    if (list.size <= max) list
    else list.sortedWith(
        compareByDescending<KnownDevice> { it.token == protectToken }
            .thenByDescending { it.fp != null }
            .thenByDescending { it.lastSeenAt }
    ).take(max)

/** Encode the device list (most recent first, capped at 8: it is a personal address book). */
fun encodeDevices(list: List<KnownDevice>): String {
    val arr = JSONArray()
    capDevices(list).sortedByDescending { it.lastSeenAt }.forEach { d ->
        val o = JSONObject()
            .put("macName", d.macName)
            .put("ips", JSONArray(d.ips))
            .put("port", d.port)
            .put("token", d.token)
            .put("lastSeenAt", d.lastSeenAt)
        if (d.fp != null) o.put("fp", d.fp)
        arr.put(o)
    }
    return arr.toString()
}

/**
 * Upsert on pairing: keeps `lastSeenAt`, and keeps the old `fp` when the new pairing has none.
 * A QR scan is [authoritative]: its `fp` (even null) replaces the stored pin, so re-pairing with
 * a Mac that no longer does TLS clears the pin instead of dialling wss:// forever.
 */
fun mergePairing(existing: List<KnownDevice>, p: Pairing, authoritative: Boolean = false): List<KnownDevice> {
    val prior = existing.firstOrNull { it.token == p.token }
    return capDevices(existing.filterNot { it.token == p.token } + KnownDevice(
        macName = p.macName,
        ips = p.ips,
        port = p.port,
        token = p.token,
        lastSeenAt = prior?.lastSeenAt ?: 0L,
        fp = if (authoritative) p.fp else p.fp ?: prior?.fp,
    ), protectToken = p.token)
}

/** Stamp a successful link: promote the working IP, refresh the name, keep `fp`. */
fun applySeen(existing: List<KnownDevice>, token: String, workingIp: String?, macName: String?, now: Long): List<KnownDevice> {
    val d = existing.firstOrNull { it.token == token } ?: return existing
    val ips = if (workingIp != null) listOf(workingIp) + d.ips.filter { it != workingIp } else d.ips
    return existing.filterNot { it.token == token } + d.copy(
        ips = trimAddresses(ips, 4),
        lastSeenAt = now,
        macName = macName?.takeIf { it.isNotBlank() } ?: d.macName,
    )
}

/** True when [token] is a known device with no pin yet (the only case a pin may be written). */
fun canPin(existing: List<KnownDevice>, token: String): Boolean =
    existing.any { it.token == token && it.fp == null }

/** Record a Mac's pin, only where the STORED pin is null. No-op for an unknown token or an existing pin. */
/** The address book without [token]'s entry, so its pin goes with it; every other entry is untouched. */
fun removeDevice(existing: List<KnownDevice>, token: String): List<KnownDevice> =
    existing.filterNot { it.token == token }

fun applyPin(existing: List<KnownDevice>, token: String, fp: String): List<KnownDevice> =
    existing.map { if (it.token == token && it.fp == null) it.copy(fp = fp) else it }

object Prefs {
    private const val FILE = "droiddock"

    fun save(ctx: Context, p: Pairing, fromQr: Boolean = false) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("ips", p.ips.joinToString(","))
            .putInt("port", p.port)
            .putString("token", p.token)
            .putString("macName", p.macName)
            .apply()
        rememberDevice(ctx, p, fromQr)
    }

    fun load(ctx: Context): Pairing? {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val token = sp.getString("token", null) ?: return null
        val ips = (sp.getString("ips", "") ?: "").split(",").filter { it.isNotBlank() }
        if (ips.isEmpty()) return null
        // The legacy keys carry no pin; it lives in the address book, keyed by token.
        // Fail closed: if the address book is unreadable but this token was pinned, say so.
        val fp = knownDevices(ctx).firstOrNull { it.token == token }?.fp
            ?: if (token in pinnedTokens(ctx)) LinkTls.PIN_UNREADABLE else null
        return Pairing(ips, sp.getInt("port", 48484), token, sp.getString("macName", "Mac") ?: "Mac", fp)
    }

    fun clear(ctx: Context) {
        // Deliberately not `.clear()` any more: that also wiped deviceId (which
        // the Mac keys its photo-sync ledger on), the theme choice and every
        // other setting. "Forget this Mac" should forget one Mac.
        val active = load(ctx)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove("ips").remove("port").remove("token").remove("macName")
            .apply()
        active?.let { forgetDevice(ctx, it.token) }
        LinkSecurity.clearProblems()
    }

    // ── Known devices ────────────────────────────────────────────────────────
    // A list rather than the single active Pairing above, so the home screen can
    // offer "last connected" and Quick Connect the way the reference does. The
    // single-Pairing keys stay the source of truth for *which* Mac the connect
    // loop is chasing; this is the address book beside it.

    private const val DEVICES = "knownDevices"

    fun knownDevices(ctx: Context): List<KnownDevice> =
        decodeDevices(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(DEVICES, null))

    private const val PINNED = "pinnedTokens"

    /** Tokens that have a pin, kept beside the device JSON so a corrupt JSON blob cannot silently unpin them. */
    private fun pinnedTokens(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(PINNED, emptySet()) ?: emptySet()

    /** True when the stored list is present but cannot be read. Writers other than a fresh pairing must not overwrite it. */
    private fun devicesCorrupt(ctx: Context): Boolean {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(DEVICES, null)
        return raw != null && decodeDevicesOrNull(raw) == null
    }

    private fun writeDevices(ctx: Context, list: List<KnownDevice>) {
        val capped = capDevices(list)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(DEVICES, encodeDevices(capped))
            .putStringSet(PINNED, capped.filter { it.fp != null }.map { it.token }.toSet())
            .apply()
    }

    fun rememberDevice(ctx: Context, p: Pairing, fromQr: Boolean = false) {
        writeDevices(ctx, mergePairing(knownDevices(ctx), p, fromQr))
    }

    /** Called on every successful link: stamps the time and promotes the IP that
     *  actually worked, so the next connect tries it first. */
    fun markSeen(ctx: Context, token: String, workingIp: String?, macName: String?) {
        if (devicesCorrupt(ctx)) return
        writeDevices(ctx, applySeen(knownDevices(ctx), token, workingIp, macName, System.currentTimeMillis()))
    }

    /** Store the Mac's TLS pin (from a verified `tls-pin` message). */
    /** Returns true only if the pin was actually stored (known device, no pin yet, list readable). */
    fun setPin(ctx: Context, token: String, fp: String): Boolean {
        if (devicesCorrupt(ctx)) return false
        val existing = knownDevices(ctx)
        if (!canPin(existing, token)) return false
        writeDevices(ctx, applyPin(existing, token, fp))
        return true
    }

    fun forgetDevice(ctx: Context, token: String) {
        if (devicesCorrupt(ctx)) return
        val existing = knownDevices(ctx)
        val gone = existing.firstOrNull { it.token == token }?.fp
        val rest = removeDevice(existing, token)
        writeDevices(ctx, rest)
        // Free the cached pinned client unless another Mac shares the fingerprint.
        if (gone != null && rest.none { it.fp == gone }) LinkTls.dropClient(gone)
    }

    /**
     * Seed the address book from a pairing made before the address book existed.
     *
     * [rememberDevice] only runs from [save], i.e. when you pair — so an install
     * that was already paired when it updated had the legacy `ips`/`port`/
     * `token`/`macName` keys and an empty device list. Everything keyed off that
     * list then silently rendered nothing: no Last Connected Device card, and
     * therefore no Quick Connect and no Disconnect. Worked perfectly on a fresh
     * pair, invisible on upgrade, which is the worst shape a migration bug takes.
     *
     * Idempotent: a no-op once the active pairing is present in the list.
     */
    fun migrateLegacyPairing(ctx: Context) {
        val active = load(ctx) ?: return
        if (knownDevices(ctx).any { it.token == active.token }) return
        rememberDevice(ctx, active)
    }

    /** Pause state: 0 = active, Long.MAX_VALUE = until the user resumes, else the
     *  epoch-millis deadline after which the link auto-resumes. */
    /** A stable per-install id, generated once. The Mac keys its photo-sync
     *  ledger on this rather than on "MANUFACTURER MODEL", which two identical
     *  phones share — and which made each of them skip the other's photos. */
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.getString("deviceId", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        sp.edit().putString("deviceId", fresh).apply()
        return fresh
    }

    fun setPausedUntil(ctx: Context, until: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("pausedUntil", until).apply()
    }

    fun pausedUntil(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("pausedUntil", 0L)

    // ── In-app updates ───────────────────────────────────────────────────────
    // DroidDock is sideloaded, so it has to look for its own new versions.
    // These two just rate-limit that: see UpdateChecker.kt.

    /** Epoch ms of the last completed automatic check, 0 if never. */
    fun lastUpdateCheck(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("lastUpdateCheck", 0L)

    fun setLastUpdateCheck(ctx: Context, at: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("lastUpdateCheck", at).apply()
    }

    /** Look for a new release on app open. On by default; the manual button in
     *  Settings works regardless. */
    fun autoCheckUpdates(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("autoCheckUpdates", true)

    fun setAutoCheckUpdates(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("autoCheckUpdates", v).apply()
    }

    /** When true (default), the Accessibility service auto-sends copies to the Mac.
     *  When false, phone→Mac clipboard is manual only (share sheet / "Send" button). */
    fun setClipboardAuto(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("clipAuto", v).apply()
        clipAutoCache = v
    }

    /**
     * Cached because [clipboardAuto] is read on the accessibility hot path.
     *
     * `ClipAccessibilityService.onAccessibilityEvent` checks it before doing
     * anything else, and that fires on every `TYPE_VIEW_TEXT_SELECTION_CHANGED`
     * — i.e. on every cursor movement while typing in *any* app on the phone.
     * A SharedPreferences lookup per keystroke is exactly the cost
     * `AccessibilityControl.enabled` was made a `@Volatile` field to avoid.
     * This is the same treatment; [setClipboardAuto] is the only writer, so the
     * cache cannot go stale.
     */
    @Volatile private var clipAutoCache: Boolean? = null

    fun clipboardAuto(ctx: Context): Boolean =
        clipAutoCache ?: ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("clipAuto", true)
            .also { clipAutoCache = it }

    /**
     * Which tab the app opens on.
     *
     * "dynamic" is the reference's own default and the more useful one: open on
     * Connect while unpaired or disconnected, Home once linked — so the first
     * screen is always the one with something to do on it.
     */
    /** Chosen UI language as a BCP-47 tag, or "" to follow the system. See [I18n]. */
    fun locale(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("locale", "") ?: ""

    fun setLocale(ctx: Context, tag: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("locale", tag).apply()
    }

    fun defaultTab(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("defaultTab", "dynamic") ?: "dynamic"

    fun setDefaultTab(ctx: Context, tab: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("defaultTab", tab).apply()
    }

    /** Post a notification naming the exception when the app crashes. On by
     *  default — this app's crashes happen in background services where there
     *  is otherwise nothing to see. Never suppresses the crash itself. */
    fun notifyOnCrash(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("notifyOnCrash", true)

    fun setNotifyOnCrash(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("notifyOnCrash", v).apply()
    }

    /** Pitch-black surfaces in dark mode, for OLED. Ignored in light mode. */
    fun pitchBlack(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("pitchBlack", false)

    fun setPitchBlack(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("pitchBlack", v).apply()
    }

    /** Keep this session's clipboard traffic in the Clipboard tab. On by
     *  default; the history never leaves memory either way. */
    fun clipboardHistory(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("clipHistory", true)

    fun setClipboardHistory(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("clipHistory", v).apply()
    }

    /**
     * What this phone calls itself to the Mac.
     *
     * Blank means "use the hardware name" — `MANUFACTURER MODEL` — which is
     * what every build before this one sent unconditionally. Stored rather than
     * derived so renaming survives reinstalls of the Mac app.
     */
    fun deviceName(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("deviceNameOverride", null)?.trim().orEmpty()

    fun setDeviceName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("deviceNameOverride", name.trim().take(60)).apply()
    }

    /**
     * "Expand networking" — keep trying the Mac's stored addresses even when
     * they aren't on this phone's subnet.
     *
     * DroidDock's discovery is LAN-shaped: the UDP broadcast probe and mDNS
     * both stop at the local link, so on a tailnet neither ever answers and the
     * connect loop gives up on a Mac that is perfectly reachable. With this on,
     * the loop keeps dialling the stored addresses — including Tailscale's
     * `100.64.0.0/10` range — instead of treating "not found on this LAN" as
     * "not there". Off by default because it means retrying addresses that will
     * never answer on a normal network.
     */
    fun expandNetworking(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("expandNetworking", false)

    fun setExpandNetworking(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("expandNetworking", v).apply()
    }

    /** "network" (Wi-Fi only, default) or "fallback" (Wi-Fi + Bluetooth fallback). Reading this
     *  touches no Bluetooth API. */
    fun connectionMode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("connectionMode", "network")
            .let { if (it == "fallback") it else "network" }

    fun connectionModeAt(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("connectionModeAt", 0L)

    fun setConnectionMode(ctx: Context, mode: String, at: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("connectionMode", if (mode == "fallback") mode else "network")
            .putLong("connectionModeAt", at).apply()
    }

    /** "Switch back automatically" while on Bluetooth. On by default. */
    fun bleAutoReturn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("bleAutoReturn", true)

    fun setBleAutoReturn(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("bleAutoReturn", v).apply()
    }

    /** Light / Dark / System. Defaults to SYSTEM, so an install that never opens
     *  the setting follows the phone instead of being pinned to the old
     *  dark-only look. */
    fun themeMode(ctx: Context): ThemeMode =
        ThemeMode.from(
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("themeMode", null)
        )

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("themeMode", mode.name).apply()
    }

    /**
     * Whether the Mac may drive this phone's screen (tap/swipe/type/back/home).
     *
     * Separate from [clipboardAuto] on purpose. Both ride the one accessibility
     * service, but they are different grants in the user's mind: wanting the
     * Mac to control the phone while mirroring does not imply wanting every
     * copy on this phone shipped to the Mac, and the reverse holds too. Tying
     * them to a single switch forced an all-or-nothing choice the service
     * itself never required.
     */
    fun screenControl(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("screenControl", true)

    fun setScreenControl(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("screenControl", v).apply()
    }

    /** When true, the Mac can start mirroring/camera without a per-time prompt on the
     *  phone (needs the "Display over other apps" permission to launch in the background). */
    fun setAutoMirror(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("autoMirror", v).apply()
    }

    fun autoMirror(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("autoMirror", false)
}
