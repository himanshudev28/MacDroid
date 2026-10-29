package com.droiddock.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockProgress
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme
import androidx.compose.ui.unit.dp

/** One line of the setup checklist: what it unlocks, and whether it is on. */
internal data class PermissionItem(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val why: String,
    /** null = the state cannot be read cheaply (shown as an Allow row, not counted). */
    val granted: Boolean?,
    val action: String = "Allow",
    val onAllow: () -> Unit,
)

/**
 * "N of M allowed" checklist. Each row says why it is needed and carries a
 * single Allow button; a granted row swaps the button for an "On" pill.
 * Only rows with a readable state count toward N of M.
 */
@Composable
internal fun PermissionChecklist(items: List<PermissionItem>) {
    val counted = items.filter { it.granted != null }
    val done = counted.count { it.granted == true }
    Column(Modifier.fillMaxWidth()) {
        if (counted.isNotEmpty()) {
            Column(Modifier.padding(horizontal = DockTheme.spacing.cardPadding, vertical = DockTheme.spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("{n} of {m} allowed", "n" to done, "m" to counted.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (done == counted.size) DockStatusPill(t("All set"))
                }
                DockProgress(
                    progress = done.toFloat() / counted.size,
                    modifier = Modifier.padding(top = DockTheme.spacing.s),
                )
            }
            DockDivider()
        }
        items.forEachIndexed { i, item ->
            if (i > 0) DockDivider()
            DockListItem(
                headline = item.title,
                supporting = item.why,
                icon = item.icon,
                iconTone = if (item.granted == true) Tone.SUCCESS else Tone.PRIMARY,
                trailing = {
                    if (item.granted == true) DockStatusPill(t("On"))
                    else DockButton(text = item.action, onClick = item.onAllow, kind = ButtonKind.TONAL, height = 48.dp)
                },
            )
        }
    }
}
