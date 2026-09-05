package com.droiddock.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager

/**
 * Answer, hang up, mute and speaker for the call the phone is on right now,
 * driven from the Mac over the Wi-Fi link.
 *
 * # Why this is not the ADB path
 *
 * [`adb.rs`'s call control][1] drives the same four actions by shelling out to
 * `input keyevent` against the dialer's UI, which needs a USB or Wi-Fi ADB
 * device attached. That left the Wi-Fi link able to *announce* an incoming
 * call and do nothing about it — a caller-ID alert you cannot decline. These
 * are the public framework equivalents, so they work on the plain app link.
 *
 * [1]: droiddock-tauri/app/src-tauri/src/adb.rs
 *
 * # What each action actually costs, and where it can fail
 *
 * **Answer** is [TelecomManager.acceptRingingCall], API 26+, gated on
 * `ANSWER_PHONE_CALLS`. It returns `void`: there is no synchronous way to learn
 * whether a call was actually picked up, so a successful reply here means *the
 * request was accepted by the platform*, not *the call is connected*. The real
 * confirmation is the `PHONE_STATE` broadcast that follows, which
 * [CallReceiver] forwards to the Mac as `state:"active"` — the Mac's overlay
 * changes on that, not on this reply.
 *
 * **End** is [TelecomManager.endCall], API 28+, same permission. It *does*
 * return a boolean, and a `false` means there was no call to end — reported as
 * a failure rather than swallowed, because the Mac has a button that must not
 * look like it worked when it didn't.
 *
 * `endCall` carries a deprecation notice as of API 29 pointing at
 * `InCallService`. That route requires being the device's default dialer, which
 * a bridge app has no business claiming — it would take over every call on the
 * phone to add a button on the Mac. The deprecated call still works with
 * `ANSWER_PHONE_CALLS` and is the only route available to a non-dialer, so it
 * is used deliberately, not by oversight.
 *
 * **Mute** and **speaker** need only `MODIFY_AUDIO_SETTINGS`, an install-time
 * permission, so they are available whenever the app is. They are also the two
 * that can be quietly ignored by the platform: the audio route belongs to
 * whichever app owns the call, and an OEM dialer is free to put it back. Both
 * are therefore **read back after writing** and the reply carries the state the
 * device actually ended up in, not the state that was asked for. The Mac's
 * toggle renders from that read-back, so a route the dialer refuses shows as a
 * button that springs back rather than a lie.
 *
 * # What is deliberately absent
 *
 * **DTMF.** Injecting tones into a live call is `Call.playDtmfTone`, reachable
 * only from an `InCallService` — the default-dialer role again. There is no
 * public route for a third-party app, so the Mac keeps its keypad on the ADB
 * transport and hides it on Wi-Fi rather than offering a pad that does nothing.
 */
object CallControl {

    /** The outcome of one action, including the audio state as it really is afterwards. */
    data class Outcome(
        val ok: Boolean,
        val error: String? = null,
        val muted: Boolean? = null,
        val speaker: Boolean? = null,
    )

    /** Every action name the Mac may send. Anything else is rejected by name. */
    val ACTIONS = setOf("answer", "end", "mute", "speaker")

