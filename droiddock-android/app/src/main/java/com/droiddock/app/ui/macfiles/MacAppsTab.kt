package com.droiddock.app.ui.macfiles

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droiddock.app.ConnectionManager
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockEmptyState
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockSkeleton
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme
import kotlinx.coroutines.launch

/**
 * The Mac can hand over names such as "\\u200eWhatsApp" with the escape still
 * literal (seen on device). Decode any literal \\uXXXX, then drop invisible
 * direction marks so the row reads "WhatsApp" and sorts/searches correctly.
 */
private fun cleanAppLabel(raw: String): String {
    val decoded = Regex("""\\u([0-9a-fA-F]{4})""").replace(raw) {
        it.groupValues[1].toInt(16).toChar().toString()
    }
    return decoded.filterNot { it in '\u200b'..'\u200f' || it in '\u202a'..'\u202e' || it == '\u2066' || it == '\u2067' || it == '\u2068' || it == '\u2069' || it == '\ufeff' }.trim()
}

/**
 * The Mac's applications, launchable from the phone.
 *
 * Mirror image of the Mac's Apps grid. Gated on the "macapps" capability, which
 * the Mac only advertises while "Let the phone control this Mac" is on — so if
 * this tab is visible at all, the permission is already granted. The Mac still
 * re-checks on every message; a stale screen gets an error rather than a launch.
 *
 * Sits under ControlScreen's header, which owns the status-bar inset.
 */
@Composable
internal fun MacAppsTab(connected: Boolean) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val sp = DockTheme.spacing

    suspend fun load() {
        error = null
        apps = null
        runCatching { ConnectionManager.macAppsList() }
            .onSuccess { arr ->
                apps = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pkg = o.optString("pkg")
                    val label = cleanAppLabel(o.optString("label"))
                    if (pkg.isEmpty() || label.isEmpty()) null else pkg to label
                }.sortedBy { it.second.lowercase() }
            }
            .onFailure {
                apps = emptyList()
                error = it.message ?: "Couldn't read the Mac's apps"
            }
    }

    LaunchedEffect(connected) { if (connected) load() }

    // Prefix matches first, same ranking the Mac's own grid uses so searching
    // behaves identically on both ends.
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        val all = apps ?: emptyList()
        if (q.isEmpty()) all
        else all.filter { it.second.lowercase().contains(q) }
            .sortedWith(compareByDescending<Pair<String, String>> {
                it.second.lowercase().startsWith(q)
            }.thenBy { it.second.lowercase() })
    }

    if (!connected) {
        DockEmptyState(
            icon = Icons.Rounded.LinkOff,
            title = t("Not connected to your Mac."),
            modifier = Modifier.fillMaxSize().padding(horizontal = sp.gutter),
        )
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = sp.gutter).padding(top = sp.s),
        verticalArrangement = Arrangement.spacedBy(sp.m),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = DockTheme.shapes.field,
            label = { Text(t("Search apps")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { DockIconButton(Icons.Rounded.Close, t("Clear search"), { query = "" }, filled = false) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        val err = error
        val list = apps
        when {
            list == null -> DockCard(contentPadding = sp.m) { DockSkeleton(rows = 6) }
            err != null -> DockEmptyState(Icons.Rounded.LinkOff, err)
            shown.isEmpty() -> DockEmptyState(
                icon = Icons.Rounded.SearchOff,
                title = if (query.isBlank()) t("No apps found.") else "Nothing matching \"$query\".",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalDockContentPadding.current.calculateBottomPadding() + sp.xl),
                verticalArrangement = Arrangement.spacedBy(sp.m),
            ) {
                groupedRows(shown, keyOf = { it.first }) { (pkg, label) ->
                    DockListItem(
                        headline = label,
                        supporting = pkg,
                        icon = Icons.Rounded.LaptopMac,
                        iconTone = Tone.SECONDARY,
                        onClick = {
                            scope.launch {
                                runCatching { ConnectionManager.macAppLaunch(pkg) }
                                    .onSuccess {
                                        Toast.makeText(ctx, "Opening $label on your Mac", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure {
                                        Toast.makeText(ctx, it.message ?: "Couldn't open $label", Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                    )
                }
            }
        }
    }
}
