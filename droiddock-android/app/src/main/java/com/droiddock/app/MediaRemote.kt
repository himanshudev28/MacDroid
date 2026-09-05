package com.droiddock.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object MediaRemote {
    /** Album art longest edge — the Mac shows it as a card backdrop, not a poster. */
    private const val ART_PX = 512

    @Volatile private var appCtx: Context? = null
    @Volatile private var controller: MediaController? = null
    /** Last track whose art we sent, so ticker pushes stay small. */
    @Volatile private var lastArtKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                push()
            }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() {
            controller = null
            push()
        }
    }

    /** Called from NotifListener.onCreate — notification access is what unlocks media sessions. */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val component = ComponentName(ctx, NotifListener::class.java)
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        runCatching {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list) }, component)
            pick(msm.getActiveSessions(component))
        }
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(callback)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        controller?.registerCallback(callback)
        push()
    }

    fun push() = push(false)

    /** [forceArt] re-sends album art even if the track hasn't changed — used on
     *  link-up, where the Mac has no cache yet. */
    fun push(forceArt: Boolean) {
        handler.removeCallbacks(ticker)
        // Nothing is listening → don't build the snapshot and don't re-arm.
        //
        // `snapshot()` runs on the main thread and costs two AudioManager
        // binder calls, a PackageManager label lookup and a twelve-field JSON
        // build; `send` then dropped all of it on the floor because `ws` was
        // null. Playing music with no Mac in sight paid that once a second,
        // indefinitely, for a message nobody received. The ticker restarts from
        // `onLinkUp` when a Mac actually arrives.
        if (!ConnectionManager.connected.value) return

        ConnectionManager.send(snapshot(forceArt))

        val c = controller
        if (c != null && c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            handler.postDelayed(ticker, 1000)
        }
    }

    fun snapshot(): JSONObject = snapshot(false)

    fun snapshot(forceArt: Boolean): JSONObject {
        val ctx = appCtx
            ?: return JSONObject().put("type", "media").put("active", false)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val c = controller
        val md = c?.metadata

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        // Identifies the artwork, not the playback position — the Mac caches
        // the image against this and only redraws when it changes.
        val trackKey = if (c == null) "" else "$title|$artist|$album"

        val out = JSONObject()
            .put("type", "media")
            .put("active", c != null)
            .put("title", title)
            .put("artist", artist)
            .put("app", c?.let { appLabel(ctx, it.packageName) } ?: "")
            .put("playing", c?.playbackState?.state == PlaybackState.STATE_PLAYING)
            .put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("pos", c?.playbackState?.position ?: 0L)
            .put("dur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .put("trackKey", trackKey)

        // Art rides along only when the track actually changed. `push()` fires
        // once a second while playing, and a ~40 KB base64 image on every tick
        // would dwarf every other message on this link combined.
        if (forceArt || trackKey != lastArtKey) {
            lastArtKey = trackKey
            val art = md?.let { artBytes(it) }
            if (art != null) {
                out.put("art", Base64.encodeToString(art, Base64.NO_WRAP))
            } else {
                // Explicit null tells the Mac to drop cached art for the old
                // track rather than keep showing it under a new one.
                out.put("art", JSONObject.NULL)
            }
        }
        return out
    }

    /** Album art as JPEG bytes, downscaled — null when the session carries none. */
    private fun artBytes(md: MediaMetadata): ByteArray? {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return null
        return runCatching {
            val longest = maxOf(bmp.width, bmp.height)
            val scaled = if (longest <= ART_PX) bmp else {
                val ratio = ART_PX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * ratio).toInt().coerceAtLeast(1),
                    (bmp.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (scaled !== bmp) scaled.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    fun command(cmd: String, value: Int) {
        val ctx = appCtx ?: return
        val tc = controller?.transportControls
        when (cmd) {
            "play" -> tc?.play()
            "pause" -> tc?.pause()
            "next" -> tc?.skipToNext()
            "prev" -> tc?.skipToPrevious()
            "seek" -> {
                runCatching {
                    tc?.seekTo(value.toLong())
                }
            }
            "setvol" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0)
                }
            }
        }
        push()
    }

    /** `pkg` → display label. A media session's package changes when the user
     *  switches player, i.e. rarely — but `snapshot()` asked PackageManager for
     *  the label once a second, which is a binder round trip on the main thread
     *  for an answer that cannot change while the app is installed. */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun appLabel(ctx: Context, pkg: String): String =
        labelCache.getOrPut(pkg) { appLabelUncached(ctx, pkg) }

    private fun appLabelUncached(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0))
            .toString()
    }.getOrDefault(pkg)
}
