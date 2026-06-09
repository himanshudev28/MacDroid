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
import java.util.concurrent.TimeUnit

object ConnectionManager {
    // Capabilities this companion advertises to the Mac (Phase 6a app-link transport).
    private val CAPS = listOf("info", "fs", "photos", "shot")

    val connected = MutableStateFlow(false)
    val macName = MutableStateFlow<String?>(null)
    val lastEvent = MutableStateFlow("idle")
    // 0 = active; Long.MAX_VALUE = until resume; else epoch-ms deadline (auto-resume).
    val pausedUntil = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var lastFromMac: String? = null
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
        runCatching { ws?.send(JSONObject().put("type", "pause").put("until", until).toString()) }
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
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "welcome" -> {
                        didConnect = true
                        ws = webSocket
                        connected.value = true
                        macName.value = msg.optString("name", pairing.macName)
                        lastEvent.value = "linked"
                        TransferManager.attach(appCtx, webSocket)
                        MediaRemote.push()
                        NotifListener.pushActive()
                        DeviceInfo.push(appCtx)
                        flushPendingClip() // deliver any copy made while we were offline
                    }
                    "clipboard" -> {
                        val t = msg.optString("text")
                        if (t.isNotEmpty()) setClipboard(t)
                    }
                    "reply" -> {
                        val ok = NotifStore.reply(appCtx, msg.optString("key"), msg.optString("text"))
                        webSocket.send(
                            JSONObject().put("type", "reply-result").put("ok", ok).toString()
                        )
                        lastEvent.value = if (ok) "reply → app" else "reply failed"
                    }
                    "dismiss" -> NotifStore.dismiss(msg.optString("key"))
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
                    "fs-pull", "fs-push-begin", "fs-push-done", "fs-cancel" ->
                        TransferManager.onControl(msg)
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
                                webSocket.send(
                                    JSONObject().put("type", "photo-thumb-error")
                                        .put("reqId", reqId).put("error", e.message ?: "thumb failed")
                                        .toString()
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
                    "mirror-start" -> MirrorPermissionActivity.request(appCtx)
                    "mirror-stop" -> MirrorService.stop(appCtx)
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
        }
    }

    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

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
        socket.send(out.toString())
    }

    fun sendClipboardText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text == lastFromMac) return true // avoid echo loops
        val socket = ws
        if (socket == null) {
            pendingClip = text // queue it; flushPendingClip() delivers on reconnect
            return false
        }
        val ok = socket.send(JSONObject().put("type", "clipboard").put("text", text).toString())
        pendingClip = if (ok) null else text
        return ok
    }

    /** Deliver a queued copy once the socket is back. */
    private fun flushPendingClip() {
        val p = pendingClip ?: return
        val socket = ws ?: return
        if (p == lastFromMac) { pendingClip = null; return }
        if (socket.send(JSONObject().put("type", "clipboard").put("text", p).toString())) {
            pendingClip = null
            lastEvent.value = "clipboard → Mac"
        }
    }

    private fun setClipboard(text: String) {
        lastFromMac = text
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DroidDock", text))
        lastEvent.value = "clipboard ← Mac"
    }

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" }
}
