package com.droiddock.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Android 10+ only lets the focused app read the clipboard.
 * This transparent activity grabs focus for a heartbeat, reads, sends, and vanishes.
 * Launched from the Quick Settings tile.
 */
class SendClipboardActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.ensureLoop(applicationContext)
        // safety net: never linger
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        // An emptied clip still yields a non-null primaryClip with zero items, and
        // getItemAt(0) on that throws — this activity is launched straight from the
        // Quick Settings tile, so that throw is a visible crash.
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
        val sent = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
        Toast.makeText(
            this,
            when {
                text.isEmpty() -> "Clipboard is empty"
                sent -> "Sent to Mac"
                else -> "Not connected to Mac"
            },
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}

/** "Send to Mac" in the text-selection toolbar. Gets the text directly — no clipboard read needed. */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.ensureLoop(applicationContext)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val sent = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
        Toast.makeText(this, if (sent) "Sent to Mac" else "Not connected to Mac", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** "Send to Mac" as a share-sheet target for plain text. */
class ShareTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.ensureLoop(applicationContext)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val sent = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
        Toast.makeText(this, if (sent) "Sent to Mac" else "Not connected to Mac", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** "Send to Mac" as a share-sheet target for any file (image, video, doc, etc.). */
class ShareFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.ensureLoop(applicationContext)
        val uri: android.net.Uri? = when {
            intent.action == Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, "No file to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Toast.makeText(this, "Sending to Mac…", Toast.LENGTH_SHORT).show()
        ConnectionManager.sendFileToMac(uri, applicationContext) { ok, err ->
            Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    if (ok) "File sent to Mac" else (err ?: "Send failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        finish()
    }
}
