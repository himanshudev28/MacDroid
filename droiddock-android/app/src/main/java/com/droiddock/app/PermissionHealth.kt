package com.droiddock.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every grant this app needs, what breaks without it, and a way to go fix it —
 * as one list the Mac can render.
 *
 * # Why this exists
 *
 * DroidDock's failure modes are overwhelmingly *silent*. With the accessibility
 * service off, the mirror still streams video and every tap from the Mac is
 * discarded. Without notification access, the Mac's Notifications tab is simply
 * empty, which looks exactly like a quiet phone. Without all-files access the
 * Files tab throws on first use. Each of those grew its own ad-hoc toast over
 * time, each phrased differently, each appearing only once you had already
 * tried the broken thing and drawn the wrong conclusion.
 *
 * This is the inversion: ask the phone what is wrong *before* something looks
 * broken, in one place, in one voice.
 *
 * # Background activity starts are the interesting part
 *
 * A "Fix" button on the Mac wants to raise a Settings screen on a phone that
 * may be in a pocket, and Android has restricted background activity starts
 * since 10. Holding `SYSTEM_ALERT_WINDOW` ("Display over other apps") is one of
 * the documented exemptions, so that grant decides the route:
 *
 * * **With it**, [openFix] starts the Settings activity directly and reports
 *   `"opened"`.
 * * **Without it**, starting the activity would very likely be swallowed with
 *   no error — the exact silent failure this file exists to abolish — so it
 *   posts a tappable notification instead and reports `"notified"`, and the
 *   Mac's toast tells you to look at your phone rather than claiming success.
 *
 * That is also why `overlay` is itself one of the checks: granting it makes
 * every other fix one click instead of two.
 */
object PermissionHealth {

    private const val CHANNEL = "health"
    private const val NOTIF_ID = 43

    /** Severity, in the order the Mac sorts them. */
    private const val ERROR = "error"   // a headline feature is dead
    private const val WARN = "warn"     // an optional feature is dead, or the link is fragile
    private const val INFO = "info"     // a second route to something that already works

    /**
     * One check. `fix` is the id [openFix] understands, or null when there is
     * nothing to launch — a check with no fix is a statement of fact, not a
     * button.
     */
    private data class Check(
        val id: String,
        val ok: Boolean,
        val severity: String,
        val title: String,
        val detail: String,
        val fix: String? = null,
    )

