package com.droiddock.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    // Phone-initiated push to Mac
    private data class PendingPhonePush(val uri: Uri, val name: String, val size: Long, val onResult: (Boolean, String?) -> Unit)
    private val pendingPhonePush = ConcurrentHashMap<String, PendingPhonePush>()     // reqId → pending
    private val pendingPhonePushResult = ConcurrentHashMap<Int, (Boolean, String?) -> Unit>() // transferId → callback
    private val phonePushSeq = AtomicInteger(1)

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
        pendingPhonePush.values.forEach { it.onResult(false, "link dropped") }
        pendingPhonePush.clear()
        pendingPhonePushResult.values.forEach { it(false, "link dropped") }
        pendingPhonePushResult.clear()
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
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
        try {
            ctx.contentResolver.openInputStream(p.uri)?.use { ins ->
                val buf = ByteArray(CHUNK)
                var seq = 0
                while (currentCoroutineContext().isActive) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    while (currentCoroutineContext().isActive && (ws?.queueSize() ?: 0) > MAX_INFLIGHT) delay(20)
                    val s = ws ?: break
                    s.send(frame(transferId, seq++, buf, n))
                }
            }
            if (currentCoroutineContext().isActive) {
                // Register result callback BEFORE sending done so the ack can't race
                pendingPhonePushResult[transferId] = p.onResult
                send(JSONObject().put("type", "phone-push-done")
                    .put("transferId", transferId).put("size", p.size))
            }
        } catch (e: Exception) {
            pendingPhonePushResult.remove(transferId)
            outgoing.remove(transferId)
            p.onResult(false, e.message ?: "send failed")
        }
    }

    private fun uriFileName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }

    private fun uriFileSize(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)) else 0L
        } ?: 0L

    /** Send a photo thumbnail (small, single frame) keyed by the request id. */
    fun sendThumb(reqId: Int, bytes: ByteArray) {
        val s = ws ?: return
        val b = Buffer()
        b.writeByte(KIND_THUMB)
        b.writeInt(reqId)
        b.writeInt(0)
        b.write(bytes)
        s.send(b.readByteString())
    }

    fun onBinary(bytes: ByteString) {
        if (bytes.size < HEADER) return
        if ((bytes[0].toInt() and 0xff) != KIND_DATA) return
        val tid = beInt(bytes, 1)
        incoming[tid]?.write(bytes.substring(HEADER))
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
                        val s = ws ?: break
                        s.send(frame(tid, seq++, buf, n))
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
            val dest = FileRepo.uniqueDest(dir, msg.optString("name", "file"))
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
                socket.send(result(false, "size mismatch ($received/$target)"))
            } else {
                socket.send(result(true, null))
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
            socket.send(result(false, err))
        }

        private fun result(ok: Boolean, err: String?) =
            JSONObject().put("type", "fs-push-result").put("transferId", transferId)
                .put("ok", ok).apply { if (err != null) put("error", err) }.toString()
    }

    /* ---- helpers ---- */
    private fun frame(tid: Int, seq: Int, payload: ByteArray, len: Int): ByteString {
        val b = Buffer()
        b.writeByte(KIND_DATA)
        b.writeInt(tid) // okio writeInt = big-endian
        b.writeInt(seq)
        b.write(payload, 0, len)
        return b.readByteString()
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