    private fun hasAnswerPerm(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether [answer] has any chance of working — API floor *and* the grant. */
    fun canAnswer(ctx: Context) = Build.VERSION.SDK_INT >= 26 && hasAnswerPerm(ctx)

    /** Whether [end] has any chance of working. `endCall` landed one release after
     *  `acceptRingingCall`, so these two genuinely differ on API 26–27 devices. */
    fun canEnd(ctx: Context) = Build.VERSION.SDK_INT >= 28 && hasAnswerPerm(ctx)

    /** Mute and speaker ride an install-time permission, so they are always available. */
    fun canAudio(ctx: Context) = audio(ctx) != null

    /** True when at least one action is available — what the `callctl` capability means. */
    fun available(ctx: Context) = canAnswer(ctx) || canEnd(ctx)

    private fun audio(ctx: Context): AudioManager? =
        ctx.getSystemService(AudioManager::class.java)

    private fun telecom(ctx: Context): TelecomManager? =
        ctx.getSystemService(TelecomManager::class.java)

    /** Current mic-mute state, or null when the audio service is unreachable. */
    fun isMuted(ctx: Context): Boolean? = audio(ctx)?.isMicrophoneMute

    /** Current speakerphone state, read the way the running API level defines it. */
    fun isSpeaker(ctx: Context): Boolean? {
        val am = audio(ctx) ?: return null
        return if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }

    /**
     * Run one action. [on] is the *absolute* state wanted for the two toggles —
     * never a flip. A toggle would desynchronise the moment the phone's own
     * in-call UI changed the route behind the Mac's back, which is exactly the
     * case these read-backs exist to catch.
     */
    fun perform(ctx: Context, action: String, on: Boolean?): Outcome {
        // The two toggles are absolute, so a missing value is a malformed
        // request rather than something to guess a direction for.
        if ((action == "mute" || action == "speaker") && on == null) {
            return Outcome(false, "$action needs an on/off value")
        }
        return when (action) {
            "answer" -> answer(ctx)
            "end" -> end(ctx)
            "mute" -> setMuted(ctx, on == true)
            "speaker" -> setSpeaker(ctx, on == true)
            else -> Outcome(false, "unknown call action")
        }
    }

    private fun answer(ctx: Context): Outcome {
        if (Build.VERSION.SDK_INT < 26) return Outcome(false, "Answering needs Android 8 or newer")
        if (!hasAnswerPerm(ctx)) return Outcome(false, PERM_HINT)
        val tm = telecom(ctx) ?: return Outcome(false, "Telephony is unavailable on this device")
        // acceptRingingCall returns void — see the class doc: `ok` here means the
        // platform took the request, and CallReceiver's `active` push is the proof.
        return runCatching { tm.acceptRingingCall(); Outcome(true) }
            .getOrElse { Outcome(false, it.message ?: "Could not answer") }
    }

    private fun end(ctx: Context): Outcome {
        if (Build.VERSION.SDK_INT < 28) return Outcome(false, "Hanging up needs Android 9 or newer")
        if (!hasAnswerPerm(ctx)) return Outcome(false, PERM_HINT)
        val tm = telecom(ctx) ?: return Outcome(false, "Telephony is unavailable on this device")
        return runCatching {
            @Suppress("DEPRECATION")
            if (tm.endCall()) Outcome(true) else Outcome(false, "There was no call to end")
        }.getOrElse { Outcome(false, it.message ?: "Could not hang up") }
    }

    private fun setMuted(ctx: Context, on: Boolean): Outcome {
        val am = audio(ctx) ?: return Outcome(false, "Audio is unavailable on this device")
        runCatching { am.isMicrophoneMute = on }
            .onFailure { return Outcome(false, it.message ?: "Could not change the microphone") }
        val now = am.isMicrophoneMute
        return if (now == on) {
            Outcome(true, muted = now, speaker = isSpeaker(ctx))
        } else {
            Outcome(false, "The phone's dialer kept the microphone ${if (now) "muted" else "live"}",
                muted = now, speaker = isSpeaker(ctx))
        }
    }

    private fun setSpeaker(ctx: Context, on: Boolean): Outcome {
        val am = audio(ctx) ?: return Outcome(false, "Audio is unavailable on this device")
        val failure = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val speaker = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: return Outcome(false, "This phone reports no built-in speaker to route to")
                    am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }.exceptionOrNull()
        if (failure != null) return Outcome(false, failure.message ?: "Could not change the speaker")

        val now = isSpeaker(ctx)
        return if (now == on) {
            Outcome(true, muted = isMuted(ctx), speaker = now)
        } else {
            Outcome(false, "The phone's dialer kept the call on ${if (now == true) "speaker" else "the earpiece"}",
                muted = isMuted(ctx), speaker = now)
        }
    }

    private const val PERM_HINT =
        "Grant the Phone permissions (Calls) to DroidDock on your phone"
}
