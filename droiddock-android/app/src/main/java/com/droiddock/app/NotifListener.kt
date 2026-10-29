package com.droiddock.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotifListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConnectionManager.ensureLoop(applicationContext)
        MediaRemote.init(applicationContext)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = forward(sbn, force = false)

    /** Send one notification to the Mac. `force` bypasses the re-post dedupe (used for backfill). */
    fun forward(sbn: StatusBarNotification, force: Boolean) {
        // Nobody to send to: skip the extras parsing, JSON and label lookup.
        // Link-up replays every active notification with force = true, so
        // nothing posted while offline is lost.
        // Internet calls (WhatsApp…) become the Mac's call pop-up, not a notification (VoipCalls).
        if (VoipCalls.isVoipCall(sbn)) {
            if (!force || ConnectionManager.connected.value) VoipCalls.onPosted(this, sbn, appLabel(sbn.packageName))
            return
        }
        if (!ConnectionManager.connected.value) return
        if (!shouldMirror(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // Progress (downloads, uploads, media conversions). `max == 0` with the
        // indeterminate flag set is Android's "spinner, no percentage".
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val hasProgress = progressMax > 0 || indeterminate

        // many apps re-post the same notification; only forward real changes.
        // Progress is part of the identity here on purpose: a download at 40%
        // and the same download at 60% are genuinely different states, and
        // hashing on title|text alone would swallow every update after the first.
        val hash = if (hasProgress) "$title|$text|$progress/$progressMax" else "$title|$text"
        val skip = synchronized(lastSent) {
            if (!force && lastSent[sbn.key] == hash) return@synchronized true
            lastSent[sbn.key] = hash
            if (lastSent.size > 300) lastSent.remove(lastSent.keys.first())
            false
        }
        if (skip) return

        val replyAction = findReplyAction(sbn.notification)
        if (replyAction != null) NotifStore.put(sbn.key, replyAction) else NotifStore.remove(sbn.key)

        // Plain (non-reply) action buttons — "Mark as read", "Snooze", "Open".
        // Stored so a tap on the Mac can fire the original PendingIntent.
        val buttons = sbn.notification.actions
            ?.filter { it.remoteInputs.isNullOrEmpty() && it.title != null }
            ?.take(3)
            ?: emptyList()
        NotifStore.putButtons(sbn.key, buttons)
        // The notification's own tap action (opens the exact chat/screen) for `notif-open`.
        NotifStore.putOpen(sbn.key, sbn.notification.contentIntent, sbn.packageName)

        ConnectionManager.send(
            JSONObject()
                .put("type", "notification")
                .put("key", sbn.key)
                .put("app", appLabel(sbn.packageName))
                // Tier B: lets the Mac fetch the real app icon (via `app-icon`)
                // instead of rendering two-letter initials.
                .put("pkg", sbn.packageName)
                .put("title", title.take(200))
                .put("text", text.take(1000))
                .put("replyable", replyAction != null)
                .put("when", sbn.postTime)
                .put("backfill", force)
                // "max" | "high" | "default" | "low" | "min" — the Mac uses this
                // to decide whether a banner is warranted, not just whether the
                // app is muted.
                .put("priority", priorityName(sbn.notification))
                .put("ongoing", sbn.isOngoing)
                .apply {
                    if (hasProgress) {
                        put("progress", progress)
                        put("progressMax", progressMax)
                        put("progressIndeterminate", indeterminate)
                    }
                    if (buttons.isNotEmpty()) {
                        put("actions", JSONArray(buttons.map { it.title.toString() }))
                    }
                }
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        VoipCalls.onRemoved(sbn.key)
        synchronized(lastSent) { lastSent.remove(sbn.key) }
        NotifStore.remove(sbn.key)
        ConnectionManager.send(
            JSONObject().put("type", "notification-removed").put("key", sbn.key)
        )
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        // Ongoing notifications are usually persistent chrome (VPN, sync
        // service) and were dropped wholesale — but a download IS ongoing, and
        // progress is precisely what's worth showing, so let those through.
        if (sbn.isOngoing && sbn.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) <= 0
            && !sbn.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        ) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // music players spam progress updates — skip them
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return false
        // One UI's Now Bar mirrors the music player as a System UI "ongoing activity" on channel
        // MediaOngoingActivity (flags=0, so the ongoing check above misses it). It duplicates the
        // now-playing card on the Mac — seen as a "MediaOngoingActivity" notification on a Galaxy S21 FE.
        if (sbn.packageName == "com.android.systemui" &&
            (n.channelId == "MediaOngoingActivity" || n.extras.containsKey("android.ongoingActivityNoti.style"))
        ) return false
        return true
    }

    /** Maps Android's numeric importance/priority onto a stable string the Mac
     *  can reason about without knowing the constants. */
    private fun priorityName(n: Notification): String {
        @Suppress("DEPRECATION")
        return when {
            n.priority >= Notification.PRIORITY_MAX -> "max"
            n.priority >= Notification.PRIORITY_HIGH -> "high"
            n.priority <= Notification.PRIORITY_MIN -> "min"
            n.priority <= Notification.PRIORITY_LOW -> "low"
            else -> "default"
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }

    // Label lookup is a PackageManager binder call; apps re-post often.
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        @Volatile
        var instance: NotifListener? = null
        private val lastSent = LinkedHashMap<String, String>()

        /** Replay every currently-active notification — called when the Mac links up. */
        fun pushActive(limit: Int = Int.MAX_VALUE) {
            val svc = instance ?: return
            runCatching {
                // Over Bluetooth only the newest `limit` are replayed (oldest of those first).
                svc.activeNotifications?.sortedByDescending { it.postTime }?.take(limit)?.asReversed()
                    ?.forEach { svc.forward(it, force = true) }
            }
        }
    }
}

/** Holds live reply actions so a reply typed on the Mac can be injected back into the app. */
object NotifStore {
    private val actions = LinkedHashMap<String, Notification.Action>()
    /** Plain action buttons per notification key, in the order sent to the Mac —
     *  the Mac fires one back by index, so this list and that one must agree. */
    private val buttons = LinkedHashMap<String, List<Notification.Action>>()

    @Synchronized
    fun put(key: String, action: Notification.Action) {
        actions[key] = action
        if (actions.size > 100) actions.remove(actions.keys.first())
    }

    @Synchronized
    fun putButtons(key: String, list: List<Notification.Action>) {
        if (list.isEmpty()) buttons.remove(key) else buttons[key] = list
        if (buttons.size > 100) buttons.remove(buttons.keys.first())
    }

    /** Fire the Nth action button of a notification. Index-based because the
     *  Mac only ever saw the labels, and two buttons can share a label. */
    @Synchronized
    fun fireAction(index: Int, key: String): Boolean {
        val action = buttons[key]?.getOrNull(index) ?: return false
        return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
    }

    /** A notification's tap action plus its package (for the launcher fallback). */
    private class OpenTarget(val intent: android.app.PendingIntent?, val pkg: String)
    private val opens = LinkedHashMap<String, OpenTarget>()

    @Synchronized
    fun putOpen(key: String, intent: android.app.PendingIntent?, pkg: String) {
        opens[key] = OpenTarget(intent, pkg)
        if (opens.size > 100) opens.remove(opens.keys.first())
    }

    /** Result of [open]: null = unknown key (nothing was fired). */
    class OpenResult(val ok: Boolean, val locked: Boolean)

    /** Fire the notification's contentIntent (the tap action); fall back to the app's launcher
     *  activity when there is none or it was cancelled. Never fires for a key we don't hold. If the
     *  phone is locked the intent is still sent; Android shows it after unlock. */
    @Synchronized
    fun open(ctx: Context, key: String, displayId: Int = -1): OpenResult? {
        val t = opens[key] ?: return null
        val locked = runCatching {
            (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        }.getOrDefault(false)
        var ok = false
        t.intent?.let { pi ->
            ok = runCatching {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                if (displayId > 0) {
                    // "notifopendisplay": open on the Mac's virtual display. The Mac verifies where it
                    // landed and moves the task if Android ignored this.
                    opts.setLaunchDisplayId(displayId)
                    pi.send(ctx, 0, null, null, null, null, opts.toBundle())
                } else if (android.os.Build.VERSION.SDK_INT >= 34) pi.send(opts.toBundle())
                else pi.send()
                true
            }.getOrDefault(false)
        }
        if (!ok) ok = AppsRepo.launch(ctx, t.pkg)
        return OpenResult(ok, locked)
    }

    @Synchronized
    fun remove(key: String) {
        actions.remove(key)
        buttons.remove(key)
        opens.remove(key)
    }

    @Synchronized
    fun reply(ctx: Context, key: String, text: String): Boolean {
        val action = actions[key] ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        val ri = remoteInputs.firstOrNull() ?: return false
        return runCatching {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(ri.resultKey, text) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            true
        }.getOrDefault(false)
    }

    fun dismiss(key: String) {
        runCatching { NotifListener.instance?.cancelNotification(key) }
    }
}
