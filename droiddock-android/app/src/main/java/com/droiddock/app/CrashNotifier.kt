package com.droiddock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Tells you *what* crashed, instead of leaving you with "it closed again".
 *
 * This app spends its life in the background across several services, so an
 * uncaught exception on a service or socket thread kills the process with no UI
 * anywhere to report it — which is exactly the situation that makes a crash
 * hard to chase: the symptom is "DroidDock keeps stopping" and the stack trace
 * only exists in a logcat buffer nobody was watching.
 *
 * # This does not swallow the crash
 *
 * The handler posts a notification and then **delegates to the handler it
 * replaced**, so the process still dies exactly as it would have and the system
 * still records it. Catching-and-continuing would be far worse than the crash:
 * it would leave the app running on top of whatever invariant just broke.
 * The only thing added here is a record you can actually see.
 */
object CrashNotifier {

    private const val CHANNEL = "crash"
    private const val NOTIF_ID = 42

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Everything here is best-effort: the process is already going down,
            // and a failure while reporting a crash must not replace the
            // original error with a less useful one.
            runCatching { if (Prefs.notifyOnCrash(app)) notify(app, thread, error) }
            previous?.uncaughtException(thread, error)
                ?: run {
                    // No prior handler is unusual but not impossible; without
                    // this the thread would simply end and a crashed process
                    // could linger in a half-dead state.
                    Runtime.getRuntime().halt(2)
                }
        }
    }

    private fun notify(ctx: Context, thread: Thread, error: Throwable) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_HIGH)
        )
        val cause = generateSequence(error) { it.cause }.last()
        // The top frame inside our own package is the line worth reading; the
        // frames above it are usually framework plumbing.
        val where = cause.stackTrace
            .firstOrNull { it.className.startsWith("com.droiddock.app") }
            ?.let { "${it.fileName}:${it.lineNumber}" }
            ?: thread.name

        val text = "${cause.javaClass.simpleName} at $where"
        val full = buildString {
            append(cause.javaClass.name)
            cause.message?.let { append(": ").append(it) }
            append("\nthread: ").append(thread.name)
            append('\n')
            cause.stackTrace.take(6).forEach { append("  at ").append(it).append('\n') }
        }

        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle("DroidDock stopped")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(full))
                .setSmallIcon(R.drawable.ic_stat)
                .setAutoCancel(true)
                .build()
        )
    }
}
