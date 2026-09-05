package com.droiddock.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates for a sideloaded APK.
 *
 * DroidDock is not on Play, so nothing tells a phone that a newer build exists.
 * This asks the GitHub Releases API, downloads the APK the release workflow
 * publishes, and hands it to [PackageInstaller].
 *
 * Two hard requirements live outside this file, both in `app/build.gradle.kts`,
 * and the installer rejects the upgrade without either:
 *
 *  * **A stable signing key.** An APK can only replace one signed by the same
 *    certificate. CI used to `assembleDebug` with the runner's throwaway debug
 *    keystore — a different key every release — so an in-place update could
 *    never have worked.
 *  * **A rising `versionCode`.** Derived from the git tag now; it used to be a
 *    hand-edited literal, which is the kind of thing that gets forgotten
 *    exactly once and then silently breaks updates for everyone.
 *
 * [PackageInstaller]'s session API is used rather than the older
 * `ACTION_INSTALL_PACKAGE` intent: the bytes stream straight into the session,
 * so there is no `FileProvider` and no world-readable APK sitting in shared
 * storage, and failures come back as a real status instead of nothing at all.
 */
object UpdateChecker {

    /** The repo the release workflow publishes to. */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/himanshudev28/MacDroid/releases/latest"

    /** Must match the name the workflow's `cp` gives the asset. */
    private const val APK_ASSET = "DroidDock-Android.apk"

    /** Where to send someone who has to install by hand. See [signedLikeInstalled]. */
    const val RELEASES_PAGE = "https://github.com/himanshudev28/MacDroid/releases/latest"

    /** Don't ask GitHub more than once a day on app open. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Broadcast the installer sends back with the session's status. */
    const val INSTALL_ACTION = "com.droiddock.app.INSTALL_RESULT"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** A published release newer than what's running. */
    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    /**
     * `null` means "nothing newer" — an ordinary, expected answer, not an error.
     * Failures throw, because the button that calls this has somewhere to put
     * the message and the user can act on "no network".
     */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            // Pins the response shape; without it GitHub is free to serve a
            // future default version of the API.
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DroidDock/${BuildConfig.VERSION_NAME}")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 404 is the one worth naming: it's what a draft-only release
                // looks like from here, and "not found" would send someone
                // hunting for a network problem that isn't there.
                if (response.code == 404) error("No published release yet.")
                error("GitHub returned ${response.code}")
            }
            response.body?.string() ?: error("Empty response from GitHub")
        }

        parseRelease(body, BuildConfig.VERSION_NAME)
    }

    /**
     * Split out from [check] so it can be tested without a network: everything
     * interesting here is the JSON shape and the version comparison.
     */
    fun parseRelease(json: String, currentVersion: String): Release? {
        val root = JSONObject(json)
        // Releases are tagged `v1.2.3`; the version is the tag without its `v`.
        val version = root.optString("tag_name").removePrefix("v")
        if (version.isEmpty() || compareVersions(version, currentVersion) <= 0) return null

        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != APK_ASSET) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            return Release(
                version = version,
                notes = root.optString("body"),
                apkUrl = url,
                sizeBytes = asset.optLong("size"),
            )
        }
        // Tagged but the APK job failed or hasn't finished. Offering an update
        // with nothing to download would be worse than staying quiet.
        return null
    }

    /**
     * Numeric dotted-version comparison: negative if [a] is older than [b].
     *
     * Deliberately not a string compare — that makes "1.10.0" older than
     * "1.9.0" and strands everyone on the tenth minor release. Missing
     * components read as 0, so `1.2` == `1.2.0`. Any non-numeric suffix (a
     * `-beta`, say) is ignored rather than rejected: it should not be able to
     * make a comparison throw.
     */
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a)
        val right = versionParts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun versionParts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }

    /**
     * Stream the APK into the app's own cache dir, reporting bytes as they land.
     *
     * `cacheDir` and not external storage: the file is private, the system can
     * reclaim it, and it never needs to be readable by anything but the
     * installer session it's about to be fed into.
     */
    suspend fun download(
        ctx: Context,
        release: Release,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // Previous attempts, and the previous version's APK. Nothing here is
        // worth keeping once we're fetching a new one.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "DroidDock-${release.version}.apk")

        val request = Request.Builder().url(release.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (${response.code})")
            val body = response.body ?: error("Download returned no body")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        }
        target
    }

    /**
     * Whether [apk] is signed by the same certificate as the running app.
     *
     * Android will only let an APK replace one signed by the **same** key. When
     * it doesn't, the session fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
     * which the system dialog renders as "app isn't compatible with your phone"
     * — a message that sends people looking for an Android-version or CPU
     * problem that isn't there, on a phone that is already running the app.
     *
     * The case that hits real users is a build installed over USB. A local
     * `assembleDebug` — or `assembleRelease` with no keystore in the
     * environment, which falls back to the debug key on purpose so
     * contributors can build — carries the machine's `~/.android/debug.keystore`
     * certificate. Releases carry the CI keystore. Neither can ever update the
     * other, however the version numbers compare.
     *
     * Certificates are compared rather than assumed, so this stays correct if
     * the release keystore is ever rotated, and nothing here has to be kept in
     * sync with a hardcoded fingerprint.
     */
    fun signedLikeInstalled(ctx: Context, apk: File): Boolean {
        val ours   = signerDigests(ctx) { flags -> ctx.packageManager.getPackageInfo(ctx.packageName, flags) }
        val theirs = signerDigests(ctx) { flags -> ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) }
        // An unreadable APK or an unsigned one is not evidence of a mismatch —
        // let the installer be the judge rather than blocking a good update on
        // a failed parse.
        if (ours.isEmpty() || theirs.isEmpty()) return true
        return ours.intersect(theirs).isNotEmpty()
    }

    /**
     * SHA-256 of every certificate a package is signed by.
     *
     * Two APIs, because `GET_SIGNING_CERTIFICATES` only exists from API 28 and
     * this app runs from 26. On the modern path `apkContentsSigners` is the set
     * that actually signed these bytes; on a rotated key it is the current
     * certificate, which is the one the installer matches against.
     */
    private inline fun signerDigests(
        ctx: Context,
        get: (Int) -> android.content.pm.PackageInfo?,
    ): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = runCatching { get(PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            info?.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { get(PackageManager.GET_SIGNATURES) }.getOrNull()?.signatures
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().filterNotNull()
            .map { digest.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            .toSet()
    }

    /**
     * Hand [apk] to the system installer.
     *
     * Returns once the session is committed, which is *before* anything is
     * installed — the user still has to confirm in a system dialog that
     * [UpdateInstallReceiver] launches. On success this process is killed and
     * replaced, so there is no "installed" callback worth waiting for here.
     */
    suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("droiddock", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(INSTALL_ACTION).setPackage(ctx.packageName)
            // MUTABLE because the installer fills in the status extras on the
            // way back; an immutable PendingIntent would arrive empty and the
            // confirmation dialog would never be raised. The flag only exists
            // from API 31 — before that mutable was the default anyway.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
