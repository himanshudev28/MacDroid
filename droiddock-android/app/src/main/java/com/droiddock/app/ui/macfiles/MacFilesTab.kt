package com.droiddock.app.ui.macfiles

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.t
import com.droiddock.app.ui.common.formatBytes
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockChevron
import com.droiddock.app.ui.components.DockChip
import com.droiddock.app.ui.components.DockEmptyState
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockProgress
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.components.DockSkeleton
import com.droiddock.app.ui.components.dockClickable
import com.droiddock.app.ui.files.FileBadge
import com.droiddock.app.ui.files.LocalSourceToggle
import com.droiddock.app.ui.theme.DockTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun macFsEntryPath(base: String, entry: JSONObject): String {
    // The synthetic root listing (base == "") carries an absolute `path` per
    // entry, because a root's display name is only its basename and there is
    // no base to join it to. Ordinary entries have no `path` and are joined.
    entry.optString("path").takeIf { it.isNotEmpty() }?.let { return it }
    val name = entry.optString("name")
    return if (base.isEmpty()) name else "$base/$name"
}

/** Label + target path for each breadcrumb. Only paths inside a shared root are ever targets. */
internal fun macFsCrumbs(path: String, roots: List<String>): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out += "Mac" to ""
    if (path.isEmpty()) return out
    val root = roots.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
    if (root == null) {
        out += path.substringAfterLast('/').ifEmpty { path } to path
        return out
    }
    out += root.substringAfterLast('/').ifEmpty { root } to root
    var acc = root
    path.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }.forEach { seg ->
        acc = "$acc/$seg"
        out += seg to acc
    }
    return out
}

@Composable
internal fun MacFilesTab(connected: Boolean) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    val toggle = LocalSourceToggle.current

    var path        by remember { mutableStateOf("") }
    var entries     by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading     by remember { mutableStateOf(false) }
    var error       by remember { mutableStateOf<String?>(null) }
    var pullingPath by remember { mutableStateOf<String?>(null) }
    val pullFrac    = remember { mutableFloatStateOf(0f) }
    var reload      by remember { mutableIntStateOf(0) }
    // The Mac's shared folders, learned from the synthetic root listing (the
    // only place their absolute paths are ever sent). Needed to know where
    // "up" stops.
    var roots       by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(path, connected, reload) {
        if (!connected) {
            error = "Not connected to Mac"
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { ConnectionManager.macFsList(path) }
            .onSuccess { arr ->
                entries = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (path.isEmpty()) {
                    roots = entries.mapNotNull { e ->
                        e.optString("path").takeIf { it.isNotEmpty() }
                    }
                }
            }
            .onFailure { error = it.message ?: "Could not load folder" }
        loading = false
    }

    val crumbs = remember(path, roots) { macFsCrumbs(path, roots) }
    val err = error

    DockScaffold(title = t("Mac Files")) {
        if (toggle != null) item(key = "source", contentType = "toggle") { toggle() }

        item(key = "crumbs", contentType = "crumbs") {
            val scroll = rememberScrollState()
            LaunchedEffect(path) { scroll.scrollTo(Int.MAX_VALUE) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (path.isNotEmpty()) {
                    DockIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = t("Up"),
                        onClick = {
                            // Walking up out of a shared folder lands on a path the
                            // Mac's allowlist rejects: /Users/you/Desktop's parent
                            // is /Users/you, which was never shared. Going up from a
                            // root (or from anything whose parent is outside every
                            // root) therefore returns to the list of shared folders.
                            val parent = path.substringBeforeLast('/', "")
                            path = if (roots.any { it == parent || parent.startsWith("$it/") })
                                parent else ""
                        },
                    )
                    Spacer(Modifier.width(DockTheme.spacing.s))
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { i, (label, target) ->
                        if (i > 0) Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        DockChip(
                            label = label,
                            onClick = { path = target },
                            selected = i == crumbs.lastIndex,
                            icon = if (i == 0) Icons.Rounded.LaptopMac else null,
                        )
                    }
                }
            }
        }

        when {
            loading -> item(key = "loading", contentType = "loading") {
                DockCard(contentPadding = DockTheme.spacing.m) { DockSkeleton(rows = 6) }
            }

            err != null -> item(key = "error", contentType = "empty") {
                DockEmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = err,
                    actionLabel = if (connected) t("Try again") else null,
                    onAction = { reload++ },
                )
            }

            entries.isEmpty() -> item(key = "empty", contentType = "empty") {
                DockEmptyState(
                    icon = Icons.Rounded.FolderOff,
                    title = t("Empty folder"),
                    body = t("Nothing to show here."),
                )
            }

            else -> groupedRows(entries, keyOf = { macFsEntryPath(path, it) }) { entry ->
                val full = macFsEntryPath(path, entry)
                val pulling = pullingPath == full
                MacFsEntryRow(
                    entry    = entry,
                    pulling  = pulling,
                    fraction = { if (pulling) pullFrac.floatValue else 0f },
                    onOpen   = { if (entry.optBoolean("dir")) path = full },
                    onPull   = {
                        pullingPath = full
                        pullFrac.floatValue = 0f
                        scope.launch {
                            val result = ConnectionManager.macFsPull(full) { received, total ->
                                pullFrac.floatValue = if (total > 0)
                                    (received.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            }
                            pullingPath = null
                            Toast.makeText(
                                ctx,
                                result.fold({ "Saved to Downloads" }, { e -> e.message ?: "Pull failed" }),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun MacFsEntryRow(
    entry:    JSONObject,
    pulling:  Boolean,
    fraction: () -> Float,
    onOpen:   () -> Unit,
    onPull:   () -> Unit,
) {
    val isDir = entry.optBoolean("dir")
    val name  = entry.optString("name")
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (isDir) Modifier.dockClickable(onClick = onOpen) else Modifier)
            .padding(horizontal = sp.cardPadding, vertical = sp.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(name, isDir = isDir)
        Spacer(Modifier.width(sp.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name, style = MaterialTheme.typography.bodyLarge, color = s.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (pulling) {
                Spacer(Modifier.size(2.dp))
                DockProgress(fraction())
            } else if (!isDir) {
                Text(formatBytes(entry.optLong("size")), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
            }
        }
        when {
            isDir -> { Spacer(Modifier.width(sp.s)); DockChevron() }
            pulling -> {
                Spacer(Modifier.width(sp.m))
                Text("${(fraction() * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = s.primary)
            }
            else -> DockIconButton(
                icon = Icons.Rounded.Download,
                contentDescription = t("Pull {name} to this phone", "name" to name),
                onClick = onPull,
                tint = s.primary,
            )
        }
    }
}
