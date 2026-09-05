package com.droiddock.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Phone → Mac audio, over the app link (no ADB / scrcpy).
 *
 * Uses `AudioPlaybackCapture` (API 29+), which re-routes what the phone is
 * *playing* into an [AudioRecord] — it is not the microphone, but it goes
 * through the same class, so it needs `RECORD_AUDIO` and a live
 * [MediaProjection]. The projection is the one [MirrorService] already holds
 * for the screen, so audio costs no extra consent dialog.
 *
 * Wire format is deliberately raw PCM rather than AAC/Opus. Encoding would save
 * bandwidth we are not short of — 48 kHz stereo s16 is ~192 KB/s next to a
 * 6 Mbps video stream on a LAN — and would buy a codec-negotiation handshake,
 * a decoder that can fail to configure, and a class of bugs that only appear on
 * the Mac. Raw samples cannot fail to decode. The header carries the format, so
 * a compressed mode can be added later without breaking the frame layout.
 *
 * **Platform limitation, not a bug:** an app can opt out of playback capture
 * with `setAllowedCapturePolicy`, and most paid music/video apps do. Those
 * stream as silence. Apps that allow capture (and system/game/unknown-usage
 * audio) come through fine.
 */
class AudioCapture(private val onStopped: () -> Unit) {

    private var record: AudioRecord? = null
    private var pump: Thread? = null
    @Volatile private var running = false
    @Volatile private var streaming = true

    /**
     * Start capturing. Returns false when the platform cannot do it at all
     * (pre-29), so the caller can carry on mirroring video without audio
     * instead of failing the whole session.
     */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO; see MirrorService
    fun start(projection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (running) return true

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            // MEDIA and GAME are the audio a user means by "my phone's sound".
            // UNKNOWN is included because a surprising number of apps never set
            // a usage at all, and would otherwise be silently uncapturable.
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        // A floor as well as a doubling: getMinBufferSize can report a buffer
        // smaller than one of our 20 ms reads, and a record buffer under the
        // read size overruns into dropouts under any scheduling jitter.
        val bufSize = maxOf(if (minBuf > 0) minBuf * 2 else 0, CHUNK_BYTES * 4)

        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrElse {
            err(it.message ?: "audio capture unavailable")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            err("audio recorder did not initialise")
            return false
        }

        runCatching { rec.startRecording() }.onFailure {
            runCatching { rec.release() }
            err(it.message ?: "could not start audio capture")
            return false
        }

        record = rec
        running = true
        streaming = true

        ConnectionManager.send(
            JSONObject()
                .put("type", "audio-started")
                .put("sampleRate", SAMPLE_RATE)
                .put("channels", 2)
                .put("format", "pcm_s16le")
        )

        pump = thread(name = "mirror-audio") { pump(rec) }
        return true
    }

    private fun pump(rec: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        // Silence suppression. Playback capture yields a continuous stream of
        // zeroes when nothing is playing, and shipping 192 KB/s of silence
        // alongside video is pure waste. We keep sending for a short tail after
        // audio stops so the Mac's buffer drains cleanly rather than clipping
        // the last few milliseconds, then go quiet until real samples return.
        var silentRun = 0
        var muted = false
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) {
                // ERROR_INVALID_OPERATION means the recorder was stopped under
                // us; a transient 0 just means no data yet.
                if (n < 0) break else continue
            }
            if (!streaming) continue

            if (isSilent(buf, n)) {
                silentRun++
                if (silentRun > SILENCE_TAIL_CHUNKS) {
                    muted = true
                    continue
                }
            } else {
                silentRun = 0
            }

            // bit0 marks the first frame after a silent gap: the Mac has let its
            // playback clock run dry by then, so it must reset the schedule
            // rather than queue this chunk after a timestamp long in the past.
            val flags = if (muted) 1 else 0
            muted = false
            ConnectionManager.sendAudio(flags, if (n == buf.size) buf else buf.copyOf(n))
        }
        if (running) {
            // Fell out of the loop on a read error rather than a stop() — tell
            // the Mac, or it waits for audio that is never coming.
            running = false
            err("audio capture ended unexpectedly")
            onStopped()
        }
    }

    /** Stop transmitting but keep the recorder open (mirrors MirrorService's pause). */
    fun pauseStreaming() { streaming = false }

    fun resumeStreaming() { streaming = true }

    fun stop() {
        if (!running && record == null) return
        running = false
        runCatching { pump?.join(300) }
        pump = null
        val rec = record
        record = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { ConnectionManager.send(JSONObject().put("type", "audio-stopped")) }
    }

    private fun err(msg: String) {
        runCatching {
            ConnectionManager.send(JSONObject().put("type", "audio-error").put("error", msg))
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** ~20 ms of 48 kHz stereo s16 — small enough for low latency, large
         *  enough that we are not paying a WebSocket frame per millisecond. */
        const val CHUNK_BYTES = 3840

        /** How long to keep streaming after audio goes quiet, in chunks (~0.5 s). */
        private const val SILENCE_TAIL_CHUNKS = 25

        /**
         * True when every sample in the chunk is at or below [SILENCE_FLOOR].
         *
         * An exact-zero test is not enough: some mixers emit a steady ±1 LSB of
         * dither, which is inaudible but would defeat suppression entirely and
         * keep the stream running at full rate forever.
         */
        fun isSilent(buf: ByteArray, len: Int): Boolean {
            var i = 0
            while (i + 1 < len) {
                // s16le: low byte first, high byte carries the sign.
                val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                if (sample > SILENCE_FLOOR || sample < -SILENCE_FLOOR) return false
                i += 2
            }
            return true
        }

        private const val SILENCE_FLOOR = 2
    }
}
