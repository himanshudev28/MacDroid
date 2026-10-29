package com.droiddock.app.ui.pairing

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.droiddock.app.Pairing
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockSheet
import com.droiddock.app.ui.components.DockSheetContent
import com.droiddock.app.ui.theme.DockTheme

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
    val ctx = LocalContext.current
    val sp = DockTheme.spacing
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("48484") }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(sp.m)) {
        Text(
            t("Open Pair Device on the Mac and read the IP, port and token it shows."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            label = { Text(t("Mac IP (e.g. 192.168.0.108)")) },
            shape = DockTheme.shapes.field,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it }, singleLine = true,
            label = { Text(t("Port")) }, shape = DockTheme.shapes.field,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text(t("Token")) }, shape = DockTheme.shapes.field,
            modifier = Modifier.fillMaxWidth(),
        )
        DockButton(
            text = t("Pair"), fullWidth = true, glow = true,
            modifier = Modifier.padding(top = sp.xs),
            onClick = {
                val ipt = ip.trim(); val tok = token.trim()
                val p = port.trim().toIntOrNull() ?: 48484
                if (ipt.isEmpty() || tok.isEmpty()) {
                    Toast.makeText(ctx, t("Enter the IP and token shown on the Mac"), Toast.LENGTH_SHORT).show()
                } else {
                    onPair(Pairing(listOf(ipt), p, tok, "Mac"))
                }
            },
        )
        DockButton(text = t("Cancel"), onClick = onCancel, kind = ButtonKind.GHOST, fullWidth = true)
    }
}
