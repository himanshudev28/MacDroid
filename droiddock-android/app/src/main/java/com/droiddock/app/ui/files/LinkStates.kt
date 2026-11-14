package com.droiddock.app.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockTether
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockEnter

/** One end of the phone-to-Mac tether: a small glyph that inherits the content colour. */
@Composable
internal fun TetherGlyph(icon: ImageVector, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
}

/** Phone glyph, tether, Mac glyph: the standard ends for every link-shaped state and transfer. */
@Composable
internal fun PhoneMacTether(
    state: TetherState,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    pulseKey: Any? = null,
    reverse: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val a = if (reverse) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid
    val b = if (reverse) Icons.Rounded.PhoneAndroid else Icons.Rounded.Laptop
    DockTether(
        state = state, modifier = modifier, progress = progress, pulseKey = pulseKey, reverse = reverse,
        height = height,
        start = { TetherGlyph(a) }, end = { TetherGlyph(b) },
    )
}

/**
 * Empty / loading / offline state drawn as the link itself: LINKED = a calm line (nothing here yet),
 * CONNECTING = drifting dashes (loading), OFFLINE = a broken line (no Mac). Docks in with the signature entrance.
 */
@Composable
internal fun LinkStatePanel(
    state: TetherState,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showTether: Boolean = true,
) {
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    Column(
        modifier.dockEnter().fillMaxWidth().padding(vertical = sp.xxl, horizontal = sp.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTether) {
            PhoneMacTether(state, Modifier.widthIn(max = 280.dp).fillMaxWidth(), height = 32.dp)
            Spacer(Modifier.height(sp.xl))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = s.onSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(sp.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(sp.l))
            DockButton(text = actionLabel, onClick = onAction)
        }
    }
}
