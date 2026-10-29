package com.droiddock.app.ui.macfiles

import androidx.compose.ui.Modifier
import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.droiddock.app.ui.components.DockGroup

/**
 * Long lists as lazily composed groups: rows are chunked so each lazy item is a
 * normal grouped card, and only the visible chunks are ever composed.
 */
internal fun <T> LazyListScope.groupedRows(
    items: List<T>,
    keyOf: (T) -> Any,
    chunk: Int = 12,
    row: @Composable (T) -> Unit,
) {
    items.chunked(chunk).forEach { part ->
        item(key = "g-" + keyOf(part.first()), contentType = "group") {
            DockGroup(dockItemAnimation(), rows = part.map { e -> { row(e) } })
        }
    }
}
