package com.droiddock.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Brings [BridgeService] back after the system refused its foreground start and it bowed out.
 *
 * A single JobScheduler job (network connectivity constraint, a minimum latency that grows with each
 * failed attempt, never more than [MAX_ATTEMPTS] tries) calls [BridgeService.start] later. No new
 * dependency, nothing that runs hot. The count resets when the service reaches the foreground
 * ([onServiceStarted]), when an hour-scale quiet period has passed, or when the app is opened.
 *
 * Android 12+ may refuse the attempt too (a job is not one of the documented exemptions to the
 * background foreground-service-start rules). That is caught and counted, so the cost of being refused
 * is a few cheap, backed-off wake-ups, after which the existing triggers (app launch, boot, tile,
 * widget) remain the way back, exactly as before.
 */
object BridgeRearm {
    const val JOB_ID = 0xD0C1
    const val MAX_ATTEMPTS = 7
    private const val PREFS = "bridge_rearm"
    private const val QUIET_RESET_MS = 12L * 60 * 60 * 1000

    private val DELAYS_MIN = longArrayOf(2, 5, 15, 30, 60, 120, 240)

    /** Delay before attempt number [attempt] (0-based), or null once the attempts are used up. */
    fun delayMs(attempt: Int): Long? =
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) null else DELAYS_MIN[attempt] * 60_000L

    /** The stored count, forgotten if the last attempt was long ago. */
    fun effectiveAttempts(stored: Int, lastAt: Long, now: Long): Int =
        if (stored <= 0 || now - lastAt >= QUIET_RESET_MS || now < lastAt) 0 else stored

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun attempts(ctx: Context, now: Long): Int {
        val p = prefs(ctx)
        return effectiveAttempts(p.getInt("n", 0), p.getLong("at", 0L), now)
    }

    private fun bump(ctx: Context, now: Long): Int {
        val n = attempts(ctx, now) + 1
        prefs(ctx).edit().putInt("n", n).putLong("at", now).apply()
        return n
    }

    /** The service reached the foreground, or the user opened the app: forget past failures. */
    fun onServiceStarted(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        cancel(ctx)
    }

    fun cancel(ctx: Context) {
        runCatching { (ctx.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }
    }

    /**
     * Arm the retry unless one is already pending, the phone is unpaired or paused, or the attempts are
     * used up. Safe to call from any context (it only schedules; it never starts a service itself).
     */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val now = System.currentTimeMillis()
            if (!wanted(app, now)) return
            val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_ID) != null) return
            val delay = delayMs(attempts(app, now)) ?: return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(app, BridgeRearmJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(delay)
                    // Failed attempts back off further by the system's own exponential policy.
                    .setBackoffCriteria(120_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            )
        }
    }

    internal fun wanted(ctx: Context, now: Long): Boolean =
        BootReceiver.shouldStartOnBoot(Prefs.load(ctx) != null, Prefs.pausedUntil(ctx), now)

    /**
     * Count one retry, whatever its outcome, and say whether more are left. Counting only refused starts
     * would let a service that starts and then fails its own foreground call re-arm at the shortest delay
     * forever; the count is cleared in [onServiceStarted] once a start really reaches the foreground.
     */
    internal fun recordAttempt(ctx: Context): Boolean {
        val n = bump(ctx, System.currentTimeMillis())
        return n < MAX_ATTEMPTS
    }
}

/** Runs the retry. Main thread, short: it only asks the system to start the service. */
class BridgeRearmJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (!BridgeRearm.wanted(app, System.currentTimeMillis()) || BridgeService.running) return false
        val again = BridgeRearm.recordAttempt(app)
        val refused = try {
            BridgeService.start(app)
            false
        } catch (e: Exception) {
            // On 12+ this is ForegroundServiceStartNotAllowedException (an IllegalStateException);
            // any other failure (e.g. OEM policy SecurityException) is treated the same: try later.
            true
        }
        if (!refused) return false // a later failure inside the service re-arms itself
        // Finish asynchronously so the system's exponential backoff reschedules us, unless used up.
        android.os.Handler(android.os.Looper.getMainLooper()).post { jobFinished(params, again) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
