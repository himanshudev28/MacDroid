package com.droiddock.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that connects to / disconnects from the Mac.
 *
 * The reference offers exactly two tiles — Connection and Clipboard — and
 * [ClipTileService] already covered the second. This one is the more useful of
 * the pair on Android, where the link is the thing you actually want to drop
 * without hunting for the app (leaving the house, joining a work network).
 *
 * It drives [ConnectionManager.disconnect] / [ConnectionManager.quickConnect]
 * rather than any new teardown path, so the tile, the Home button and the Pause
 * dialog all end up in the same state machine — a tile that had its own notion
 * of "off" would be a second source of truth for something the app already
 * tracks.
 */
class ConnectionTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        ConnectionManager.ensureLoop(applicationContext)
        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        // The tile is only visible while listening, so the collector lives
        // exactly that long — a link that drops while the shade is open still
        // repaints, and nothing is left running once it closes.
        watcher = s.launch {
            combine(
                ConnectionManager.connected,
                ConnectionManager.manuallyDisconnected,
            ) { linked, off -> linked to off }.collect { (linked, off) -> render(linked, off) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val ctx = applicationContext
        if (ConnectionManager.connected.value || !ConnectionManager.manuallyDisconnected.value) {
            ConnectionManager.disconnect(ctx)
        } else {
            // Connecting takes a moment; showing the service as already started
            // keeps the notification honest while the socket comes up.
            BridgeService.start(ctx)
            ConnectionManager.quickConnect(ctx)
        }
    }

    private fun render(linked: Boolean, off: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (linked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val status = when {
            linked -> ConnectionManager.macName.value ?: getString(R.string.conn_tile_linked)
            off    -> getString(R.string.conn_tile_off)
            else   -> getString(R.string.conn_tile_searching)
        }
        // Tile.setSubtitle is API 29 and this app ships to 26 — calling it
        // unguarded is a NoSuchMethodError on Oreo/Pie, in a system process
        // rendering the shade. Older devices fold the status into the label.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.label = getString(R.string.conn_tile_label)
            tile.subtitle = status
        } else {
            tile.label = status
        }
        runCatching { tile.updateTile() }
    }
}
