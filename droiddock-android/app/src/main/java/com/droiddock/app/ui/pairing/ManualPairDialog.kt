package com.droiddock.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.droiddock.app.Pairing
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.components.DockSheetContent
import com.droiddock.app.ui.theme.DockTheme

/** Field-level problems with manual pairing input; null means the field is fine. */
internal data class ManualPairErrors(val ip: String? = null, val port: String? = null, val token: String? = null) {
    val any get() = ip != null || port != null || token != null
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$""")
private val IPV6 = Regex("""^[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?$""")

internal fun isValidHostOrIp(s: String): Boolean {
    val m = IPV4.matchEntire(s)
    if (m != null) return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    if (s.contains(':')) return IPV6.matches(s) && s.count { it == ':' } >= 2
    // Anything that looks like a dotted number but isn't a valid IPv4 is a typo, not a host name.
    if (s.all { it.isDigit() || it == '.' }) return false
    return HOST.matches(s)
}

/** Port is optional (blank means the default); otherwise it must be 1-65535. Returns null when invalid. */
internal fun parseManualPort(s: String): Int? =
    if (s.isBlank()) 48484 else s.trim().toIntOrNull()?.takeIf { it in 1..65535 }

internal fun validateManualPair(ip: String, port: String, token: String): ManualPairErrors = ManualPairErrors(
    ip = if (ip.isBlank()) t("Enter the Mac's IP address") else if (!isValidHostOrIp(ip.trim())) t("That doesn't look like an IP address") else null,
    port = if (parseManualPort(port) == null) t("Port must be between 1 and 65535") else null,
    token = if (token.isBlank()) t("Enter the token shown on the Mac") else null,
)

/** Manual pairing as a bottom sheet (fallback when the QR can't be scanned). */
@Composable
internal fun ManualPairDialog(onDismiss: () -> Unit, onPair: (Pairing) -> Unit) {
    DockSheet(onDismiss = onDismiss, title = t("Pair manually")) {
        ManualPairForm(onCancel = onDismiss, onPair = onPair)
    }
}

/** The sheet body, separate so snapshots can render it inline. */
@Composable
internal fun ManualPairForm(onCancel: () -> Unit, onPair: (Pairing) -> Unit) {
    val sp = DockTheme.spacing
    // Saveable: rotating the phone mid-entry keeps what was typed.
    var ip by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("48484") }
    var token by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(ManualPairErrors()) }
    Column(verticalArrangement = Arrangement.spacedBy(sp.m)) {
        // In landscape the fields don't fit: they scroll, and Pair / Cancel stay pinned in view.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sp.m),
        ) {
            Text(
                t("Open Pair Device on the Mac and read the IP, port and token it shows."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ip, onValueChange = { ip = it; errors = errors.copy(ip = null) }, singleLine = true,
                isError = errors.ip != null, supportingText = errors.ip?.let { m -> { Text(m) } },
                label = { Text(t("Mac IP (e.g. 192.168.0.108)")) },
                shape = DockTheme.shapes.field, colors = com.droiddock.app.ui.components.dockFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it; errors = errors.copy(port = null) }, singleLine = true,
                isError = errors.port != null, supportingText = errors.port?.let { m -> { Text(m) } },
                label = { Text(t("Port")) }, shape = DockTheme.shapes.field, colors = com.droiddock.app.ui.components.dockFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it; errors = errors.copy(token = null) }, singleLine = true,
                isError = errors.token != null, supportingText = errors.token?.let { m -> { Text(m) } },
                label = { Text(t("Token")) }, shape = DockTheme.shapes.field, colors = com.droiddock.app.ui.components.dockFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DockButton(
            text = t("Pair"), fullWidth = true, glow = true,
            modifier = Modifier.padding(top = sp.xs),
            onClick = {
                val found = validateManualPair(ip, port, token)
                errors = found
                if (!found.any) onPair(Pairing(listOf(ip.trim()), parseManualPort(port)!!, token.trim(), "Mac"))
            },
        )
        DockButton(text = t("Cancel"), onClick = onCancel, kind = ButtonKind.GHOST, fullWidth = true)
    }
}
