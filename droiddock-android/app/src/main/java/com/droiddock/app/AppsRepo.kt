package com.droiddock.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The phone's launchable apps, for the Mac's Apps grid (Tier B).
 *
 * Deliberately enumerated via a LAUNCHER-intent query rather than
 * `getInstalledApplications` + `QUERY_ALL_PACKAGES`: the manifest's `<queries>`
 * element covers this without the broad "see every package on the device"
 * permission, and launchable apps are exactly the set the grid wants anyway.
 */
object AppsRepo {
    /** Icons are square by construction on Android; 128px covers a 2× 64pt cell. */
    private const val ICON_PX = 128

    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            // A package can expose several launcher activities; the grid wants
            // one entry per app.
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label) ->
                out.put(JSONObject().put("pkg", pkg).put("label", label))
            }
        return out
    }

    /** PNG, not JPEG — app icons are frequently non-square/transparent once the
     *  adaptive-icon mask is applied, and a JPEG matte would show as a box. */
    fun iconBytes(ctx: Context, pkg: String): ByteArray {
        val drawable = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = toBitmap(drawable)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Open the app on the phone. Returns false if it has no launcher entry. */
    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun toBitmap(d: Drawable): Bitmap {
        (d as? BitmapDrawable)?.bitmap?.let {
            return Bitmap.createScaledBitmap(it, ICON_PX, ICON_PX, true)
        }
        // Adaptive/vector icons have no backing bitmap — rasterise them.
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(bmp))
        return bmp
    }
}
