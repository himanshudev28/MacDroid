package com.droiddock.app

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile for DroidDock's accessibility service.
 *
 * Drives [AccessibilityControl.disableSelf], the same call the Settings row
 * uses, rather than a second copy of the teardown.
 *
 * The asymmetry is Android's, not ours, and the tile is shaped around it: a
 * service may switch *itself* off, but nothing may switch one on — that would
 * let any app grant itself the ability to read the screen and synthesise
 * input. So:
 *
 * - **on**  → tapping turns it off immediately, right here.
 * - **off** → tapping can only open the Accessibility settings screen.
 *
 * Worth a tile because this is the one permission you might reasonably want to
 * revoke in a hurry — handing the phone to someone, joining a call, sharing a
 * screen — and digging four levels into Settings to do it is exactly the
 * friction that stops people bothering.
 */
class A11yTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (AccessibilityControl.service != null) {
            AccessibilityControl.disableSelf()
            // disableSelf tears down asynchronously; repaint once it has.
            qsTile?.let { tile ->
                tile.state = Tile.STATE_INACTIVE
                runCatching { tile.updateTile() }
            }
        } else {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = AccessibilityControl.service != null
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val status = if (on) getString(R.string.a11y_tile_on) else getString(R.string.a11y_tile_off)
        // setSubtitle is API 29; this app ships to 26. Older devices carry the
        // status in the label instead.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.label = getString(R.string.a11y_tile_label)
            tile.subtitle = status
        } else {
            tile.label = status
        }
        runCatching { tile.updateTile() }
    }
}
