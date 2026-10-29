package com.droiddock.app

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * Internet calls (WhatsApp, Telegram, Signal, Meet…) as the Mac's call pop-up.
 *
 * The phone's `PHONE_STATE` broadcast fires for some of these calls, but with no number or name, and
 * Telecom's answer / hang-up only reach carrier calls. Their own call notification has both: the
 * caller's name and the app's Answer / Decline / Hang up actions. So while such a notification is up,
 * it drives the `call` messages and the Mac's buttons fire those actions; [CallReceiver] stays quiet.
 */
object VoipCalls {
    /** The phone's own dialer / in-call UI: carrier calls, already handled by [CallReceiver]. */
    private val PHONE_APPS = setOf(
        "com.samsung.android.incallui", "com.samsung.android.dialer", "com.android.dialer",
        "com.google.android.dialer", "com.android.incallui", "com.android.server.telecom", "com.android.phone",
    )
    private val ANSWER = listOf("answer", "accept", "pick up")
    private val DECLINE = listOf("decline", "reject", "dismiss")
    private val HANG_UP = listOf("hang up", "end call", "end", "leave")

    private class Call(
        val key: String,
        val state: String,
        val name: String,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        /** The notification's own button labels (localized), for tapping them on screen. */
        val labels: Map<String, String>,
    )

    private const val TAG = "DroidDockCall"
    @Volatile private var current: Call? = null

    /** An internet call is ringing or in progress (its notification is up). */
    val active: Boolean get() = current != null

    fun isVoipCall(sbn: StatusBarNotification): Boolean =
        sbn.notification.category == Notification.CATEGORY_CALL && sbn.packageName !in PHONE_APPS

    fun onPosted(ctx: Context, sbn: StatusBarNotification, appLabel: String) {
        val n = sbn.notification
        val ex = n.extras
        val answer = extraIntent(ex, "android.answerIntent") ?: action(n, ANSWER)
        val decline = extraIntent(ex, "android.declineIntent") ?: action(n, DECLINE)
        val hangUp = extraIntent(ex, "android.hangUpIntent") ?: action(n, HANG_UP)
        // CallStyle: 1 = incoming, 2 = ongoing, 3 = screening. Older apps: an Answer button means it rings.
        val state = when (ex.getInt("android.callType", 0)) {
            1, 3 -> "ringing"
            2 -> "active"
            else -> if (answer != null) "ringing" else "active"
        }
        Log.i(TAG, "call notification ${sbn.packageName} state=$state callType=${ex.getInt("android.callType", 0)} " +
            "actions=${n.actions?.map { it.title } ?: emptyList<CharSequence>()} answer=${answer != null} " +
            "decline=${decline != null} hangUp=${hangUp != null}")
        val person = if (Build.VERSION.SDK_INT >= 28) personName(ex) else null
        val name = person ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val prev = current
        current = Call(sbn.key, state, name, answer, decline, hangUp, labelsOf(n))
        if (prev != null && prev.key == sbn.key && prev.state == state && prev.name == name) return
        ConnectionManager.send(
            JSONObject()
                .put("type", "call")
                .put("state", state)
                .put("name", name.ifEmpty { appLabel })
                .put("app", appLabel)
                .put("pkg", sbn.packageName)
                .put("voip", true)
                .put("canAnswer", state == "ringing" && answer != null)
                .put("canEnd", (decline ?: hangUp) != null)
                .put("canAudio", CallControl.canAudio(ctx))
        )
    }

    fun onRemoved(key: String) {
        if (current?.key != key) return
        current = null
        ConnectionManager.send(JSONObject().put("type", "call").put("state", "idle"))
    }

    /** The Mac's Answer / Decline / Hang up for an internet call; null when no such call is up. */
    fun perform(action: String): CallControl.Outcome? {
        val c = current ?: return null.also { Log.i(TAG, "perform $action: no internet call up") }
        Log.i(TAG, "perform $action on ${c.state} call")
        // Answer starts the app's call screen, which Android refuses to a background sender. Tapping the
        // real button (heads-up, shade or call screen) through our accessibility service counts as the
        // user's own tap, so try that first; the PendingIntent stays as the fallback.
        if (action == "answer" && AccessibilityControl.available()) {
            val label = c.labels["answer"]
            if (AccessibilityControl.clickLabel(listOfNotNull(label) + ANSWER + listOf("accept call", "answer call"), openShade = true)) {
                Log.i(TAG, "perform answer: tapped on screen")
                return CallControl.Outcome(true)
            }
            Log.i(TAG, "perform answer: no button on screen, sending the action")
        }
        val pi = when (action) {
            "answer" -> c.answer ?: return CallControl.Outcome(false, "This app doesn't allow answering from another device")
            "end" -> (if (c.state == "ringing") c.decline ?: c.hangUp else c.hangUp ?: c.decline)
                ?: return CallControl.Outcome(false, "This app doesn't allow hanging up from another device")
            else -> return null   // mute / speaker: the shared AudioManager path still applies
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Answering opens the app's call screen: allow that start from the background.
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(null, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            Log.i(TAG, "perform $action: sent")
            CallControl.Outcome(true)
        }.getOrElse {
            Log.w(TAG, "perform $action failed", it)
            CallControl.Outcome(false, it.message ?: "The app refused the action")
        }
    }

    /** answer / decline / hangUp → the button's own text, matched by intent or by word. */
    private fun labelsOf(n: Notification): Map<String, String> {
        val out = HashMap<String, String>()
        val ex = n.extras
        for (a in n.actions.orEmpty()) {
            val t = a.title?.toString()?.trim().orEmpty()
            if (t.isEmpty()) continue
            val pi = a.actionIntent
            val lower = t.lowercase()
            when {
                pi != null && pi == extraIntent(ex, "android.answerIntent") || ANSWER.any { lower.contains(it) } -> out.putIfAbsent("answer", t)
                pi != null && pi == extraIntent(ex, "android.declineIntent") || DECLINE.any { lower.contains(it) } -> out.putIfAbsent("decline", t)
                pi != null && pi == extraIntent(ex, "android.hangUpIntent") || HANG_UP.any { lower.contains(it) } -> out.putIfAbsent("hangUp", t)
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun extraIntent(ex: android.os.Bundle, key: String): PendingIntent? =
        ex.get(key) as? PendingIntent

    private fun action(n: Notification, words: List<String>): PendingIntent? =
        n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { w -> t == w || t.startsWith("$w ") || t.contains(w) && w.length > 3 }
        }?.actionIntent

    @Suppress("DEPRECATION")
    private fun personName(ex: android.os.Bundle): String? =
        (ex.get("android.callPerson") as? Person)?.name?.toString()?.takeIf { it.isNotBlank() }
}
