package com.droiddock.app

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

class MainActivity : ComponentActivity() {
    private val pairFlow = MutableStateFlow<String?>(null)

    /** Theme choice, hoisted here so the Settings picker can change it live
     *  rather than only on next launch. */

    override fun onStart() {
        super.onStart()
        com.droiddock.app.ui.theme.glass.GlassKillSwitch.onStart(applicationContext)
    }

    override fun onStop() {
        com.droiddock.app.ui.theme.glass.GlassKillSwitch.onStop(applicationContext)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any composition: `t` reads I18n.locale, and a composable that
        // reads it at its default value and then sees it change would recompose
        // for no reason on every cold start.
        I18n.load(applicationContext)
        enableEdgeToEdge()
        CrashNotifier.install(this)
        AppearanceStore.load(this)
        if (Prefs.load(this) != null) BridgeService.start(this)
        intent?.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
            ?.let { pairFlow.value = it }
        setContent {
            val pairUri by pairFlow.collectAsStateWithLifecycle()
            val appearance by AppearanceStore.state.collectAsStateWithLifecycle()
            // The app's language is its own setting, not the phone's, so Compose
            // cannot get the layout direction from the configuration the way it
            // normally would. Providing it here is what actually mirrors rows,
            // start/end padding and text alignment for Arabic — reading
            // I18n.isRtl() inside composition also means switching language
            // flips the layout without recreating the activity.
            CompositionLocalProvider(
                LocalLayoutDirection provides
                    if (I18n.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                DroidDockTheme(appearance) {
                    DroidDockScreen(
                        pairUri = pairUri,
                        clearPairUri = { pairFlow.value = null },
                        appearance = appearance,
                        onThemeMode = { mode -> AppearanceStore.update(this) { it.copy(themeMode = mode) } },
                        onPitchBlack = { v -> AppearanceStore.update(this) { it.copy(amoled = v) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
            ?.let { pairFlow.value = it }
    }
}

/**
 * Flips the status/navigation bar icons to match the theme.
 *
 * `enableEdgeToEdge()` draws the app behind both bars, so on a light page the
 * default light-on-transparent glyphs land on cream and vanish. This has to run
 * as a side effect rather than at `onCreate`, since the resolved theme can
 * change under us — SYSTEM mode following the OS, or the user picking another
 * option in Settings.
 */
@Composable
internal fun SystemBarsFollowTheme() {
    val dark = LocalDroidColors.current.isDark
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
internal fun DroidDockScreen(
    pairUri: String? = null,
    clearPairUri: () -> Unit = {},
    appearance: Appearance = Appearance(),
    onThemeMode: (ThemeMode) -> Unit = {},
    onPitchBlack: (Boolean) -> Unit = {},
) {
    val themeMode = appearance.themeMode
    val pitchBlack = appearance.amoled
    val ctx         = LocalContext.current
    val connected   by ConnectionManager.connected.collectAsStateWithLifecycle()
    val macName     by ConnectionManager.macName.collectAsStateWithLifecycle()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsStateWithLifecycle()
    val isPaused    = pausedUntil != 0L
    val macCaps     by ConnectionManager.macCaps.collectAsStateWithLifecycle()
    val macInfo     by ConnectionManager.macInfo.collectAsStateWithLifecycle()
    val knownDevices by ConnectionManager.knownDevices.collectAsStateWithLifecycle()
    val disconnected by ConnectionManager.manuallyDisconnected.collectAsStateWithLifecycle()

    var paired      by remember { mutableStateOf(Prefs.load(ctx) != null) }
    // First run only: anyone already paired (an upgrade) skips straight to the app.
    var onboarded   by remember { mutableStateOf(paired || Onboarding.isDone(ctx)) }
    /** Which known device the connect loop is currently pointed at. */
    var activeToken by remember { mutableStateOf(Prefs.load(ctx)?.token) }
    /** Forget the active Mac: drop its pairing and pin, then switch to another known Mac if there is one. */
    fun forgetActiveMac() {
        Prefs.clear(ctx) // drops the active pairing + its address-book entry
        ConnectionManager.shutdown()
        val remaining = Prefs.knownDevices(ctx)
        ConnectionManager.knownDevices.value = remaining
        activeToken = null
        paired = false
        // Another Mac is still known — keep the bridge up and
        // switch to it rather than dropping the user onto a
        // pairing screen they don't need.
        val next = remaining.firstOrNull()
        if (next != null) {
            ConnectionManager.connectTo(ctx, next)
            activeToken = next.token
            paired = true
        } else {
            BridgeService.stop(ctx)
        }
        Toast.makeText(ctx, t("Forgot this Mac"), Toast.LENGTH_SHORT).show()
    }
    var notifAccess by remember { mutableStateOf(notifAccessGranted(ctx)) }
    var phonePerms  by remember { mutableStateOf(phonePermsGranted(ctx)) }
    var allFiles    by remember { mutableStateOf(FileRepo.hasAllFiles()) }
    var clipA11y    by remember { mutableStateOf(clipAccessibilityEnabled(ctx)) }
    // Hoisted, and refreshed by the poll below rather than in composition:
    // `isAdminActive` is a binder round trip, and its only consumer sits inside
    // a lazily-composed Settings row that re-enters composition every time it
    // scrolls back into view. Seeded once here — like `clipA11y` above — so the
    // row's first frame is already right instead of flashing "Grant" at someone
    // who granted it months ago.
    var deviceAdmin by remember { mutableStateOf(LockAdmin.isActive(ctx)) }
    var clipAuto    by remember { mutableStateOf(Prefs.clipboardAuto(ctx)) }
    var overlayOk   by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var autoMirror  by remember { mutableStateOf(Prefs.autoMirror(ctx)) }
    var expandNet   by remember { mutableStateOf(Prefs.expandNetworking(ctx)) }
    var clipKeepHistory by remember { mutableStateOf(Prefs.clipboardHistory(ctx)) }
    var defaultTab  by remember { mutableStateOf(Prefs.defaultTab(ctx)) }
    var crashNotify by remember { mutableStateOf(Prefs.notifyOnCrash(ctx)) }
    var showManual  by rememberSaveable { mutableStateOf(false) }
    var showGuide   by rememberSaveable { mutableStateOf(false) }
    var showPause   by rememberSaveable { mutableStateOf(false) }
    // Found by the throttled check below. Its only job out here is the dot on
    // the Settings nav item — the row that acts on it lives in SettingsTab.
    var newRelease  by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var sending         by remember { mutableStateOf(false) }
    // Resolved once, at first composition, from the saved preference. "dynamic"
    // reads the link state rather than a fixed choice: land on Connect when
    // there's nothing linked to act on, Home when there is. Evaluated eagerly
    // rather than in an effect so the first frame is already the right tab —
    // a visible flick from Home to Connect would be worse than either.
    // Saveable: rotation recreates the Activity, and this used to fall back to Home.
    var currentTab by rememberSaveable {
        mutableStateOf(
            when (val saved = Prefs.defaultTab(ctx)) {
                "dynamic" -> if (Prefs.load(ctx) == null) "connect" else "home"
                "clipboard" -> if (appearance.layout == NavLayout.CLASSIC_5) "clipboard" else "home"
                else -> saved
            }
        )
    }
    // Switching layout live: Clipboard is a page only in Classic 5.
    LaunchedEffect(appearance.layout) {
        if (currentTab == "clipboard" && appearance.layout != NavLayout.CLASSIC_5) currentTab = "home"
    }

    // The "macfs" tab only exists while the connected Mac advertises the "macfs" cap
    // (Phase 19). If it disappears — reconnect to an older Mac build — bounce off the
    // tab immediately rather than leaving it selected but absent from the nav bar.
    // Mac Files and Remote are no longer destinations of their own — they're
    // sub-views of Files and Control, which handle a capability disappearing
    // themselves. Nothing left to bounce off.


    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sending = true
        ConnectionManager.sendFileToMac(uri, ctx) { ok, err ->
            sending = false
            Toast.makeText(
                ctx,
                if (ok) "File sent to Mac" else (err ?: "Send failed"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val applyPairing: (Pairing, Boolean) -> Unit = { pairing, fromQr ->
        BridgeService.start(ctx)
        LinkSecurity.clearProblems()
        ConnectionManager.onPaired(ctx, pairing, fromQr)
        paired = true
        activeToken = pairing.token
        Toast.makeText(ctx, "Paired with ${pairing.macName}", Toast.LENGTH_SHORT).show()
    }

    // Permission state, re-read only while a screen that shows it is open.
    //
    // This was the app's main source of jank: five synchronous binder round
    // trips — two `Settings.Secure` reads, `canDrawOverlays`,
    // `isExternalStorageManager` and five permission checks — running on the
    // **main thread** every 2s, forever, no matter which tab was on screen. A
    // tab switch landing on one of those ticks stalled the frame that was
    // trying to draw the new screen. Now: off the main thread, and only for the
    // two screens that actually render these values.
    // Only while the app is on screen: a backgrounded activity keeps its composition, and this loop with it.
    val permLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(currentTab) {
        if (currentTab != "settings" && currentTab != "control") return@LaunchedEffect
        permLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                PermissionSnapshot(
                    notifAccess = notifAccessGranted(ctx),
                    phonePerms  = phonePermsGranted(ctx),
                    allFiles    = FileRepo.hasAllFiles(),
                    clipA11y    = clipAccessibilityEnabled(ctx),
                    overlayOk   = Settings.canDrawOverlays(ctx),
                    deviceAdmin = LockAdmin.isActive(ctx),
                )
            }
            notifAccess = snapshot.notifAccess
            phonePerms  = snapshot.phonePerms
            allFiles    = snapshot.allFiles
            clipA11y    = snapshot.clipA11y
            overlayOk   = snapshot.overlayOk
            deviceAdmin = snapshot.deviceAdmin
            kotlinx.coroutines.delay(3_000)
        } }
    }

    val handleQr: (String) -> Unit = { raw ->
        runCatching {
            LinkTls.parsePairingQr(raw)
        }.onSuccess { pairing ->
            applyPairing(pairing, true) // a scanned QR is authoritative, including "no fp"
        }.onFailure {
            Toast.makeText(ctx, t("Not a DroidDock QR code"), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pairUri) {
        pairUri ?: return@LaunchedEffect
        clearPairUri()
        handleQr(pairUri)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleQr(it) }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
        else Toast.makeText(ctx, t("Camera permission needed to scan QR"), Toast.LENGTH_SHORT).show()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { phonePerms = phonePermsGranted(ctx) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // On a first run the onboarding Permissions page asks instead, with context.
    LaunchedEffect(onboarded) {
        if (onboarded && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Look for a new release once a day, silently. Nothing downloads here — a
    // dot appears on Settings and that is the whole of it, because installing
    // restarts the app and that is never something to do unprompted.
    //
    // A failure is deliberately swallowed: the user didn't ask, and the manual
    // button in Settings reports properly when they do.
    LaunchedEffect(Unit) {
        if (!Prefs.autoCheckUpdates(ctx)) return@LaunchedEffect
        val since = System.currentTimeMillis() - Prefs.lastUpdateCheck(ctx)
        if (since < UpdateChecker.CHECK_INTERVAL_MS) return@LaunchedEffect
        runCatching { UpdateChecker.check() }
            .onSuccess {
                Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis())
                newRelease = it
            }
    }

    val launchScan = {
        val hasCam = ctx.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCam) scanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    // A pairing deep link opened during onboarding finishes it.
    LaunchedEffect(paired) {
        if (paired && !onboarded) { Onboarding.markDone(ctx); onboarded = true }
    }

    if (!onboarded) {
        OnboardingFlow(onFinish = { pairNow ->
            Onboarding.markDone(ctx)
            onboarded = true
            if (pairNow && !paired) { currentTab = "connect"; launchScan() }
        })
        return
    }

    if (showGuide) {
        FeatureGuideScreen(onBack = { showGuide = false })
        return
    }

    if (showManual) {
        ManualPairDialog(onDismiss = { showManual = false }) { pairing ->
            showManual = false; applyPairing(pairing, false)
        }
    }

    if (showPause) {
        PauseDialog(onDismiss = { showPause = false }) { durationMs ->
            showPause = false; ConnectionManager.pause(ctx, durationMs)
        }
    }

    AppShell(
        layout = appearance.layout,
        current = currentTab,
        onSelect = { currentTab = it },
        settingsBadge = newRelease != null,
        hubHome = {
            HubDest(
                connected = connected, paired = paired, isPaused = isPaused, macName = macName, macInfo = macInfo,
                onPause = { showPause = true },
                onResume = { ConnectionManager.resume(ctx) },
                onQuickConnect = { ConnectionManager.quickConnect(ctx) },
                onGoToConnect = { currentTab = "connect" },
                onNavigate = { currentTab = it },
                settingsBadge = newRelease != null,
            )
        },
    ) { tab ->
        when (tab) {
                "home" -> {
                    val event by ConnectionManager.lastEvent.collectAsStateWithLifecycle()
                    val macMedia by ConnectionManager.macMedia.collectAsStateWithLifecycle()
                    val transport by ConnectionManager.transport.collectAsStateWithLifecycle()
                    val secure by LinkSecurity.encrypted.collectAsStateWithLifecycle()
                    val identityChanged by LinkSecurity.identityChanged.collectAsStateWithLifecycle()
                    val securityProblem by LinkSecurity.problem.collectAsStateWithLifecycle()
                    HomeTab(
                    transport   = transport,
                    secure      = secure,
                    identityChanged = identityChanged,
                    securityProblem = securityProblem,
                    connected   = connected,
                    isPaused    = isPaused,
                    paired      = paired,
                    macName     = macName,
                    macInfo     = macInfo,
                    event       = event,
                    pausedUntil = pausedUntil,
                    devices     = knownDevices,
                    activeToken = activeToken,
                    disconnected = disconnected,
                    canControlMac = macCaps.contains("remote"),
                    macMedia    = macMedia,
                    onPause     = { showPause = true },
                    onResume    = { ConnectionManager.resume(ctx) },
                    onQuickConnect = { ConnectionManager.quickConnect(ctx) },
                    onDisconnect   = { ConnectionManager.disconnect(ctx) },
                    onPickDevice   = { device ->
                        ConnectionManager.connectTo(ctx, device)
                        activeToken = device.token
                        paired = true
                    },
                    onRemoveDevice = { device ->
                        if (device.token == activeToken) {
                            forgetActiveMac()
                        } else {
                            // Not the Mac we're linked to: drop its entry (and its pin) only.
                            ConnectionManager.forget(ctx, device.token)
                            Toast.makeText(ctx, t("Removed {name}", "name" to device.macName), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onGoToConnect = { currentTab = "connect" },
                    onGoToFiles   = { currentTab = "files" },
                    clipboard = if (appearance.layout == NavLayout.CLASSIC_5) null else ({
                        val clipHistory by ConnectionManager.clipHistory.collectAsStateWithLifecycle()
                        val shell = LocalShellActions.current
                        HomeClipboardCard(clipHistory, connected, onOpen = { shell.openClipboard() })
                    }),
                    )
                }
                "connect" -> ConnectTab(
                    onBack      = { currentTab = "home" },
                    connected   = connected,
                    paired      = paired,
                    macName     = macName,
                    onScan      = launchScan,
                    onManual    = { showManual = true },
                    onForget    = { forgetActiveMac() },
                )
                "files" -> {
                val activeTransfers by TransferManager.activeTransfers.collectAsStateWithLifecycle()
                val recentTransfers by TransferManager.recentTransfers.collectAsStateWithLifecycle()
                FilesScreen(
                    macFsAvailable = macCaps.contains("macfs"),
                    connected      = connected,
                ) { FilesTab(
                    connected       = connected,
                    sending         = sending,
                    activeTransfers = activeTransfers,
                    recentTransfers = recentTransfers,
                    onSendFile = { if (!sending) filePicker.launch("*/*") },
                    onSendClipboard = {
                        val cm   = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = readClipboardText(cm, ctx)
                        val sent = text.isNotEmpty() && ConnectionManager.sendClipboardText(text)
                        Toast.makeText(
                            ctx,
                            when {
                                text.isEmpty() -> "Clipboard is empty"
                                sent -> "Sent to Mac"
                                else -> "Not connected to Mac"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                ) }
                }
                "clipboard" -> {
                val clipHistory by ConnectionManager.clipHistory.collectAsStateWithLifecycle()
                ClipboardTab(
                    connected     = connected,
                    history       = clipHistory,
                    keepHistory   = clipKeepHistory,
                    onKeepHistory = { v ->
                        clipKeepHistory = v
                        Prefs.setClipboardHistory(ctx, v)
                        if (!v) ConnectionManager.clearClipHistory()
                    },
                )
                }
                "control" -> ControlScreen(
                    remoteAvailable  = macCaps.contains("remote"),
                    macAppsAvailable = macCaps.contains("macapps"),
                    connected        = connected,
                ) { MirrorTab(
                    connected   = connected,
                    overlayOk   = overlayOk,
                    autoMirror  = autoMirror,
                    onEnableOverlay = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                        Toast.makeText(ctx, t("Allow DroidDock to display over other apps"), Toast.LENGTH_LONG).show()
                    },
                    onToggleAutoMirror = { v ->
                        autoMirror = v; Prefs.setAutoMirror(ctx, v)
                        if (!v) MirrorService.stop(ctx)
                    },
                ) }
                "settings" -> SettingsTab(
                    clipA11y    = clipA11y,
                    clipAuto    = clipAuto,
                    notifAccess = notifAccess,
                    phonePerms  = phonePerms,
                    allFiles    = allFiles,
                    deviceAdmin = deviceAdmin,
                    themeMode   = themeMode,
                    onThemeMode = onThemeMode,
                    pitchBlack  = pitchBlack,
                    onPitchBlack = onPitchBlack,
                    onOpenConnect = { currentTab = "connect" },
                    crashNotify = crashNotify,
                    onCrashNotify = { v ->
                        crashNotify = v
                        Prefs.setNotifyOnCrash(ctx, v)
                    },
                    defaultTab  = defaultTab,
                    onDefaultTab = { tab ->
                        defaultTab = tab
                        Prefs.setDefaultTab(ctx, tab)
                    },
                    expandNet   = expandNet,
                    onExpandNet = { v ->
                        expandNet = v
                        Prefs.setExpandNetworking(ctx, v)
                    },
                    onEnableClip = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        Toast.makeText(ctx, t("Find DroidDock Clipboard and turn it on"), Toast.LENGTH_LONG).show()
                    },
                    onToggleClipAuto = { v -> clipAuto = v; Prefs.setClipboardAuto(ctx, v) },
                    onEnableNotif = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    },
                    onGrantPhonePerms = { permLauncher.launch(PHONE_PERMS) },
                    onGrantFiles = {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= 30) {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .setData(Uri.parse("package:${ctx.packageName}"))
                                )
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
                    onOpenGuide = { showGuide = true },
                    knownUpdate = newRelease,
                )
            else -> Unit
        }
    }
}

/** Hub layout's home: hero + big tiles, fed from the same state as Home. */
@Composable
private fun HubDest(
    connected: Boolean,
    paired: Boolean,
    isPaused: Boolean,
    macName: String?,
    macInfo: ConnectionManager.MacInfo?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onQuickConnect: () -> Unit,
    onGoToConnect: () -> Unit,
    onNavigate: (String) -> Unit,
    settingsBadge: Boolean,
) {
    val state = when {
        !paired -> HeroState.UNPAIRED
        isPaused -> HeroState.PAUSED
        connected -> HeroState.CONNECTED
        else -> HeroState.OFFLINE
    }
    val status = when (state) {
        HeroState.UNPAIRED -> t("Not paired yet")
        HeroState.PAUSED -> t("Paused")
        HeroState.CONNECTED -> t("Connected")
        else -> t("Not connected")
    }
    val (label, icon, action) = when (state) {
        HeroState.UNPAIRED -> Triple(t("Pair a Mac"), Icons.Rounded.Add, onGoToConnect)
        HeroState.PAUSED -> Triple(t("Resume"), Icons.Rounded.PlayArrow, onResume)
        HeroState.CONNECTED -> Triple(t("Pause"), Icons.Rounded.Pause, onPause)
        else -> Triple(t("Connect"), Icons.Rounded.Link, onQuickConnect)
    }
    val hero = HeroModel(
        title = macName ?: t("DroidDock"), status = status, state = state,
        battery = macInfo?.battery, charging = macInfo?.charging ?: false,
    )
    val tiles = listOf(
        HubTileModel("files", t("Files"), Icons.Rounded.Folder),
        HubTileModel("clipboard", t("Clipboard"), Icons.Rounded.ContentPaste),
        HubTileModel("control", t("Control"), Icons.Rounded.ScreenShare),
        HubTileModel("connect", t("Devices"), Icons.Rounded.Link),
    )
    HubHome(
        appName = t("DroidDock"), hero = hero, tiles = tiles,
        onTile = onNavigate, onSettings = { onNavigate("settings") }, settingsLabel = t("Settings"),
        settingsBadge = settingsBadge, primaryLabel = label, primaryIcon = icon, onPrimary = action,
    )
}

