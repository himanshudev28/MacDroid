package com.droiddock.app.ui.settings

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
import com.droiddock.app.ui.common.*
import com.droiddock.app.ui.home.*
import com.droiddock.app.ui.pairing.*
import com.droiddock.app.ui.clipboard.*
import com.droiddock.app.ui.files.*
import com.droiddock.app.ui.macfiles.*
import com.droiddock.app.ui.remote.*
import com.droiddock.app.ui.mirror.*
import com.droiddock.app.ui.setup.*
import com.droiddock.app.ui.guide.*

/**
 * Check → download → install, in one row that never moves.
 *
 * Built on [ServiceRow] so it sits in the Settings list exactly like the
 * permission rows above it: same badge, same chip, same shape. The whole state
 * machine is one sealed hierarchy rather than a handful of booleans — a flag
 * soup lets "downloading" and "failed" both be true, which is a state this row
 * has no sensible way to draw.
 *
 * The install path needs "install unknown apps", which is a per-app grant the
 * user makes in system Settings. Rather than asking for it up front — before
 * there is even an update to install — the row only routes there at the moment
 * it's actually needed.
 */
@Composable
internal fun UpdateRow(known: UpdateChecker.Release?) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    // A background find is worth exactly as much as a manual one, so it lands
    // in the same state. Guarded so it can't interrupt a download already
    // running when the app-open check finally answers.
    LaunchedEffect(known) {
        if (known != null && state is UpdateUi.Idle) state = UpdateUi.Available(known)
    }

    val canInstall = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    val subtitle = when (val s = state) {
        is UpdateUi.Idle        -> stringResource(R.string.update_idle)
        is UpdateUi.Checking    -> stringResource(R.string.update_checking)
        is UpdateUi.Current     -> stringResource(R.string.update_current)
        is UpdateUi.Available   ->
            if (canInstall) stringResource(R.string.update_available, s.release.version)
            else stringResource(R.string.update_needs_permission)
        is UpdateUi.Downloading -> stringResource(R.string.update_downloading, s.release.version, s.percent)
        is UpdateUi.Ready       -> stringResource(R.string.update_ready, s.release.version)
        is UpdateUi.Blocked     -> stringResource(R.string.update_signed_differently, s.release.version)
        is UpdateUi.Failed      -> stringResource(R.string.update_failed, s.message)
    }

    val action = when (state) {
        is UpdateUi.Available   -> if (canInstall) stringResource(R.string.update_action_download)
                                   else stringResource(R.string.update_action_allow)
        is UpdateUi.Ready       -> stringResource(R.string.update_action_install)
        is UpdateUi.Blocked     -> stringResource(R.string.update_action_get)
        else                    -> stringResource(R.string.update_action_check)
    }

    val busy = state is UpdateUi.Checking || state is UpdateUi.Downloading

    ServiceRow(
        icon     = Icons.Outlined.SystemUpdate,
        tone     = com.droiddock.app.ui.components.Tone.SECONDARY,
        title    = stringResource(R.string.update_title),
        subtitle = subtitle,
        granted  = null,
        action   = action,
    ) {
        // A second tap while a download is in flight would start a second one.
        if (busy) return@ServiceRow
        when (val s = state) {
            is UpdateUi.Available -> {
                if (!canInstall) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                    return@ServiceRow
                }
                scope.launch {
                    state = UpdateUi.Downloading(s.release, 0)
                    runCatching {
                        UpdateChecker.download(ctx, s.release) { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            state = UpdateUi.Downloading(s.release, pct)
                        }
                    }.onSuccess { apk ->
                        // Checked here rather than at the install tap: this is
                        // the first moment the certificate can be read, and it
                        // is better to say why now than to draw an Install
                        // button whose only outcome is the system's
                        // "app isn't compatible with your phone".
                        state = if (UpdateChecker.signedLikeInstalled(ctx, apk))
                            UpdateUi.Ready(s.release, apk)
                        else
                            UpdateUi.Blocked(s.release)
                    }.onFailure { state = UpdateUi.Failed(it.message ?: "download failed") }
                }
            }

            is UpdateUi.Blocked -> {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return@ServiceRow
            }

            is UpdateUi.Ready -> scope.launch {
                // The system dialog UpdateInstallReceiver raises is the real
                // confirmation; on approval this process is replaced, so there
                // is no success state past here worth drawing.
                runCatching { UpdateChecker.install(ctx, s.apk) }
                    .onFailure { state = UpdateUi.Failed(it.message ?: "install failed") }
            }

            else -> scope.launch {
                state = UpdateUi.Checking
                runCatching { UpdateChecker.check() }
                    .onSuccess {
                        Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis())
                        state = if (it != null) UpdateUi.Available(it) else UpdateUi.Current
                    }
                    .onFailure { state = UpdateUi.Failed(it.message ?: "check failed") }
            }
        }
    }
}

/** Every state [UpdateRow] can be in, and nothing else. */
internal sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object Current : UpdateUi
    data class Available(val release: UpdateChecker.Release) : UpdateUi
    data class Downloading(val release: UpdateChecker.Release, val percent: Int) : UpdateUi
    data class Ready(val release: UpdateChecker.Release, val apk: File) : UpdateUi

    /**
     * Downloaded, but signed by a different key than the running app — an
     * in-place update is impossible, so the row stops offering one and explains
     * the manual route instead. See `UpdateChecker.signedLikeInstalled`.
     */
    data class Blocked(val release: UpdateChecker.Release) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}
