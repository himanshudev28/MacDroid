package com.droiddock.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home-screen wallpaper, sent to the Mac so its phone card can wear the
 * same backdrop as the phone (Tier B).
 *
 * Requested once per connection, not pushed — it's a ~100 KB payload that
 * changes rarely, so the Mac caches it per device and only re-asks on reconnect.
 *
 * Reading it needs storage access: `WallpaperManager.getDrawable()` has required
 * READ_EXTERNAL_STORAGE since Android 7, and from Android 13 it hands back the
 * *default* wallpaper (or throws) unless the caller holds
 * MANAGE_EXTERNAL_STORAGE — which this app already declares and prompts for so
 * the Files browser can reach /sdcard. If that permission was declined the call
 * throws, which `ConnectionManager.respond` turns into a readable error and the
 * Mac's card silently falls back to its generated backdrop.
 */
object WallpaperRepo {
    /** Longest edge. The Mac renders this into a ~256pt-wide card, so anything
     *  larger is bytes on the wire nobody sees. */
    private const val MAX_PX = 720

    fun bytes(ctx: Context): ByteArray {
        val wm = WallpaperManager.getInstance(ctx)

        // Lock screen first, home screen second.
        //
        // `wm.drawable` is the *home* wallpaper, and on a phone with different
        // images set for the two (which is common, and the default on One UI)
        // the Mac's card wore a picture the phone's lock screen never shows —
        // reported as "it shows a different lock screen image". The card is
        // imitating a lock screen, so it should wear the lock screen's own
        // wallpaper when there is one.
        //
        // `getWallpaperFile(FLAG_LOCK)` returns null — not an error — when the
        // user hasn't set a separate lock wallpaper, which is exactly when the
        // home one is the right answer anyway.
        val lock = if (Build.VERSION.SDK_INT >= 24) {
            runCatching {
                wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { pfd ->
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                }
            }.getOrNull()
        } else null

        val drawable = if (lock == null) {
            wm.drawable ?: throw IllegalStateException("No wallpaper available")
        } else null

        val source = lock ?: (drawable as? BitmapDrawable)?.bitmap ?: run {
            val drawable = drawable!!
            val w = max(1, drawable.intrinsicWidth)
            val h = max(1, drawable.intrinsicHeight)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(bmp))
            }
        }

        val longest = max(source.width, source.height)
        val scaled = if (longest <= MAX_PX) {
            source
        } else {
            val ratio = MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).roundToInt().coerceAtLeast(1),
                (source.height * ratio).roundToInt().coerceAtLeast(1),
                true
            )
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== source) scaled.recycle()
        return out.toByteArray()
    }
}
