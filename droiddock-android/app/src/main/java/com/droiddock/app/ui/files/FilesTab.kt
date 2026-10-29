package com.droiddock.app.ui.files

import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.TransferProgress
import com.droiddock.app.TransferRecord
import com.droiddock.app.t
import com.droiddock.app.ui.common.formatBytes
import com.droiddock.app.ui.common.formatSpeed
import com.droiddock.app.ui.common.formatTimeAgo
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockEmptyState
import com.droiddock.app.ui.components.DockGroup
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockProgress
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.components.DockSection
import com.droiddock.app.ui.components.DockSectionHeader
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme
import java.util.Calendar

/**
 * Slot the Files screen fills with its "This phone / Mac" switch. Each tab puts
 * it as the first row under its title, so the switch scrolls with the page and
 * the header is owned by exactly one scaffold.
 */
internal val LocalSourceToggle = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
internal fun FilesTab(
    connected:       Boolean,
    sending:         Boolean,
    activeTransfers: List<TransferProgress>,
    recentTransfers: List<TransferRecord>,
    onSendFile:      () -> Unit,
    onSendClipboard: () -> Unit,
) {
    val toggle = LocalSourceToggle.current
    val todayBytes = remember(recentTransfers) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        recentTransfers.filter { it.completedAt >= start && it.success }.sumOf { it.sizeBytes }
    }

    DockScaffold(title = t("Files")) {
        if (toggle != null) item(key = "source", contentType = "toggle") { toggle() }

        item(key = "summary", contentType = "summary") {
            DockCard(dockItemAnimation()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t("Transferred today"),
                            style = DockTheme.type.overline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (todayBytes == 0L) "—" else formatBytes(todayBytes),
                            style = DockTheme.type.numeral,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(DockTheme.spacing.m))
                    DockButton(
                        text = if (sending) t("Sending…") else t("Send"),
                        onClick = onSendFile,
                        icon = Icons.Rounded.Add,
                        enabled = !sending,
                        glow = true,
                    )
                }
            }
        }

        if (activeTransfers.isNotEmpty()) {
            item(key = "active", contentType = "group") {
                Column(dockItemAnimation()) {
                    DockSectionHeader(t("Sending now"))
                    DockGroup(rows = activeTransfers.map { tr -> { ActiveTransferRow(tr) } })
                }
            }
        }

        if (recentTransfers.isNotEmpty()) {
            item(key = "recent", contentType = "group") {
                Column(dockItemAnimation()) {
                    DockSectionHeader(t("Recent transfers"))
                    DockGroup(rows = recentTransfers.map { r -> { RecentTransferRow(r) } })
                }
            }
        } else if (activeTransfers.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                DockEmptyState(
                    modifier = dockItemAnimation(),
                    icon = Icons.Rounded.SwapVert,
                    title = t("No transfers yet"),
                    body = t("Files you send or receive show up here."),
                )
            }
        }

        item(key = "quick", contentType = "group") {
            Column(dockItemAnimation()) {
                DockSectionHeader(t("Quick actions"))
                DockGroup(
                    rows = listOf({
                        DockListItem(
                            headline = t("Send Clipboard to Mac"),
                            supporting = t("Push whatever you copied right now"),
                            icon = Icons.Rounded.ContentCopy,
                            iconTone = Tone.TERTIARY,
                            onClick = onSendClipboard,
                            trailing = {
                                Text(
                                    t("Send"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    })
                )
            }
        }

        item(key = "tips", contentType = "tips") {
            DockSection(t("Tips"), dockItemAnimation()) {
                Column(
                    Modifier.padding(horizontal = DockTheme.spacing.cardPadding, vertical = DockTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(DockTheme.spacing.m),
                ) {
                    TIPS.forEach { tip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(DockTheme.spacing.m)) {
                            Box(
                                Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
                            )
                            Text(
                                t(tip),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TIPS = listOf(
    "Share from any app using the Android share sheet — tap 'Send to Mac'",
    "Long-press any text and choose 'Send to Mac' from the selection menu",
    "On Mac, drag files onto the DroidDock window to send to your phone",
    "Received files appear in your Downloads folder on both devices",
)

@Composable
internal fun ActiveTransferRow(transfer: TransferProgress) {
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m),
        verticalAlignment = Alignment.Top,
    ) {
        FileBadge(transfer.fileName)
        Spacer(Modifier.width(sp.l))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transfer.fileName, style = MaterialTheme.typography.bodyLarge, color = s.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(sp.s))
                Text("${transfer.percent}%", style = MaterialTheme.typography.labelLarge, color = s.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatBytes(transfer.totalBytes), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                if (transfer.speedBps > 0) {
                    Text(formatSpeed(transfer.speedBps), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(sp.s))
            DockProgress(transfer.sentBytes.toFloat() / transfer.totalBytes.toFloat().coerceAtLeast(1f))
        }
    }
}

@Composable
internal fun RecentTransferRow(record: TransferRecord) {
    val dir = if (record.direction == "toMac") "→ Mac" else "← Phone"
    DockListItem(
        headline = record.fileName,
        supporting = "${formatBytes(record.sizeBytes)} · $dir · ${formatTimeAgo(record.completedAt)}",
        leading = { FileBadge(record.fileName) },
        trailing = {
            if (record.success) {
                Icon(Icons.Rounded.CheckCircle, t("Completed"), tint = DockTheme.colors.success, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Rounded.Error, t("Failed"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        },
    )
}
