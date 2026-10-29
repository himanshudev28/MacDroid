package com.droiddock.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Phase 6a A4 — photos & videos over the app link. MediaStore listing + thumbnails
 *  so the Mac's Photos tab works with no ADB. Images and videos are merged
 *  newest-first; each item carries a `kind` ("image" | "video") so the Mac can
 *  badge videos and request the right thumbnail. */
object PhotoRepo {
    /** Newest-first images + videos: {id, name, date, size, path, kind, duration}.
     *
     *  Both MediaStore tables are already `date_modified DESC`, so the page is a
     *  two-cursor merge: skip [offset] rows, build JSON for only [limit] rows.
     *  Previously every row up to offset+limit of BOTH tables became a
     *  JSONObject and was then sorted, so page N cost O(N) allocations.
     *  Ties go to the image row, matching the old stable sort (images first). */
    fun list(ctx: Context, offset: Int, limit: Int): JSONArray {
        val arr = JSONArray()
        val img = openCursor(ctx, false)
        val vid = openCursor(ctx, true)
        try {
            var imgOk = img?.moveToNext() == true
            var vidOk = vid?.moveToNext() == true
            var i = 0
            while ((imgOk || vidOk) && arr.length() < limit) {
                val takeImage = when {
                    !vidOk -> true
                    !imgOk -> false
                    else -> img!!.getLong(2) >= vid!!.getLong(2)
                }
                val c = if (takeImage) img!! else vid!!
                if (i >= offset) arr.put(row(c, !takeImage))
                i++
                if (takeImage) imgOk = c.moveToNext() else vidOk = c.moveToNext()
            }
        } finally {
            img?.close()
            vid?.close()
        }
        return arr
    }

    private fun row(c: android.database.Cursor, isVideo: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", c.getLong(0))
            .put("name", c.getString(1) ?: if (isVideo) "video" else "image")
            .put("date", c.getLong(2) * 1000)
            .put("size", c.getLong(3))
            .put("path", c.getString(4) ?: "")
            .put("kind", if (isVideo) "video" else "image")
        if (isVideo) o.put("duration", c.getLong(5))
        return o
    }

    private fun openCursor(ctx: Context, isVideo: Boolean): android.database.Cursor? {
        val uri = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // DURATION exists on the Video table only; request it just for videos.
        val proj = if (isVideo) arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION
        ) else arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        return ctx.contentResolver.query(uri, proj, null, null, "date_modified DESC")
    }

    /** A small JPEG thumbnail for one image/video id. [kind] selects the table
     *  ("image" by default for back-compat with older Mac builds). */
    fun thumbBytes(ctx: Context, id: Long, kind: String = "image"): ByteArray {
        val base = if (kind == "video")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(base, id)
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else if (kind == "video") {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
            ) ?: throw IllegalStateException("no thumbnail")
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
            ) ?: throw IllegalStateException("no thumbnail")
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
