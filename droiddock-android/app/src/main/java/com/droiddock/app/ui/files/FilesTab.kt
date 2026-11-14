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
import com.droiddock.app.ui.components.DockGroup
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockNumeral
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.theme.DockEntrance
import com.droiddock.app.ui.theme.dockStagger
import com.droiddock.app.ui.theme.rememberDockEntrance
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
    // Plays once per process for the first screenful; rows arriving later or scrolled back in stay still.
    val entrance = rememberDockEntrance("files")
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
            val (num, unit) = remember(todayBytes) { bytesNumeral(todayBytes) }
            val activeFrac = remember(activeTransfers) {
                val total = activeTransfers.sumOf { it.totalBytes }
                if (activeTransfers.isEmpty() || total <= 0L) null
                else (activeTransfers.sumOf { it.sentBytes }.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
            DockCard(dockItemAnimation()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t("Transferred today"),
                            style = DockTheme.type.overline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DockNumeral(
                            value = num, unit = unit,
                            color = MaterialTheme.colorScheme.onSurface,
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
                Spacer(Modifier.size(DockTheme.spacing.m))
                // The link itself: fills while files move, pulses once when one lands, breaks when the Mac is away.
                PhoneMacTether(
                    state = if (connected) TetherState.LINKED else TetherState.OFFLINE,
                    modifier = Modifier.fillMaxWidth(),
                    progress = if (connected) activeFrac else null,
                    pulseKey = recentTransfers.firstOrNull()?.id,
                )
            }
        }

        if (activeTransfers.isNotEmpty()) {
            item(key = "active", contentType = "group") {
                Column(dockItemAnimation()) {
                    DockSectionHeader(t("Sending now"))
                    DockGroup(rows = activeTransfers.mapIndexed { i, tr -> { ActiveTransferRow(tr, entrance, i) } })
                }
            }
        }

        if (recentTransfers.isNotEmpty()) {
            item(key = "recent", contentType = "group") {
                Column(dockItemAnimation()) {
                    DockSectionHeader(t("Recent transfers"))
                    DockGroup(rows = recentTransfers.mapIndexed { i, r -> { RecentTransferRow(r, entrance, i) } })
                }
            }
        } else if (activeTransfers.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                LinkStatePanel(
                    state = if (connected) TetherState.LINKED else TetherState.OFFLINE,
                    modifier = dockItemAnimation(),
                    title = if (connected) t("No transfers yet") else t("Not connected to Mac"),
                    body = if (connected) t("Files you send or receive show up here.") else null,
                    showTether = connected,
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
                    TIPS.forEachIndexed { i, tip ->
                        Row(Modifier.dockStagger(entrance, i), horizontalArrangement = Arrangement.spacedBy(DockTheme.spacing.m)) {
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
internal fun ActiveTransferRow(transfer: TransferProgress, entrance: DockEntrance, index: Int = 0) {
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    Row(
        Modifier.dockStagger(entrance, index).fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m),
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
                DockNumeral(transfer.percent, unit = "%", style = MaterialTheme.typography.titleMedium, color = s.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatBytes(transfer.totalBytes), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                if (transfer.speedBps > 0) {
                    Text(formatSpeed(transfer.speedBps), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(sp.s))
            val frac = (transfer.sentBytes.toFloat() / transfer.totalBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            PhoneMacTether(
                TetherState.LINKED,
                Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(frac, 0f..1f) },
                progress = frac,
                height = 20.dp,
            )
        }
    }
}

@Composable
internal fun RecentTransferRow(record: TransferRecord, entrance: DockEntrance, index: Int = 0) {
    val dir = if (record.direction == "toMac") "→ Mac" else "← Phone"
    DockListItem(
        modifier = Modifier.dockStagger(entrance, index),
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

/**
 * Bytes as a whole number plus unit for [DockNumeral]; null means nothing yet. Stays in the smaller unit until the
 * number reaches four digits, so 1.9 GB reads "1900 MB" rather than a truncated "1 GB".
 */
internal fun bytesNumeral(bytes: Long): Pair<Int?, String?> {
    fun r(div: Long) = ((bytes + div / 2) / div).toInt()
    return when {
        bytes <= 0L -> null to null
        bytes < 10_000L -> bytes.toInt() to "B"
        bytes < 10_000_000L -> r(1_000L) to "KB"
        bytes < 10_000_000_000L -> r(1_000_000L) to "MB"
        else -> r(1_000_000_000L) to "GB"
    }
}
