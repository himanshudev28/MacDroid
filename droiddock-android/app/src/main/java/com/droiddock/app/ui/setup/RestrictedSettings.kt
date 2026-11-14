package com.droiddock.app.ui.setup

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Android 13+ "restricted settings": for an app installed from a downloaded APK, the system greys out the switches for
 * notification access and accessibility until the user opens App info › ⋮ › Allow restricted settings. (That menu item
 * only appears after the user has tried the greyed-out switch once.)
 *
 * Detection is best effort and unverified on Android 15+, where this became "enhanced confirmation mode". So the UI shows
 * the guide on its own only when this says [Verdict.RESTRICTED], and always keeps a small "Switch greyed out?" link that
 * opens the same guide.
 */
internal object RestrictedSettings {
    enum class Verdict { NOT_APPLICABLE, ALLOWED, RESTRICTED, UNKNOWN }

    /** Hidden app-op; not a public constant, so an unknown op must not crash. */
    private const val OP = "android:access_restricted_settings"

    fun check(ctx: Context): Verdict {
        if (Build.VERSION.SDK_INT < 33) return Verdict.NOT_APPLICABLE
        val mode: Int? = runCatching {
            ctx.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(OP, Process.myUid(), ctx.packageName)
        }.getOrNull()
        val source: Int? = runCatching {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource
        }.getOrNull()
        val sideloaded = source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        return when {
            // Allowed means "never restricted" or "the user already allowed it": either way, no guide.
            mode == AppOpsManager.MODE_ALLOWED -> Verdict.ALLOWED
            mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED -> Verdict.RESTRICTED
            // MODE_DEFAULT (or no answer): fall back to how the app was installed.
            sideloaded -> Verdict.RESTRICTED
            source != null -> Verdict.ALLOWED
            else -> Verdict.UNKNOWN
        }
    }

    /** App info for DroidDock, where the ⋮ menu holds "Allow restricted settings". */
    fun openAppInfo(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
