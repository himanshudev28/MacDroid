package com.droiddock.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.droiddock.app.ui.common.*
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.droiddock.app.ui.onboarding.Onboarding
import com.droiddock.app.ui.onboarding.OnboardingFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.AppearanceStore
import com.droiddock.app.ui.theme.NavLayout
import com.droiddock.app.ui.shell.*
import com.droiddock.app.ui.components.HeroState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Stable

/** Home tab, with its live link state collected here rather than threaded down from the screen. */
@Composable
internal fun HomeDest(
    layout: NavLayout,
    paired: Boolean,
    activeToken: String?,
    onPause: () -> Unit,
    onPickDevice: (KnownDevice) -> Unit,
    onForgetActive: () -> Unit,
    onGoToConnect: () -> Unit,
    onGoToFiles: () -> Unit,
) {
    val ctx = LocalContext.current
    val connected by ConnectionManager.connected.collectAsStateWithLifecycle()
    val macName by ConnectionManager.macName.collectAsStateWithLifecycle()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsStateWithLifecycle()
    val macCaps by ConnectionManager.macCaps.collectAsStateWithLifecycle()
    val macInfo by ConnectionManager.macInfo.collectAsStateWithLifecycle()
    val knownDevices by ConnectionManager.knownDevices.collectAsStateWithLifecycle()
    val disconnected by ConnectionManager.manuallyDisconnected.collectAsStateWithLifecycle()
    val event by ConnectionManager.lastEvent.collectAsStateWithLifecycle()
    val macMedia by ConnectionManager.macMedia.collectAsStateWithLifecycle()
    val transport by ConnectionManager.transport.collectAsStateWithLifecycle()
    val secure by LinkSecurity.encrypted.collectAsStateWithLifecycle()
    val identityChanged by LinkSecurity.identityChanged.collectAsStateWithLifecycle()
    val securityProblem by LinkSecurity.problem.collectAsStateWithLifecycle()
    HomeTab(
        transport = transport,
        secure = secure,
        identityChanged = identityChanged,
        securityProblem = securityProblem,
        connected = connected,
        isPaused = pausedUntil != 0L,
        paired = paired,
        macName = macName,
        macInfo = macInfo,
        event = event,
        pausedUntil = pausedUntil,
        devices = knownDevices,
        activeToken = activeToken,
        disconnected = disconnected,
        canControlMac = macCaps.contains("remote"),
        macMedia = macMedia,
        onPause = onPause,
        onResume = { ConnectionManager.resume(ctx) },
        onQuickConnect = { ConnectionManager.quickConnect(ctx) },
        onDisconnect = { ConnectionManager.disconnect(ctx) },
        onPickDevice = onPickDevice,
        onRemoveDevice = { device ->
            if (device.token == activeToken) {
                onForgetActive()
            } else {
                // Not the Mac we're linked to: drop its entry (and its pin) only.
                ConnectionManager.forget(ctx, device.token)
                Toast.makeText(ctx, t("Removed {name}", "name" to device.macName), Toast.LENGTH_SHORT).show()
            }
        },
        onGoToConnect = onGoToConnect,
        onGoToFiles = onGoToFiles,
        clipboard = if (layout == NavLayout.CLASSIC_5) null else ({
            val clipHistory by ConnectionManager.clipHistory.collectAsStateWithLifecycle()
            val shell = LocalShellActions.current
            HomeClipboardCard(clipHistory, connected, onOpen = { shell.openClipboard() })
        }),
    )
}

@Composable
internal fun ConnectDest(
    paired: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onForget: () -> Unit,
) {
    val connected by ConnectionManager.connected.collectAsStateWithLifecycle()
    val macName by ConnectionManager.macName.collectAsStateWithLifecycle()
    ConnectTab(
        onBack = onBack, connected = connected, paired = paired, macName = macName,
        onScan = onScan, onManual = onManual, onForget = onForget,
    )
}

