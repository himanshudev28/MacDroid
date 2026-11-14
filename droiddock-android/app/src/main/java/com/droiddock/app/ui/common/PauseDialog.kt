package com.droiddock.app.ui.common

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.droiddock.app.*
import com.droiddock.app.R
import com.droiddock.app.ui.home.*
import com.droiddock.app.ui.pairing.*
import com.droiddock.app.ui.clipboard.*
import com.droiddock.app.ui.files.*
import com.droiddock.app.ui.macfiles.*
import com.droiddock.app.ui.remote.*
import com.droiddock.app.ui.mirror.*
import com.droiddock.app.ui.settings.*
import com.droiddock.app.ui.setup.*
import com.droiddock.app.ui.guide.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.CardTone
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockEnter
import com.droiddock.app.ui.theme.dockEnterTransition
import com.droiddock.app.ui.theme.dockExitTransition
import kotlinx.coroutines.delay

/**
 * Pause sheet: a dialog whose scrim fades and whose card docks in with the current style's enter transition
 * (and leaves with its exit), options stagger in. Dismiss plays the exit before telling the caller.
 */
@Composable
internal fun PauseDialog(onDismiss: () -> Unit, onPause: (Long?) -> Unit) {
    val motion = DockTheme.motion
    val sp = DockTheme.spacing
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // Hand control back first, then let the exit play on its own: if the dialog leaves composition mid-exit
    // (rotation), the choice has already been delivered.
    val finish: (() -> Unit) -> Unit = { after ->
        if (shown) {
            shown = false
            after()
        }
    }
    val scrimAlpha by animateFloatAsState(if (shown) 0.5f else 0f, motion.fade(), label = "pauseScrim")
    Dialog(
        onDismissRequest = { finish(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The platform dialog dims the window by itself; our own scrim replaces that, so turn it off.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish(onDismiss) }
                .padding(horizontal = sp.gutter),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(shown, enter = dockEnterTransition(), exit = dockExitTransition()) {
                // Swallow taps on the card so they do not hit the scrim behind it.
                DockCard(
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    tone = CardTone.RAISED,
                ) {
                    Text(
                        t("Pause DroidDock"), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(sp.s))
                    Text(
                        t("Stops auto-reconnect and tells your Mac to stop trying. The persistent notification stays silent."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(sp.l))
                    Column(verticalArrangement = Arrangement.spacedBy(sp.s)) {
                        PauseOption(t("Pause for 1 hour"), Modifier.dockEnter(0)) { finish { onPause(60L * 60 * 1000) } }
                        PauseOption(t("Pause for 8 hours"), Modifier.dockEnter(1)) { finish { onPause(8L * 60 * 60 * 1000) } }
                        PauseOption(t("Until I resume"), Modifier.dockEnter(2)) { finish { onPause(null) } }
                    }
                    DockButton(
                        t("Cancel"), { finish(onDismiss) }, kind = ButtonKind.GHOST, fullWidth = true,
                        modifier = Modifier.padding(top = sp.s).dockEnter(3),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PauseOption(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DockButton(label, onClick, modifier, kind = ButtonKind.TONAL, fullWidth = true, height = DockTheme.spacing.touch)
}

// ── Feature guide ──────────────────────────────────────────────────────────

internal fun pauseSubtitle(until: Long): String =
    if (until == Long.MAX_VALUE) t("Until you resume")
    else t("Resumes {time}", "time" to java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(until)))
