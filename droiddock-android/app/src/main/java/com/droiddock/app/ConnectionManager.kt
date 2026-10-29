package com.droiddock.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.droiddock.app.link.FallbackController
import com.droiddock.app.link.Link
import com.droiddock.app.link.LinkKind
import com.droiddock.app.link.MessageIds
import com.droiddock.app.link.Transport
import com.droiddock.app.link.TransportClass
import com.droiddock.app.link.TransportClassifier
import com.droiddock.app.link.WsLink
import com.droiddock.app.link.ble.BleLink
import com.droiddock.app.link.ble.BleStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ConnectionManager {
    // Capabilities this companion advertises to the Mac (Phase 6a app-link transport).
    // "photosync" (Phase 18) — Mac only expects `photos-changed` pings if this is present.
    // "wallpaper"/"apps" (Tier B) — the Mac only asks for those if advertised here,
    // so an older phone build simply never gets the requests.
    // "macinfo" is receive-only: it tells the Mac this build can display its
    // status, so an older phone never gets the pushes at all.
    // "lock" tells the Mac this build understands `mirror-key{key:"lock"}`
    // (GLOBAL_ACTION_LOCK_SCREEN). Without it the Mac hides its lock button
    // rather than showing one that silently does nothing on an older phone.
    private fun caps(): List<String> = buildList {
        addAll(listOf("info", "fs", "photos", "shot", "photosync", "wallpaper", "apps", "macinfo", "macmedia"))
        // Two independent routes to locking, and the Mac only needs to know
        // that *some* route exists. Evaluated per connection rather than once
        // at class init, because granting device admin can happen at any time
        // and the next handshake should reflect it.
        //
        // GLOBAL_ACTION_LOCK_SCREEN landed in Android 9 (API 28); device admin
        // has no such floor. Advertising "lock" with neither available would
        // put a button on the Mac that silently does nothing — the exact
        // failure this capability exists to prevent.
        if (Build.VERSION.SDK_INT >= 28 || LockAdmin.isActive(appCtx)) add("lock")
        // "callctl" says this build understands `call-action`. Like "lock" it is
        // evaluated per connection and gated on the grant actually being there,
        // so the Mac never renders an Answer button that cannot answer. The
        // per-call flags on the `call` message are the finer-grained answer —
        // this one only decides whether the Calls tab describes the feature at
        // all. See CallControl.kt.
        if (CallControl.available(appCtx)) add("callctl")
        // "health" says this build can answer the Mac's permission-health
        // request. Unconditional: unlike the two above it describes a message
        // this build understands rather than a grant it holds — the whole point
        // of the panel is to report grants that are *missing*.
        add("health")
        // "screenshot": the Mac can ask for a screenshot over Wi-Fi
        // (`{type:"screenshot",reqId}` -> `{type:"screenshot",reqId,data:<base64 PNG>}`
        // or `{...,error}`). Needs takeScreenshot (API 30+); whether the
        // accessibility service is actually on is answered per request.
        if (Build.VERSION.SDK_INT >= 30) add("screenshot")
        // "fsmkdir" gates the Finder mount's folder creation. Without it the
        // Mac refuses MKCOL outright rather than sending a message an older
        // phone would ignore, leaving Finder waiting on a folder that never
        // appears.
        add("fsmkdir")
        // "ring" is unconditional for the same reason as "health": it describes
        // a message this build understands, not a grant. The alarm stream needs
        // no permission, and VIBRATE is install-time.
        add("ring")
        // "mirrorpause": understands `mirror-pause` / `mirror-resume` (video only;
        // the Mac sends them when the mirror window is hidden so the encoder and
        // radio go quiet). Unconditional: it names a message, not a grant.
        add("mirrorpause")
        // "notifopen": understands `notif-open` (fire a notification's tap action).
        add("notifopen")
        // "notifopendisplay": `notif-open` may carry an optional `displayId` (fire the tap action on that display).
        add("notifopendisplay")
        add(LinkCrypto.CAP)
        add(LinkCrypto.CAP_FRAMES)
        // "ble": this build speaks BLE-DESIGN (link-mode / link-prefer / mid). Advertised whenever the
        // hardware exists, even in Wi-Fi-only mode; it creates no Bluetooth object.
        if (BleStack.hasBle(appCtx)) add("ble")
    }

    val connected = MutableStateFlow(false)
    val macName = MutableStateFlow<String?>(null)
    val lastEvent = MutableStateFlow("idle")
    // 0 = active; Long.MAX_VALUE = until resume; else epoch-ms deadline (auto-resume).
    val pausedUntil = MutableStateFlow(0L)
    // Phase 19 — capabilities the connected Mac advertised in its `welcome` (e.g. "macfs").
    // Empty for an older Mac build that doesn't send `caps` at all.
    val macCaps = MutableStateFlow<List<String>>(emptyList())

    /** The Mac's own status, as it last reported it. `null` until a Mac that
     *  supports this sends one — the Home row stays hidden rather than showing
     *  a card full of blanks. */
    data class MacInfo(
        val name: String,
        val battery: Int?,
        val charging: Boolean,
        val hasBattery: Boolean,
        /** Mac output volume 0–100. Null on a Mac build that predates Phase 2,
         *  which is what keeps the slider hidden rather than showing a made-up
         *  position. */
        val volume: Int? = null,
        /** Built-in display brightness 0..1; null on a Mac that can't report it. */
        val brightness: Float? = null,
    )
    val macInfo = MutableStateFlow<MacInfo?>(null)

    /** What the Mac is playing, as it last reported it (Phase 3).
     *
     *  [title] is empty whenever the Mac couldn't name the source — a player we
     *  can't read over AppleScript, or a browser tab that isn't on a media
     *  site. That is not the same as "nothing is playing", and it is not a
     *  reason to hide the transport controls: those work for every app on the
     *  Mac regardless, because they're posted as real media keys. */
    data class MacMedia(
        val playing: Boolean,
        val title: String,
        val artist: String,
        val app: String,
    )
    val macMedia = MutableStateFlow<MacMedia?>(null)

    /** One clip that crossed the link, in either direction. */
    data class ClipEntry(val text: String, val fromMac: Boolean, val at: Long)

    /**
     * This session's clipboard traffic, newest first.
     *
     * Deliberately in memory only, matching the reference's own promise that
     * "clipboard will clear after the session": everything you copy on either
     * device passes through here, so persisting it would turn a convenience
     * feature into a plaintext log of passwords and one-time codes sitting in
     * app storage. Killed with the process, and capped so a long session can't
     * grow without bound.
     */
    val clipHistory = MutableStateFlow<List<ClipEntry>>(emptyList())
    private const val CLIP_HISTORY_MAX = 50

    /** Deadline for the parallel "is anyone home" knock in [reachableFirst].
     *  Short on purpose: this is a LAN round trip, so a Mac that needs longer
     *  than this to accept a TCP connection is not the one we want to try
     *  first — and it is still tried, just after the addresses that answered. */
    private const val KNOCK_TIMEOUT_MS = 900

    private fun recordClip(text: String, fromMac: Boolean) {
        if (text.isBlank()) return
        if (!Prefs.clipboardHistory(appCtx)) return
        val prev = clipHistory.value.firstOrNull()
        // A copy that round-trips (phone → Mac → phone) would otherwise show up
        // twice within a second of itself.
        if (prev != null && prev.text == text) return
        clipHistory.value =
            (listOf(ClipEntry(text, fromMac, System.currentTimeMillis())) + clipHistory.value)
                .take(CLIP_HISTORY_MAX)
    }

    fun clearClipHistory() {
        clipHistory.value = emptyList()
    }

    /** Macs this phone has paired with, newest-seen first. Mirrors [Prefs] so the
     *  UI can observe it; refreshed on link-up and whenever pairing changes. */
    val knownDevices = MutableStateFlow<List<KnownDevice>>(emptyList())

    /** True while the link is deliberately down because the user hit Disconnect,
     *  as opposed to merely not having found the Mac yet. Drives whether Home
     *  offers "Quick Connect" or shows "Searching…". */
    val manuallyDisconnected = MutableStateFlow(false)

    // Phase 19 reverse file browsing: phone-originated mac-fs-* request/reply registry.
    // "pfs"-prefixed reqIds are a namespace deliberately separate from the Mac's own
    // numeric reqIds and from TransferManager's "pp${seq}" phone-push reqIds, so an
    // incoming mac-fs-* reply can never be misrouted into the wrong pending-table on
    // either side.
    private val macFsSeq = AtomicInteger(1)
    private fun nextMacFsReqId() = "pfs${macFsSeq.getAndIncrement()}"
    private val pendingMacFs = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wsLink: WsLink? = null
    @Volatile private var bleLink: BleLink? = null
    @Volatile private var lastFromMac: String? = null
    /// Tier C: set only when the Mac's welcome echoed "enc". Null means this
    /// session is plaintext — the default, and how every pre-Tier-C build ran.
    @Volatile private var linkKey: javax.crypto.spec.SecretKeySpec? = null
    /** Key for sealed binary frames; null = frames go out in the clear.
     *  Separate from [linkKey] because `enc` and `enc2` negotiate apart. */
    @Volatile private var frameKey: javax.crypto.spec.SecretKeySpec? = null
    // Last copy we couldn't deliver (link was down). Flushed on the next reconnect
    // so a copy made during a Wi-Fi blip / Mac restart still reaches the Mac.
    @Volatile private var pendingClip: String? = null
    private lateinit var appCtx: Context

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()

    private fun reloadPrefsState() {
        val ctx = appCtx
        pausedUntil.value = Prefs.pausedUntil(ctx)
        // Every entry point into the app funnels through ensureLoop, so this is
        // the one place a pre-address-book pairing is guaranteed to be seen.
        Prefs.migrateLegacyPairing(ctx)
        knownDevices.value = Prefs.knownDevices(ctx)
        manuallyDisconnected.value = pausedUntil.value == Long.MAX_VALUE
        connectionMode.value = Prefs.connectionMode(ctx)
        bleAutoReturn.value = Prefs.bleAutoReturn(ctx)
        // Brings Bluetooth up only if fallback is on AND permitted; otherwise a no-op.
        BleStack.sync(ctx)
    }

    fun ensureLoop(ctx: Context) {
        appCtx = ctx.applicationContext
        // Registered once, process-wide. This is what lets the loop below sleep
        // on the network instead of polling it — see [NetworkWatch].
        NetworkWatch.start(appCtx)
        if (!netWatching) {
            netWatching = true
            scope.launch { NetworkWatch.online.collect { reevaluateLinks() } }
        }
        // The SharedPreferences reads and the legacy-pairing migration used to
        // run right here, on whichever (usually main) thread called us; the
        // first read of the prefs file blocks on disk. They now run on the IO
        // scope, ahead of the loop, so ordering is unchanged for the loop.
        if (loopJob?.isActive == true) {
            scope.launch { reloadPrefsState() }
            return
        }
        loopJob = scope.launch {
            reloadPrefsState()
            connectLoop()
        }
    }

    /**
     * Drop the link and stop chasing the Mac until the user asks again.
     *
     * Deliberately implemented on top of [pause] rather than as a second
     * teardown path: an indefinite pause already does exactly this — closes the
     * socket, tells the Mac to stop its own reconnect attempts so it isn't left
     * hammering a phone that hung up, and parks the loop idle. The only thing
     * this adds is the flag that tells Home to offer Quick Connect instead of
     * reporting a pause the user never chose.
     */
    fun disconnect(ctx: Context) {
        pause(ctx, null)
        manuallyDisconnected.value = true
        lastEvent.value = "disconnected"
    }

    /** Reconnect now — the inverse of [disconnect]. Also clears a timed pause,
     *  since "connect now" is an unambiguous instruction either way. */
    fun quickConnect(ctx: Context) {
        manuallyDisconnected.value = false
        resume(ctx)
    }

    /** Make [device] the active Mac and connect to it immediately. */
    fun connectTo(ctx: Context, device: KnownDevice) = onPaired(ctx, device.toPairing())

    /**
     * Single entry point for "this is the Mac now" — QR scan, manual entry, or
     * picking another known device.
     *
     * All three previously had to remember to save the pairing, refresh the
     * address book, clear any pause, drop stale Mac state and kick the loop; one
     * of them forgetting a step is how a freshly-paired Mac ends up unreachable
     * because an old Disconnect is still in force.
     */
    fun onPaired(ctx: Context, pairing: Pairing) {
        appCtx = ctx.applicationContext
        Prefs.save(appCtx, pairing) // also upserts into the known-devices list
        knownDevices.value = Prefs.knownDevices(appCtx)
        macInfo.value = null // belongs to the Mac we're leaving
        macCaps.value = emptyList()
        frameKey = null
        BleStack.onPairingChanged()
        Prefs.setPausedUntil(appCtx, 0L)
        pausedUntil.value = 0L
        manuallyDisconnected.value = false
        restart(appCtx)
    }

    /** Drop [token] from the address book, keeping the flow in step. */
    fun forget(ctx: Context, token: String) {
        appCtx = ctx.applicationContext
        Prefs.forgetDevice(appCtx, token)
        knownDevices.value = Prefs.knownDevices(appCtx)
    }

    // ── Available Devices ────────────────────────────────────────────────────

    /** Macs currently advertising on this network. Addresses only — mDNS carries
     *  no token, so an entry here is connectable only if it is also a known
     *  device. */
    val discovered = MutableStateFlow<List<DiscoveredMac>>(emptyList())
    val scanning = MutableStateFlow(false)
    @Volatile private var scanJob: Job? = null

    /** Browse the LAN and publish the result to [discovered]. Cheap enough to
     *  call when the Connect screen opens; re-entrant calls are ignored rather
     *  than stacking multicast browses on top of each other. */
    fun scanNow(ctx: Context) {
        appCtx = ctx.applicationContext
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            scanning.value = true
            try {
                discovered.value = MdnsDiscovery.browse(appCtx)
            } catch (_: Exception) {
                // leave the previous list up; a failed sweep isn't evidence the
                // Mac went away
            } finally {
                scanning.value = false
            }
        }
    }

    /** The known device an advertised address belongs to, if any — this is what
     *  makes a discovered Mac actually connectable. Matched on address rather
     *  than name because the name is cosmetic and the address is what changed. */
    fun knownFor(mac: DiscoveredMac): KnownDevice? =
        knownDevices.value.firstOrNull { it.ips.contains(mac.ip) && it.port == mac.port }
            ?: knownDevices.value.firstOrNull { it.macName.equals(mac.name, ignoreCase = true) }

    /** Point the active pairing at a freshly-discovered address for a Mac we
     *  already hold a token for — the "it changed IP" recovery. */
    fun connectToDiscovered(ctx: Context, mac: DiscoveredMac, known: KnownDevice) {
        onPaired(
            ctx,
            known.toPairing().copy(
                ips = listOf(mac.ip) + known.ips.filter { it != mac.ip },
                port = mac.port,
            )
        )
    }

    /** Cancel the running loop and restart immediately — use after QR/manual re-pair
     *  so the new IPs are tried right away without waiting for the current attempt. */
    fun restart(ctx: Context) {
        appCtx = ctx.applicationContext
        pausedUntil.value = Prefs.pausedUntil(appCtx)
        loopJob?.cancel()
        loopJob = scope.launch { connectLoop() }
    }

    /** Forget-the-Mac teardown: stop the connect loop and drop any live socket.
     *  A later [ensureLoop] (e.g. after re-pairing) starts a fresh loop. */
    fun shutdown() {
        loopJob?.cancel()
        loopJob = null
        runCatching { ws?.close(1000, "unpaired") }
        ws = null
        wsLink = null
        runCatching { bleLink?.close() }
        bleLink = null
        refreshState()
        macName.value = null
        macInfo.value = null
        pausedUntil.value = 0L
        manuallyDisconnected.value = false
        lastEvent.value = "not paired"
    }

    /** Pause the link: stop reconnect attempts and ask the Mac to stop trying too.
     *  [durationMs] null → until the user resumes; otherwise auto-resumes after it. */
    fun pause(ctx: Context, durationMs: Long?) {
        appCtx = ctx.applicationContext
        val until = if (durationMs == null) Long.MAX_VALUE
                    else System.currentTimeMillis() + durationMs
        Prefs.setPausedUntil(appCtx, until)
        pausedUntil.value = until
        // best-effort: tell the Mac to stop its mDNS/reconnect attempts, then drop the link
        runCatching { currentPrimary()?.sendJson(JSONObject().put("type", "pause").put("until", until)) }
        runCatching { ws?.close(1000, "paused") }
        ws = null
        wsLink = null
        runCatching { bleLink?.close() }
        bleLink = null
        refreshState()
        macName.value = null
        lastEvent.value = "paused"
        ensureLoop(appCtx) // keep the idle loop running so it enforces + auto-resumes
    }

    /** Resume immediately: clear the pause and reconnect now. */
    fun resume(ctx: Context) {
        appCtx = ctx.applicationContext
        Prefs.setPausedUntil(appCtx, 0L)
        pausedUntil.value = 0L
        // Resuming from the Pause dialog also ends a Disconnect — both park the
        // loop the same way, so either "go again" affordance must clear both.
        manuallyDisconnected.value = false
        lastEvent.value = "resuming…"
        loopJob?.cancel()
        loopJob = null
        loopJob = scope.launch { connectLoop() }
    }

    private suspend fun connectLoop() {
        var backoff = 2_000L
        while (true) {
            val pairing = Prefs.load(appCtx)
            if (pairing == null) {
                lastEvent.value = "not paired"
                // Was `delay(3_000)`: a wake-up every three seconds, forever,
                // to re-read a preference that only changes when the user
                // pairs — and pairing calls `restart()`, which cancels this
                // loop outright. The long sleep is a backstop, not a poll.
                NetworkWatch.sleepOrWake(60_000)
                continue
            }
            // Honor a pause: idle without connecting until it expires / the user resumes.
            val until = Prefs.pausedUntil(appCtx)
            if (until != 0L) {
                val now = System.currentTimeMillis()
                if (now < until) {
                    pausedUntil.value = until
                    refreshState()
                    lastEvent.value = "paused"
                    // Sleep to the deadline rather than re-checking the clock
                    // every two seconds. `resume`/`restart`/`quickConnect` all
                    // cancel this job, so nothing needs us awake to notice
                    // them; only a *timed* pause expiring does, and that has a
                    // known time. Capped so a "pause indefinitely"
                    // (`Long.MAX_VALUE`) can't overflow the arithmetic.
                    NetworkWatch.sleepOrWake((until - now).coerceIn(0L, 60_000L))
                    continue
                }
                // deadline passed → auto-resume
                Prefs.setPausedUntil(appCtx, 0L)
                pausedUntil.value = 0L
                lastEvent.value = "resuming…"
            }

            // Nothing below can succeed without a network, and probing without
            // one is the single most expensive thing this app used to do:
            // four TCP dials, a UDP broadcast and a live mDNS browse, on a
            // 15s ceiling, all night, in airplane mode. Park instead.
            if (!NetworkWatch.online.value) {
                refreshState()
                lastEvent.value = "waiting for a network"
                NetworkWatch.awaitOnline()
                // A fresh network deserves a fresh, fast retry.
                backoff = 2_000L
            }

            var linked = false
            // Stale addresses are the norm, not the exception — a pairing
            // holds up to four and after a router reboot or a network change
            // most of them are dead. Dialed one at a time at OkHttp's 4s
            // connect timeout that is 16s of nothing before the discovery
            // probes even start, which is most of why a reconnect could take
            // half a minute. A parallel TCP knock finds who is actually home
            // in about one timeout, and only that address gets a real session.
            for (ip in reachableFirst(pairing)) {
                if (attempt(ip, pairing)) {
                    linked = true
                    break
                }
            }
            // "Expand networking": both probes below are link-local — the UDP
            // broadcast never leaves the subnet and mDNS multicast doesn't
            // traverse a tailnet — so off-LAN they can only ever burn ~4.5s per
            // round before failing. Skipping them makes retrying the stored
            // Tailscale address the whole loop, which is the point.
            val lanOnlyProbes = !Prefs.expandNetworking(appCtx)

            // Known IPs failed — broadcast on the LAN to find the Mac's current IP.
            // Handles WiFi-switch without re-pairing: both devices on new network,
            // Mac answers the broadcast, phone saves the fresh IP.
            var broadcastIp: String? = null
            if (!linked && lanOnlyProbes) {
                broadcastIp = discoverViaBroadcast(pairing.token, pairing.port + 1)
                if (broadcastIp != null && !pairing.ips.contains(broadcastIp)) {
                    lastEvent.value = "found Mac at $broadcastIp"
                    if (attempt(broadcastIp, pairing)) {
                        linked = true
                        Prefs.save(
                            appCtx,
                            pairing.copy(
                                ips = trimAddresses(listOf(broadcastIp) + pairing.ips, 4)
                            )
                        )
                    }
                }
            }
            // Tier C: last resort. Some routers drop directed broadcast or
            // isolate clients, so the probe above never gets an answer even
            // though the Mac is right there — multicast usually still works.
            // Auth is unchanged: this only supplies an address to try. Skip any
            // address the two paths above already exhausted this round.
            if (!linked && lanOnlyProbes) {
                val viaMdns = MdnsDiscovery.find(appCtx)
                if (viaMdns != null && viaMdns != broadcastIp && !pairing.ips.contains(viaMdns)) {
                    lastEvent.value = "found Mac at $viaMdns (mDNS)"
                    if (attempt(viaMdns, pairing)) {
                        linked = true
                        Prefs.save(
                            appCtx,
                            pairing.copy(
                                ips = trimAddresses(listOf(viaMdns) + pairing.ips, 4)
                            )
                        )
                    }
                }
            }
            if (!linked) {
                // One full round (knock, broadcast, mDNS) found nothing: the fallback may now start advertising.
                roundFailed = true
                reevaluateLinks()
            }
            backoff = if (linked) 2_000L else minOf(backoff * 2, 15_000L)
            // Interruptible: Wi-Fi coming back is exactly the moment the Mac
            // becomes reachable again, and waiting out the rest of a 15s
            // backoff to discover that is the difference between a reconnect
            // that feels instant and one that feels broken.
            NetworkWatch.sleepOrWake(backoff)
        }
    }

    /**
     * The pairing's addresses, ordered by which ones actually answered a TCP
     * knock just now, with the rest kept behind them as a fallback.
     *
     * A plain `connect()` to a dead LAN address costs the full connect timeout;
     * doing that serially across four stored addresses is the bulk of a slow
     * reconnect. These knocks run concurrently and share one short deadline, so
     * the whole probe costs about one timeout no matter how many addresses are
     * stored — and the winner is dialed for real immediately after.
     *
     * Unreachable addresses are still tried afterwards rather than dropped: the
     * knock is a hint, and a Mac that is slow to accept must not become
     * permanently unreachable because of it.
     */
    private suspend fun reachableFirst(pairing: Pairing): List<String> {
        val ips = pairing.ips
        if (ips.size < 2) return ips
        val alive = coroutineScope {
            ips.map { ip ->
                async(Dispatchers.IO) {
                    val ok = runCatching {
                        java.net.Socket().use { probe ->
                            probe.connect(
                                java.net.InetSocketAddress(ip, pairing.port),
                                KNOCK_TIMEOUT_MS,
                            )
                            true
                        }
                    }.getOrDefault(false)
                    ip to ok
                }
            }.awaitAll()
        }
        val (up, down) = alive.partition { it.second }
        return up.map { it.first } + down.map { it.first }
    }

    /** Tries one address; if the session opens, suspends until it dies. Returns true if it ever connected. */
    private suspend fun attempt(ip: String, pairing: Pairing): Boolean {
        val closed = CompletableDeferred<Unit>()
        var didConnect = false
        lastEvent.value = "connecting to $ip…"

        val request = Request.Builder().url("ws://$ip:${pairing.port}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildHello(pairing)
                webSocket.send(hello.toString())
            }

            // OkHttp delivers messages on its own reader thread and does not catch
            // what a listener throws — anything escaping here reaches the thread's
            // default handler and kills the process. One bad message from the Mac,
            // one revoked permission, one OEM quirk in a handler below used to take
            // the whole app down. Contain it: drop the message, keep the link.
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(webSocket, text) }.onFailure { e ->
                    lastEvent.value = "message failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            private var thisLink: WsLink? = null

            private fun handleMessage(webSocket: WebSocket, text: String) {
                val outer = runCatching { JSONObject(text) }.getOrNull() ?: return
                // Tier C: unwrap first so every branch below keeps seeing
                // plaintext. An envelope that fails to authenticate is dropped,
                // never routed.
                val msg = if (outer.optString("type") == "enc") {
                    val key = linkKey ?: return
                    LinkCrypto.open(key, outer) ?: return
                } else outer
                lastInbound = System.currentTimeMillis()
                if (msg.optString("type") == "welcome") {
                    didConnect = true
                    onWsWelcome(WsLink(webSocket, ::wire, ::sealOut).also { thisLink = it }, webSocket, msg, pairing, ip)
                } else {
                    if (wifiUpSince != 0L) probeOk = true
                    dispatch(thisLink ?: return, msg)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastInbound = System.currentTimeMillis()
                runCatching {
                    val plain = openFrame(bytes.toByteArray()) ?: return@runCatching
                    TransferManager.onBinary(plain.toByteString())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cleanup(webSocket)
                closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cleanup(webSocket)
                closed.complete(Unit)
            }
        })

        // `closed.await()` is cancellable, and `restart`/`resume`/`onPaired`
        // all cancel this job while it is parked right here. Without the
        // finally, `socket.cancel()` never ran: the OkHttp WebSocket, its
        // reader thread and its connection stayed alive, so repeated Quick
        // Connect taps stacked live sockets.
        try {
            closed.await()
        } finally {
            socket.cancel()
        }
        return didConnect
    }

    private fun cleanup(socket: WebSocket) {
        if (ws !== socket) return
        val dead = wsLink
        ws = null
        wsLink = null
        wifiUpSince = 0L
        wifiDownSince = System.currentTimeMillis()
        roundFailed = false
        probeOk = false
        probeSentAt = 0L
        if (prefer == FallbackController.Prefer.WIFI) prefer = FallbackController.Prefer.AUTO
        TransferManager.detach() // abort transfers; JSON features recover on reconnect
        // Fail any mac-fs-list/mac-fs-pull call still waiting on a reply so it doesn't
        // hang forever; a generic "-error" type satisfies either caller's check below.
        val dropped = JSONObject().put("type", "mac-fs-link-error").put("error", "link dropped")
        pendingMacFs.values.forEach { it.complete(dropped) }
        pendingMacFs.clear()
        if (bleLink == null) {
            lastEvent.value = "disconnected"
            // Stale Mac battery is worse than no Mac battery: the reading stops being true when the last link drops.
            macInfo.value = null
            macMedia.value = null
        } else {
            lastEvent.value = "Wi-Fi lost, using Bluetooth"
        }
        dead?.let { onLinkDown(it) }
        refreshState()
        reevaluateLinks()
    }

    /** Auto mode (+ "Display over other apps") launches the consent flow directly with no
     *  tap on the phone; otherwise it posts a tappable notification. */
    private fun launchMirror(source: String, facing: Int) {
        val auto = Prefs.autoMirror(appCtx) && android.provider.Settings.canDrawOverlays(appCtx)
        if (auto) MirrorPermissionActivity.start(appCtx, source, facing)
        else MirrorPermissionActivity.request(appCtx, source, facing)
    }

    // ── Message routing (shared by every link) ──────────────────────────────

    private val seenMids = MessageIds.Lru()

    /**
     * Handle one decoded message from the Mac. Both the Wi-Fi socket and the Bluetooth session call this,
     * so there is exactly one set of handlers; replies go back on [link], the link the request arrived on.
     */
    internal fun dispatch(link: Link, msg: JSONObject) {
        val mid = msg.optString("mid")
        if (mid.isNotEmpty() && seenMids.seenBefore(mid)) return
        // Defence in depth: a Wi-Fi-only message arriving over Bluetooth is never routed.
        if (link.kind == LinkKind.BLE &&
            TransportClassifier.classOf(msg, TransportClassifier.Dir.MAC_TO_PHONE) == TransportClass.WIFI
        ) return
        when (msg.optString("type")) {
            "welcome" -> if (link.kind == LinkKind.BLE) onBleWelcome(link, msg)
            "link-mode" -> applyLinkMode(msg)
            "link-prefer" -> applyLinkPrefer(msg)
            // The Mac re-advertising what it can do, after a switch was
            // flipped in its Settings mid-session. Caps used to arrive
            // only in `welcome`, so turning "Mac files" on over there
            // did nothing visible here until the socket happened to be
            // remade — the Mac Files tab is gated on the `macfs` cap.
            //
            // Deliberately does NOT touch `linkKey`/`frameKey`:
            // encryption is negotiated once, in the handshake, and
            // re-keying the live socket from a later message would
            // desync both ends mid-stream. The Mac re-sends the enc
            // caps here unchanged for exactly that reason.
            "caps" -> msg.optJSONArray("caps")?.let { arr ->
                macCaps.value = List(arr.length()) { i -> arr.optString(i) }
            }
            // Tier C link-quality probe. Echo `t` back untouched — it's
            // the Mac's own clock reading, so this needs no time sync
            // here, and answering in the message loop (rather than at
            // the OkHttp ping layer) is what proves this loop is alive.
            "ping" -> reply(
                link, JSONObject().put("type", "pong").put("t", msg.optLong("t"))
            )
            "clipboard" -> {
                // An image carries `kind:"image"` and no `text` at all,
                // so a build that predates this simply skipped it —
                // which is what makes the shape safe to add.
                if (msg.optString("kind") == "image") {
                    val n = ClipImage.receive(appCtx, msg.optString("data"))
                    lastEvent.value =
                        if (n != null) "image ← Mac" else "image blocked by Android"
                } else {
                    val t = msg.optString("text")
                    if (t.isNotEmpty()) setClipboard(t)
                }
            }
            // The Mac telling us about itself, so Home can show both
            // devices. Purely informational — nothing here acts on it,
            // and an absent/older Mac simply leaves the row hidden.
            "mac-info" -> macInfo.value = MacInfo(
                name       = msg.optString("name").ifEmpty { macName.value ?: "Mac" },
                battery    = if (msg.isNull("battery")) null else msg.optInt("battery"),
                charging   = msg.optBoolean("charging", true),
                hasBattery = msg.optBoolean("hasBattery", false),
                volume     = if (msg.isNull("volume")) null else msg.optInt("volume"),
                // Lenient: absent, null or garbage all mean "unknown".
                brightness = if (!msg.has("brightness") || msg.isNull("brightness")) null
                             else msg.optDouble("brightness", Double.NaN)
                                 .takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f)
            )
            "mac-media" -> macMedia.value = MacMedia(
                playing = msg.optBoolean("playing", false),
                title   = msg.optString("title"),
                artist  = msg.optString("artist"),
                app     = msg.optString("app"),
            )
            "reply" -> {
                val ok = NotifStore.reply(appCtx, msg.optString("key"), msg.optString("text"))
                reply(link, JSONObject().put("type", "reply-result").put("ok", ok))
                lastEvent.value = if (ok) "reply → app" else "reply failed"
            }
            "dismiss" -> NotifStore.dismiss(msg.optString("key"))
            // AirSync v4 parity: tap a notification's action button on
            // the Mac and fire the original PendingIntent here.
            "notif-action" -> {
                val ok = NotifStore.fireAction(msg.optInt("index"), msg.optString("key"))
                lastEvent.value = if (ok) "action → app" else "action failed"
            }
            // Mac clicked a notification: fire its own tap action (opens the exact chat).
            // Answers with notif-open-result {ok, locked?}; an unknown key fires nothing.
            "notif-open" -> {
                val key = msg.optString("key")
                val r = NotifStore.open(appCtx, key, msg.optInt("displayId", -1))
                val out = JSONObject().put("type", "notif-open-result")
                    .put("reqId", msg.opt("reqId") ?: 0).put("key", key).put("ok", r?.ok == true)
                if (r?.locked == true) out.put("locked", true)
                reply(link, out)
                lastEvent.value = if (r?.ok == true) "open → app" else "open failed"
            }
            "sms-threads" -> respond(link, msg, "sms-threads") {
                it.put("threads", SmsRepo.threads(appCtx))
            }
            "sms-messages" -> respond(link, msg, "sms-messages") {
                val tid = msg.optLong("threadId")
                val (address, messages) = SmsRepo.messages(appCtx, tid, if (link.kind == LinkKind.BLE) 50 else 60)
                it.put("threadId", tid).put("address", address).put("messages", messages)
            }
            "sms-send" -> respond(link, msg, "sms-send") {
                SmsRepo.send(appCtx, msg.optString("address"), msg.optString("text"))
                it.put("ok", true)
            }
            "contacts" -> respond(link, msg, "contacts") {
                it.put("contacts", ContactsRepo.list(appCtx))
            }
            "fs-list" -> respond(link, msg, "fs-list") {
                it.put("entries", FileRepo.list(msg.optString("path")))
            }
            "fs-delete" -> respond(link, msg, "fs-delete") {
                FileRepo.delete(msg.optString("path"))
                it.put("ok", true)
            }
            // MKCOL from the Finder mount. The only write the mount
            // needed that had no phone-side operation already.
            "fs-mkdir" -> respond(link, msg, "fs-mkdir") {
                it.put("path", FileRepo.mkdir(msg.optString("path"), msg.optString("name")))
            }
            "fs-rename" -> respond(link, msg, "fs-rename") {
                val newPath = FileRepo.rename(msg.optString("path"), msg.optString("newName"))
                it.put("ok", true).put("newPath", newPath)
            }
            "fs-pull", "fs-push-begin", "fs-push-done", "fs-cancel",
            "phone-push", "phone-push-result" ->
                TransferManager.onControl(msg)
            "mac-fs-list-result", "mac-fs-list-error", "mac-fs-pull-begin" -> {
                pendingMacFs.remove(msg.optString("reqId"))?.complete(msg)
            }
            // Mac apps ride the same pending registry as mac-fs: both are
            // phone-initiated requests to the Mac, keyed by our own reqId.
            "mac-apps-list", "mac-app-launch" -> {
                pendingMacFs.remove(msg.optString("reqId"))?.complete(msg)
            }
            "mac-fs-pull-done" -> TransferManager.onMacFsPullControl(msg)
            "mac-fs-pull-error" -> {
                // Arrives either before a transfer begins (still in this registry,
                // e.g. a bad path) or mid-transfer after mac-fs-pull-begin already
                // handed off to TransferManager — the message has no transferId to
                // tell us which, so check both in order.
                val pending = pendingMacFs.remove(msg.optString("reqId"))
                if (pending != null) pending.complete(msg)
                else TransferManager.onMacFsPullControl(msg)
            }
            "photos-list" -> respond(link, msg, "photos-list") {
                it.put("items", PhotoRepo.list(appCtx, msg.optInt("offset", 0), msg.optInt("limit", 500)))
            }
            "photo-thumb" -> {
                val reqId = msg.optInt("reqId")
                val id = msg.optLong("id")
                val kind = msg.optString("kind", "image")
                scope.launch {
                    try {
                        TransferManager.sendThumb(reqId, PhotoRepo.thumbBytes(appCtx, id, kind))
                    } catch (e: Exception) {
                        reply(
                            link,
                            JSONObject().put("type", "photo-thumb-error")
                                .put("reqId", reqId).put("error", e.message ?: "thumb failed")
                        )
                    }
                }
            }
            "action-call" -> {
                val number = msg.optString("number")
                if (number.isNotEmpty()) {
                    val canCall = appCtx.checkSelfPermission(
                        android.Manifest.permission.CALL_PHONE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    // place the call directly if allowed, else just open the dialer
                    val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    val intent = Intent(action).setData(Uri.parse("tel:$number"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Arm before starting: on a fast dial the OFFHOOK
                    // broadcast can beat us back here, and an unarmed
                    // receiver drops it as a call the Mac never knew
                    // about — leaving the Mac stuck on "Calling…".
                    if (canCall) CallReceiver.armOutgoing(appCtx, number)
                    runCatching { appCtx.startActivity(intent) }
                }
            }
            // Answer / hang up / mute / speaker for the current call.
            // Replies with the audio state the device really ended up in
            // rather than the state that was asked for — see CallControl.
            "call-action" -> respond(link, msg, "call-action-result") {
                val action = msg.optString("action")
                require(action in CallControl.ACTIONS) { "unknown call action" }
                val on = if (msg.has("on")) msg.optBoolean("on") else null
                val outcome = CallControl.perform(appCtx, action, on)
                it.put("ok", outcome.ok)
                outcome.error?.let { e -> it.put("error", e) }
                outcome.muted?.let { m -> it.put("muted", m) }
                outcome.speaker?.let { sp -> it.put("speaker", sp) }
            }
            // Find the phone. `on:false` stops it; the phone also stops
            // itself after a minute and from its own notification, so a
            // Mac that walks out of range can't leave it ringing.
            "ring" -> respond(link, msg, "ring") {
                val on = msg.optBoolean("on", true)
                if (on) {
                    Ringer.start(appCtx, msg.optInt("seconds", Ringer.DEFAULT_SECONDS))
                        .getOrThrow()
                } else {
                    Ringer.stop(appCtx)
                }
                it.put("ringing", Ringer.ringing)
            }
            // Every grant this app needs, and what breaks without each —
            // see PermissionHealth. Answered on demand rather than
            // pushed: the probes are binder round trips, and nothing
            // needs them until a Mac has the panel open.
            "health" -> respond(link, msg, "health") {
                it.put("items", PermissionHealth.snapshot(appCtx))
            }
            // Take the user to the screen that fixes one of them. The
            // result distinguishes "opened it" from "left a
            // notification", because a phone that cannot start
            // activities from the background can only do the latter and
            // the Mac must not claim otherwise.
            "health-fix" -> respond(link, msg, "health-fix") {
                it.put("result", PermissionHealth.openFix(appCtx, msg.optString("id")))
            }
            "action-sms" -> {
                val number = msg.optString("number")
                if (number.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_SENDTO).setData(Uri.parse("smsto:$number"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { appCtx.startActivity(intent) }
                }
            }
            "media-cmd" -> MediaRemote.command(msg.optString("cmd"), msg.optInt("value"))
            // ── Tier B: wallpaper / apps ─────────────────────────────
            "wallpaper" -> respond(link, msg, "wallpaper") {
                it.put(
                    "data",
                    android.util.Base64.encodeToString(
                        WallpaperRepo.bytes(appCtx), android.util.Base64.NO_WRAP
                    )
                )
            }
            "screenshot" -> {
                val reqId = msg.optLong("reqId")
                AccessibilityControl.takeScreenshot { png, err ->
                    val out = JSONObject().put("type", "screenshot").put("reqId", reqId)
                    if (png != null) {
                        out.put("data", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                    } else {
                        out.put("error", err ?: "Screenshot failed")
                    }
                    reply(link, out)
                }
            }
            "apps-list" -> respond(link, msg, "apps-list") {
                it.put("apps", AppsRepo.list(appCtx))
            }
            "app-icon" -> {
                // Same shape as photo-thumb: the icon rides the existing
                // KIND_THUMB binary frame keyed by reqId, so no new
                // transport is introduced for it.
                val reqId = msg.optInt("reqId")
                val pkg = msg.optString("pkg")
                scope.launch {
                    try {
                        TransferManager.sendThumb(reqId, AppsRepo.iconBytes(appCtx, pkg))
                    } catch (e: Exception) {
                        reply(
                            link,
                            JSONObject().put("type", "app-icon-error")
                                .put("reqId", reqId)
                                .put("error", e.message ?: "icon failed")
                        )
                    }
                }
            }
            "app-launch" -> {
                val pkg = msg.optString("pkg")
                if (pkg.isNotEmpty()) {
                    val ok = AppsRepo.launch(appCtx, pkg)
                    lastEvent.value = if (ok) "opened $pkg" else "could not open $pkg"
                }
            }
            "mirror-start" -> {
                // Additive fields — a Mac that predates them sends none
                // and the service keeps its own defaults.
                MirrorService.setQuality(
                    msg.optInt("bitrate"), msg.optInt("fps"), msg.optInt("maxSize")
                )
                MirrorService.setCodec(msg.optString("codec"))
                MirrorService.setAudio(msg.optBoolean("audio"))
                val inst = MirrorService.instance
                if (inst != null && inst.isScreenAlive()) inst.resumeStreaming()
                else launchMirror(
                    "screen", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                )
            }
            "mirror-pause" -> MirrorService.instance?.pauseVideo()
            "mirror-resume" -> MirrorService.instance?.resumeVideo()
            "mirror-stop", "camera-stop" -> {
                // Always fully stop so the phone clears its cast/screen-share
                // notification. In Auto mode the next mirror-start will re-show
                // the system consent dialog directly (no notification needed).
                MirrorService.stop(appCtx)
            }
            "camera-start" -> {
                MirrorService.setQuality(
                    msg.optInt("bitrate"), msg.optInt("fps"), msg.optInt("maxSize")
                )
                MirrorService.setCodec(msg.optString("codec"))
                MirrorService.setAudio(false)
                val facing = if (msg.optString("facing") == "front")
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                launchMirror("camera", facing)
            }
            "camera-flip" -> {
                val facing = if (msg.optString("facing") == "front")
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                MirrorService.instance?.flip(facing)
            }
            // Screen control. All four need the accessibility service;
            // without it they no-op silently, so tell the Mac once
            // rather than letting the user keep clicking at a live
            // video that never responds.
            "mirror-tap", "mirror-swipe", "mirror-key", "mirror-text" ->
                // Lock is the one action with a second route. Device
                // admin's `lockNow()` needs no accessibility service,
                // which is what keeps the Mac's Lock button alive for
                // anyone who has to keep accessibility off for their
                // banking apps. Tried first; falls through to the
                // accessibility path when the admin isn't granted.
                if (msg.optString("type") == "mirror-key" &&
                    msg.optString("key") == "lock" &&
                    LockAdmin.lock(appCtx)
                ) {
                    lastEvent.value = "locked by Mac"
                } else if (!AccessibilityControl.available()) {
                    reportControlUnavailable(
                        link,
                        // A failed Lock has a fix the others don't:
                        // grant device admin and it stops needing the
                        // accessibility service at all. Worth naming,
                        // since this is the button people reach for
                        // *because* they keep accessibility off.
                        wasLock = msg.optString("type") == "mirror-key" &&
                            msg.optString("key") == "lock"
                    )
                } else when (msg.optString("type")) {
                    "mirror-tap" -> AccessibilityControl.tap(msg.optDouble("x"), msg.optDouble("y"))
                    "mirror-swipe" -> AccessibilityControl.swipe(
                        msg.optDouble("x1"), msg.optDouble("y1"),
                        msg.optDouble("x2"), msg.optDouble("y2"), msg.optInt("dur", 120)
                    )
                    "mirror-key" -> AccessibilityControl.key(msg.optString("key"))
                    "mirror-text" -> when (msg.optString("op")) {
                        "backspace" -> AccessibilityControl.backspace()
                        "enter" -> AccessibilityControl.typeText("\n")
                        else -> AccessibilityControl.typeText(msg.optString("text"))
                    }
                }
        }
    }

    /** Seal if this session negotiated encryption. Every JSON write on this
     *  link goes through here — a direct `socket.send(obj.toString())` anywhere
     *  would silently emit plaintext on an encrypted session, which is the one
     *  failure mode optional crypto must not have. */
    internal fun wire(obj: JSONObject): String {
        val key = linkKey ?: return obj.toString()
        return runCatching { LinkCrypto.seal(key, obj).toString() }.getOrElse {
            // Never silently downgrade to plaintext — a caller that thinks it
            // is encrypted must not have its message sent in the clear.
            ""
        }
    }

    /** Reply on a specific socket (the listener has `webSocket` in hand before
     *  `ws` is even assigned), still honouring the session key. */
    private fun reply(link: Link, obj: JSONObject): Boolean {
        if (link.sendJson(obj)) return true
        // Too big for Bluetooth: say so instead of leaving the request to time out (BLE-DESIGN §2.2).
        if (link.kind == LinkKind.BLE && obj.has("reqId") && !obj.has("error")) {
            val err = JSONObject().put("type", obj.optString("type")).put("reqId", obj.opt("reqId"))
                .put("error", "Too large for Bluetooth \u2014 connect Wi-Fi")
            return link.sendJson(err)
        }
        return false
    }

    @Volatile private var lastControlWarnAt = 0L

    /**
     * Tell the Mac its screen-control message went nowhere.
     *
     * Throttled to one message per 5s: a scroll or a dragged swipe arrives as a
     * burst, and the point is to explain the problem once, not to answer every
     * frame of it.
     *
     * Carries *why*, because there are now two reasons and they need opposite
     * advice: the accessibility service being off (fix it in system Settings) or
     * the user having switched Mac screen control off while leaving the service
     * running for auto-clipboard (fix it in DroidDock). Telling someone to grant
     * a permission they already granted is worse than saying nothing. The field
     * is optional — an older Mac ignores it and shows its generic text.
     */
    private fun reportControlUnavailable(link: Link, wasLock: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastControlWarnAt < 5_000) return
        lastControlWarnAt = now
        val serviceOff = AccessibilityControl.service == null
        val reason = when {
            // Reached only when `LockAdmin.lock` already declined, so the admin
            // is definitely not granted — the one case with a fix that doesn't
            // involve turning accessibility back on.
            wasLock && serviceOff -> "lock-needs-admin"
            serviceOff            -> "service"
            else                  -> "disabled"
        }
        lastEvent.value = when (reason) {
            "lock-needs-admin" -> "lock needs Accessibility or device admin"
            "service"          -> "screen control needs Accessibility"
            else               -> "screen control is switched off"
        }
        reply(
            link,
            JSONObject().put("type", "control-unavailable").put("reason", reason)
        )
    }

    /**
     * The one send path. WIFI-class messages go to the Wi-Fi link only; ANY-class go to the primary link
     * (Wi-Fi when it is stable, Bluetooth while it is the only good one). With Bluetooth off there is never
     * a second link, so this is exactly the old behaviour.
     */
    fun send(obj: JSONObject): Boolean {
        if (obj.optString("type") in MessageIds.STAMPED && !obj.has("mid") && macCaps.value.contains("ble")) {
            obj.put("mid", MessageIds.next())
        }
        val link = (if (TransportClassifier.classOf(obj) == TransportClass.WIFI) wsLink else currentPrimary())
            ?: return false
        return sendOn(link, obj)
    }

    private fun sendOn(link: Link, obj: JSONObject): Boolean {
        var o = obj
        if (link.kind == LinkKind.BLE) {
            when (o.optString("type")) {
                // Art is left out over Bluetooth; the key is absent (not null) so the Mac keeps its cache.
                "media" -> if (o.has("art")) {
                    o = JSONObject(o, o.keys().asSequence().filter { it != "art" }.toList().toTypedArray())
                }
                "clipboard" -> if (o.optString("text").toByteArray().size > TransportClassifier.MAX_BLE_CLIPBOARD) {
                    lastEvent.value = "Too large for Bluetooth"
                    toast(t("Too large for Bluetooth"))
                    return false
                }
            }
        }
        val ok = link.sendJson(o)
        if (ok && o.has("mid")) replay.record(link.id, o)
        return ok
    }

    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    /** Send one H.264 access unit as a binary frame: [kind=3][flags][payload].
     *  flags bit0 = keyframe. Used by MirrorService for screen mirroring. */
    fun sendVideo(flags: Int, payload: ByteArray): Boolean = sendVideoParts(flags, null, payload)

    /**
     * [sendVideo], but assembling the header, the optional codec-config prefix
     * and the access unit into a single buffer.
     *
     * A keyframe used to cost three whole-frame arrays on the way out: one for
     * `configBytes + bytes`, one for the two header bytes, and one more for
     * okio's vararg spread. Every one of those is a full memcpy of an
     * I-frame-sized payload, on the encoder's drain thread, at the keyframe
     * rate. This builds the wire buffer once.
     */
    fun sendVideoParts(flags: Int, prefix: ByteArray?, payload: ByteArray): Boolean =
        sendVideoParts(flags, prefix, java.nio.ByteBuffer.wrap(payload))

    /** Same wire bytes as the array overload, read straight from [payload]'s
     *  remaining bytes (the codec output buffer) with no intermediate copy.
     *  [payload]'s position is left untouched. */
    fun sendVideoParts(flags: Int, prefix: ByteArray?, payload: java.nio.ByteBuffer): Boolean =
        sendBinary(buildVideoFrame(flags, prefix, payload))

    /** Bytes queued for transmission but not yet written to the socket.
     *  The mirror path uses this to decide when it is falling behind — see
     *  `MirrorService.VIDEO_BACKLOG_BYTES`. Zero when nothing is linked. */
    fun outboundQueueBytes(): Long = ws?.queueSize() ?: 0L

    /** Send one PCM chunk as a binary frame: [kind=4][flags][s16le stereo].
     *  flags bit0 = first frame after a silent gap, so the Mac resets its
     *  playback schedule instead of queueing behind a long-past timestamp. */
    fun sendAudio(flags: Int, payload: ByteArray): Boolean {
        val out = ByteArray(payload.size + 2)
        out[0] = 4
        out[1] = flags.toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return sendBinary(out)
    }

    /**
     * The single exit for every binary frame — mirror video, audio, file chunks,
     * thumbnails. Seals when `enc2` was negotiated, otherwise sends as-is.
     *
     * One funnel on purpose: four separate `socket.send` sites is four places to
     * forget the seal, and a frame that escapes unsealed on an encrypted link is
     * a silent downgrade nobody would notice.
     */
    /** [kind=3][flags][prefix?][payload]. Pure, so it is unit-testable. */
    internal fun buildVideoFrame(flags: Int, prefix: ByteArray?, payload: java.nio.ByteBuffer): ByteArray {
        val pre = prefix?.size ?: 0
        val len = payload.remaining()
        val out = ByteArray(2 + pre + len)
        out[0] = 3
        out[1] = flags.toByte()
        if (prefix != null) System.arraycopy(prefix, 0, out, 2, pre)
        payload.duplicate().get(out, 2 + pre, len)
        return out
    }

    private fun sealOut(frame: ByteArray): ByteArray {
        val key = frameKey
        return if (key == null) frame else LinkCrypto.sealFrame(key, frame)
    }

    fun sendBinary(frame: ByteArray): Boolean {
        val socket = ws ?: return false
        val bytes = sealOut(frame)
        // `ByteString.of(*bytes)` spreads the array into a vararg, which the
        // compiler implements as an `Arrays.copyOf` — okio then copies again
        // internally. On the video path that was two extra full-frame memcpys
        // per frame at 30–60fps, and two more per 256 KiB transfer chunk. The
        // offset/length overload takes the array directly.
        return socket.send(bytes.toByteString())
    }

    /** Unseal an inbound frame, or null to drop it. Strict once engaged: an
     *  unsealed frame on an encrypted link is dropped rather than trusted, or
     *  anyone on the network could inject simply by not encrypting. */
    fun openFrame(buf: ByteArray): ByteArray? {
        val key = frameKey
        val sealed = buf.isNotEmpty() && buf[0] == LinkCrypto.KIND_SEALED
        return when {
            key == null && !sealed -> buf
            key != null && sealed -> LinkCrypto.openFrame(key, buf)
            else -> null
        }
    }

    /** Replies to a reqId-tagged request from the Mac, converting failures into readable errors. */
    private fun respond(link: Link, req: JSONObject, type: String, build: (JSONObject) -> Unit) {
        val out = JSONObject().put("type", type).put("reqId", req.optLong("reqId"))
        runCatching { build(out) }.onFailure { e ->
            out.put(
                "error",
                if (e is SecurityException) {
                    "Grant phone permissions (SMS · Contacts · Calls) in the DroidDock app"
                } else {
                    e.message ?: "failed on phone"
                }
            )
        }
        reply(link, out)
    }

    // ── Links: Wi-Fi + optional Bluetooth fallback (BLE-DESIGN §6–§8) ───────

    /** "network" (default) or "fallback". */
    val connectionMode = MutableStateFlow("network")
    /** "Switch back automatically" (default on). */
    val bleAutoReturn = MutableStateFlow(true)
    /** How the Mac is reached right now; null = not connected. */
    val transport = MutableStateFlow<Transport?>(null)

    /** What the Settings section needs to offer manual switching. */
    data class LinkUi(
        /** Bluetooth is carrying traffic while a stable Wi-Fi link exists: "Wi-Fi available, switch now". */
        val wifiAvailable: Boolean = false,
        /** Wi-Fi is carrying traffic and a Bluetooth session is also live: "Use Bluetooth now". */
        val bleAvailable: Boolean = false,
    )
    val linkUi = MutableStateFlow(LinkUi())

    @Volatile private var netWatching = false
    @Volatile private var wifiUpSince = 0L
    @Volatile private var wifiDownSince = System.currentTimeMillis()
    @Volatile private var roundFailed = false
    @Volatile private var probeOk = false
    @Volatile private var lastInbound = 0L
    @Volatile private var probeSentAt = 0L
    @Volatile private var prefer = FallbackController.Prefer.AUTO
    @Volatile private var lastDecision: FallbackController.Decision? = null
    private var fbJob: Job? = null
    private val replay = MessageIds.ReplayRing()
    private val pendingReplay = ArrayList<JSONObject>()
    @Volatile private var pendingReplayUntil = 0L

    /** The `hello` for either link (Wi-Fi: plaintext/enc WS; Bluetooth: inside the sealed session). */
    internal fun buildHello(pairing: Pairing): JSONObject = JSONObject()
        .put("type", "hello")
        .put("token", pairing.token)
        .put("name", deviceName())
        .put("caps", JSONArray(caps()))
        // Stable across reconnects and re-pairs; the Mac keys its photo-sync ledger on this.
        .put("deviceId", Prefs.deviceId(appCtx))

    private fun parseCaps(msg: JSONObject): List<String> =
        msg.optJSONArray("caps")?.let { arr -> List(arr.length()) { i -> arr.optString(i) } } ?: emptyList()

    /** Where ANY-class traffic goes right now. */
    private fun currentPrimary(): Link? {
        val w = wsLink
        val b = bleLink
        if (b == null) return w
        if (w == null) return b
        return if (lastDecision?.primary == LinkKind.WIFI) w else b
    }

    private fun refreshState() {
        val any = wsLink != null || bleLink != null
        if (connected.value != any) connected.value = any
        val t = when (currentPrimary()?.kind) {
            LinkKind.WIFI -> Transport.WIFI
            LinkKind.BLE -> Transport.BLE
            null -> null
        }
        if (transport.value != t) transport.value = t
    }

    /** Re-run the fallback policy and apply it. Cheap and safe to call from any event; inert in "network" mode. */
    @Synchronized
    fun reevaluateLinks() {
        if (!::appCtx.isInitialized) return
        val ctx = appCtx
        val now = System.currentTimeMillis()
        val inputs = FallbackController.Inputs(
            fallback = connectionMode.value == "fallback" && BleStack.wanted(ctx),
            blocked = pausedUntil.value != 0L || manuallyDisconnected.value || BleStack.lockedOut(),
            radioOn = BleStack.radioOn,
            online = NetworkWatch.online.value,
            wifiUp = wsLink != null,
            wifiUpSince = wifiUpSince,
            wifiDownSince = wifiDownSince,
            roundFailed = roundFailed,
            probeOk = probeOk,
            lastInbound = lastInbound,
            probeSentAt = probeSentAt,
            bleAuthed = bleLink != null,
            autoReturn = bleAutoReturn.value,
            prefer = prefer,
        )
        val d = BleStack.evaluate(inputs)
        lastDecision = d
        when (d.wifiAction) {
            // Silent Wi-Fi counts as lost after ~16 s (only in fallback mode): ping, then give up on it.
            FallbackController.WifiAction.PING -> {
                probeSentAt = now
                wsLink?.sendJson(JSONObject().put("type", "ping").put("t", now))
            }
            FallbackController.WifiAction.KILL -> wsLink?.close()
            else -> {}
        }
        val ui = LinkUi(
            wifiAvailable = d.wifiAvailableWhileBle,
            bleAvailable = bleLink != null && wsLink != null && d.primary == LinkKind.WIFI,
        )
        if (linkUi.value != ui) linkUi.value = ui
        refreshState()
        val lock = BleStack.lockedUntil - now
        scheduleReevaluate(if (lock > 0) minOf(d.nextCheckMs, lock) else d.nextCheckMs)
    }

    /** One timer, only while something time-based is pending (never a poll). */
    private fun scheduleReevaluate(ms: Long) {
        fbJob?.cancel()
        fbJob = null
        if (ms == Long.MAX_VALUE) return
        fbJob = scope.launch {
            delay(ms.coerceAtLeast(250))
            reevaluateLinks()
        }
    }

    // ── Wi-Fi welcome (was inline in the socket listener) ──

    private fun onWsWelcome(link: WsLink, socket: WebSocket, msg: JSONObject, pairing: Pairing, ip: String) {
        val handover = bleLink != null // another link is already authed: not a fresh session (§7)
        val now = System.currentTimeMillis()
        ws = socket
        wsLink = link
        wifiUpSince = now
        lastInbound = now
        probeOk = false
        probeSentAt = 0L
        roundFailed = false
        macName.value = msg.optString("name", pairing.macName)
        macCaps.value = parseCaps(msg)
        // The Mac only echoes "enc" when the user enabled it there; quiet means plaintext.
        linkKey = if (macCaps.value.contains(LinkCrypto.CAP)) LinkCrypto.derive(pairing.token) else null
        // Sealed binary frames are negotiated separately.
        frameKey = if (macCaps.value.contains(LinkCrypto.CAP_FRAMES)) LinkCrypto.derive(pairing.token) else null
        lastEvent.value = "linked"
        refreshState()
        runCatching {
            Prefs.markSeen(appCtx, pairing.token, ip, macName.value)
            knownDevices.value = Prefs.knownDevices(appCtx)
        }
        TransferManager.attach(appCtx, socket)
        if (!handover) {
            // forceArt: a fresh Mac has no album-art cache.
            MediaRemote.push(true)
            NotifListener.pushActive()
            DeviceInfo.push(appCtx)
        }
        flushPendingClip() // deliver any copy made while we were offline
        if (macCaps.value.contains("ble")) sendLinkMode()
        onLinkUp(link)
        reevaluateLinks()
    }

    // ── Bluetooth session events (called by BleStack) ──

    internal fun onBleMessage(link: BleLink, msg: JSONObject) {
        runCatching { dispatch(link, msg) }.onFailure { e ->
            lastEvent.value = "message failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun onBleWelcome(link: Link, msg: JSONObject) {
        val fresh = wsLink == null
        bleLink = link as BleLink
        lastInbound = System.currentTimeMillis()
        if (fresh) {
            macName.value = msg.optString("name", Prefs.load(appCtx)?.macName ?: "Mac")
            lastEvent.value = "linked over Bluetooth"
        }
        macCaps.value = parseCaps(msg)
        refreshState()
        if (fresh) {
            // Fresh Bluetooth-only session: 20 newest notifications as backfill, no more.
            MediaRemote.push(true)
            NotifListener.pushActive(20)
            DeviceInfo.push(appCtx)
            flushPendingClip()
            sendLinkMode()
        }
        onLinkUp(link)
        reevaluateLinks()
    }

    internal fun onBleClosed(link: BleLink) {
        if (bleLink?.id != link.id) return
        bleLink = null
        if (prefer == FallbackController.Prefer.BLE) prefer = FallbackController.Prefer.AUTO
        if (wsLink == null) {
            lastEvent.value = "disconnected"
            macInfo.value = null
            macMedia.value = null
            val dropped = JSONObject().put("type", "mac-fs-link-error").put("error", "link dropped")
            pendingMacFs.values.forEach { it.complete(dropped) }
            pendingMacFs.clear()
        }
        onLinkDown(link)
        refreshState()
        reevaluateLinks()
    }

    // ── Handover without duplicates (§7) ──

    private fun onLinkDown(l: Link) {
        val lost = replay.drain(l.id)
        if (lost.isEmpty()) return
        val other = currentPrimary()
        if (other != null) lost.forEach { sendOn(other, it) }
        else synchronized(pendingReplay) {
            pendingReplay.addAll(lost)
            pendingReplayUntil = System.currentTimeMillis() + 120_000
        }
    }

    private fun onLinkUp(l: Link) {
        val todo = synchronized(pendingReplay) {
            val fresh = System.currentTimeMillis() < pendingReplayUntil
            (if (fresh) pendingReplay.toList() else emptyList()).also { pendingReplay.clear() }
        }
        // Original mids and fields, so the Mac drops anything it already showed.
        todo.forEach { sendOn(l, it) }
    }

    // ── Settings: mode, auto-return, manual switching ──

    /** The user's choice in Settings. Permission must already be in hand (the UI asks first). */
    fun setConnectionMode(ctx: Context, mode: String) {
        appCtx = ctx.applicationContext
        val m = if (mode == "fallback") "fallback" else "network"
        Prefs.setConnectionMode(appCtx, m, System.currentTimeMillis())
        connectionMode.value = m
        if (m != "fallback") prefer = FallbackController.Prefer.AUTO
        if (m == "network") runCatching { bleLink?.close() }
        BleStack.sync(appCtx)
        if (macCaps.value.contains("ble")) sendLinkMode()
    }

    fun setBleAutoReturn(ctx: Context, on: Boolean) {
        appCtx = ctx.applicationContext
        Prefs.setBleAutoReturn(appCtx, on)
        bleAutoReturn.value = on
        reevaluateLinks()
    }

    /** "Use Wi-Fi now" / "Use Bluetooth now" / back to automatic. Tells the Mac so both sides switch together. */
    fun preferTransport(t: FallbackController.Prefer) {
        if (macCaps.value.contains("ble")) {
            val name = when (t) { FallbackController.Prefer.WIFI -> "wifi"; FallbackController.Prefer.BLE -> "ble"; else -> "auto" }
            send(JSONObject().put("type", "link-prefer").put("transport", name))
        }
        prefer = t
        reevaluateLinks()
    }

    private fun sendLinkMode() {
        send(JSONObject().put("type", "link-mode").put("mode", connectionMode.value)
            .put("at", Prefs.connectionModeAt(appCtx)))
    }

    private fun applyLinkMode(msg: JSONObject) {
        val mode = if (msg.optString("mode") == "fallback") "fallback" else "network"
        val at = msg.optLong("at")
        if (at <= Prefs.connectionModeAt(appCtx)) return
        Prefs.setConnectionMode(appCtx, mode, at)
        connectionMode.value = mode
        // Never prompt from here: without the grant, leave a notification that opens the app instead.
        if (mode == "fallback" && BleStack.hasBle(appCtx) && !BleStack.hasPermission(appCtx)) {
            PermissionHealth.notifyBleSetup(appCtx)
        }
        BleStack.sync(appCtx)
    }

    private fun applyLinkPrefer(msg: JSONObject) {
        prefer = when (msg.optString("transport")) {
            "wifi" -> FallbackController.Prefer.WIFI
            "ble" -> FallbackController.Prefer.BLE
            else -> FallbackController.Prefer.AUTO
        }
        reevaluateLinks()
    }

    fun sendFileToMac(uri: android.net.Uri, ctx: Context, onResult: (Boolean, String?) -> Unit) =
        TransferManager.pushToMac(uri, ctx, onResult)

    /** Phase 19 — list a Mac folder (phone-initiated; awaits mac-fs-list-result/-error). */
    suspend fun macFsList(path: String): JSONArray {
        val socket = ws ?: throw IllegalStateException("Not connected to Mac")
        val reqId = nextMacFsReqId()
        val deferred = CompletableDeferred<JSONObject>()
        pendingMacFs[reqId] = deferred
        socket.send(wire(JSONObject().put("type", "mac-fs-list").put("reqId", reqId).put("path", path)))
        val reply = deferred.await()
        if (reply.optString("type").endsWith("-error")) {
            throw Exception(reply.optString("error").ifEmpty { "Could not list folder" })
        }
        return reply.optJSONArray("entries") ?: JSONArray()
    }

    /** List the Mac's applications. Only answered while the Mac has
     *  "Let the phone control this Mac" switched on — otherwise it replies with
     *  an `error` explaining that, which surfaces as the exception below. */
    suspend fun macAppsList(): JSONArray {
        val socket = ws ?: throw IllegalStateException("Not connected to Mac")
        val reqId = nextMacFsReqId()
        val deferred = CompletableDeferred<JSONObject>()
        pendingMacFs[reqId] = deferred
        socket.send(wire(JSONObject().put("type", "mac-apps-list").put("reqId", reqId)))
        val reply = deferred.await()
        reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw Exception(it) }
        return reply.optJSONArray("apps") ?: JSONArray()
    }

    /** Open one Mac app by bundle id. The Mac refuses anything not in its own
     *  scan, so a stale list can fail — hence the error path. */
    suspend fun macAppLaunch(pkg: String) {
        val socket = ws ?: throw IllegalStateException("Not connected to Mac")
        val reqId = nextMacFsReqId()
        val deferred = CompletableDeferred<JSONObject>()
        pendingMacFs[reqId] = deferred
        socket.send(wire(JSONObject().put("type", "mac-app-launch").put("reqId", reqId).put("pkg", pkg)))
        val reply = deferred.await()
        reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw Exception(it) }
    }

    /** Phase 19 — pull a file from the Mac into Downloads. Awaits mac-fs-pull-begin here,
     *  then hands the binary receive off to TransferManager (which owns the Downloads-folder
     *  write + progress), and that resolves once mac-fs-pull-done/-error arrives. */
    suspend fun macFsPull(
        path: String,
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit
    ): Result<File> {
        val socket = ws ?: return Result.failure(IllegalStateException("Not connected to Mac"))
        val reqId = nextMacFsReqId()
        val deferred = CompletableDeferred<JSONObject>()
        pendingMacFs[reqId] = deferred
        socket.send(wire(JSONObject().put("type", "mac-fs-pull").put("reqId", reqId).put("path", path)))
        val begin = deferred.await()
        if (begin.optString("type").endsWith("-error")) {
            return Result.failure(Exception(begin.optString("error").ifEmpty { "Pull failed" }))
        }
        val transferId = begin.optInt("transferId")
        val size = begin.optLong("size")
        val name = path.substringAfterLast('/').ifEmpty { "file" }
        return TransferManager.receiveMacFsPull(reqId, transferId, size, name, onProgress)
    }

    /** Tier D — drive the Mac's pointer/keyboard. Fire-and-forget by design:
     *  the Mac has no ack for input events, and a trackpad that waited for one
     *  would feel broken. Only reachable while the Mac advertises "remote",
     *  which it only does when the user enabled it there. */
    fun sendRemote(action: String, build: (JSONObject) -> Unit = {}): Boolean {
        if (!macCaps.value.contains("remote")) return false
        val obj = JSONObject().put("type", "remote").put("action", action)
        runCatching { build(obj) }
        return send(obj)
    }

    /** True while the Mac understands press-and-hold ("remotedrag"). */
    fun canDragRemote(): Boolean = macCaps.value.contains("remotedrag")

    /** Press or release the Mac's left button so pointer moves become drags.
     *  Additive and cap-gated: an older Mac never sees it. Sending `down` again
     *  while held is a harmless heartbeat that defers the Mac's idle release. */
    fun sendRemoteButton(down: Boolean, button: String = "left"): Boolean {
        if (!macCaps.value.contains("remotedrag")) return false
        return send(JSONObject().put("type", "remote-button").put("button", button)
            .put("state", if (down) "down" else "up"))
    }

    /** Absolute Mac display brightness, 0..1. Only sent while the Mac advertises
     *  "macbrightness"; older Macs keep the stepped `brightness` remote action. */
    fun sendMacBrightness(level: Float): Boolean {
        if (!macCaps.value.contains("macbrightness")) return false
        return send(JSONObject().put("type", "mac-brightness-set").put("level", level.coerceIn(0f, 1f).toDouble()))
    }

    /**
     * Send the clipboard's current *image* to the Mac.
     *
     * Separate from [sendClipboardText] rather than folded into it: the caller
     * has to distinguish "there was no image, try text" from "there was an
     * image and the link is down", and a single boolean cannot say both.
     */
    fun sendClipboardImage(base64: String): Boolean {
        val socket = ws ?: return false
        val ok = socket.send(
            wire(
                JSONObject()
                    .put("type", "clipboard")
                    .put("kind", "image")
                    .put("mime", "image/png")
                    .put("data", base64)
            )
        )
        if (ok) lastEvent.value = "image → Mac"
        return ok
    }

    fun sendClipboardText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text == lastFromMac) return true // avoid echo loops
        if (currentPrimary() == null) {
            pendingClip = text // queue it; flushPendingClip() delivers on reconnect
            return false
        }
        val ok = send(JSONObject().put("type", "clipboard").put("text", text))
        if (ok) recordClip(text, fromMac = false)
        pendingClip = if (ok) null else text
        return ok
    }

    /** Deliver a queued copy once the socket is back. */
    private fun flushPendingClip() {
        val p = pendingClip ?: return
        if (currentPrimary() == null) return
        if (p == lastFromMac) { pendingClip = null; return }
        if (send(JSONObject().put("type", "clipboard").put("text", p))) {
            recordClip(p, fromMac = false)
            pendingClip = null
            lastEvent.value = "clipboard → Mac"
        }
    }

    /** A clip is written across a Binder transaction with a ~1 MB budget shared
     *  with everything else in flight; past this the write fails outright. */
    private const val MAX_CLIP_CHARS = 200_000

    private fun setClipboard(text: String) {
        val clip = if (text.length > MAX_CLIP_CHARS) text.take(MAX_CLIP_CHARS) else text
        // Echo suppression compares against what actually sits on the clipboard,
        // so it must be the truncated string — otherwise the accessibility service
        // reads back the short version, fails to recognise it as ours, and bounces
        // it straight back to the Mac.
        lastFromMac = clip
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // setPrimaryClip is posted to the main thread, where a throw is fatal,
            // and it genuinely throws in the wild: TransactionTooLargeException on
            // an oversized clip, SecurityException on OEMs that gate clipboard
            // writes. A copy that doesn't land must not kill the app.
            runCatching {
                val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DroidDock", clip))
            }.onSuccess {
                recordClip(clip, fromMac = true)
                lastEvent.value = "clipboard ← Mac"
            }.onFailure {
                lastFromMac = null // it never landed; don't suppress it as an echo later
                lastEvent.value = "clipboard blocked by Android"
            }
        }
    }

    /** Broadcast on 255.255.255.255:<discoveryPort> with the pairing token.
     *  The Mac replies with "DROIDDOCK:HERE" from its current IP — we return that IP.
     *  Returns null if nothing replies within 1.5 s (Mac not on this network). */
    private fun discoverViaBroadcast(token: String, discoveryPort: Int): String? = try {
        java.net.DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1500
            val payload = "DROIDDOCK:DISCOVER:$token".toByteArray()
            socket.send(
                java.net.DatagramPacket(
                    payload, payload.size,
                    java.net.InetAddress.getByName("255.255.255.255"),
                    discoveryPort
                )
            )
            val buf = ByteArray(64)
            val resp = java.net.DatagramPacket(buf, buf.size)
            socket.receive(resp)
            if (String(resp.data, 0, resp.length).trim() == "DROIDDOCK:HERE")
                resp.address.hostAddress
            else null
        }
    } catch (_: Exception) { null }

    /** The name sent in `hello` — the user's own if they set one, else the
     *  hardware name every previous build sent. */
    private fun deviceName(): String {
        val custom = runCatching { Prefs.deviceName(appCtx) }.getOrDefault("")
        if (custom.isNotBlank()) return custom
        return hardwareName()
    }

    /** Shown in Settings as the placeholder, so the field makes clear what the
     *  Mac would see if it were left blank. */
    fun hardwareName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" }

    /**
     * This phone's address on the current network, for the Settings readout.
     *
     * Enumerated rather than read from `WifiManager`, which needs a location
     * permission on modern Android and still only knows about Wi-Fi — this also
     * reports the tailnet address when Tailscale is up, which is exactly the one
     * worth seeing when debugging "expand networking".
     */
    /**
     * The previous [localIpAddress] answer, or null if it has never been asked.
     *
     * Exists so the Settings readout can paint the address it showed last time
     * on its first frame and refresh in the background, rather than blocking
     * composition on the interface sweep or blinking through a placeholder on
     * every visit.
     */
    @Volatile var lastKnownLocalIp: String? = null
        private set

    fun localIpAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull().also { lastKnownLocalIp = it }
}
