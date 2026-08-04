package com.droiddock.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
    private val CAPS = listOf("info", "fs", "photos", "shot", "photosync", "wallpaper", "apps", LinkCrypto.CAP)

    val connected = MutableStateFlow(false)
    val macName = MutableStateFlow<String?>(null)
    val lastEvent = MutableStateFlow("idle")
    // 0 = active; Long.MAX_VALUE = until resume; else epoch-ms deadline (auto-resume).
    val pausedUntil = MutableStateFlow(0L)
    // Phase 19 — capabilities the connected Mac advertised in its `welcome` (e.g. "macfs").
    // Empty for an older Mac build that doesn't send `caps` at all.
    val macCaps = MutableStateFlow<List<String>>(emptyList())

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
    @Volatile private var lastFromMac: String? = null
    /// Tier C: set only when the Mac's welcome echoed "enc". Null means this
    /// session is plaintext — the default, and how every pre-Tier-C build ran.
    @Volatile private var linkKey: javax.crypto.spec.SecretKeySpec? = null
    // Last copy we couldn't deliver (link was down). Flushed on the next reconnect
    // so a copy made during a Wi-Fi blip / Mac restart still reaches the Mac.
    @Volatile private var pendingClip: String? = null
    private lateinit var appCtx: Context

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()

    fun ensureLoop(ctx: Context) {
        appCtx = ctx.applicationContext
        pausedUntil.value = Prefs.pausedUntil(appCtx)
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { connectLoop() }
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
        connected.value = false
        macName.value = null
        pausedUntil.value = 0L
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
        runCatching { ws?.send(wire(JSONObject().put("type", "pause").put("until", until))) }
        runCatching { ws?.close(1000, "paused") }
        ws = null
        connected.value = false
        macName.value = null
        lastEvent.value = "paused"
        ensureLoop(appCtx) // keep the idle loop running so it enforces + auto-resumes
    }

    /** Resume immediately: clear the pause and reconnect now. */
    fun resume(ctx: Context) {
        appCtx = ctx.applicationContext
        Prefs.setPausedUntil(appCtx, 0L)
        pausedUntil.value = 0L
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
                delay(3_000)
                continue
            }
            // Honor a pause: idle without connecting until it expires / the user resumes.
            val until = Prefs.pausedUntil(appCtx)
            if (until != 0L) {
                if (System.currentTimeMillis() < until) {
                    pausedUntil.value = until
                    connected.value = false
                    lastEvent.value = "paused"
                    delay(2_000)
                    continue
                }
                // deadline passed → auto-resume
                Prefs.setPausedUntil(appCtx, 0L)
                pausedUntil.value = 0L
                lastEvent.value = "resuming…"
            }
            var linked = false
            for (ip in pairing.ips) {
                if (attempt(ip, pairing)) {
                    linked = true
                    break
                }
            }
            // Known IPs failed — broadcast on the LAN to find the Mac's current IP.
            // Handles WiFi-switch without re-pairing: both devices on new network,
            // Mac answers the broadcast, phone saves the fresh IP.
            var broadcastIp: String? = null
            if (!linked) {
                broadcastIp = discoverViaBroadcast(pairing.token, pairing.port + 1)
                if (broadcastIp != null && !pairing.ips.contains(broadcastIp)) {
                    lastEvent.value = "found Mac at $broadcastIp"
                    if (attempt(broadcastIp, pairing)) {
                        linked = true
                        Prefs.save(appCtx, pairing.copy(ips = listOf(broadcastIp) + pairing.ips.take(2)))
                    }
                }
            }
            // Tier C: last resort. Some routers drop directed broadcast or
            // isolate clients, so the probe above never gets an answer even
            // though the Mac is right there — multicast usually still works.
            // Auth is unchanged: this only supplies an address to try. Skip any
            // address the two paths above already exhausted this round.
            if (!linked) {
                val viaMdns = MdnsDiscovery.find(appCtx)
                if (viaMdns != null && viaMdns != broadcastIp && !pairing.ips.contains(viaMdns)) {
                    lastEvent.value = "found Mac at $viaMdns (mDNS)"
                    if (attempt(viaMdns, pairing)) {
                        linked = true
                        Prefs.save(appCtx, pairing.copy(ips = listOf(viaMdns) + pairing.ips.take(2)))
                    }
                }
            }
            backoff = if (linked) 2_000L else minOf(backoff * 2, 15_000L)
            delay(backoff)
        }
    }

    /** Tries one address; if the session opens, suspends until it dies. Returns true if it ever connected. */
    private suspend fun attempt(ip: String, pairing: Pairing): Boolean {
        val closed = CompletableDeferred<Unit>()
        var didConnect = false
        lastEvent.value = "connecting to $ip…"

        val request = Request.Builder().url("ws://$ip:${pairing.port}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("token", pairing.token)
                    .put("name", deviceName())
                    .put("caps", JSONArray(CAPS))
                    // Stable across reconnects and re-pairs; the Mac keys its
                    // photo-sync ledger on this instead of the model name.
                    .put("deviceId", Prefs.deviceId(appCtx))
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val outer = runCatching { JSONObject(text) }.getOrNull() ?: return
                // Tier C: unwrap first so every branch below keeps seeing
                // plaintext. An envelope that fails to authenticate is dropped,
                // never routed.
                val msg = if (outer.optString("type") == "enc") {
                    val key = linkKey ?: return
                    LinkCrypto.open(key, outer) ?: return
                } else outer
                when (msg.optString("type")) {
                    "welcome" -> {
                        didConnect = true
                        ws = webSocket
                        connected.value = true
                        macName.value = msg.optString("name", pairing.macName)
                        macCaps.value = msg.optJSONArray("caps")?.let { arr ->
                            List(arr.length()) { i -> arr.optString(i) }
                        } ?: emptyList()
                        // The Mac only echoes "enc" when the user enabled it
                        // there. If it stays quiet we simply run in plaintext,
                        // so an older Mac build is never stranded.
                        linkKey = if (macCaps.value.contains(LinkCrypto.CAP)) {
                            LinkCrypto.derive(pairing.token)
                        } else null
                        lastEvent.value = "linked"
                        TransferManager.attach(appCtx, webSocket)
                        // forceArt: a fresh Mac has no album-art cache, and the
                        // track hasn't "changed" from the phone's point of view.
                        MediaRemote.push(true)
                        NotifListener.pushActive()
                        DeviceInfo.push(appCtx)
                        flushPendingClip() // deliver any copy made while we were offline
                    }
                    // Tier C link-quality probe. Echo `t` back untouched — it's
                    // the Mac's own clock reading, so this needs no time sync
                    // here, and answering in the message loop (rather than at
                    // the OkHttp ping layer) is what proves this loop is alive.
                    "ping" -> reply(
                        webSocket, JSONObject().put("type", "pong").put("t", msg.optLong("t"))
                    )
                    "clipboard" -> {
                        val t = msg.optString("text")
                        if (t.isNotEmpty()) setClipboard(t)
                    }
                    "reply" -> {
                        val ok = NotifStore.reply(appCtx, msg.optString("key"), msg.optString("text"))
                        reply(webSocket, JSONObject().put("type", "reply-result").put("ok", ok))
                        lastEvent.value = if (ok) "reply → app" else "reply failed"
                    }
                    "dismiss" -> NotifStore.dismiss(msg.optString("key"))
                    // AirSync v4 parity: tap a notification's action button on
                    // the Mac and fire the original PendingIntent here.
                    "notif-action" -> {
                        val ok = NotifStore.fireAction(msg.optInt("index"), msg.optString("key"))
                        lastEvent.value = if (ok) "action → app" else "action failed"
                    }
                    "sms-threads" -> respond(webSocket, msg, "sms-threads") {
                        it.put("threads", SmsRepo.threads(appCtx))
                    }
                    "sms-messages" -> respond(webSocket, msg, "sms-messages") {
                        val tid = msg.optLong("threadId")
                        val (address, messages) = SmsRepo.messages(appCtx, tid)
                        it.put("threadId", tid).put("address", address).put("messages", messages)
                    }
                    "sms-send" -> respond(webSocket, msg, "sms-send") {
                        SmsRepo.send(appCtx, msg.optString("address"), msg.optString("text"))
                        it.put("ok", true)
                    }
                    "contacts" -> respond(webSocket, msg, "contacts") {
                        it.put("contacts", ContactsRepo.list(appCtx))
                    }
                    "fs-list" -> respond(webSocket, msg, "fs-list") {
                        it.put("entries", FileRepo.list(msg.optString("path")))
                    }
                    "fs-delete" -> respond(webSocket, msg, "fs-delete") {
                        FileRepo.delete(msg.optString("path"))
                        it.put("ok", true)
                    }
                    "fs-rename" -> respond(webSocket, msg, "fs-rename") {
                        val newPath = FileRepo.rename(msg.optString("path"), msg.optString("newName"))
                        it.put("ok", true).put("newPath", newPath)
                    }
                    "fs-pull", "fs-push-begin", "fs-push-done", "fs-cancel",
                    "phone-push", "phone-push-result" ->
                        TransferManager.onControl(msg)
                    "mac-fs-list-result", "mac-fs-list-error", "mac-fs-pull-begin" -> {
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
                    "photos-list" -> respond(webSocket, msg, "photos-list") {
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
                                    webSocket,
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
                            runCatching { appCtx.startActivity(intent) }
                        }
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
                    "wallpaper" -> respond(webSocket, msg, "wallpaper") {
                        it.put(
                            "data",
                            android.util.Base64.encodeToString(
                                WallpaperRepo.bytes(appCtx), android.util.Base64.NO_WRAP
                            )
                        )
                    }
                    "apps-list" -> respond(webSocket, msg, "apps-list") {
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
                                    webSocket,
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
                        val inst = MirrorService.instance
                        if (inst != null && inst.isScreenAlive()) inst.resumeStreaming()
                        else launchMirror(
                            "screen", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                        )
                    }
                    "mirror-stop", "camera-stop" -> {
                        // Always fully stop so the phone clears its cast/screen-share
                        // notification. In Auto mode the next mirror-start will re-show
                        // the system consent dialog directly (no notification needed).
                        MirrorService.stop(appCtx)
                    }
                    "camera-start" -> {
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
                        if (!AccessibilityControl.available()) {
                            reportControlUnavailable(webSocket)
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

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                TransferManager.onBinary(bytes)
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

        closed.await()
        socket.cancel()
        return didConnect
    }

    private fun cleanup(socket: WebSocket) {
        if (ws === socket) {
            ws = null
            connected.value = false
            lastEvent.value = "disconnected"
            TransferManager.detach() // abort transfers; JSON features recover on reconnect
            // Fail any mac-fs-list/mac-fs-pull call still waiting on a reply so it doesn't
            // hang forever; a generic "-error" type satisfies either caller's check below.
            val dropped = JSONObject().put("type", "mac-fs-link-error").put("error", "link dropped")
            pendingMacFs.values.forEach { it.complete(dropped) }
            pendingMacFs.clear()
        }
    }

    /** Auto mode (+ "Display over other apps") launches the consent flow directly with no
     *  tap on the phone; otherwise it posts a tappable notification. */
    private fun launchMirror(source: String, facing: Int) {
        val auto = Prefs.autoMirror(appCtx) && android.provider.Settings.canDrawOverlays(appCtx)
        if (auto) MirrorPermissionActivity.start(appCtx, source, facing)
        else MirrorPermissionActivity.request(appCtx, source, facing)
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
    private fun reply(socket: WebSocket, obj: JSONObject): Boolean {
        val payload = wire(obj)
        return payload.isNotEmpty() && socket.send(payload)
    }

    @Volatile private var lastControlWarnAt = 0L

    /**
     * Tell the Mac its screen-control message went nowhere.
     *
     * Throttled to one message per 5s: a scroll or a dragged swipe arrives as a
     * burst, and the point is to explain the problem once, not to answer every
     * frame of it.
     */
    private fun reportControlUnavailable(socket: WebSocket) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastControlWarnAt < 5_000) return
        lastControlWarnAt = now
        lastEvent.value = "screen control needs Accessibility"
        reply(socket, JSONObject().put("type", "control-unavailable"))
    }

    fun send(obj: JSONObject): Boolean {
        val socket = ws ?: return false
        val payload = wire(obj)
        if (payload.isEmpty()) return false
        return socket.send(payload)
    }

    /** Send one H.264 access unit as a binary frame: [kind=3][flags][payload].
     *  flags bit0 = keyframe. Used by MirrorService for screen mirroring. */
    fun sendVideo(flags: Int, payload: ByteArray): Boolean {
        val socket = ws ?: return false
        val out = ByteArray(payload.size + 2)
        out[0] = 3
        out[1] = flags.toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return socket.send(ByteString.of(*out))
    }

    /** Replies to a reqId-tagged request from the Mac, converting failures into readable errors. */
    private fun respond(socket: WebSocket, req: JSONObject, type: String, build: (JSONObject) -> Unit) {
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
        socket.send(wire(out))
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

    fun sendClipboardText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text == lastFromMac) return true // avoid echo loops
        val socket = ws
        if (socket == null) {
            pendingClip = text // queue it; flushPendingClip() delivers on reconnect
            return false
        }
        val ok = socket.send(wire(JSONObject().put("type", "clipboard").put("text", text)))
        pendingClip = if (ok) null else text
        return ok
    }

    /** Deliver a queued copy once the socket is back. */
    private fun flushPendingClip() {
        val p = pendingClip ?: return
        val socket = ws ?: return
        if (p == lastFromMac) { pendingClip = null; return }
        if (socket.send(wire(JSONObject().put("type", "clipboard").put("text", p)))) {
            pendingClip = null
            lastEvent.value = "clipboard → Mac"
        }
    }

    private fun setClipboard(text: String) {
        lastFromMac = text
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DroidDock", text))
            lastEvent.value = "clipboard ← Mac"
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

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" }
}
