package com.droiddock.app.ui.setup

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.droiddock.app.AccessibilityControl
import com.droiddock.app.LockAdmin
import com.droiddock.app.Prefs
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.DockListItem
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.components.DockSwitch
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.DockTheme

/**
 * Banking apps refuse to run while *any* accessibility service is enabled, so
 * these rows make switching it off (and a lock path that doesn't need it) cheap.
 * Behaviour is unchanged; only the surface moved onto Dock tokens.
 *
 * The device-admin prompt must be launched from a result launcher (never
 * FLAG_ACTIVITY_NEW_TASK), and `justDisabled` bridges the async `disableSelf()`.
 */
@Composable
internal fun ScreenControlRows(serviceOn: Boolean, adminOn: Boolean) {
    val ctx = LocalContext.current
    var justDisabled by remember(serviceOn) { mutableStateOf(false) }
    val a11yOn = serviceOn && !justDisabled
    val adminPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    if (a11yOn) {
        var screenControl by remember { mutableStateOf(Prefs.screenControl(ctx)) }
        ToggleRow(
            icon = Icons.Outlined.TouchApp,
            tone = Tone.TERTIARY,
            title = t("Mac screen control"),
            subtitle = if (screenControl) t("Mac can tap, swipe and type on this phone")
            else t("Off. Mirroring still streams, but the Mac can't touch it"),
            checked = screenControl,
            onChecked = { v ->
                screenControl = v
                Prefs.setScreenControl(ctx, v)
                AccessibilityControl.enabled = v
            },
        )
        DockDivider()
    }

    ServiceRow(
        icon = Icons.Outlined.Accessibility,
        tone = if (a11yOn) Tone.SUCCESS else Tone.NEUTRAL,
        title = t("Accessibility service"),
        subtitle = if (a11yOn) t("Running. Powers auto clipboard and screen control.")
        else t("Off. Auto clipboard and Mac screen control both need this"),
        granted = null,
        action = if (a11yOn) "Turn off" else "Enable",
    ) {
        if (a11yOn) {
            AccessibilityControl.disableSelf()
            justDisabled = true
        } else {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    DockDivider()
    ServiceRow(
        icon = Icons.Outlined.Lock,
        tone = Tone.SECONDARY,
        title = t("Lock Without Accessibility"),
        subtitle = if (adminOn) t("Mac's Lock button works with Screen Control off")
        else t("Optional. Lets the Mac lock this screen when Screen Control is off"),
        granted = adminOn,
        action = "Grant",
    ) {
        runCatching { adminPrompt.launch(LockAdmin.enableIntent(ctx)) }
            .onFailure {
                Toast.makeText(ctx, t("Couldn't open the device-admin screen on this phone"), Toast.LENGTH_LONG).show()
            }
    }
    if (adminOn) {
        Row(
            Modifier.fillMaxWidth().padding(end = DockTheme.spacing.l, bottom = DockTheme.spacing.s),
            horizontalArrangement = Arrangement.End,
        ) {
            DockButton(
                text = t("Revoke lock permission"), onClick = { LockAdmin.disable(ctx) },
                kind = ButtonKind.GHOST, height = 48.dp,
            )
        }
    }
}

/**
 * One settings row with a trailing action: an "On" pill when [granted], a tonal
 * button otherwise. [tint] is accepted for older call sites and ignored: the
 * style decides colour through [tone].
 */
@Composable
internal fun ServiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean?,
    action: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    tone: Tone = Tone.PRIMARY,
    onClick: () -> Unit,
) {
    DockListItem(
        headline = title,
        supporting = subtitle,
        icon = icon,
        iconTone = tone,
        modifier = modifier,
        trailing = {
            if (granted == true) DockStatusPill(t("On"))
            else DockButton(text = action, onClick = onClick, kind = ButtonKind.TONAL, height = 48.dp)
        },
    )
}

/** A row whose whole surface toggles a [DockSwitch]. */
@Composable
internal fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.PRIMARY,
    enabled: Boolean = true,
) {
    DockListItem(
        headline = title,
        supporting = subtitle,
        icon = icon,
        iconTone = tone,
        modifier = modifier,
        role = Role.Switch,
        onClick = if (enabled) ({ onChecked(!checked) }) else null,
        trailing = { DockSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}
