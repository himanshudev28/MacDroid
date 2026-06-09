package com.droiddock.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
        if (!shouldMirror(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // many apps re-post the same notification; only forward real changes
        val hash = "$title|$text"
        if (!force && lastSent[sbn.key] == hash) return
        lastSent[sbn.key] = hash
        if (lastSent.size > 300) lastSent.remove(lastSent.keys.first())

        val replyAction = findReplyAction(sbn.notification)
        if (replyAction != null) NotifStore.put(sbn.key, replyAction) else NotifStore.remove(sbn.key)

        ConnectionManager.send(
            JSONObject()
                .put("type", "notification")
                .put("key", sbn.key)
                .put("app", appLabel(sbn.packageName))
                .put("title", title.take(200))
                .put("text", text.take(1000))
                .put("replyable", replyAction != null)
                .put("when", sbn.postTime)
                .put("backfill", force)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
        NotifStore.remove(sbn.key)
        ConnectionManager.send(
            JSONObject().put("type", "notification-removed").put("key", sbn.key)
        )
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // music players spam progress updates — skip them
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return false
        return true
    }

    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        @Volatile
        var instance: NotifListener? = null
        private val lastSent = LinkedHashMap<String, String>()

        /** Replay every currently-active notification — called when the Mac links up. */
        fun pushActive() {
            val svc = instance ?: return
            runCatching {
                svc.activeNotifications?.sortedBy { it.postTime }?.forEach { svc.forward(it, force = true) }
            }
        }
    }
}

/** Holds live reply actions so a reply typed on the Mac can be injected back into the app. */
object NotifStore {
    private val actions = LinkedHashMap<String, Notification.Action>()

    @Synchronized
    fun put(key: String, action: Notification.Action) {
        actions[key] = action
        if (actions.size > 100) actions.remove(actions.keys.first())
    }

    @Synchronized
    fun remove(key: String) {
        actions.remove(key)
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
