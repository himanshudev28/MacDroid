package com.droiddock.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File

/**
 * Pictures on the clipboard, in both directions.
 *
 * # Why an image can't just be `ClipData.newPlainText`
 *
 * A clip crosses a Binder transaction with a budget of roughly a megabyte,
 * shared with everything else in flight — which is why [ConnectionManager]
 * truncates text at 200k characters. Putting image bytes in a clip would blow
 * that on the first screenshot. Android's answer is that image clips carry a
 * **`content://` URI**, not the data: the pasting app opens the URI and the
 * system grants it read access for the length of the paste. So the file is
 * written to this app's cache and served through a `FileProvider`.
 *
 * # Direction is not symmetric, and cannot be
 *
 * **Mac → phone works automatically.** The Mac watches its own pasteboard, so
 * copying a picture there lands it here with nothing to press.
 *
 * **Phone → Mac is explicit only** — the Quick Settings tile, the widget, the
 * "Send to Mac" share item. This is the same platform restriction that already
 * shapes text sync: Android 13+ and One UI refuse background clipboard reads
 * outright, and the accessibility trick that rescues *text* (reading the copied
 * string out of the accessibility event itself) has no equivalent for an image
 * — the event carries text, not pixels. So the phone can send a picture when
 * you ask it to, and cannot notice one on its own.
 *
 * # Cache hygiene
 *
 * Received images accumulate in `cacheDir/clipboard`, and a clip URI has to
 * outlive the paste that reads it — deleting on write would break the paste
 * itself. Instead each write [prunes][prune] everything but the most recent
 * few, which bounds the directory without ever removing the clip currently on
 * the clipboard.
 */
object ClipImage {

    /** Matches the Mac's own ceiling (`MAX_IMAGE_BYTES` in clipboard.rs). Both
     *  ends enforce it independently: whichever side is newer protects the link
     *  regardless of what the other side sends. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** How many received images to keep. Enough that a paste can never race a
     *  prune, small enough that the cache stays trivial. */
    private const val KEEP = 4

    private const val AUTHORITY_SUFFIX = ".clips"

    /**
     * Put a received PNG on the phone's clipboard.
     *
     * Returns the number of bytes written, or null if it could not be done —
     * the caller reports that rather than leaving a copy that silently isn't
     * there.
     */
    fun receive(ctx: Context, base64: String): Int? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null

        val dir = File(ctx.cacheDir, "clipboard").apply { mkdirs() }
        val file = File(dir, "clip-${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        prune(dir, file)

        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, file)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // newUri, not newPlainText: this is what makes the system hand the
        // pasting app a temporary read grant for the URI above.
        cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "DroidDock image", uri))
        bytes.size
    }.getOrNull()

    /**
     * The clipboard's current image as base64 PNG/JPEG, or null when it holds
     * no image.
     *
     * **Only call this from a focused activity.** Android refuses clipboard
     * reads to an app that isn't in the foreground, and returns nothing rather
     * than an error when it does — which is why every caller here is one of the
     * momentary activities that exist to gain focus, read, and vanish.
     */
    fun readFromClipboard(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val uri = clip.getItemAt(0)?.uri ?: return null
        val type = ctx.contentResolver.getType(uri) ?: return null
        if (!type.startsWith("image/")) return null
        encode(ctx.contentResolver, uri)
    }.getOrNull()

    private fun encode(resolver: ContentResolver, uri: Uri): String? {
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            // Read one byte past the ceiling so an oversized image is detected
            // rather than silently truncated to exactly the limit.
            stream.readBytes()
        } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Keep [KEEP] newest, and never touch [keep] itself — it is the one the
     *  clipboard is pointing at right now. */
    private fun prune(dir: File, keep: File) = runCatching {
        dir.listFiles()
            ?.filter { it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
    }
}