@Composable
internal fun FilesDest(sending: Boolean, onSendFile: () -> Unit) {
    val ctx = LocalContext.current
    val connected by ConnectionManager.connected.collectAsStateWithLifecycle()
    val macCaps by ConnectionManager.macCaps.collectAsStateWithLifecycle()
    val activeTransfers by TransferManager.activeTransfers.collectAsStateWithLifecycle()
    val recentTransfers by TransferManager.recentTransfers.collectAsStateWithLifecycle()
    FilesScreen(
        macFsAvailable = macCaps.contains("macfs"),
        connected = connected,
    ) {
        FilesTab(
            connected = connected,
            sending = sending,
            activeTransfers = activeTransfers,
            recentTransfers = recentTransfers,
            onSendFile = onSendFile,
            onSendClipboard = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = readClipboardText(cm, ctx)
                val sent = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
                Toast.makeText(
                    ctx,
                    when {
                        text.isEmpty() -> t("Clipboard is empty")
                        sent -> t("Sent to Mac")
                        else -> t("Not connected to Mac")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
        )
    }
}

@Composable
internal fun ClipboardDest(keepHistory: PrefState<Boolean>) {
    val connected by ConnectionManager.connected.collectAsStateWithLifecycle()
    val clipHistory by ConnectionManager.clipHistory.collectAsStateWithLifecycle()
    ClipboardTab(
        connected = connected,
        history = clipHistory,
        keepHistory = keepHistory.value,
        onKeepHistory = { v ->
            keepHistory.set(v)
            if (!v) ConnectionManager.clearClipHistory()
        },
    )
}

@Composable
internal fun ControlDest(overlayOk: Boolean, autoMirror: PrefState<Boolean>) {
    val ctx = LocalContext.current
    val connected by ConnectionManager.connected.collectAsStateWithLifecycle()
    val macCaps by ConnectionManager.macCaps.collectAsStateWithLifecycle()
    ControlScreen(
        remoteAvailable = macCaps.contains("remote"),
        macAppsAvailable = macCaps.contains("macapps"),
        connected = connected,
    ) {
        MirrorTab(
            connected = connected,
            overlayOk = overlayOk,
            autoMirror = autoMirror.value,
            onEnableOverlay = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                Toast.makeText(ctx, t("Allow DroidDock to display over other apps"), Toast.LENGTH_LONG).show()
            },
            onToggleAutoMirror = { v ->
                autoMirror.set(v)
                if (!v) MirrorService.stop(ctx)
            },
        )
    }
}

@Composable
internal fun SettingsDest(
    perms: PermissionSnapshot,
    clipAuto: PrefState<Boolean>,
    expandNet: PrefState<Boolean>,
    crashNotify: PrefState<Boolean>,
    defaultTab: PrefState<String>,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    pitchBlack: Boolean,
    onPitchBlack: (Boolean) -> Unit,
    onOpenConnect: () -> Unit,
    onGrantPhonePerms: () -> Unit,
    onOpenGuide: () -> Unit,
    knownUpdate: UpdateChecker.Release?,
) {
    val ctx = LocalContext.current
    SettingsTab(
        clipA11y = perms.clipA11y,
        clipAuto = clipAuto.value,
        notifAccess = perms.notifAccess,
        phonePerms = perms.phonePerms,
        allFiles = perms.allFiles,
        deviceAdmin = perms.deviceAdmin,
        themeMode = themeMode,
        onThemeMode = onThemeMode,
        pitchBlack = pitchBlack,
        onPitchBlack = onPitchBlack,
        onOpenConnect = onOpenConnect,
        crashNotify = crashNotify.value,
        onCrashNotify = crashNotify::set,
        defaultTab = defaultTab.value,
        onDefaultTab = defaultTab::set,
        expandNet = expandNet.value,
        onExpandNet = expandNet::set,
        onEnableClip = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Toast.makeText(ctx, t("Find DroidDock Clipboard and turn it on"), Toast.LENGTH_LONG).show()
        },
        onToggleClipAuto = clipAuto::set,
        onEnableNotif = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        },
        onGrantPhonePerms = onGrantPhonePerms,
        onGrantFiles = {
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                    )
                } else {
                    // Below Android 11 there is no all-files switch to open.
                    Toast.makeText(ctx, t("All-files access isn't needed on this Android version"), Toast.LENGTH_SHORT).show()
                }
            }
        },
        onBattery = {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }
        },
        onOpenGuide = onOpenGuide,
        knownUpdate = knownUpdate,
    )
}
