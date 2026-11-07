package com.droiddock.app

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import com.droiddock.app.link.Link
import com.droiddock.app.link.LinkKind
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Per-feature message handlers split out of `ConnectionManager.dispatch`. Each `handle` returns true if it
 * owned the message type, so [ConnectionManager.dispatch] can try them in turn. Routing and threading
 * (`respond` vs `respondAsync`) are unchanged from the single big `when` they came from.
 */
internal object MessagingHandlers {
    fun handle(link: Link, msg: JSONObject, type: String): Boolean {
        val cm = ConnectionManager
        val ctx = cm.appCtx
        when (type) {
            "sms-threads" -> cm.respondAsync(link, msg, "sms-threads") {
                it.put("threads", SmsRepo.threads(ctx))
            }
            "sms-messages" -> cm.respondAsync(link, msg, "sms-messages") {
                val tid = msg.optLong("threadId")
                val (address, messages) = SmsRepo.messages(ctx, tid, if (link.kind == LinkKind.BLE) 50 else 60)
                it.put("threadId", tid).put("address", address).put("messages", messages)
            }
            "sms-send" -> cm.respond(link, msg, "sms-send") {
                SmsRepo.send(ctx, msg.optString("address"), msg.optString("text"))
                it.put("ok", true)
            }
            "contacts" -> cm.respondAsync(link, msg, "contacts") {
                it.put("contacts", ContactsRepo.list(ctx))
            }
            else -> return false
        }
        return true
    }
}

internal object FileHandlers {
    fun handle(link: Link, msg: JSONObject, type: String): Boolean {
        val cm = ConnectionManager
        val ctx = cm.appCtx
        when (type) {
            "fs-list" -> cm.respondFs(link, msg, "fs-list") {
                it.put("entries", FileRepo.list(msg.optString("path")))
            }
            "fs-delete" -> cm.respondFs(link, msg, "fs-delete") {
                FileRepo.delete(msg.optString("path"))
                it.put("ok", true)
            }
            // MKCOL from the Finder mount.
            "fs-mkdir" -> cm.respondFs(link, msg, "fs-mkdir") {
                it.put("path", FileRepo.mkdir(msg.optString("path"), msg.optString("name")))
            }
            "fs-rename" -> cm.respondFs(link, msg, "fs-rename") {
                val newPath = FileRepo.rename(msg.optString("path"), msg.optString("newName"))
                it.put("ok", true).put("newPath", newPath)
            }
            "fs-pull", "fs-push-begin", "fs-push-done", "fs-cancel",
            "phone-push", "phone-push-error", "phone-push-result" ->
                TransferManager.onControl(msg)
            "photos-list" -> cm.respondAsync(link, msg, "photos-list") {
                it.put("items", PhotoRepo.list(ctx, msg.optInt("offset", 0), msg.optInt("limit", 500)))
            }
            "photo-thumb" -> {
                val reqId = msg.optLong("reqId")
                val id = msg.optLong("id")
                val kind = msg.optString("kind", "image")
                cm.thumbAsync(link, reqId, "photo-thumb-error", "thumb failed") {
                    PhotoRepo.thumbBytes(ctx, id, kind)
                }
            }
            else -> return false
        }
        return true
    }
}

/** Phone-initiated requests to the Mac (mac-fs-* and Mac apps) share one pending registry keyed by our reqId. */
internal object MacFsHandlers {
    fun handle(msg: JSONObject, type: String): Boolean {
        when (type) {
            "mac-fs-list-result", "mac-fs-list-error", "mac-fs-pull-begin",
            "mac-apps-list", "mac-app-launch" -> ConnectionManager.completePendingMacFs(msg)
            "mac-fs-pull-done" -> TransferManager.onMacFsPullControl(msg)
            // Arrives either before a transfer begins (still in the registry, e.g. a bad path) or
            // mid-transfer after mac-fs-pull-begin handed off to TransferManager; there is no
            // transferId to tell which, so check both in order.
            "mac-fs-pull-error" ->
                if (!ConnectionManager.completePendingMacFs(msg)) TransferManager.onMacFsPullControl(msg)
            else -> return false
        }
        return true
    }
}

