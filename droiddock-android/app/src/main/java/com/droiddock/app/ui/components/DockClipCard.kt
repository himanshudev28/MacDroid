package com.droiddock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.theme.DockTheme

/**
 * Clipboard as a Home card (4-tab layout): the newest clip large, Send/Copy,
 * and the whole card opens the full history. Strings come from the caller
 * (already translated with `t()`).
 */
@Composable
fun DockClipCard(
    title: String,
    latest: String?,
    meta: String?,
    emptyText: String,
    sendLabel: String,
    openLabel: String,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    copyLabel: String? = null,
    onCopy: (() -> Unit)? = null,
) {
    val s = MaterialTheme.colorScheme
    DockCard(modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockIconBadge(Icons.Rounded.ContentPaste, tone = Tone.SECONDARY, size = 36.dp)
            Spacer(Modifier.width(DockTheme.spacing.m))
            Text(
                if (DockTheme.type.upperCaseLabels) title.uppercase() else title,
                style = if (DockTheme.type.upperCaseLabels) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                color = s.onSurface, modifier = Modifier.weight(1f),
            )
            if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant)
        }
        Spacer(Modifier.height(DockTheme.spacing.m))
        Text(
            latest ?: emptyText,
            style = if (latest != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (latest != null) s.onSurface else s.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DockTheme.spacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(DockTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            DockButton(sendLabel, onSend, kind = ButtonKind.TONAL, icon = Icons.AutoMirrored.Rounded.Send, height = 44.dp)
            if (copyLabel != null && onCopy != null) {
                DockButton(copyLabel, onCopy, kind = ButtonKind.GHOST, icon = Icons.Rounded.ContentCopy, height = 44.dp)
            }
            Spacer(Modifier.weight(1f))
            DockButton(openLabel, onOpen, kind = ButtonKind.GHOST, height = 44.dp)
        }
    }
}
