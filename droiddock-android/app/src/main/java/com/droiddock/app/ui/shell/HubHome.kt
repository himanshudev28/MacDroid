package com.droiddock.app.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockHero
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockTile
import com.droiddock.app.ui.components.HeroState
import com.droiddock.app.ui.components.LocalDockContentPadding
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme

/** Everything [com.droiddock.app.ui.components.DockHero] shows, as data. */
@Immutable
data class HeroModel(
    val title: String,
    val status: String,
    val state: HeroState,
    val detail: String? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val primaryLabel: String? = null,
    val primaryIcon: ImageVector? = null,
    val onPrimary: (() -> Unit)? = null,
)

@Immutable
data class HubTileModel(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val tone: Tone = Tone.PRIMARY,
    val badge: String? = null,
)

/**
 * Hub layout home: built for one thumb. The top is glance-only (name, gear);
 * the hero sits in the lower half, tiles below it, and the one primary action
 * is a full-width button at the very bottom. When everything fits, the column
 * is bottom-aligned; when it doesn't, it scrolls.
 */
@Composable
fun HubHome(
    appName: String,
    hero: HeroModel,
    tiles: List<HubTileModel>,
    onTile: (String) -> Unit,
    onSettings: () -> Unit,
    settingsLabel: String,
    modifier: Modifier = Modifier,
    settingsBadge: Boolean = false,
    primaryLabel: String? = null,
    primaryIcon: ImageVector? = null,
    onPrimary: (() -> Unit)? = null,
) {
    val sp = DockTheme.spacing
    val origin = LocalHubOrigin.current
    val bottom = LocalDockContentPadding.current.calculateBottomPadding()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minH)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = sp.gutter, end = sp.gutter, top = 64.dp, bottom = bottom + sp.l),
                verticalArrangement = Arrangement.spacedBy(sp.m, Alignment.Bottom),
            ) {
                DockHero(
                    title = hero.title, status = hero.status, state = hero.state, detail = hero.detail,
                    battery = hero.battery, charging = hero.charging,
                    primaryLabel = hero.primaryLabel, primaryIcon = hero.primaryIcon, onPrimary = hero.onPrimary,
                )
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.m)) {
                        pair.forEach { tile ->
                            DockTile(
                                tile.title, tile.icon, { onTile(tile.id) },
                                Modifier.weight(1f).onGloballyPositioned { origin?.centers?.set(tile.id, it.boundsInWindow().center) },
                                subtitle = tile.subtitle, tone = tile.tone,
                                badge = tile.badge, height = sp.tileHeight + 24.dp,
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (primaryLabel != null && onPrimary != null) {
                    DockButton(primaryLabel, onPrimary, icon = primaryIcon, kind = ButtonKind.TONAL, fullWidth = true, height = 56.dp)
                }
            }
        }
        // Glance-only header: not in the thumb zone, so it holds nothing urgent.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = sp.gutter, vertical = sp.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                appName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = sp.xs),
            )
            BadgedBox(badge = { if (settingsBadge) Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                DockIconButton(
                    Icons.Rounded.Settings, settingsLabel, onSettings,
                    Modifier.onGloballyPositioned { origin?.centers?.set(Dest.SETTINGS, it.boundsInWindow().center) },
                )
            }
        }
    }
}
