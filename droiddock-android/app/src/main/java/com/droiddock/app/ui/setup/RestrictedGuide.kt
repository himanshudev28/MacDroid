package com.droiddock.app.ui.setup

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockEnterTransition
import com.droiddock.app.ui.theme.dockExitTransition

/**
 * Help for Android 13+'s "restricted settings" under a switch that lives in system Settings (notification access,
 * accessibility). Shown only while that switch is still off: opens by itself when [RestrictedSettings] says the app is
 * restricted, otherwise it waits behind a small "Switch greyed out?" link (detection is best effort, see there).
 *
 * @param switchOff the feature this row turns on is still off. When it flips on, the guide folds away.
 */
@Composable
internal fun RestrictedSettingsHint(switchOff: Boolean, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 33 || !switchOff) return
    val ctx = LocalContext.current
    var verdict by remember { mutableStateOf(RestrictedSettings.check(ctx)) }
    // Re-read on every return from App info: allowing it there flips the verdict.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { verdict = RestrictedSettings.check(ctx) }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val showGuide = open || verdict == RestrictedSettings.Verdict.RESTRICTED

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(!showGuide, enter = dockEnterTransition(), exit = dockExitTransition()) {
            DockButton(t("Switch greyed out?"), { open = true }, kind = ButtonKind.GHOST, height = 48.dp)
        }
        AnimatedVisibility(showGuide, enter = dockEnterTransition(), exit = dockExitTransition()) {
            Guide(onOpenAppInfo = { RestrictedSettings.openAppInfo(ctx) })
        }
    }
}

@Composable
private fun Guide(onOpenAppInfo: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val sp = DockTheme.spacing
    Column(Modifier.fillMaxWidth().padding(top = sp.s), verticalArrangement = Arrangement.spacedBy(sp.s)) {
        Text(
            t("Android is holding this switch back"), style = MaterialTheme.typography.titleSmall, color = s.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            t("Apps installed from a downloaded file need one extra step before Android lets you turn this on."),
            style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant,
        )
        listOf(
            t("Tap the greyed-out switch once, then close the message"),
            t("Open App info and tap ⋮ in the top corner"),
            t("Choose Allow restricted settings and confirm"),
            t("Come back here and turn the switch on"),
        ).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).background(s.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = s.onPrimaryContainer)
                }
                Spacer(Modifier.width(sp.m))
                Text(step, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        DockButton(
            t("Open App info"), onOpenAppInfo, kind = ButtonKind.TONAL,
            icon = Icons.AutoMirrored.Rounded.OpenInNew, height = 48.dp,
        )
    }
}
