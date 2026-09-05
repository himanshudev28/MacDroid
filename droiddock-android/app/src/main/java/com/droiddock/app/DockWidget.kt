package com.droiddock.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home-screen widget: link status plus the three things you'd otherwise open
 * the app to do.
 *
 * The Quick Settings tiles ([ConnectionTileService], [ClipTileService]) already
 * cover "drop the link" and "send my clipboard" from the shade. This is the
 * home-screen half of the same idea, and it earns its place by showing state
 * the tiles can't: *which* Mac you're linked to, and whether the phone is
 * merely searching rather than deliberately disconnected — the distinction that
 * decides whether anything is wrong.
 *
 * Every button drives an existing entry point rather than a private copy of it
 * ([ConnectionManager.disconnect] / [ConnectionManager.quickConnect],
 * [SendClipboardActivity], [MirrorPermissionActivity]), so the widget, the
 * tiles and the app can't disagree about what "connected" means.
 */
class DockWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        // First instance placed. Bring the link machinery up so the widget has
        // something true to show before it is ever tapped.
        ConnectionManager.ensureLoop(context.applicationContext)
        watch(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        watch(context)
        ids.forEach { render(context, mgr, it) }
    }

    /** Resized. Re-render so the action row appears/disappears at the new height. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        render(context, mgr, id)
    }

    override fun onDisabled(context: Context) {
        watcher?.cancel()
        watcher = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only the toggles come back here as broadcasts; the clipboard and
        // mirror buttons are activity PendingIntents, which the launcher's
        // click grants the foreground privileges they need to show a dialog.
        when (intent.action) {
            ACTION_TOGGLE_LINK -> {
                val ctx = context.applicationContext
                if (ConnectionManager.connected.value || !ConnectionManager.manuallyDisconnected.value) {
                    ConnectionManager.disconnect(ctx)
                } else {
                    BridgeService.start(ctx)
                    ConnectionManager.quickConnect(ctx)
                }
                refresh(ctx)
            }
            ACTION_STOP_MIRROR -> {
                MirrorService.stop(context.applicationContext)
                refresh(context.applicationContext)
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE_LINK = "com.droiddock.app.widget.TOGGLE_LINK"
        private const val ACTION_STOP_MIRROR = "com.droiddock.app.widget.STOP_MIRROR"

        // Distinct request codes: PendingIntents that differ only in their extras
        // are "the same" to the system, and a shared code would have the
        // launcher reuse one button's intent for another.
        private const val RC_OPEN = 10
        private const val RC_TOGGLE = 11
        private const val RC_CLIP = 12
        private const val RC_MIRROR = 13

        /** Below this the widget is a status strip and the buttons don't fit. */
        private const val ACTIONS_MIN_HEIGHT_DP = 92

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var watcher: Job? = null

        /**
         * Repaint every placed widget from the live [ConnectionManager] state.
         *
         * Safe to call from anywhere, including when no widget is placed —
         * `ids` is simply empty then.
         */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching {
                mgr.getAppWidgetIds(ComponentName(app, DockWidget::class.java))
            }.getOrNull() ?: return
            ids.forEach { render(app, mgr, it) }
        }

        /**
         * Mirror the connection flows onto the widget for as long as this
         * process lives.
         *
         * A widget has no lifecycle of its own to hang a collector off — the
         * provider is a broadcast receiver that exists only for the duration of
         * one `onReceive` — so the collector belongs to the process instead,
         * started idempotently from every entry point that proves a widget is
         * placed. If the process is killed the widget freezes on its last
         * frame; the next `onUpdate` (boot, re-add, any tap) revives both.
         */
        private fun watch(ctx: Context) {
            if (watcher?.isActive == true) return
            val app = ctx.applicationContext
            watcher = scope.launch {
                combine(
                    ConnectionManager.connected,
                    ConnectionManager.macName,
                    ConnectionManager.manuallyDisconnected,
                    ConnectionManager.pausedUntil,
                ) { _, _, _, _ -> Unit }.collect { refresh(app) }
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val linked = ConnectionManager.connected.value
            val off = ConnectionManager.manuallyDisconnected.value
            val paused = ConnectionManager.pausedUntil.value != 0L && !off
            val mirroring = MirrorService.instance != null

            val v = RemoteViews(ctx.packageName, R.layout.widget_dock)

            v.setTextViewText(
                R.id.widget_status,
                when {
                    linked -> ctx.getString(
                        R.string.widget_status_linked,
                        ConnectionManager.macName.value ?: ctx.getString(R.string.widget_mac_fallback)
                    )
                    paused -> ctx.getString(R.string.widget_status_paused)
                    off -> ctx.getString(R.string.widget_status_off)
                    else -> ctx.getString(R.string.widget_status_searching)
                }
            )
            v.setInt(
                R.id.widget_dot, "setColorFilter",
                ctx.getColor(
                    when {
                        linked -> R.color.widget_dot_on
                        off || paused -> R.color.widget_dot_off
                        else -> R.color.widget_dot_wait
                    }
                )
            )

            // Link button. "Connected" and "still looking" both offer Disconnect,
            // matching the Quick Settings tile — the alternative is a button that
            // says Connect while the phone is already dialling.
            val down = !linked && off
            v.setImageViewResource(
                R.id.widget_btn_link_icon,
                if (down) R.drawable.ic_widget_link_off else R.drawable.ic_widget_link
            )
            v.setInt(
                R.id.widget_btn_link_icon, "setColorFilter",
                ctx.getColor(if (linked) R.color.widget_accent else R.color.widget_icon)
            )
            v.setTextViewText(
                R.id.widget_btn_link_label,
                ctx.getString(if (down) R.string.widget_action_connect else R.string.widget_action_disconnect)
            )

            // Mirror button doubles as Stop while a stream is up, so the widget
            // can end a share it started.
            v.setImageViewResource(
                R.id.widget_btn_mirror_icon,
                if (mirroring) R.drawable.ic_widget_mirror_stop else R.drawable.ic_widget_mirror
            )
            v.setInt(
                R.id.widget_btn_mirror_icon, "setColorFilter",
                ctx.getColor(if (mirroring) R.color.widget_accent else R.color.widget_icon)
            )
            v.setTextViewText(
                R.id.widget_btn_mirror_label,
                ctx.getString(if (mirroring) R.string.widget_action_stop else R.string.widget_action_mirror)
            )

            v.setOnClickPendingIntent(R.id.widget_header, openApp(ctx))
            v.setOnClickPendingIntent(R.id.widget_btn_link, broadcast(ctx, RC_TOGGLE, ACTION_TOGGLE_LINK))
            v.setOnClickPendingIntent(R.id.widget_btn_clip, sendClipboard(ctx))
            v.setOnClickPendingIntent(
                R.id.widget_btn_mirror,
                if (mirroring) broadcast(ctx, RC_MIRROR, ACTION_STOP_MIRROR) else startMirror(ctx)
            )

            // A widget squeezed to one row keeps the status line and drops the
            // buttons, rather than clipping them to slivers nobody can hit.
            val heightDp = runCatching {
                mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            }.getOrDefault(0)
            v.setViewVisibility(
                R.id.widget_actions,
                if (heightDp in 1 until ACTIONS_MIN_HEIGHT_DP) android.view.View.GONE
                else android.view.View.VISIBLE
            )

            runCatching { mgr.updateAppWidget(id, v) }
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_OPEN,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Same transparent helper the clipboard tile uses — Android 10+ only
         *  lets the focused app read the clipboard, so this can't be done from
         *  the receiver. */
        private fun sendClipboard(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_CLIP,
            Intent(ctx, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun startMirror(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_MIRROR,
            Intent(ctx, MirrorPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MirrorService.EXTRA_SOURCE, "screen"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun broadcast(ctx: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, requestCode,
                Intent(ctx, DockWidget::class.java).setAction(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
