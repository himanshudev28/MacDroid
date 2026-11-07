package com.droiddock.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.droiddock.app.LinkTls
import com.droiddock.app.Pairing
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.theme.DockTheme

/** What to do with a `droiddock://pair` link that arrived from outside the app. */
internal sealed interface DeepLinkDecision {
    /** Valid and pinned: show the confirmation sheet; nothing is paired until the user taps Pair. */
    data class Confirm(val pairing: Pairing) : DeepLinkDecision
    /** No certificate fingerprint: pairing would be plain `ws://`, so refuse. */
    data object RefusedNoFingerprint : DeepLinkDecision
    /** Not a pairing link at all, or malformed. */
    data object Invalid : DeepLinkDecision
}

/**
 * A deep link can be fired by any web page or app, so it must never pair on its own and must carry
 * the Mac's key fingerprint (otherwise the attacker's host could be reached over unauthenticated
 * transport). An in-app QR scan is a deliberate act and does not go through here.
 */
internal fun evaluateDeepLink(raw: String): DeepLinkDecision {
    if (!raw.startsWith("droiddock://pair")) return DeepLinkDecision.Invalid
    val pairing = runCatching { LinkTls.parsePairingQr(raw) }.getOrNull()
        ?: return DeepLinkDecision.Invalid
    if (pairing.fp.isNullOrEmpty()) return DeepLinkDecision.RefusedNoFingerprint
    return DeepLinkDecision.Confirm(pairing)
}

/** Confirmation for a deep-link pair: the Mac's name and addresses, Pair or Cancel. */
@Composable
internal fun DeepLinkConfirmSheet(pairing: Pairing, onCancel: () -> Unit, onPair: () -> Unit) {
    val sp = DockTheme.spacing
    DockSheet(onDismiss = onCancel, title = t("Pair with this Mac?")) {
        Column(verticalArrangement = Arrangement.spacedBy(sp.m)) {
            Text(
                t("A link asked DroidDock to pair with the Mac below. Only continue if you started this from your own Mac."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(pairing.macName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                pairing.ips.joinToString("\n") { "$it:${pairing.port}" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DockButton(text = t("Pair"), fullWidth = true, glow = true, modifier = Modifier.padding(top = sp.xs), onClick = onPair)
            DockButton(text = t("Cancel"), onClick = onCancel, kind = ButtonKind.GHOST, fullWidth = true)
        }
    }
}
