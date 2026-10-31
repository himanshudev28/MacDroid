package com.droiddock.app.ui.clipboard

import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.dockClickable
import com.droiddock.app.ui.components.dockFieldColors
import com.droiddock.app.ui.theme.dockPageBackground
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.t
import com.droiddock.app.ui.common.formatTimeAgo
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockClipCard
import com.droiddock.app.ui.components.DockEmptyState
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.components.DockSwitch
import com.droiddock.app.ui.theme.DockTheme

/**
 * Clipboard as a full tab (Classic layout): this session's traffic plus a
 * compose box pinned at the bottom, within thumb reach.
 *
 * The list is memory-only and dies with the process, which the empty state says
 * out loud: a persisted version would be a plaintext log of passwords and codes.
 */
@Composable
internal fun ClipboardTab(
    connected:  Boolean,
    history:    List<ConnectionManager.ClipEntry>,
    keepHistory: Boolean,
    onKeepHistory: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf("") }
    val notConnected = t("Not connected to Mac")

    // The compose bar sits under the list, so the list itself reserves no room for the
    // floating nav; the bar does instead (or for the keyboard, whichever is taller).
    val shell = LocalDockContentPadding.current
    val dir = LocalLayoutDirection.current
    val shellBottom = shell.calculateBottomPadding()
    Column(Modifier.fillMaxSize().dockPageBackground()) {
        CompositionLocalProvider(
            LocalDockContentPadding provides PaddingValues(
                start = shell.calculateStartPadding(dir), end = shell.calculateEndPadding(dir),
            ),
        ) {
            DockScaffold(
                title = t("Clipboard"),
                subtitle = if (connected) t("Copy on either device") else notConnected,
                modifier = Modifier.weight(1f),
                actions = {
                    if (history.isNotEmpty()) {
                        DockIconButton(Icons.Rounded.Delete, t("Clear history"), { ConnectionManager.clearClipHistory() })
                    }
                },
            ) {
                item(key = "history-toggle") {
                    DockCard(dockItemAnimation(), contentPadding = 0.dp) {
                        DockListItem(
                            headline = t("History"),
                            supporting = t("Cleared when the app closes"),
                            icon = Icons.Rounded.ContentPaste,
                            trailing = { DockSwitch(checked = keepHistory, onCheckedChange = onKeepHistory) },
                        )
                    }
                }
                if (history.isEmpty()) {
                    item(key = "empty") {
                        DockEmptyState(
                            Icons.Rounded.ContentPaste,
                            title = if (keepHistory) t("Nothing shared yet") else t("History is off"),
                        )
                    }
                } else {
                    items(history, key = { "${it.at}-${it.fromMac}-${it.text.hashCode()}" }) { entry -> ClipRow(entry, dockItemAnimation()) }
                }
            }
        }

        // Compose box: bottom of the screen, above the nav and the keyboard.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets(bottom = shellBottom)).only(WindowInsetsSides.Bottom),
                )
                .padding(start = sp.gutter, end = sp.gutter, top = sp.s, bottom = sp.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(t("Type a message…")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                colors = dockFieldColors(),
                maxLines = 3,
            )
            Spacer(Modifier.width(sp.s))
            SendButton(
                enabled = connected && draft.isNotBlank(),
                label = t("Send"),
                onSend = {
                    val ok = ConnectionManager.sendClipboardText(draft.trim())
                    if (ok) draft = "" else Toast.makeText(ctx, notConnected, Toast.LENGTH_SHORT).show()
                    ok
                },
            )
        }
    }
}

/**
 * Round send key: accent-filled when there is something to send, quiet when
 * not. A successful send morphs the arrow into a check for a moment.
 */
@Composable
private fun SendButton(enabled: Boolean, label: String, onSend: () -> Boolean) {
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    var sentAt by remember { mutableStateOf(0L) }
    LaunchedEffect(sentAt) { if (sentAt != 0L) { kotlinx.coroutines.delay(1200); sentAt = 0L } }
    val sent = sentAt != 0L
    val bg by animateColorAsState(
        when { sent -> d.success; enabled -> s.primary; else -> s.onSurface.copy(alpha = if (d.isDark) 0.10f else 0.07f) },
        motion.fade(), label = "send-bg",
    )
    val fg = if (enabled || sent) s.onPrimary else s.onSurfaceVariant.copy(alpha = 0.6f)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .dockClickable(enabled = enabled) {
                if (onSend()) { haptics.confirm(); sentAt = System.currentTimeMillis() } else haptics.reject()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = sent,
            transitionSpec = {
                (scaleIn(motion.pop(), initialScale = 0.5f) + fadeIn(motion.fade())) togetherWith
                    (scaleOut(motion.exit(), targetScale = 0.5f) + fadeOut(motion.exit()))
            },
            label = "send-icon",
        ) { done ->
            androidx.compose.material3.Icon(
                if (done) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send, null,
                tint = if (done) d.onSuccess else fg, modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun ClipRow(entry: ConnectionManager.ClipEntry, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = MaterialTheme.colorScheme
    val copied = t("Copied")
    DockCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                if (entry.fromMac) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid, null,
                tint = s.primary, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(DockTheme.spacing.s))
            Text(
                if (entry.fromMac) t("From Mac") else t("Sent to Mac"),
                style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatTimeAgo(entry.at), style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant)
            DockIconButton(Icons.Rounded.ContentCopy, t("Copy"), {
                copyToPhone(ctx, entry.text)
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }, filled = false, size = 48.dp, tint = s.onSurfaceVariant)
        }
        Text(entry.text, style = MaterialTheme.typography.bodyLarge, color = s.onSurface,
            maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Clipboard as a Home card (4-tab layout): newest clip, Send (phone clipboard to
 * Mac) and Copy (newest clip to this phone). The whole card opens [onOpen]
 * (the full history).
 */
@Composable
fun HomeClipboardCard(
    history: List<ConnectionManager.ClipEntry>,
    connected: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val latest = history.firstOrNull()
    val empty = t("Empty")
    val sent = t("Sent to Mac")
    val offline = t("Not connected to Mac")
    val copied = t("Copied")
    DockClipCard(
        title = t("Clipboard"),
        latest = latest?.text,
        meta = latest?.let { formatTimeAgo(it.at) },
        emptyText = if (connected) t("Copy on either device") else offline,
        sendLabel = t("Send"),
        openLabel = t("History"),
        onSend = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = readClipboardText(cm, ctx)
            val ok = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
            Toast.makeText(ctx, when { text.isEmpty() -> empty; ok -> sent; else -> offline }, Toast.LENGTH_SHORT).show()
        },
        onOpen = onOpen,
        modifier = modifier,
        copyLabel = if (latest != null) t("Copy") else null,
        onCopy = latest?.let { e -> { copyToPhone(ctx, e.text); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() } },
    )
}

private fun copyToPhone(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DroidDock", text))
    }
}

/**
 * The current clipboard as plain text, or "" when there is nothing readable.
 *
 * `primaryClip` is non-null whenever a clip description exists, including an
 * emptied clip with zero items, where `getItemAt(0)` throws. Android also
 * refuses background reads (SecurityException), hence the runCatching.
 */
internal fun readClipboardText(cm: ClipboardManager, ctx: Context): String = runCatching {
    val clip = cm.primaryClip ?: return@runCatching ""
    if (clip.itemCount == 0) return@runCatching ""
    clip.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}.getOrDefault("")