internal object CallHandlers {
    fun handle(link: Link, msg: JSONObject, type: String): Boolean {
        val cm = ConnectionManager
        val ctx = cm.appCtx
        when (type) {
            "action-call" -> {
                val number = msg.optString("number")
                if (number.isNotEmpty()) {
                    val canCall = ctx.checkSelfPermission(
                        android.Manifest.permission.CALL_PHONE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    // place the call directly if allowed, else just open the dialer
                    val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    val intent = Intent(action).setData(Uri.parse("tel:$number"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Arm before starting: on a fast dial the OFFHOOK broadcast can beat us back here.
                    if (canCall) CallReceiver.armOutgoing(ctx, number)
                    runCatching { ctx.startActivity(intent) }
                }
            }
            // Answer / hang up / mute / speaker. Replies with the audio state the device really ended up in.
            "call-action" -> cm.respond(link, msg, "call-action-result") {
                val action = msg.optString("action")
                require(action in CallControl.ACTIONS) { "unknown call action" }
                val on = if (msg.has("on")) msg.optBoolean("on") else null
                val outcome = CallControl.perform(ctx, action, on)
                it.put("ok", outcome.ok)
                outcome.error?.let { e -> it.put("error", e) }
                outcome.muted?.let { m -> it.put("muted", m) }
                outcome.speaker?.let { sp -> it.put("speaker", sp) }
            }
            // Find the phone. `on:false` stops it; it also stops itself after a minute.
            "ring" -> cm.respond(link, msg, "ring") {
                val on = msg.optBoolean("on", true)
                if (on) {
                    Ringer.start(ctx, msg.optInt("seconds", Ringer.DEFAULT_SECONDS)).getOrThrow()
                } else {
                    Ringer.stop(ctx)
                }
                it.put("ringing", Ringer.ringing)
            }
            else -> return false
        }
        return true
    }
}

internal object AppsMediaHandlers {
    fun handle(link: Link, msg: JSONObject, type: String): Boolean {
        val cm = ConnectionManager
        val ctx = cm.appCtx
        when (type) {
            // Every grant this app needs, and what breaks without each; answered on demand.
            "health" -> cm.respond(link, msg, "health") {
                it.put("items", PermissionHealth.snapshot(ctx))
            }
            "health-fix" -> cm.respond(link, msg, "health-fix") {
                it.put("result", PermissionHealth.openFix(ctx, msg.optString("id")))
            }
            "media-cmd" -> MediaRemote.command(msg.optString("cmd"), msg.optInt("value"))
            "wallpaper" -> cm.respondAsync(link, msg, "wallpaper") {
                it.put(
                    "data",
                    android.util.Base64.encodeToString(WallpaperRepo.bytes(ctx), android.util.Base64.NO_WRAP)
                )
            }
            "screenshot" -> {
                val reqId = msg.optLong("reqId")
                AccessibilityControl.takeScreenshot { png, err ->
                    val out = ScreenshotReply.build(reqId, png, err)
                    // A refused send (outbound queue full) used to vanish; say why instead.
                    if (!cm.reply(link, out) && !out.has("error")) {
                        cm.reply(link, ScreenshotReply.build(reqId, null, "Screenshot too large to send; try again"))
                    }
                }
            }
            "apps-list" -> cm.respondAsync(link, msg, "apps-list") {
                it.put("apps", AppsRepo.list(ctx))
            }
            // Same shape as photo-thumb: the icon rides the KIND_THUMB binary frame keyed by reqId.
            "app-icon" -> {
                val reqId = msg.optLong("reqId")
                val pkg = msg.optString("pkg")
                cm.thumbAsync(link, reqId, "app-icon-error", "icon failed") { AppsRepo.iconBytes(ctx, pkg) }
            }
            "app-launch" -> {
                val pkg = msg.optString("pkg")
                if (pkg.isNotEmpty()) {
                    val ok = AppsRepo.launch(ctx, pkg)
                    cm.lastEvent.value = if (ok) "opened $pkg" else "could not open $pkg"
                }
            }
            else -> return false
        }
        return true
    }
}

internal object MirrorHandlers {
    private fun facingOf(msg: JSONObject): Int =
        if (msg.optString("facing") == "front") CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

    /** Additive fields: a Mac that predates them sends none and the service keeps its defaults. */
    private fun applyStreamParams(msg: JSONObject) {
        MirrorService.setQuality(msg.optInt("bitrate"), msg.optInt("fps"), msg.optInt("maxSize"))
        MirrorService.setCodec(msg.optString("codec"))
    }

    fun handle(link: Link, msg: JSONObject, type: String): Boolean {
        val cm = ConnectionManager
        val ctx = cm.appCtx
        when (type) {
            "mirror-start" -> {
                applyStreamParams(msg)
                MirrorService.setAudio(msg.optBoolean("audio"))
                val inst = MirrorService.instance
                if (inst != null && inst.isScreenAlive()) inst.resumeStreaming()
                else cm.launchMirror("screen", CameraCharacteristics.LENS_FACING_BACK)
            }
            "mirror-pause" -> MirrorService.instance?.pauseVideo()
            "mirror-resume" -> MirrorService.instance?.resumeVideo()
            // Always fully stop so the phone clears its cast/screen-share notification.
            "mirror-stop", "camera-stop" -> MirrorService.stop(ctx)
            "camera-start" -> {
                applyStreamParams(msg)
                MirrorService.setAudio(false)
                cm.launchMirror("camera", facingOf(msg))
            }
            "camera-flip" -> MirrorService.instance?.flip(facingOf(msg))
            // Screen control. All four need the accessibility service; without it they no-op, so tell the Mac once.
            "mirror-tap", "mirror-swipe", "mirror-key", "mirror-text" -> control(link, msg, type)
            else -> return false
        }
        return true
    }

    private fun control(link: Link, msg: JSONObject, type: String) {
        val cm = ConnectionManager
        val isLock = type == "mirror-key" && msg.optString("key") == "lock"
        // Lock has a second route: device admin's lockNow() needs no accessibility service.
        if (isLock && LockAdmin.lock(cm.appCtx)) {
            cm.lastEvent.value = "locked by Mac"
        } else if (!AccessibilityControl.available()) {
            cm.reportControlUnavailable(link, wasLock = isLock)
        } else when (type) {
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