    /** The full list, freshly probed. Cheap enough to call on a timer, but every
     *  entry is a binder round trip or a `Settings.Secure` read — so it is
     *  deliberately only called when a Mac has the panel open. */
    fun snapshot(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (c in checks(ctx)) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("ok", c.ok)
                    .put("severity", c.severity)
                    .put("title", c.title)
                    .put("detail", c.detail)
                    .apply { c.fix?.let { put("fix", it) } }
            )
        }
        return arr
    }

    private fun checks(ctx: Context): List<Check> = listOf(
        Check(
            id = "a11y",
            ok = accessibilityOn(ctx),
            severity = ERROR,
            title = "Clipboard & Screen Control",
            detail = "Off. The mirror still shows the screen, but every tap, swipe, " +
                "keystroke and nav press from the Mac is silently discarded, and " +
                "copying on the phone no longer reaches the Mac. Android turns this " +
                "off on every app update.",
            fix = "a11y",
        ),
        Check(
            id = "notif-access",
            ok = notifAccess(ctx),
            severity = ERROR,
            title = "Notification access",
            detail = "Off. The Mac's Notifications tab stays empty, which looks the " +
                "same as a quiet phone.",
            fix = "notif-access",
        ),
        Check(
            id = "phone-perms",
            ok = phonePerms(ctx),
            severity = ERROR,
            title = "Phone permissions (SMS · Contacts · Calls)",
            detail = "Incomplete. Messages, Contacts and Calls on the Mac need these, " +
                "and answering or hanging up from the Mac needs the Calls one in " +
                "particular.",
            fix = "app-details",
        ),
        Check(
            id = "all-files",
            ok = FileRepo.hasAllFiles(),
            severity = ERROR,
            title = "All-files access",
            detail = "Off. Browsing phone storage and every file transfer fail on first use.",
            fix = "all-files",
        ),
        Check(
            id = "battery",
            ok = batteryUnrestricted(ctx),
            severity = WARN,
            title = "Unrestricted battery use",
            detail = "Restricted. Android will put the link to sleep once the screen has " +
                "been off for a while, so the Mac loses the phone and reconnects on wake.",
            fix = "battery",
        ),
        Check(
            id = "overlay",
            ok = Settings.canDrawOverlays(ctx),
            severity = WARN,
            title = "Display over other apps",
            detail = "Off. Screen and camera need a tap on the phone to start each " +
                "session, and the Fix buttons here can only leave you a notification " +
                "rather than opening the settings screen directly.",
            fix = "overlay",
        ),
        Check(
            id = "mic",
            ok = granted(ctx, Manifest.permission.RECORD_AUDIO),
            severity = WARN,
            title = "Microphone",
            detail = "Off. Phone audio over the Wi-Fi mirror needs it — Android routes " +
                "captured playback through the recording API even though no microphone " +
                "is opened. Without it the mirror is silent.",
            fix = "app-details",
        ),
        Check(
            id = "post-notifs",
            ok = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            severity = WARN,
            title = "Show notifications",
            detail = "Off. DroidDock cannot show its own status, and the fallback " +
                "notification these Fix buttons rely on would never appear.",
            fix = "app-details",
        ),
        Check(
            id = "lock",
            // Two independent routes, mirroring ConnectionManager's `lock`
            // capability: device admin, or GLOBAL_ACTION_LOCK_SCREEN through the
            // accessibility service on Android 9+. The accessibility half is
            // only real while that service is actually running, which is why
            // this reads it rather than just the API level.
            ok = LockAdmin.isActive(ctx) || (Build.VERSION.SDK_INT >= 28 && accessibilityOn(ctx)),
            severity = INFO,
            title = "Lock the phone from the Mac",
            detail = "No route available. This needs either the accessibility service " +
                "above (Android 9+) or device admin, and neither is on.",
            fix = "device-admin",
        ),
    )

    // ── Probes ──────────────────────────────────────────────────────────────
    // Kept here rather than in MainActivity so the phone's own Settings screen
    // and the Mac's panel can never drift into disagreeing about what "granted"
    // means for the same row.

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(ClipAccessibilityService::class.java.name) == true

    fun notifAccess(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    fun phonePerms(ctx: Context): Boolean = PHONE_HEALTH_PERMS.all { granted(ctx, it) }

    private fun batteryUnrestricted(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(true)

    /** The one list of runtime permissions in the Phone group this app needs.
     *
     *  `MainActivity` passes exactly this array to its permission launcher, so
     *  the set that gets *requested* and the set this panel calls incomplete
     *  cannot drift apart into telling the user to grant something nothing ever
     *  asks for. */
    val PHONE_HEALTH_PERMS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    // ── Fixes ───────────────────────────────────────────────────────────────

    /**
     * Take the user to the screen that fixes [id].
     *
     * Returns `"opened"`, `"notified"` or `"unavailable"` — see the class doc
     * for why those are three different answers and not a boolean.
     */
    fun openFix(ctx: Context, id: String): String {
        val intent = intentFor(ctx, id) ?: return "unavailable"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Settings.canDrawOverlays(ctx)) {
            val started = runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
            if (started) return "opened"
        }
        return if (notifyFor(ctx, id, intent)) "notified" else "unavailable"
    }

    private fun intentFor(ctx: Context, id: String): Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (id) {
            "a11y" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notif-access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "all-files" ->
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
                } else {
                    // Below 30 the grant is an ordinary runtime permission, so the
                    // app's own details page is where it lives.
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                }
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "device-admin" -> LockAdmin.enableIntent(ctx)
            "app-details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> null
        }
    }

    /**
     * The fallback route: a notification whose tap opens the same screen.
     *
     * Tapping a notification is a user gesture, so the activity start that
     * follows is not a background start and is not subject to the restriction
     * that made the direct route unreliable.
     */
    private fun notifyFor(ctx: Context, id: String, intent: Intent): Boolean = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH)
        )
        val title = checks(ctx).firstOrNull { it.fix == id || it.id == id }?.title ?: "DroidDock setup"
        // Distinct request codes: two fixes queued at once must not have the
        // second silently reuse the first one's intent.
        val pi = PendingIntent.getActivity(
            ctx, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Finish setup: $title")
                .setContentText("Tap to open the settings screen your Mac asked for.")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
        true
    }.getOrDefault(false)
}
