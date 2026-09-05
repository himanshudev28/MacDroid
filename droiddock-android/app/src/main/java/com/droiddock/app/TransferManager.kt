package com.droiddock.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TransferProgress(
    val transferId: Int,
    val fileName: String,
    val totalBytes: Long,
    val sentBytes: Long,
    val speedBps: Long
) {
    val percent: Int get() = if (totalBytes > 0) ((sentBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

data class TransferRecord(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long,
    val direction: String, // "toMac" or "fromMac"
    val completedAt: Long,
    val success: Boolean
)

/** Phase 6a A3 — binary file transfer over the app link. Isolated from the JSON
 *  message path; ConnectionManager hands binary frames + fs-* control here. */
object TransferManager {
    private const val KIND_DATA = 1
    private const val KIND_THUMB = 2 // small photo thumbnail in one frame (A4)
    private const val HEADER = 9 // [1B kind][4B transferId][4B seq]
    private const val CHUNK = 256 * 1024
    private const val MAX_INFLIGHT = 4L * 1024 * 1024
    private const val STALL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: okhttp3.WebSocket? = null
    private var appCtx: Context? = null
    private val nextTid = AtomicInteger(1)

    private val incoming = ConcurrentHashMap<Int, PushReceiver>() // Mac → phone
    private val outgoing = ConcurrentHashMap<Int, Job>()           // phone → Mac (Mac-requested pull)

    // Phase 19 reverse file browsing: mac-fs-pull downloads (Mac → phone, phone-initiated).
    // Kept separate from `incoming` (which serves Mac-initiated fs-push) since transferId
    // here is allocated independently by the Mac; onBinary checks both maps.
    private val incomingMacFs = ConcurrentHashMap<Int, MacFsPullReceiver>()      // transferId → receiver
    private data class MacFsPullEntry(val transferId: Int, val completion: CompletableDeferred<Result<File>>)
    private val pendingMacFsPull = ConcurrentHashMap<String, MacFsPullEntry>()  // reqId → awaiting completion

    // Phone-initiated push to Mac
    private data class PendingPhonePush(val uri: Uri, val name: String, val size: Long, val onResult: (Boolean, String?) -> Unit)
    private val pendingPhonePush = ConcurrentHashMap<String, PendingPhonePush>()     // reqId → pending
    private val pendingPhonePushResult = ConcurrentHashMap<Int, (Boolean, String?) -> Unit>() // transferId → callback
    private val phonePushSeq = AtomicInteger(1)

    val activeTransfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val recentTransfers = MutableStateFlow<List<TransferRecord>>(emptyList())

    fun attach(ctx: Context, socket: okhttp3.WebSocket) {
        appCtx = ctx.applicationContext
        ws = socket
    }

    /** Link dropped: abort every in-flight transfer. JSON features are unaffected. */
    fun detach() {
        ws = null
        incoming.values.forEach { it.abort() }
        incoming.clear()
        outgoing.values.forEach { it.cancel() }
        outgoing.clear()
        // Mark active phone-push transfers as failed in recent list
        val now = System.currentTimeMillis()
        val dropped = activeTransfers.value.map { p ->
            TransferRecord(p.transferId.toLong(), p.fileName, p.totalBytes, "toMac", now, false)
        }
        if (dropped.isNotEmpty()) {
            recentTransfers.value = (dropped + recentTransfers.value).take(20)
        }
        activeTransfers.value = emptyList()
        pendingPhonePush.values.forEach { it.onResult(false, "link dropped") }
        pendingPhonePush.clear()
        pendingPhonePushResult.values.forEach { it(false, "link dropped") }
        pendingPhonePushResult.clear()
        incomingMacFs.values.forEach { it.abort() }
        incomingMacFs.clear()
        pendingMacFsPull.values.forEach { it.completion.complete(Result.failure(Exception("link dropped"))) }
        pendingMacFsPull.clear()
    }

    private fun send(obj: JSONObject) {
        // Through ConnectionManager so an encrypted session seals these too.
        ws?.send(ConnectionManager.wire(obj))
    }

    fun onControl(msg: JSONObject) {
        when (msg.optString("type")) {
            "fs-pull"       -> startPull(msg)
            "fs-push-begin" -> beginPush(msg)
            "fs-push-done"  -> incoming.remove(msg.optInt("transferId"))?.finish(msg.optLong("size"))
            "fs-cancel" -> {
                val tid = msg.optInt("transferId")
                outgoing.remove(tid)?.cancel()
                incoming.remove(tid)?.abort()
            }
            // Phone-initiated push: Mac acknowledged and allocated a transferId
            "phone-push" -> {
                val reqId  = msg.optString("reqId")
                val tid    = msg.optInt("transferId")
                val p      = pendingPhonePush.remove(reqId) ?: return
                val job    = scope.launch { streamToMac(p, tid) }
                outgoing[tid] = job
            }
            // Mac confirmed it received the file (or rejected it)
            "phone-push-result" -> {
                val tid = msg.optInt("transferId")
                outgoing.remove(tid)?.cancel()
                pendingPhonePushResult.remove(tid)?.invoke(
                    msg.optBoolean("ok", false),
                    msg.optString("error").ifEmpty { null }
                )
            }
        }
    }

    /** Initiate a phone → Mac file transfer. Calls [onResult] on completion. */
    fun pushToMac(uri: Uri, ctx: Context, onResult: (ok: Boolean, error: String?) -> Unit) {
        val socket = ws
        if (socket == null) { onResult(false, "Not connected to Mac"); return }
        val name = uriFileName(ctx, uri) ?: "file"
        val size = uriFileSize(ctx, uri)
        val reqId = "pp${phonePushSeq.getAndIncrement()}"
        pendingPhonePush[reqId] = PendingPhonePush(uri, name, size, onResult)
        send(JSONObject().put("type", "phone-push-begin").put("reqId", reqId)
            .put("name", name).put("size", size))
    }

    private suspend fun streamToMac(p: PendingPhonePush, transferId: Int) {
        val ctx    = appCtx ?: run { p.onResult(false, "no context"); return }
        val socket = ws     ?: run { p.onResult(false, "link dropped"); return }

        activeTransfers.value = activeTransfers.value + TransferProgress(transferId, p.name, p.size, 0L, 0L)

        try {
            ctx.contentResolver.openInputStream(p.uri)?.use { ins ->
                val buf = ByteArray(CHUNK)
                var seq = 0
                var bytesSent = 0L
                var lastEmitNs = System.nanoTime()
                var bytesAtLastEmit = 0L

                while (currentCoroutineContext().isActive) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    while (currentCoroutineContext().isActive && (ws?.queueSize() ?: 0) > MAX_INFLIGHT) delay(20)
                    if (ws == null) break
                    ConnectionManager.sendBinary(frame(transferId, seq++, buf, n))
                    bytesSent += n

                    val nowNs = System.nanoTime()
                    val elapsedMs = (nowNs - lastEmitNs) / 1_000_000L
                    if (elapsedMs >= 500L) {
                        val speedBps = if (elapsedMs > 0) ((bytesSent - bytesAtLastEmit) * 1000L) / elapsedMs else 0L
                        val cur = TransferProgress(transferId, p.name, p.size, bytesSent, speedBps)
                        activeTransfers.value = activeTransfers.value.map { if (it.transferId == transferId) cur else it }
                        lastEmitNs = nowNs
                        bytesAtLastEmit = bytesSent
                    }
                }
            }
            if (currentCoroutineContext().isActive) {
                // Register result callback BEFORE sending done so the ack can't race
                pendingPhonePushResult[transferId] = { ok, err ->
                    activeTransfers.value = activeTransfers.value.filter { it.transferId != transferId }
                    val rec = TransferRecord(transferId.toLong(), p.name, p.size, "toMac", System.currentTimeMillis(), ok)
                    recentTransfers.value = (listOf(rec) + recentTransfers.value).take(20)
                    p.onResult(ok, err)
                }
                send(JSONObject().put("type", "phone-push-done")
                    .put("transferId", transferId).put("size", p.size))
            } else {
                activeTransfers.value = activeTransfers.value.filter { it.transferId != transferId }
            }
        } catch (e: Exception) {
            activeTransfers.value = activeTransfers.value.filter { it.transferId != transferId }
            val rec = TransferRecord(transferId.toLong(), p.name, p.size, "toMac", System.currentTimeMillis(), false)
            recentTransfers.value = (listOf(rec) + recentTransfers.value).take(20)
            pendingPhonePushResult.remove(transferId)
            outgoing.remove(transferId)
            p.onResult(false, e.message ?: "send failed")
        }
    }

    private fun uriFileName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        }

    private fun uriFileSize(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && col >= 0) c.getLong(col) else 0L
        } ?: 0L

    /** Send a photo thumbnail (small, single frame) keyed by the request id. */
    fun sendThumb(reqId: Int, bytes: ByteArray) {
        if (ws == null) return
        val b = Buffer()
        b.writeByte(KIND_THUMB)
        b.writeInt(reqId)
        b.writeInt(0)
        b.write(bytes)
        ConnectionManager.sendBinary(b.readByteArray())
    }

    fun onBinary(bytes: ByteString) {
        if (bytes.size < HEADER) return
        if ((bytes[0].toInt() and 0xff) != KIND_DATA) return
        val tid = beInt(bytes, 1)
        val payload = bytes.substring(HEADER)
        incoming[tid]?.write(payload)
        incomingMacFs[tid]?.write(payload)
    }

    /* ---- mac-fs-pull: phone-initiated download from the Mac (Phase 19) ---- */

    /** Registers a receiver for an in-progress mac-fs-pull download and suspends until
     *  [onMacFsPullControl] reports mac-fs-pull-done/-error. Bytes land in the same
     *  Downloads folder existing Mac→phone pushes use ([FileRepo.uniqueDest] avoids
     *  overwriting a same-named file already there). */
    suspend fun receiveMacFsPull(
        reqId: String,
        transferId: Int,
        size: Long,
        name: String,
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit
    ): Result<File> {
        val dest = FileRepo.uniqueDest(File("/sdcard/Download/"), name)
        incomingMacFs[transferId] = MacFsPullReceiver(dest, size, onProgress)
        val completion = CompletableDeferred<Result<File>>()
        pendingMacFsPull[reqId] = MacFsPullEntry(transferId, completion)
        // Only now is it safe for the Mac to start sending. Before this ack
        // existed it streamed immediately after mac-fs-pull-begin, so the
        // leading chunks arrived before this receiver was registered and were
        // dropped — producing a silently truncated file.
        send(JSONObject().put("type", "mac-fs-pull-ready").put("reqId", reqId))
        return completion.await()
    }

    /** Handles mac-fs-pull-done / mac-fs-pull-error once a download is underway — routed
     *  here from ConnectionManager since these gate a binary transfer's lifecycle, mirroring
     *  how fs-push-begin/-done are handled above via [onControl]. */
    fun onMacFsPullControl(msg: JSONObject) {
        val entry = pendingMacFsPull.remove(msg.optString("reqId")) ?: return
        val receiver = incomingMacFs.remove(entry.transferId)
        if (msg.optString("type") == "mac-fs-pull-done") {
            val file = receiver?.finish()
            entry.completion.complete(
                when {
                    file == null -> Result.failure(IllegalStateException("no receiver for transfer"))
                    // Never report success on a short file. Without this a
                    // dropped chunk looked exactly like a completed download.
                    receiver.expected > 0 && file.length() != receiver.expected ->
                        Result.failure(
                            IllegalStateException(
                                "incomplete download (${file.length()}/${receiver.expected} bytes)"
                            )
                        )
                    else -> Result.success(file)
                }
            )
        } else {
            receiver?.abort()
            entry.completion.complete(Result.failure(Exception(msg.optString("error").ifEmpty { "pull failed" })))
        }
    }

    private class MacFsPullReceiver(
        val dest: File,
        val expected: Long,
        val onProgress: (Long, Long) -> Unit
    ) {
        private val out = FileOutputStream(dest)
        private var received = 0L
        private var done = false

        @Synchronized
        fun write(payload: ByteString) {
            if (done) return
            try {
                val a = payload.toByteArray()
                out.write(a)
                received += a.size
                onProgress(received, expected)
            } catch (_: Exception) { /* surfaced when mac-fs-pull-done/-error resolves */ }
        }

        @Synchronized
        fun finish(): File {
            if (!done) { done = true; runCatching { out.flush(); out.close() } }
            return dest
        }

        @Synchronized
        fun abort() {
            if (done) return
            done = true
            runCatching { out.close() }
            dest.delete()
        }
    }

    /* ---- pull: phone → Mac ---- */
    private fun startPull(msg: JSONObject) {
        val reqId = msg.optString("reqId")
        val socket = ws ?: return
        val file: File = try {
            FileRepo.openForRead(msg.optString("path"))
        } catch (e: Exception) {
            send(JSONObject().put("type", "fs-pull-error").put("reqId", reqId).put("error", reason(e)))
            return
        }
        val tid = nextTid.getAndIncrement()
        val size = file.length()
        send(
            JSONObject().put("type", "fs-pull-begin").put("reqId", reqId)
                .put("transferId", tid).put("size", size)
        )
        val job = scope.launch {
            try {
                file.inputStream().use { ins ->
                    val buf = ByteArray(CHUNK)
                    var seq = 0
                    while (isActive) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        // backpressure: keep ≤4MB queued on the socket
                        while (isActive && (ws?.queueSize() ?: 0) > MAX_INFLIGHT) delay(20)
                        if (ws == null) break
                        ConnectionManager.sendBinary(frame(tid, seq++, buf, n))
                    }
                }
                if (isActive) send(
                    JSONObject().put("type", "fs-pull-done").put("reqId", reqId)
                        .put("transferId", tid).put("size", size)
                )
            } catch (e: Exception) {
                send(
                    JSONObject().put("type", "fs-pull-error").put("reqId", reqId)
                        .put("transferId", tid).put("error", reason(e))
                )
            } finally {
                outgoing.remove(tid)
            }
        }
        outgoing[tid] = job
    }

    /* ---- push: Mac → phone ---- */
    private fun beginPush(msg: JSONObject) {
        val reqId = msg.optString("reqId")
        val socket = ws ?: return
        try {
            if (!FileRepo.hasAllFiles()) throw SecurityException("Grant All-files access in the DroidDock app")
            val dir = File(msg.optString("dest", "/sdcard/Download/").ifBlank { "/sdcard/Download/" })
            val name = msg.optString("name", "file")
            // The Mac sets overwrite=true only for edit-in-place writeback,
            // where the whole point is to replace the file the user opened.
            // uniqueDest() would instead fork every save into "name (2).ext"
            // and leave the original untouched while reporting success.
            val dest = if (msg.optBoolean("overwrite", false)) File(dir, name)
                       else FileRepo.uniqueDest(dir, name)
            val tid = nextTid.getAndIncrement()
            incoming[tid] = PushReceiver(tid, dest, msg.optLong("size"), socket)
            send(JSONObject().put("type", "fs-push").put("reqId", reqId).put("transferId", tid))
        } catch (e: Exception) {
            send(JSONObject().put("type", "fs-push-error").put("reqId", reqId).put("error", reason(e)))
        }
    }

    private class PushReceiver(
        val transferId: Int,
        val dest: File,
        val expected: Long,
        val socket: okhttp3.WebSocket
    ) {
        private val out = FileOutputStream(dest)
        private var received = 0L
        private var done = false

        @Synchronized
        fun write(payload: ByteString) {
            if (done) return
            try {
                val a = payload.toByteArray()
                out.write(a)
                received += a.size
            } catch (e: Exception) {
                fail(reason(e))
            }
        }

        @Synchronized
        fun finish(size: Long) {
            if (done) return
            done = true
            runCatching { out.flush(); out.close() }
            val target = if (size > 0) size else expected
            if (received != target) {
                dest.delete()
                socket.send(ConnectionManager.wire(result(false, "size mismatch ($received/$target)")))
            } else {
                socket.send(ConnectionManager.wire(result(true, null)))
            }
        }

        @Synchronized
        fun abort() {
            if (done) return
            done = true
            runCatching { out.close() }
            dest.delete()
        }

        private fun fail(err: String) {
            if (done) return
            done = true
            runCatching { out.close() }
            dest.delete()
            socket.send(ConnectionManager.wire(result(false, err)))
        }

        /** Returns the JSONObject rather than a String so callers can hand it to
         *  ConnectionManager.wire() and have it sealed on an encrypted session. */
        private fun result(ok: Boolean, err: String?): JSONObject =
            JSONObject().put("type", "fs-push-result").put("transferId", transferId)
                .put("ok", ok).apply { if (err != null) put("error", err) }
    }

    /* ---- helpers ---- */
    /** Returns a plain ByteArray, not a ByteString: every frame now leaves via
     *  [ConnectionManager.sendBinary], which may seal it before it reaches the
     *  socket. */
    private fun frame(tid: Int, seq: Int, payload: ByteArray, len: Int): ByteArray {
        val b = Buffer()
        b.writeByte(KIND_DATA)
        b.writeInt(tid) // okio writeInt = big-endian
        b.writeInt(seq)
        b.write(payload, 0, len)
        return b.readByteArray()
    }

    private fun beInt(b: ByteString, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    private fun reason(e: Throwable): String =
        if (e is SecurityException) "Grant All-files access in the DroidDock app"
        else e.message ?: "transfer failed"
}
