package com.droiddock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.droiddock.app.ThemeMode
import com.droiddock.app.TransferProgress
import com.droiddock.app.TransferRecord
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.files.FilesTab
import com.droiddock.app.ui.macfiles.FilesScreen
import com.droiddock.app.ui.macfiles.MacFsEntryRow
import com.droiddock.app.ui.macfiles.groupedRows
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Files tab and Mac file rows across styles, light and dark. */
class SnapshotFILESTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
        maxPercentDifference = 0.1,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    private fun shoot(screen: String, body: @Composable () -> Unit) {
        for (style in listOf(UiStyle.MATERIAL_YOU, UiStyle.LIQUID_GLASS, UiStyle.MINIMAL_MONO)) {
            for (dark in listOf(false, true)) {
                paparazzi.snapshot(name = "files_${screen}_${style.name.lowercase()}_${if (dark) "dark" else "light"}") {
                    DroidDockTheme(
                        appearance = Appearance(style = style, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                        dynamicColor = false,
                    ) { Box(Modifier.fillMaxSize().dockPageBackground()) { body() } }
                }
            }
        }
    }

    private val now = System.currentTimeMillis() - 5_000L

    @Test fun transfers() = shoot("transfers") {
        FilesScreen(macFsAvailable = true, connected = true) {
            FilesTab(
                connected = true, sending = false,
                activeTransfers = listOf(TransferProgress(1, "holiday.mp4", 80_000_000, 32_000_000, 6_000_000)),
                recentTransfers = listOf(
                    TransferRecord(1, "report.pdf", 1_200_000, "toMac", now, true),
                    TransferRecord(2, "photo.jpg", 3_400_000, "fromMac", now - 3_600_000L, true),
                    TransferRecord(3, "notes.txt", 2_000, "toMac", now - 7_200_000L, false),
                    TransferRecord(4, "backup.zip", 90_000_000, "fromMac", now - 9_200_000L, true),
                ),
                onSendFile = {}, onSendClipboard = {},
            )
        }
    }

    @Test fun transfers_empty() = shoot("empty") {
        FilesTab(
            connected = true, sending = false, activeTransfers = emptyList(), recentTransfers = emptyList(),
            onSendFile = {}, onSendClipboard = {},
        )
    }

    @Test fun macRows() = shoot("macrows") {
        val entries = listOf(
            JSONObject("""{"name":"Projects","dir":true}"""),
            JSONObject("""{"name":"Invoices","dir":true}"""),
            JSONObject("""{"name":"deck.pptx","dir":false,"size":8400000}"""),
            JSONObject("""{"name":"song.mp3","dir":false,"size":5100000}"""),
            JSONObject("""{"name":"movie.mov","dir":false,"size":420000000}"""),
        )
        DockScaffold(title = "Mac Files") {
            groupedRows(entries, keyOf = { it.optString("name") }) { e ->
                val pulling = e.optString("name") == "song.mp3"
                MacFsEntryRow(e, pulling = pulling, fraction = { 0.4f }, onOpen = {}, onPull = {})
            }
        }
    }
}
