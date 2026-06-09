package com.droiddock.app

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

object MediaRemote {
    @Volatile private var appCtx: Context? = null
    @Volatile private var controller: MediaController? = null

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

    fun push() {
        ConnectionManager.send(snapshot())

        handler.removeCallbacks(ticker)
        val c = controller
        if (c != null && c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            handler.postDelayed(ticker, 1000)
        }
    }

    fun snapshot(): JSONObject {
        val ctx = appCtx
            ?: return JSONObject().put("type", "media").put("active", false)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val c = controller
        val md = c?.metadata
        return JSONObject()
            .put("type", "media")
            .put("active", c != null)
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("app", c?.let { appLabel(ctx, it.packageName) } ?: "")
            .put("playing", c?.playbackState?.state == PlaybackState.STATE_PLAYING)
            .put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("pos", c?.playbackState?.position ?: 0L)
            .put("dur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
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

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0))
            .toString()
    }.getOrDefault(pkg)
}
