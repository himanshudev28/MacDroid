package com.droiddock.app.ui.macfiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.droiddock.app.ui.components.DockGroup
import com.droiddock.app.ui.theme.DockEntrance
import com.droiddock.app.ui.theme.dockStagger
import com.droiddock.app.ui.theme.dockItemAnimation

/**
 * Long lists as lazily composed groups: rows are chunked so each lazy item is a
 * normal grouped card, and only the visible chunks are ever composed.
 *
 * With an [entrance] the first chunk docks in row by row on first open only (fresh-only, so scrolling back or
 * re-keying the list never replays it); later chunks appear without motion.
 */
internal fun <T> LazyListScope.groupedRows(
    items: List<T>,
    keyOf: (T) -> Any,
    chunk: Int = 12,
    entrance: DockEntrance? = null,
    row: @Composable (T) -> Unit,
) {
    items.chunked(chunk).forEachIndexed { chunkIndex, part ->
        item(key = "g-" + keyOf(part.first()), contentType = "group") {
            DockGroup(dockItemAnimation(), rows = part.mapIndexed { i, e ->
                {
                    Box(if (entrance != null && chunkIndex == 0) Modifier.dockStagger(entrance, i) else Modifier) { row(e) }
                }
            })
        }
    }
}
