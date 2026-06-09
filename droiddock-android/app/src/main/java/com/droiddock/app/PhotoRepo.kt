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
    /** Newest-first images + videos: {id, name, date, size, path, kind, duration}. */
    fun list(ctx: Context, offset: Int, limit: Int): JSONArray {
        // Pull enough of each table to satisfy this page after the merge.
        val cap = offset + limit
        val all = ArrayList<JSONObject>(cap.coerceAtMost(4000))
        all += queryMedia(ctx, "image", cap)
        all += queryMedia(ctx, "video", cap)
        // Stable newest-first ordering across both media types.
        all.sortByDescending { it.optLong("date") }

        val arr = JSONArray()
        var i = offset
        while (i < all.size && arr.length() < limit) {
            arr.put(all[i])
            i++
        }
        return arr
    }

    /** One MediaStore table → list of item objects, newest-first, up to [cap]. */
    private fun queryMedia(ctx: Context, kind: String, cap: Int): List<JSONObject> {
        val isVideo = kind == "video"
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
        val out = ArrayList<JSONObject>()
        ctx.contentResolver.query(uri, proj, null, null, "date_modified DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n < cap) {
                n++
                val o = JSONObject()
                    .put("id", c.getLong(0))
                    .put("name", c.getString(1) ?: if (isVideo) "video" else "image")
                    .put("date", c.getLong(2) * 1000)
                    .put("size", c.getLong(3))
                    .put("path", c.getString(4) ?: "")
                    .put("kind", kind)
                if (isVideo) o.put("duration", c.getLong(5))
                out.add(o)
            }
        }
        return out
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
