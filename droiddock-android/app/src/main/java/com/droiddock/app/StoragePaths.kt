package com.droiddock.app

import android.os.Environment
import java.io.File

/**
 * Confines the file paths the Mac sends to shared storage. Pure logic ([resolve]) so it runs on the JVM.
 *
 * A path is accepted only if its canonical form (`..` and symlinks resolved, so `/sdcard` still works)
 * is a root or below one. The prefix test appends a separator, so `/storage/emulated/0evil` is not
 * "inside" `/storage/emulated/0`. Same approach as [SafeNames.dirUnder], which is reused for the check.
 */
object StoragePaths {
    /** Unit tests only: stands in for the device's storage roots (there are none on the JVM). */
    @Volatile internal var rootsForTest: List<File>? = null

    /** Shared-storage roots: the primary volume plus removable volumes under /storage (shared with transfers). */
    fun sharedRoots(): List<File> {
        rootsForTest?.let { return it }
        val primary = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?: File("/storage/emulated/0")
        return listOf(primary) + SafeNames.volumeRoots()
    }

    /** Directories that must never be reachable even if a root were ever to contain them. */
    fun privateDirs(): List<File> = runCatching {
        val c = ConnectionManager.appCtx
        listOfNotNull(c.filesDir, c.cacheDir, c.codeCacheDir, c.noBackupFilesDir, c.dataDir)
    }.getOrDefault(emptyList())

    /** [raw] resolved to a canonical path inside shared storage, or null if it escapes or is private. */
    fun resolve(
        raw: String,
        roots: List<File> = sharedRoots(),
        denied: List<File> = privateDirs(),
    ): File? {
        if (raw.indexOf('\u0000') >= 0) return null
        val f = SafeNames.dirUnder(raw, roots) ?: return null
        if (denied.isNotEmpty() && SafeNames.dirUnder(f.path, denied) != null) return null
        return f
    }

    /** True when [f] (canonical) is itself a storage root. */
    fun isRoot(f: File, roots: List<File> = sharedRoots()): Boolean =
        roots.any { runCatching { it.canonicalPath }.getOrNull() == f.path }
}
