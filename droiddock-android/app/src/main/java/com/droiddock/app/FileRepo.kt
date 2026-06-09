package com.droiddock.app

import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FileRepo {
    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun denied() = SecurityException("Grant All-files access in the DroidDock app")

    fun list(path: String): JSONArray {
        if (!hasAllFiles()) throw denied()
        val dir = File(path.ifBlank { "/sdcard" })
        val arr = JSONArray()
        val files = dir.listFiles() ?: throw denied()
        files
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name)
                        .put("dir", it.isDirectory)
                        .put("size", if (it.isDirectory) 0 else it.length())
                        .put("modified", it.lastModified())
                )
            }
        return arr
    }

    fun openForRead(path: String): File {
        if (!hasAllFiles()) throw denied()
        val f = File(path)
        if (!f.exists() || !f.isFile) throw java.io.FileNotFoundException(path)
        return f
    }

    /** Delete a file or directory (recursive). Throws if the path can't be removed. */
    fun delete(path: String) {
        if (!hasAllFiles()) throw denied()
        val f = File(path)
        if (!f.exists()) throw java.io.FileNotFoundException(path)
        val gone = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (!gone) throw java.io.IOException("Could not delete ${f.name}")
    }

    /** Rename a file/folder within its current directory. Returns the new absolute path. */
    fun rename(path: String, newName: String): String {
        if (!hasAllFiles()) throw denied()
        val src = File(path)
        if (!src.exists()) throw java.io.FileNotFoundException(path)
        // Reject path separators so a rename can never move the file elsewhere.
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == "..")
            throw IllegalArgumentException("Invalid name")
        val dest = File(src.parentFile, clean)
        if (dest.exists()) throw java.io.IOException("\"$clean\" already exists")
        if (!src.renameTo(dest)) throw java.io.IOException("Could not rename ${src.name}")
        return dest.absolutePath
    }

    fun uniqueDest(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (candidate.exists()) candidate = File(dir, "$stem ($i)$ext").also { i++ }
        return candidate
    }
}
