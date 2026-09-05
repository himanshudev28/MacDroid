package com.droiddock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Where a [PackageInstaller] session reports back.
 *
 * The interesting status is [PackageInstaller.STATUS_PENDING_USER_ACTION],
 * which is not a failure: it means the system has prepared the install and
 * wants the user to confirm it. The confirmation dialog is an Intent the
 * installer hands us, and **nothing happens until we launch it** — a session
 * committed without this receiver just sits there looking like a silent
 * failure.
 *
 * Everything else is terminal. Success needs no message: the app is about to be
 * replaced and restarted, which is its own confirmation. Failure does, because
 * otherwise "Install" appears to do nothing at all.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != UpdateChecker.INSTALL_ACTION) return

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // A receiver has no task of its own to launch into. Without this
                // flag the confirm dialog throws instead of appearing.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            // A signature mismatch, almost always. The raw message for it is
            // `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, which reads as an
            // Android-version or CPU problem on a phone that is plainly running
            // the app already. UpdateChecker.signedLikeInstalled catches this
            // before the download is ever offered for install; this is the
            // backstop for a copy replaced underneath us in between.
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Toast.makeText(ctx, R.string.update_install_conflict, Toast.LENGTH_LONG).show()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.update_install_failed, message ?: "unknown error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
