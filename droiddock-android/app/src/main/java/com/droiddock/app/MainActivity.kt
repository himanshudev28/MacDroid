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

// The palette these names used to hold now lives in Theme.kt, one instance per
// theme. Reading them through the CompositionLocal keeps every `color = Fg` in
// this file working untouched while making all of them theme-aware; the cost is
// that a colour is only legible from inside a composable, which the compiler
// enforces for us.
private val Ink:       Color @Composable get() = LocalDroidColors.current.ink
private val Surface1:  Color @Composable get() = LocalDroidColors.current.surface1
private val Surface2:  Color @Composable get() = LocalDroidColors.current.surface2
private val Surface3:  Color @Composable get() = LocalDroidColors.current.surface3
private val Amber:     Color @Composable get() = LocalDroidColors.current.amber
private val AmberDim:  Color @Composable get() = LocalDroidColors.current.amberDim
private val OnAmber:   Color @Composable get() = LocalDroidColors.current.onAmber
private val Ok:        Color @Composable get() = LocalDroidColors.current.ok
private val OnOk:      Color @Composable get() = LocalDroidColors.current.onOk
private val Bad:       Color @Composable get() = LocalDroidColors.current.bad
private val Fg:        Color @Composable get() = LocalDroidColors.current.fg
private val Dim:       Color @Composable get() = LocalDroidColors.current.dim
private val LineColor: Color @Composable get() = LocalDroidColors.current.line
private val Purple:    Color @Composable get() = LocalDroidColors.current.purple
private val Blue:      Color @Composable get() = LocalDroidColors.current.blue
private val Orange:    Color @Composable get() = LocalDroidColors.current.orange

class MainActivity : ComponentActivity() {
    private val pairFlow = MutableStateFlow<String?>(null)

    /** Theme choice, hoisted here so the Settings picker can change it live
     *  rather than only on next launch. */
    private val themeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    private val pitchBlackFlow = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashNotifier.install(this)
        themeFlow.value = Prefs.themeMode(this)
        pitchBlackFlow.value = Prefs.pitchBlack(this)
        if (Prefs.load(this) != null) BridgeService.start(this)
        intent?.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
            ?.let { pairFlow.value = it }
        setContent {
            val pairUri by pairFlow.collectAsState()
            val theme by themeFlow.collectAsState()
            val pitchBlack by pitchBlackFlow.collectAsState()
            DroidDockTheme(theme, pitchBlack) {
                SystemBarsFollowTheme()
                DroidDockScreen(
                    pairUri = pairUri,
                    clearPairUri = { pairFlow.value = null },
                    themeMode = theme,
                    onThemeMode = { mode ->
                        Prefs.setThemeMode(this, mode)
                        themeFlow.value = mode
                    },
                    pitchBlack = pitchBlack,
                    onPitchBlack = { v ->
                        Prefs.setPitchBlack(this, v)
                        pitchBlackFlow.value = v
                    },
                )
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
private fun SystemBarsFollowTheme() {
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
private fun DroidDockScreen(
    pairUri: String? = null,
    clearPairUri: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
    pitchBlack: Boolean = false,
    onPitchBlack: (Boolean) -> Unit = {},
) {
    val ctx         = LocalContext.current
    val connected   by ConnectionManager.connected.collectAsState()
    val macName     by ConnectionManager.macName.collectAsState()
    val event       by ConnectionManager.lastEvent.collectAsState()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsState()
    val isPaused    = pausedUntil != 0L
    val macCaps     by ConnectionManager.macCaps.collectAsState()
    val macInfo     by ConnectionManager.macInfo.collectAsState()
    val macMedia    by ConnectionManager.macMedia.collectAsState()
    val knownDevices by ConnectionManager.knownDevices.collectAsState()
    val disconnected by ConnectionManager.manuallyDisconnected.collectAsState()

    var paired      by remember { mutableStateOf(Prefs.load(ctx) != null) }
    /** Which known device the connect loop is currently pointed at. */
    var activeToken by remember { mutableStateOf(Prefs.load(ctx)?.token) }
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
    val clipHistory by ConnectionManager.clipHistory.collectAsState()
    var showManual  by remember { mutableStateOf(false) }
    var showGuide   by remember { mutableStateOf(false) }
    var showPause   by remember { mutableStateOf(false) }
    // Found by the throttled check below. Its only job out here is the dot on
    // the Settings nav item — the row that acts on it lives in SettingsTab.
    var newRelease  by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var sending         by remember { mutableStateOf(false) }
    val activeTransfers by TransferManager.activeTransfers.collectAsState()
    val recentTransfers by TransferManager.recentTransfers.collectAsState()
    // Resolved once, at first composition, from the saved preference. "dynamic"
    // reads the link state rather than a fixed choice: land on Connect when
    // there's nothing linked to act on, Home when there is. Evaluated eagerly
    // rather than in an effect so the first frame is already the right tab —
    // a visible flick from Home to Connect would be worse than either.
    var currentTab by remember {
        mutableStateOf(
            when (val saved = Prefs.defaultTab(ctx)) {
                "dynamic" -> if (Prefs.load(ctx) == null) "connect" else "home"
                else -> saved
            }
        )
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

    val applyPairing: (Pairing) -> Unit = { pairing ->
        BridgeService.start(ctx)
        ConnectionManager.onPaired(ctx, pairing)
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
    LaunchedEffect(currentTab) {
        if (currentTab != "settings" && currentTab != "control") return@LaunchedEffect
        while (true) {
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
            kotlinx.coroutines.delay(2_000)
        }
    }

    val handleQr: (String) -> Unit = { raw ->
        runCatching {
            if (raw.startsWith("droiddock://pair")) {
                val uri = Uri.parse(raw)
                val ips = (uri.getQueryParameter("ips") ?: "")
                    .split(",").filter { it.isNotBlank() }
                val token = uri.getQueryParameter("token") ?: ""
                require(ips.isNotEmpty() && token.isNotEmpty())
                Pairing(
                    ips,
                    uri.getQueryParameter("port")?.toIntOrNull() ?: 48484,
                    token,
                    uri.getQueryParameter("name") ?: "Mac"
                )
            } else {
                val o   = JSONObject(raw)
                val ips = mutableListOf<String>()
                val arr = o.optJSONArray("ips")
                if (arr != null) for (i in 0 until arr.length()) ips.add(arr.getString(i))
                require(ips.isNotEmpty() && o.has("token"))
                Pairing(ips, o.optInt("port", 48484), o.getString("token"), o.optString("name", "Mac"))
            }
        }.onSuccess { pairing ->
            applyPairing(pairing)
        }.onFailure {
            Toast.makeText(ctx, "Not a DroidDock QR code", Toast.LENGTH_SHORT).show()
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
        else Toast.makeText(ctx, "Camera permission needed to scan QR", Toast.LENGTH_SHORT).show()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { phonePerms = phonePermsGranted(ctx) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
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

    if (showGuide) {
        FeatureGuideScreen(onBack = { showGuide = false })
        return
    }

    if (showManual) {
        ManualPairDialog(onDismiss = { showManual = false }) { pairing ->
            showManual = false; applyPairing(pairing)
        }
    }

    if (showPause) {
        PauseDialog(onDismiss = { showPause = false }) { durationMs ->
            showPause = false; ConnectionManager.pause(ctx, durationMs)
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(
                containerColor = Surface1,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .navigationBarsPadding()
            ) {
                // Five fixed destinations.
                //
                // This had grown to eight — Home, Connect, Files, Clipboard,
                // Mac Files, Remote, Mirror, Settings — two of them appearing
                // and vanishing with the Mac's capabilities, so the bar
                // reshuffled under your thumb mid-session. Material's
                // NavigationBar is specified for three to five; at eight the
                // labels wrapped ("Clipboar/d") and every icon shrank.
                //
                // Nothing was removed, only regrouped: Mac Files is a source
                // toggle inside Files, Remote joins Mirror and Camera under
                // Control (they are all "the other device's screen"), and
                // Connect is reached from Home, since pairing is setup rather
                // than a daily destination.
                val navItems = listOf(
                    Triple("home",      "Home",      Icons.Filled.Home),
                    Triple("files",     "Files",     Icons.Outlined.Folder),
                    Triple("clipboard", "Clipboard", Icons.Outlined.ContentPaste),
                    Triple("control",   "Control",   Icons.Outlined.ScreenShare),
                    Triple("settings",  "Settings",  Icons.Outlined.Settings),
                )
                navItems.forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = currentTab == id,
                        onClick  = { currentTab = id },
                        icon     = {
                            // A dot, not a count: there is only ever one update,
                            // and a "1" here reads as an unread message.
                            if (id == "settings" && newRelease != null) {
                                BadgedBox(badge = { Badge(containerColor = Amber) }) {
                                    Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                                }
                            } else {
                                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                            }
                        },
                        label    = { Text(label, fontSize = 10.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = OnAmber,
                            selectedTextColor   = Amber,
                            indicatorColor      = Amber,
                            unselectedIconColor = Dim,
                            unselectedTextColor = Dim,
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Ink)
        ) {
            // A short crossfade, not a slide: tabs here have no left/right
            // relationship to imply, and 120ms is under the threshold where a
            // transition starts to feel like waiting. It also covers the one
            // frame a heavier screen needs to compose, which is what made
            // switching feel abrupt rather than slow.
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(120)) togetherWith
                        fadeOut(animationSpec = tween(90))
                },
                label = "tab"
            ) { tab ->
            when (tab) {
                "home" -> HomeTab(
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
                    onGoToConnect = { currentTab = "connect" },
                    onGoToFiles   = { currentTab = "files" },
                )
                "connect" -> ConnectTab(
                    onBack      = { currentTab = "home" },
                    connected   = connected,
                    paired      = paired,
                    macName     = macName,
                    onScan      = launchScan,
                    onManual    = { showManual = true },
                    onForget    = {
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
                        Toast.makeText(ctx, "Forgot this Mac", Toast.LENGTH_SHORT).show()
                    },
                )
                "files" -> FilesScreen(
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
                "clipboard" -> ClipboardTab(
                    connected     = connected,
                    history       = clipHistory,
                    keepHistory   = clipKeepHistory,
                    onKeepHistory = { v ->
                        clipKeepHistory = v
                        Prefs.setClipboardHistory(ctx, v)
                        if (!v) ConnectionManager.clearClipHistory()
                    },
                )
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
                        Toast.makeText(ctx, "Allow DroidDock to display over other apps", Toast.LENGTH_LONG).show()
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
                    onDefaultTab = { t ->
                        defaultTab = t
                        Prefs.setDefaultTab(ctx, t)
                    },
                    expandNet   = expandNet,
                    onExpandNet = { v ->
                        expandNet = v
                        Prefs.setExpandNetworking(ctx, v)
                    },
                    onEnableClip = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        Toast.makeText(ctx, "Find DroidDock Clipboard and turn it on", Toast.LENGTH_LONG).show()
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
            }
            }
        }
    }
}

// ── Home tab ──────────────────────────────────────────────────────────────

@Composable
private fun HomeTab(
    connected:    Boolean,
    isPaused:     Boolean,
    paired:       Boolean,
    macName:      String?,
    macInfo:      ConnectionManager.MacInfo?,
    event:        String?,
    pausedUntil:  Long,
    devices:      List<KnownDevice>,
    activeToken:  String?,
    disconnected: Boolean,
    canControlMac: Boolean,
    macMedia:     ConnectionManager.MacMedia?,
    onPause:      () -> Unit,
    onResume:     () -> Unit,
    onQuickConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPickDevice: (KnownDevice) -> Unit,
    onGoToConnect: () -> Unit,
    onGoToFiles:   () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        Brush.linearGradient(listOf(Amber, Orange)),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PhoneAndroid, null, tint = OnAmber,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("DroidDock", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp)
        }

        Spacer(Modifier.height(18.dp))

        ConnectionHeroCard(
            connected    = connected,
            isPaused     = isPaused,
            paired       = paired,
            macName      = macName,
            event        = event,
            pausedUntil  = pausedUntil,
            onPause      = onPause,
            onResume     = onResume,
            onGoConnect  = onGoToConnect,
        )

        // The Mac reporting back. Absent entirely on an older Mac build, or
        // when the user turned the sync off there — an empty card would just
        // look broken, so there isn't one.
        // Only when there is no device card to fold it into — otherwise the
        // Mac's name would appear twice, one card above the other.
        if (devices.isEmpty()) {
            macInfo?.let {
                Spacer(Modifier.height(10.dp))
                MacStatusCard(it)
            }
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            DevicesCard(
                devices        = devices,
                connected      = connected,
                disconnected   = disconnected,
                activeToken    = activeToken,
                macInfo        = macInfo,
                onQuickConnect = onQuickConnect,
                onDisconnect   = onDisconnect,
                onPickDevice   = onPickDevice,
            )
        }

        // Only while the Mac says it will accept input — same gate the Remote
        // tab uses, so the two can never disagree about whether it's available.
        if (connected && canControlMac) {
            Spacer(Modifier.height(10.dp))
            MacNowPlayingCard(media = macMedia)
            Spacer(Modifier.height(10.dp))
            MacControlsCard(volume = macInfo?.volume)
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionBtn(
                icon      = Icons.Outlined.ScreenShare,
                label     = "Mirror",
                tint      = Purple,
                modifier  = Modifier.weight(1f),
                onClick   = onGoToMirror,
            )
            QuickActionBtn(
                icon      = Icons.Outlined.UploadFile,
                label     = "Send File",
                tint      = Orange,
                modifier  = Modifier.weight(1f),
                onClick   = onGoToFiles,
            )
            QuickActionBtn(
                icon      = Icons.Outlined.Videocam,
                label     = "Camera",
                tint      = Blue,
                modifier  = Modifier.weight(1f),
                onClick   = onGoToMirror,
            )
        }

        // The "SYNC & SHARING" grid that stood here — eight tiles, each a
        // title, a subtitle and a status dot — was removed. None of them did
        // anything when tapped; they restated features the nav bar already
        // reaches and pushed the cards that *are* interactive a full screen
        // further down. Connection state is already on the hero card above.

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionHeroCard(
    connected:   Boolean,
    isPaused:    Boolean,
    paired:      Boolean,
    macName:     String?,
    event:       String?,
    pausedUntil: Long,
    onPause:     () -> Unit,
    onResume:    () -> Unit,
    onGoConnect: () -> Unit,
) {
    val dotColor = when {
        isPaused  -> Amber
        connected -> Ok
        else      -> Dim
    }
    // While paired-but-offline the card used to say only "Searching…", which
    // dropped the one fact worth showing — *which* Mac it is looking for. The
    // stored pairing name is right there.
    val knownName = ConnectionManager.knownDevices.collectAsState().value
        .firstOrNull()?.macName?.takeIf { it.isNotBlank() && it != "Mac" }
    val headline = when {
        isPaused  -> "Paused"
        connected -> macName ?: knownName ?: "Mac"
        paired    -> knownName ?: "Searching…"
        else      -> "Not paired"
    }
    val subline = when {
        isPaused  -> pauseSubtitle(pausedUntil)
        connected -> if (event.isNullOrBlank()) "Connected · Wi-Fi" else "Connected · $event"
        paired    -> if (knownName != null) "Searching for this Mac…"
                     else "Waiting for Mac to come online"
        else      -> "Pair with your Mac to get started"
    }

    Surface(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(24.dp),
        color     = Surface1,
        tonalElevation = 0.dp
    ) {
        Box {
            if (connected && !isPaused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(
                            Brush.verticalGradient(listOf(Ok.copy(alpha = 0.12f), Color.Transparent))
                        )
                )
            }
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(dotColor.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = if (connected) Icons.Default.CheckCircle else if (isPaused) Icons.Default.PauseCircle else Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint               = dotColor,
                            modifier           = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(headline, color = if (isPaused) Amber else Fg,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subline, color = Dim, fontSize = 12.sp,
                            lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (paired) {
                        Spacer(Modifier.width(8.dp))
                        if (isPaused) {
                            FilledTonalButton(
                                onClick = onResume,
                                colors  = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Amber.copy(alpha = 0.18f),
                                    contentColor   = Amber
                                ),
                                shape          = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                elevation      = ButtonDefaults.filledTonalButtonElevation(0.dp)
                            ) { Text("Resume", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        } else {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Default.PowerSettingsNew, "Pause",
                                    tint = Dim, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (!paired) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick   = onGoConnect,
                        modifier  = Modifier.fillMaxWidth().height(46.dp),
                        colors    = ButtonDefaults.buttonColors(containerColor = Amber),
                        shape     = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pair with Mac", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            color = OnAmber)
                    }
                }
            }
        }
    }
}

/**
 * Phase 3 — what's playing on the Mac, and the transport for it.
 *
 * Shown whenever the Mac accepts remote control, not only when a track could be
 * named. That asymmetry is the whole design: play/pause/next/prev are posted as
 * real HID media keys, so they drive *whatever* owns the Mac's now-playing
 * session — YouTube in Chrome, VLC, a podcast app — while the title above them
 * can only be filled in for sources the Mac can actually read. Hiding the
 * buttons whenever the label is unknown would take away working controls.
 */
@Composable
private fun MacNowPlayingCard(media: ConnectionManager.MacMedia?) {
    // Flip the glyph on tap and let the Mac's next report confirm it.
    //
    // Only a cosmetic head start now: the Mac pushes a fresh report about
    // 600ms after the media key lands, and notices a play or pause made over
    // there within about a second, for browser tabs and unscriptable apps
    // included. This just spares the button from sitting wrong for that round
    // trip. `remember(media)` clears the guess the moment a report arrives, so
    // the Mac always has the last word.
    var optimistic by remember(media) { mutableStateOf<Boolean?>(null) }

    val playing = optimistic ?: (media?.playing == true)
    val title = media?.title.orEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            (if (playing) Ok else Dim).copy(alpha = 0.14f),
                            RoundedCornerShape(13.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Outlined.MusicNote else Icons.Outlined.Headphones,
                        null,
                        tint = if (playing) Ok else Dim,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            title.isNotEmpty() -> title
                            playing            -> "Playing on your Mac"
                            else               -> "Nothing Playing"
                        },
                        color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // "from your Mac" matches the reference's own subtitle
                        // when there's nothing more specific to say.
                        listOfNotNull(
                            media?.artist?.takeIf { it.isNotBlank() },
                            media?.app?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifEmpty { "from your Mac" },
                        color = Dim, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportButton(Icons.Outlined.SkipPrevious, "Previous", Modifier.weight(1f)) {
                    ConnectionManager.sendRemote("media") { it.put("key", "prev") }
                }
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Amber)
                        .clickable {
                            optimistic = !playing
                            ConnectionManager.sendRemote("media") { it.put("key", "playpause") }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = "Play or pause",
                        tint = OnAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }
                TransportButton(Icons.Outlined.SkipNext, "Next", Modifier.weight(1f)) {
                    ConnectionManager.sendRemote("media") { it.put("key", "next") }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Fg, modifier = Modifier.size(21.dp))
    }
}

/**
 * Phase 2 — controlling the Mac from the phone.
 *
 * Gated on the Mac advertising `remote`, which it only does while the user has
 * remote control switched on over there; an older or opted-out Mac never shows
 * this card at all rather than showing buttons that quietly do nothing.
 *
 * Brightness is stepped rather than absolute because setting a display's level
 * outright needs private CoreDisplay calls — these post the same HID keys the
 * keyboard's own brightness keys do. Volume *is* absolute, so it gets a slider,
 * seeded from the level the Mac reports in `mac-info`.
 */
@Composable
private fun MacControlsCard(volume: Int?) {
    var slider by remember(volume) { mutableStateOf((volume ?: 50).toFloat()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "MAC CONTROLS",
                color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacActionChip(
                    icon = Icons.Outlined.Lock, label = "Lock",
                    tint = Blue, modifier = Modifier.weight(1f)
                ) { ConnectionManager.sendRemote("lock") }
                MacActionChip(
                    icon = Icons.Outlined.DesktopWindows, label = "Screensaver",
                    tint = Purple, modifier = Modifier.weight(1f)
                ) { ConnectionManager.sendRemote("screensaver") }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BrightnessMedium, null, tint = Orange,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Brightness", color = Fg, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                StepButton("−") {
                    ConnectionManager.sendRemote("brightness") { it.put("dir", "down") }
                }
                Spacer(Modifier.width(8.dp))
                StepButton("+") {
                    ConnectionManager.sendRemote("brightness") { it.put("dir", "up") }
                }
            }

            if (volume != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VolumeUp, null, tint = Ok,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Volume", color = Fg, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        // Only the settled value is sent: dragging emits a value
                        // per frame, and each one is an osascript process on the
                        // Mac. The label tracks the drag so it still feels live.
                        onValueChangeFinished = {
                            ConnectionManager.sendRemote("volume_set") {
                                it.put("level", slider.toInt())
                            }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = Surface3,
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${slider.toInt()}", color = Dim, fontSize = 12.sp,
                        modifier = Modifier.width(28.dp))
                }
            }
        }
    }
}

@Composable
private fun MacActionChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Surface2,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * "Last Connected Device" — the reference's home card.
 *
 * The head of the list is whichever Mac the connect loop is currently pointed
 * at; the rest are previous pairings, tappable to switch. Only rendered when at
 * least one Mac is known, so a fresh install still sees the pairing prompt in
 * the hero card above rather than an empty shell.
 */
@Composable
private fun DevicesCard(
    devices:        List<KnownDevice>,
    connected:      Boolean,
    disconnected:   Boolean,
    activeToken:    String?,
    macInfo:        ConnectionManager.MacInfo?,
    onQuickConnect: () -> Unit,
    onDisconnect:   () -> Unit,
    onPickDevice:   (KnownDevice) -> Unit,
) {
    val active = devices.firstOrNull { it.token == activeToken } ?: devices.first()
    val others = devices.filter { it.token != active.token }

    // Prefer the name the Mac just told us over the one stored at pairing time —
    // it's the one that reflects a rename on the Mac. Falls back to the stored
    // name while disconnected, which is the whole point of a "last connected
    // device" card: it should still say *which* Mac when the link is down.
    val liveName by ConnectionManager.macName.collectAsState()
    val displayName = (if (connected) liveName else null)
        ?.takeIf { it.isNotBlank() }
        ?: active.macName.takeIf { it.isNotBlank() && it != "Mac" }
        ?: "Mac"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "LAST CONNECTED DEVICE",
                color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            )

            // The reference leads with a large laptop illustration, and it's
            // right to: this card is about *which machine*, so the device
            // deserves more weight than a list-row icon gives it.
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(Amber.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.LaptopMac, null, tint = Amber,
                        modifier = Modifier.size(52.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    displayName,
                    color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        connected -> "Connected · ${active.ips.first()}:${active.port}"
                        else      -> lastSeenLabel(active.lastSeenAt)
                    },
                    color = if (connected) Ok else Dim,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // The Mac's battery lives here rather than in a card of its own.
                // A separate status card repeated the machine's name directly
                // under this one, which read as two devices rather than one.
                macInfo?.let { info ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                !info.hasBattery || info.battery == null ->
                                    Icons.Outlined.PowerSettingsNew
                                info.charging -> Icons.Outlined.BatteryChargingFull
                                else -> Icons.Outlined.BatteryFull
                            },
                            null,
                            tint = when {
                                info.battery != null && info.battery <= 20 && !info.charging -> Orange
                                else -> Ok
                            },
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                // A desktop Mac has no battery at all. "0%" would
                                // be a lie; "Plugged in" is the whole truth.
                                !info.hasBattery || info.battery == null ->
                                    if (info.charging) "Plugged in" else "On mains"
                                info.charging -> "${info.battery}% · charging"
                                else          -> "${info.battery}%"
                            },
                            color = Dim, fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (connected) {
                OutlinedButton(
                    onClick  = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Dim),
                    border   = androidx.compose.foundation.BorderStroke(0.5.dp, LineColor)
                ) {
                    Icon(Icons.Outlined.LinkOff, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick   = onQuickConnect,
                    modifier  = Modifier.fillMaxWidth().height(46.dp),
                    colors    = ButtonDefaults.buttonColors(containerColor = Amber),
                    shape     = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(Icons.Outlined.Cable, null, modifier = Modifier.size(17.dp),
                        tint = OnAmber)
                    Spacer(Modifier.width(8.dp))
                    Text("Quick Connect", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = OnAmber)
                }
                if (disconnected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Auto-reconnect is off until you connect again.",
                        color = Dim, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
            }

            if (others.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                others.forEach { device ->
                    RowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickDevice(device) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.LaptopMac, null, tint = Dim,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.macName, color = Fg, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(lastSeenLabel(device.lastSeenAt), color = Dim, fontSize = 11.sp)
                        }
                        Text("Connect", color = Amber, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** "Last seen 4m ago" — matching the reference's wording, including its
 *  "Just now" for anything under a minute. Never-linked reads as paired-only. */
private fun lastSeenLabel(ts: Long): String {
    if (ts <= 0L) return "Not connected yet"
    val diff = System.currentTimeMillis() - ts
    return "Last seen " + when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else               -> "${diff / 86_400_000L}d ago"
    }
}

@Composable
private fun QuickActionBtn(
    icon:     ImageVector,
    label:    String,
    tint:     Color,
    modifier: Modifier = Modifier,
    onClick:  () -> Unit,
) {
    Surface(
        onClick    = onClick,
        modifier   = modifier,
        shape      = RoundedCornerShape(18.dp),
        color      = Surface2,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tint.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(label, color = Fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}


/** The Mac's own name and battery, pushed over the link (`mac-info`).
 *
 *  Read-only by design — there is no control here. The Mac already knows the
 *  phone's battery; this closes the loop so Home can show both. */
@Composable
private fun MacStatusCard(info: ConnectionManager.MacInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(18.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Blue.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.LaptopMac, null, tint = Blue, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        // A desktop Mac has no battery at all. "0%" would be a
                        // lie; "Plugged in" is the whole truth about its power.
                        !info.hasBattery || info.battery == null ->
                            if (info.charging) "Plugged in" else "On mains"
                        info.charging -> "${info.battery}% · charging"
                        else          -> "${info.battery}%"
                    },
                    color = Dim, fontSize = 12.sp
                )
            }
            if (info.hasBattery && info.battery != null) {
                Icon(
                    if (info.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryFull,
                    null,
                    tint = if (info.battery <= 20 && !info.charging) Orange else Ok,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Connect tab ────────────────────────────────────────────────────────────

@Composable
private fun ConnectTab(
    connected: Boolean,
    paired:    Boolean,
    macName:   String?,
    onScan:    () -> Unit,
    onManual:  () -> Unit,
    onForget:  () -> Unit,
    onBack:    () -> Unit,
) {
    val ctx = LocalContext.current
    val discovered by ConnectionManager.discovered.collectAsState()
    val scanning   by ConnectionManager.scanning.collectAsState()

    // Sweep when the tab opens, then keep it fresh while the user is looking at
    // it. Stops the moment they navigate away — this holds a multicast browse
    // open, so it has no business running on a background tab.
    LaunchedEffect(Unit) {
        while (true) {
            ConnectionManager.scanNow(ctx)
            kotlinx.coroutines.delay(6_000)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        // Reached from Home or Settings rather than the nav bar, so it carries
        // its own back affordance.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Fg,
                    modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Connection", color = Fg, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Text("Pair or manage your Mac link", color = Dim, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(24.dp),
            color    = Surface1,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (connected) Ok else Dim.copy(alpha = 0.3f), CircleShape)
                    )
                    Text(
                        text       = if (connected) "Connected to ${macName ?: "Mac"}" else if (paired) "Searching for Mac…" else "Not paired",
                        color      = if (connected) Ok else Dim,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick   = onScan,
                    modifier  = Modifier.fillMaxWidth().height(50.dp),
                    colors    = ButtonDefaults.buttonColors(containerColor = Amber),
                    shape     = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = if (paired) "Re-pair with Mac" else "Scan QR code",
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = OnAmber
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onManual) {
                        Text("Enter IP manually", color = Amber, fontSize = 13.sp)
                    }
                    if (paired) {
                        TextButton(onClick = onForget) {
                            Text("Forget this Mac", color = Dim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        AvailableDevicesCard(
            discovered = discovered,
            scanning   = scanning,
            onConnect  = { mac, known -> ConnectionManager.connectToDiscovered(ctx, mac, known) },
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text          = "HOW TO PAIR",
            color         = Dim,
            fontSize      = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.SemiBold,
            modifier      = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(20.dp),
            color    = Surface1,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                val steps = listOf(
                    "Open DroidDock on your Mac",
                    "Click 'Pair Device' or the QR icon in the sidebar",
                    "Tap 'Scan QR code' above and scan the code on screen",
                    "Both devices join the same Wi-Fi network automatically",
                )
                steps.forEachIndexed { i, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Amber.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${i + 1}", color = Amber, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(step, color = Dim, fontSize = 13.sp, lineHeight = 18.sp,
                            modifier = Modifier.weight(1f))
                    }
                    if (i < steps.lastIndex) HorizontalDivider(color = LineColor, thickness = 0.5.dp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * "Available Devices" — Macs currently advertising themselves over mDNS.
 *
 * The important limitation, stated in the UI rather than hidden: discovery
 * hands back an address, never a token. A Mac this phone has paired with before
 * can be connected to straight from here — which is the whole point, since it's
 * how you recover when the Mac's IP changes — but an unrecognised one still has
 * to go through QR or manual pairing, because that's the only place a token
 * comes from.
 */
@Composable
private fun AvailableDevicesCard(
    discovered: List<DiscoveredMac>,
    scanning:   Boolean,
    onConnect:  (DiscoveredMac, KnownDevice) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "AVAILABLE DEVICES",
            color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
        )
        if (scanning) Text("Scanning…", color = Dim, fontSize = 10.sp)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        if (discovered.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (scanning) "Looking for Macs on this network…"
                    else "No Macs found on this network",
                    color = Dim, fontSize = 12.sp
                )
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                discovered.forEachIndexed { idx, mac ->
                    if (idx > 0) RowDivider()
                    val known = ConnectionManager.knownFor(mac)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (known != null) Modifier.clickable { onConnect(mac, known) }
                                else Modifier
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.LaptopMac, null,
                            tint = if (known != null) Amber else Dim,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mac.name, color = Fg, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${mac.ip}:${mac.port}", color = Dim, fontSize = 11.sp)
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Amber.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(mac.via, color = Amber, fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (known != null) {
                            Text("Connect", color = Amber, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                        } else {
                            Text("Scan QR to pair", color = Dim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clipboard tab — this session's traffic plus a compose box.
 *
 * The list is memory-only and dies with the process, which the empty state says
 * out loud: everything copied on either device lands here, so a persisted
 * version would be a plaintext log of passwords and one-time codes.
 */
@Composable
private fun ClipboardTab(
    connected:  Boolean,
    history:    List<ConnectionManager.ClipEntry>,
    keepHistory: Boolean,
    onKeepHistory: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clipboard", color = Fg, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Text(
                    if (connected) "Copy on either device" else "Not connected to Mac",
                    color = if (connected) Dim else Bad, fontSize = 13.sp
                )
            }
            if (history.isNotEmpty()) {
                IconButton(onClick = { ConnectionManager.clearClipHistory() }) {
                    Icon(Icons.Outlined.Delete, "Clear history", tint = Dim,
                        modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(18.dp),
            color    = Surface1,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("History", color = Fg, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Cleared when the app closes", color = Dim, fontSize = 11.sp)
                }
                DroidSwitch(checked = keepHistory, onCheckedChange = onKeepHistory)
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (keepHistory) "Nothing shared yet" else "History is off",
                        color = Dim, fontSize = 13.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.forEach { entry -> ClipRow(entry) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Type a message…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            val canSend = connected && draft.isNotBlank()
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Amber else Surface3)
                    .clickable(enabled = canSend) {
                        if (ConnectionManager.sendClipboardText(draft.trim())) {
                            draft = ""
                        } else {
                            Toast.makeText(ctx, "Not connected to Mac", Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Send, "Send",
                    tint = if (canSend) OnAmber else Dim, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ClipRow(entry: ConnectionManager.ClipEntry) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.fromMac) Icons.Outlined.LaptopMac else Icons.Outlined.PhoneAndroid,
                    null,
                    tint = if (entry.fromMac) Blue else Ok,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (entry.fromMac) "From Mac" else "Sent to Mac",
                    color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(formatTimeAgo(entry.at), color = Dim, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.ContentCopy, "Copy",
                    tint = Dim,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable {
                            runCatching {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("DroidDock", entry.text)
                                )
                            }
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(entry.text, color = Fg, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A segmented switch above a screen, for grouping destinations that belong
 * together rather than giving each its own slot in the nav bar.
 *
 * Options that depend on a Mac capability are simply absent when it isn't
 * advertised — same rule the nav bar used to follow — but here their coming and
 * going only reshuffles a local control instead of the app's primary
 * navigation.
 */
@Composable
private fun SegmentedHeader(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (options.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (id, label) ->
            val on = id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Amber else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) OnAmber else Dim,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Files — this phone's storage, or the Mac's when it offers it. */
@Composable
private fun FilesScreen(
    macFsAvailable: Boolean,
    connected: Boolean,
    phoneFiles: @Composable () -> Unit,
) {
    var source by remember { mutableStateOf("phone") }
    // Fall back rather than showing an empty screen if the Mac stops offering
    // its filesystem while we're looking at it.
    if (source == "mac" && !macFsAvailable) source = "phone"

    // The status-bar inset is taken here, on the parent, rather than on the
    // spacer below. `windowInsetsPadding` consumes what it applies for its
    // *descendants*, so a sibling spacer would have left the inner tab free to
    // apply the full inset a second time and push its content down twice.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        if (macFsAvailable) {
            Spacer(Modifier.height(8.dp))
            SegmentedHeader(
                options = listOf("phone" to "This phone", "mac" to "Mac"),
                selected = source,
                onSelect = { source = it },
            )
        }
        Box(Modifier.weight(1f)) {
            if (source == "mac") MacFilesTab(connected = connected) else phoneFiles()
        }
    }
}

/** Control — the Mac's view of this phone, and this phone's control of the Mac. */
@Composable
private fun ControlScreen(
    remoteAvailable: Boolean,
    macAppsAvailable: Boolean,
    connected: Boolean,
    mirror: @Composable () -> Unit,
) {
    var mode by remember { mutableStateOf("mirror") }
    // A capability can vanish mid-session (the Mac's switch is turned off, or we
    // reconnect to an older build). Bounce off a segment that no longer exists
    // rather than showing an empty pane.
    if (mode == "remote" && !remoteAvailable) mode = "mirror"
    if (mode == "macapps" && !macAppsAvailable) mode = "mirror"

    // Same inset ownership as FilesScreen above.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        if (remoteAvailable || macAppsAvailable) {
            Spacer(Modifier.height(8.dp))
            SegmentedHeader(
                options = buildList {
                    add("mirror" to "Mirror & Camera")
                    if (remoteAvailable) add("remote" to "Mac Remote")
                    if (macAppsAvailable) add("macapps" to "Mac Apps")
                },
                selected = mode,
                onSelect = { mode = it },
            )
        }
        Box(Modifier.weight(1f)) {
            when (mode) {
                "remote" -> MacRemoteTab(connected = connected)
                "macapps" -> MacAppsTab(connected = connected)
                else -> mirror()
            }
        }
    }
}

// ── Files tab ──────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824f)} GB"
    bytes >= 1_048_576L     -> "${"%.1f".format(bytes / 1_048_576f)} MB"
    bytes >= 1024L          -> "${"%.0f".format(bytes / 1024f)} KB"
    else                    -> "$bytes B"
}

private fun formatSpeed(bps: Long): String = when {
    bps >= 1_048_576L -> "${"%.1f".format(bps / 1_048_576f)} MB/s"
    bps >= 1024L      -> "${"%.0f".format(bps / 1024f)} KB/s"
    else              -> "$bps B/s"
}

private fun formatTimeAgo(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L      -> "just now"
        diff < 3_600_000L   -> "${diff / 60_000L}m ago"
        diff < 86_400_000L  -> "${diff / 3_600_000L}h ago"
        else                -> "${diff / 86_400_000L}d ago"
    }
}

private fun extColor(name: String): Color = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf"                                           -> Color(0xFFEF4444)
    "jpg", "jpeg", "png", "gif", "webp", "heic",
    "bmp", "svg"                                    -> Color(0xFF10B981)
    "mp4", "mov", "avi", "mkv", "webm", "m4v"      -> Color(0xFF8B5CF6)
    "mp3", "wav", "m4a", "flac", "aac", "ogg"      -> Color(0xFFF59E0B)
    "doc", "docx", "txt", "rtf", "odt"             -> Color(0xFF3B82F6)
    "xls", "xlsx", "csv"                            -> Color(0xFF22C55E)
    "ppt", "pptx"                                   -> Color(0xFFF97316)
    "zip", "rar", "7z", "tar", "gz", "bz2"         -> Color(0xFF6B7280)
    "apk"                                           -> Color(0xFF78C1A3)
    else                                            -> Color(0xFF6B7280)
}

private fun extLabel(name: String): String =
    name.substringAfterLast('.', "file").uppercase().take(4)

@Composable
private fun FilesTab(
    connected:       Boolean,
    sending:         Boolean,
    activeTransfers: List<TransferProgress>,
    recentTransfers: List<TransferRecord>,
    onSendFile:      () -> Unit,
    onSendClipboard: () -> Unit,
) {
    val startOfDay = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val todayBytes = recentTransfers
        .filter { it.completedAt >= startOfDay && it.success }
        .sumOf { it.sizeBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Summary card + Send button
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Surface2,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Transferred today", color = Dim, fontSize = 11.sp, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (todayBytes == 0L) "—" else formatBytes(todayBytes),
                        color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                }
                Button(
                    onClick = onSendFile,
                    enabled = !sending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = OnAmber,
                        disabledContainerColor = Amber.copy(alpha = 0.4f),
                        disabledContentColor = OnAmber.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(if (sending) "Sending…" else "+ Send", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Upload queue (only when active)
        if (activeTransfers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "UPLOAD QUEUE",
                color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Surface1,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    activeTransfers.forEachIndexed { idx, transfer ->
                        if (idx > 0) RowDivider()
                        ActiveTransferRow(transfer)
                    }
                }
            }
        }

        // Recent transfers
        if (recentTransfers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "RECENT TRANSFERS",
                color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Surface1,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    recentTransfers.forEachIndexed { idx, record ->
                        if (idx > 0) RowDivider()
                        RecentTransferRow(record)
                    }
                }
            }
        }

        // Quick actions
        Spacer(Modifier.height(20.dp))
        Text(
            "QUICK ACTIONS",
            color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Surface1,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ServiceRow(
                    icon = Icons.Default.ContentCopy,
                    tint = Purple,
                    title = "Send Clipboard to Mac",
                    subtitle = "Push whatever you copied right now",
                    granted = null,
                    action = "Send"
                ) { onSendClipboard() }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "TIPS",
            color = Dim, fontSize = 10.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Surface1,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "Share from any app using the Android share sheet — tap 'Send to Mac'",
                    "Long-press any text and choose 'Send to Mac' from the selection menu",
                    "On Mac, drag files onto the DroidDock window to send to your phone",
                    "Received files appear in your Downloads folder on both devices",
                ).forEach { tip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .background(Amber.copy(alpha = 0.6f), CircleShape)
                        )
                        Text(tip, color = Dim, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActiveTransferRow(transfer: TransferProgress) {
    val color = extColor(transfer.fileName)
    Row(
        modifier = Modifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(extLabel(transfer.fileName), color = color,
                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    transfer.fileName, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text("${transfer.percent}%", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatBytes(transfer.totalBytes), color = Dim, fontSize = 11.sp)
                if (transfer.speedBps > 0) {
                    Text(formatSpeed(transfer.speedBps), color = Dim, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (transfer.sentBytes.toFloat() / transfer.totalBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Amber,
                trackColor = Surface3,
            )
        }
    }
}

@Composable
private fun RecentTransferRow(record: TransferRecord) {
    val color = extColor(record.fileName)
    Row(
        modifier = Modifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(extLabel(record.fileName), color = color,
                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.fileName, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatBytes(record.sizeBytes), color = Dim, fontSize = 11.sp)
                Text("·", color = Dim, fontSize = 11.sp)
                Text(if (record.direction == "toMac") "→ Mac" else "← Phone", color = Dim, fontSize = 11.sp)
                Text("·", color = Dim, fontSize = 11.sp)
                Text(formatTimeAgo(record.completedAt), color = Dim, fontSize = 11.sp)
            }
        }
        if (record.success) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Ok, modifier = Modifier.size(18.dp))
        } else {
            Icon(Icons.Default.Error, contentDescription = null, tint = Bad, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Mac Files tab (Phase 19 — reverse file browsing) ───────────────────────

/**
 * Tier D — a trackpad for the Mac.
 *
 * Deliberately thin: it translates gestures into the fixed `remote` vocabulary
 * `mac_remote.rs` accepts and sends them fire-and-forget. There is no state to
 * keep in sync, and nothing here can express an action the Mac's allow-list
 * doesn't already permit.
 */
/**
 * The Mac's applications, launchable from the phone.
 *
 * Mirror image of the Mac's Apps grid. Gated on the "macapps" capability, which
 * the Mac only advertises while "Let the phone control this Mac" is on — so if
 * this tab is visible at all, the permission is already granted. The Mac still
 * re-checks on every message; a stale screen gets an error rather than a launch.
 */
@Composable
private fun MacAppsTab(connected: Boolean) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    suspend fun load() {
        error = null
        apps = null
        runCatching { ConnectionManager.macAppsList() }
            .onSuccess { arr ->
                apps = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pkg = o.optString("pkg")
                    val label = o.optString("label")
                    if (pkg.isEmpty() || label.isEmpty()) null else pkg to label
                }
            }
            .onFailure {
                apps = emptyList()
                error = it.message ?: "Couldn't read the Mac's apps"
            }
    }

    LaunchedEffect(connected) { if (connected) load() }

    // Prefix matches first, same ranking the Mac's own grid uses so searching
    // behaves identically on both ends.
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        val all = apps ?: emptyList()
        if (q.isEmpty()) all
        else all.filter { it.second.lowercase().contains(q) }
            .sortedWith(compareByDescending<Pair<String, String>> {
                it.second.lowercase().startsWith(q)
            }.thenBy { it.second.lowercase() })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            Text("Not connected to your Mac.", color = Bad, fontSize = 12.sp)
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth()
        )

        when {
            apps == null -> Text("Reading your Mac's apps…", color = Dim, fontSize = 12.sp)
            error != null -> Text(error!!, color = Bad, fontSize = 12.sp)
            shown.isEmpty() -> Text(
                if (query.isBlank()) "No apps found." else "Nothing matching \"$query\".",
                color = Dim, fontSize = 12.sp
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.first }) { (pkg, label) ->
                    Surface(
                        color = Surface2,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        runCatching { ConnectionManager.macAppLaunch(pkg) }
                                            .onSuccess {
                                                Toast.makeText(ctx, "Opening $label on your Mac", Toast.LENGTH_SHORT).show()
                                            }
                                            .onFailure {
                                                Toast.makeText(ctx, it.message ?: "Couldn't open $label", Toast.LENGTH_LONG).show()
                                            }
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Laptop,
                                contentDescription = null,
                                tint = Dim,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 14.sp)
                                Text(pkg, color = Dim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacRemoteTab(connected: Boolean) {
    var typed by remember { mutableStateOf("") }
    // Relative deltas accumulated into absolute moves: the Mac's `mouse_move`
    // takes screen coordinates, so the phone owns the cursor while dragging.
    var cursorX by remember { mutableStateOf(600f) }
    var cursorY by remember { mutableStateOf(400f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (connected) "Drag to move · tap to click · long-press to right-click"
            else "Not connected to your Mac.",
            color = if (connected) Dim else Bad,
            fontSize = 12.sp
        )

        // The trackpad is the point of this screen, so it takes the slack and
        // is floored at a size worth dragging on. It used to share `weight(1f)`
        // with nine fixed-height blocks below it and ended up a letterbox.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface2)
                .pointerInput(connected) {
                    if (!connected) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        // 1.6× so a short phone drag crosses a large display.
                        cursorX = (cursorX + drag.x * 1.6f).coerceAtLeast(0f)
                        cursorY = (cursorY + drag.y * 1.6f).coerceAtLeast(0f)
                        ConnectionManager.sendRemote("mouse_move") {
                            it.put("x", cursorX.toDouble()).put("y", cursorY.toDouble())
                        }
                    }
                }
                .pointerInput(connected) {
                    if (!connected) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            ConnectionManager.sendRemote("mouse_click") { it.put("button", "left") }
                        },
                        onLongPress = {
                            ConnectionManager.sendRemote("mouse_click") { it.put("button", "right") }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Mouse, null, tint = Dim.copy(alpha = 0.45f),
                    modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(8.dp))
                Text("Trackpad", color = Dim.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }

        // Arrows as a d-pad rather than four equal slabs in a row — a cross is
        // the shape the fingers already expect, and it halves the width the
        // keys need.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drawn icons rather than "↑←↓→" text: the arrow glyphs render at
            // whatever weight the system font gives them, which on One UI is a
            // thin, off-centre hairline inside a key cap.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RemoteKeyIcon(Icons.Outlined.KeyboardArrowUp, "Up", Modifier.size(46.dp)) {
                    sendKey("up")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKeyIcon(Icons.Outlined.KeyboardArrowLeft, "Left", Modifier.size(46.dp)) {
                        sendKey("left")
                    }
                    RemoteKeyIcon(Icons.Outlined.KeyboardArrowDown, "Down", Modifier.size(46.dp)) {
                        sendKey("down")
                    }
                    RemoteKeyIcon(Icons.Outlined.KeyboardArrowRight, "Right", Modifier.size(46.dp)) {
                        sendKey("right")
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKey("Enter", Modifier.weight(1f)) { sendKey("enter") }
                    RemoteKey("Esc", Modifier.weight(1f)) { sendKey("escape") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKey("Space", Modifier.weight(1f)) { sendKey("space") }
                    RemoteKey("Tab", Modifier.weight(1f)) { sendKey("tab") }
                }
            }
        }

        // The media transport that used to sit here was removed: the Now
        // Playing card on Home carries the same six actions, next to the title
        // they act on, which is where they belong.

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Type on the Mac", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            val canSend = connected && typed.isNotBlank()
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canSend) Amber else Surface3)
                    .clickable(enabled = canSend) {
                        ConnectionManager.sendRemote("text") { it.put("text", typed) }
                        typed = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Send, "Send text",
                    tint = if (canSend) OnAmber else Dim, modifier = Modifier.size(19.dp))
            }
        }
    }
}

private fun sendKey(name: String) {
    ConnectionManager.sendRemote("key") { it.put("key", name) }
}

@Composable
private fun RemoteKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    RemoteKeyBase(modifier, onClick) {
        Text(label, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }

@Composable
private fun RemoteKeyIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = RemoteKeyBase(modifier, onClick) {
    Icon(icon, description, tint = Fg, modifier = Modifier.size(22.dp))
}

/**
 * A key cap.
 *
 * Deliberately not a Material `Button`: that carries 24dp of *content* padding
 * on each side, so at the widths these sit at — a 46dp d-pad cell, or a quarter
 * of a row — 48dp of mandatory padding exceeded the cap itself. "Enter" and
 * "Space" wrapped to two lines and the arrows were squeezed into their
 * corners, which is what made them read as unresponsive: they were being
 * pressed, they just didn't look pressable.
 *
 * Haptic feedback on every press, because the result of a key lands on the
 * *Mac* — often out of the corner of your eye, or on a screen you can't see at
 * all. Without it there's no local evidence the tap registered.
 */
@Composable
private fun RemoteKeyBase(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface3)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun macFsEntryPath(base: String, entry: JSONObject): String {
    // The synthetic root listing (base == "") carries an absolute `path` per
    // entry, because a root's display name is only its basename and there is
    // no base to join it to. Ordinary entries have no `path` and are joined.
    entry.optString("path").takeIf { it.isNotEmpty() }?.let { return it }
    val name = entry.optString("name")
    return if (base.isEmpty()) name else "$base/$name"
}

@Composable
private fun MacFilesTab(connected: Boolean) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()

    var path        by remember { mutableStateOf("") }
    var entries     by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading     by remember { mutableStateOf(false) }
    var error       by remember { mutableStateOf<String?>(null) }
    var pullingPath by remember { mutableStateOf<String?>(null) }
    var pullFrac    by remember { mutableStateOf(0f) }
    // The Mac's shared folders, learned from the synthetic root listing (the
    // only place their absolute paths are ever sent). Needed to know where
    // "up" stops — see the Up button below.
    var roots       by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(path, connected) {
        if (!connected) {
            error = "Not connected to Mac"
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { ConnectionManager.macFsList(path) }
            .onSuccess { arr ->
                entries = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (path.isEmpty()) {
                    roots = entries.mapNotNull { e ->
                        e.optString("path").takeIf { it.isNotEmpty() }
                    }
                }
            }
            .onFailure { error = it.message ?: "Could not load folder" }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Mac Files", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(12.dp))

        // Breadcrumb + up navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (path.isNotEmpty()) {
                IconButton(
                    onClick = {
                        // Walking up out of a shared folder lands on a path the
                        // Mac's allowlist rejects — /Users/you/Desktop's parent
                        // is /Users/you, which was never shared. Going up from a
                        // root (or from anything whose parent is outside every
                        // root) therefore returns to the list of shared folders,
                        // not to a dead end that renders "path escapes allowed
                        // roots".
                        val parent = path.substringBeforeLast('/', "")
                        path = if (roots.any { it == parent || parent.startsWith("$it/") })
                            parent else ""
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, "Up", tint = Fg, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (path.isEmpty()) "Mac" else path,
                color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) { Text("Loading…", color = Dim, fontSize = 13.sp) }

            error != null -> Box(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) { Text(error ?: "", color = Bad, fontSize = 13.sp) }

            entries.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) { Text("Empty folder", color = Dim, fontSize = 13.sp) }

            else -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Surface1,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    entries.forEachIndexed { idx, entry ->
                        if (idx > 0) RowDivider()
                        val full = macFsEntryPath(path, entry)
                        MacFsEntryRow(
                            entry    = entry,
                            pulling  = pullingPath == full,
                            fraction = if (pullingPath == full) pullFrac else 0f,
                            onOpen   = { if (entry.optBoolean("dir")) path = full },
                            onPull   = {
                                pullingPath = full
                                pullFrac = 0f
                                scope.launch {
                                    val result = ConnectionManager.macFsPull(full) { received, total ->
                                        pullFrac = if (total > 0)
                                            (received.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                    }
                                    pullingPath = null
                                    Toast.makeText(
                                        ctx,
                                        result.fold({ "Saved to Downloads" }, { e -> e.message ?: "Pull failed" }),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MacFsEntryRow(
    entry:    JSONObject,
    pulling:  Boolean,
    fraction: Float,
    onOpen:   () -> Unit,
    onPull:   () -> Unit,
) {
    val isDir = entry.optBoolean("dir")
    val name  = entry.optString("name")
    val color = if (isDir) Blue else extColor(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(enabled = isDir, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isDir) Icon(Icons.Outlined.Folder, null, tint = color, modifier = Modifier.size(20.dp))
            else Text(extLabel(name), color = color,
                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!isDir) {
                Spacer(Modifier.height(2.dp))
                Text(formatBytes(entry.optLong("size")), color = Dim, fontSize = 11.sp)
            }
            if (pulling) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Amber,
                    trackColor = Surface3,
                )
            }
        }
        if (!isDir) {
            if (pulling) {
                Text("${(fraction * 100).toInt()}%", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else {
                TonalChip("Pull", Amber, onPull)
            }
        }
    }
}

// ── Mirror tab ─────────────────────────────────────────────────────────────

@Composable
private fun MirrorTab(
    connected:          Boolean,
    overlayOk:          Boolean,
    autoMirror:         Boolean,
    onEnableOverlay:    () -> Unit,
    onToggleAutoMirror: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Mirror & Camera", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(4.dp))
        Text("Two ways to project your screen or camera", color = Dim, fontSize = 13.sp)

        Spacer(Modifier.height(20.dp))

        Text(
            text          = "SCREEN MIRROR",
            color         = Dim,
            fontSize      = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.SemiBold,
            modifier      = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        MirrorModeCard(
            title       = "Mirror via Wi-Fi",
            badge       = "Wi-Fi",
            badgeColor  = Ok,
            icon        = Icons.Outlined.ScreenShare,
            description = "Stream your screen over local Wi-Fi. No USB or Developer Options needed. Start from Mac's 'Screen Mirror' tab.",
            tint        = Ok,
            extra = {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Auto-accept mirror", color = Fg, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                        Text("Start instantly, no per-session tap", color = Dim, fontSize = 11.sp)
                    }
                    if (!overlayOk) {
                        TonalChip("Enable", Blue, onEnableOverlay)
                    } else {
                        DroidSwitch(checked = autoMirror, onCheckedChange = onToggleAutoMirror)
                    }
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        MirrorModeCard(
            title       = "Mirror via ADB",
            badge       = "ADB",
            badgeColor  = Amber,
            icon        = Icons.Outlined.Usb,
            description = "Higher quality, lower latency mirror using scrcpy over USB. Requires Developer Options enabled on this phone.",
            tint        = Amber,
            extra = {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface3, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.Info, null, tint = Dim, modifier = Modifier.size(16.dp))
                    Text(
                        "Connect USB cable, then click 'Mirror via ADB' in the Mac app's Screen Mirror tab.",
                        color = Dim, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text          = "CAMERA STREAMING",
            color         = Dim,
            fontSize      = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.SemiBold,
            modifier      = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        MirrorModeCard(
            title       = "Camera via Wi-Fi",
            badge       = "Wi-Fi",
            badgeColor  = Ok,
            icon        = Icons.Outlined.Videocam,
            description = "Stream your phone camera over local Wi-Fi — use it as a webcam on your Mac. Start from Mac's 'Camera' tab.",
            tint        = Ok,
        )

        Spacer(Modifier.height(10.dp))

        MirrorModeCard(
            title       = "Camera via ADB",
            badge       = "ADB",
            badgeColor  = Amber,
            icon        = Icons.Outlined.CameraAlt,
            description = "Higher quality camera stream using scrcpy over USB. Requires Developer Options and USB cable.",
            tint        = Amber,
            extra = {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface3, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.Info, null, tint = Dim, modifier = Modifier.size(16.dp))
                    Text(
                        "Connect USB cable, then click 'Start Camera (ADB)' in the Mac app's Camera tab.",
                        color = Dim, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MirrorModeCard(
    title:       String,
    badge:       String,
    badgeColor:  Color,
    icon:        ImageVector,
    description: String,
    tint:        Color,
    extra:       (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(tint.copy(alpha = 0.14f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(badge, color = badgeColor, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                        }
                    }
                    Text(description, color = Dim, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            extra?.invoke()
        }
    }
}

// ── Settings tab ───────────────────────────────────────────────────────────

@Composable
private fun SettingsTab(
    clipA11y:         Boolean,
    clipAuto:         Boolean,
    notifAccess:      Boolean,
    phonePerms:       Boolean,
    allFiles:         Boolean,
    deviceAdmin:      Boolean,
    themeMode:        ThemeMode,
    onThemeMode:      (ThemeMode) -> Unit,
    pitchBlack:       Boolean,
    onPitchBlack:     (Boolean) -> Unit,
    defaultTab:       String,
    onDefaultTab:     (String) -> Unit,
    crashNotify:      Boolean,
    onCrashNotify:    (Boolean) -> Unit,
    onOpenConnect:    () -> Unit,
    expandNet:        Boolean,
    onExpandNet:      (Boolean) -> Unit,
    onEnableClip:     () -> Unit,
    onToggleClipAuto: (Boolean) -> Unit,
    onEnableNotif:    () -> Unit,
    onGrantPhonePerms: () -> Unit,
    onGrantFiles:     () -> Unit,
    onBattery:        () -> Unit,
    onOpenGuide:      () -> Unit,
    /// Seeded by the app-open check so a user who follows the nav-bar dot here
    /// sees the result immediately, instead of being told to press Check to
    /// learn what the dot already told them.
    knownUpdate:      UpdateChecker.Release?,
) {
    // LazyColumn, not Column(verticalScroll): this screen is eight section
    // cards deep and a scrolling Column composes every one of them — including
    // the six below the fold — on the frame you open it. Measured at 200ms for
    // that frame, twelve dropped, easily the worst switch in the app. Lazy
    // composition builds only what's on screen.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
        }
        item {
            Text("Settings", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp)
        }

        item {
            Spacer(Modifier.height(20.dp))
        }

        item {
            SectionCard("APPEARANCE") {
                ThemeRow(mode = themeMode, onMode = onThemeMode)
                RowDivider()
                // Only meaningful on a dark surface, so it doesn't pretend to be
                // available while the light theme is showing.
                val darkNow = LocalDroidColors.current.isDark
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Outlined.Contrast, Purple)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pitch black", color = Fg, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(
                            if (darkNow) "True black backgrounds, for OLED screens"
                            else "Applies when the dark theme is showing",
                            color = Dim, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    DroidSwitch(checked = pitchBlack, onCheckedChange = onPitchBlack)
                }
                RowDivider()
                DefaultTabRow(selected = defaultTab, onSelect = onDefaultTab)
                RowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Outlined.BugReport, Bad)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Notify on crash", color = Fg, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "Name the error in a notification if DroidDock stops. " +
                                "It never hides a crash — only reports it.",
                            color = Dim, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    DroidSwitch(checked = crashNotify, onCheckedChange = onCrashNotify)
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("CONNECTION") {
                ServiceRow(
                    icon     = Icons.Outlined.WifiTethering,
                    tint     = Amber,
                    title    = "Pair or change Mac",
                    subtitle = "Scan a QR code, enter an IP, or forget this Mac",
                    granted  = null,
                    action   = "Open"
                ) { onOpenConnect() }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("THIS PHONE") {
                DeviceIdentityRows()
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("QUICK SETTINGS TILES") {
                TileRows()
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("NETWORK") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Outlined.VpnKey, Ok)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Expand networking", color = Fg, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "Reach your Mac over a VPN such as Tailscale. Local " +
                                "discovery can't cross a tailnet, so this stops " +
                                "waiting on it and keeps dialling the saved addresses.",
                            color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    DroidSwitch(checked = expandNet, onCheckedChange = onExpandNet)
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("PERMISSIONS") {
                AutoClipRow(
                    a11yOn   = clipA11y,
                    auto     = clipAuto,
                    onEnable = onEnableClip,
                    onToggle = onToggleClipAuto,
                )
                RowDivider()
                ScreenControlRows(serviceOn = clipA11y, adminOn = deviceAdmin)
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.Notifications,
                    tint     = Blue,
                    title    = "Notification Access",
                    subtitle = "Show phone notifications on your Mac",
                    granted  = notifAccess,
                    action   = "Enable"
                ) { onEnableNotif() }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.Message,
                    tint     = Ok,
                    title    = "SMS · Contacts · Calls",
                    subtitle = "Texts, contacts and call alerts on Mac",
                    granted  = phonePerms,
                    action   = "Grant"
                ) { onGrantPhonePerms() }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.FolderOpen,
                    tint     = Orange,
                    title    = "All-files Access",
                    subtitle = "Browse and transfer your phone's files",
                    granted  = allFiles,
                    action   = "Grant"
                ) { onGrantFiles() }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.BatteryChargingFull,
                    tint     = Dim,
                    title    = "Background (Battery)",
                    subtitle = "Keep the link alive when screen is off",
                    granted  = null,
                    action   = "Allow"
                ) { onBattery() }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("HELP") {
                ServiceRow(
                    icon     = Icons.Outlined.LibraryBooks,
                    tint     = Amber,
                    title    = "Feature Guide",
                    subtitle = "Step-by-step help for every feature",
                    granted  = null,
                    action   = "Open"
                ) { onOpenGuide() }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionCard("UPDATES") {
                UpdateRow(knownUpdate)
                RowDivider()
                AutoUpdateRow()
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            Text(
                text     = "DroidDock · ${BuildConfig.VERSION_NAME}",
                color    = Dim.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text          = title,
            color         = Dim,
            fontSize      = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.Medium,
            modifier      = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
        )
        Surface(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(18.dp),
            color     = Surface1,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
}

/**
 * Auto-clipboard, as its own switch.
 *
 * It used to be titled "Clipboard & Screen Control" and carry both features on
 * one control, because both ride the same accessibility service. But they are
 * different grants in the user's mind — wanting the Mac to drive the phone
 * while mirroring does not imply wanting every copy on this phone shipped to
 * the Mac — and the service never required them to move together. They are two
 * switches now; [ScreenControlRows] owns the other one.
 */
@Composable
private fun AutoClipRow(
    a11yOn:   Boolean,
    auto:     Boolean,
    onEnable: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Default.ContentCopy, Purple)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Auto clipboard", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    !a11yOn -> "Needs the accessibility service below"
                    auto    -> "Copies on this phone go to the Mac automatically"
                    else    -> "Off — send copies manually from the Clipboard tab"
                },
                color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        if (!a11yOn) {
            TonalChip("Enable", Purple, onEnable)
        } else {
            DroidSwitch(checked = auto, onCheckedChange = onToggle)
        }
    }
}

/**
 * The two controls that exist because of one specific, recurring problem:
 * banking apps refuse to run while *any* accessibility service is enabled.
 *
 * They enumerate `AccessibilityManager.getEnabledAccessibilityServiceList()`
 * and don't care which service it is or what it's allowed to do — so nothing
 * DroidDock declares about its own service avoids the conflict. Toggling it
 * off is the only answer, and these two rows make that cost as little as
 * possible:
 *
 *  · **Screen control** — off in one tap via `disableSelf()`. Android gives no
 *    API to switch one back *on*, so that direction deep-links to the Settings
 *    page rather than pretending.
 *  · **Lock without accessibility** — device admin, whose `lockNow()` those
 *    apps generally don't inspect. Grant it and the Mac's Lock button survives
 *    accessibility being off.
 *
 * Self-contained (own state, own `LocalContext`) rather than threaded through
 * `SettingsTab`'s parameter list, which is already twenty entries long.
 */
@Composable
private fun ScreenControlRows(serviceOn: Boolean, adminOn: Boolean) {
    val ctx = LocalContext.current
    // Both flags are flipped in *other* apps' UI — Settings, and the system's
    // device-admin prompt — so neither survives a trip away from this screen as
    // remembered state. They used to be read right here, with `remember(tick)`.
    // That put two binder round trips on the composition thread inside a
    // *lazily* composed row: LazyColumn disposes an item once it leaves the
    // viewport and composes it again on the way back, so scrolling Settings ran
    // a `Settings.Secure` lookup and an `isAdminActive` call per pass. They now
    // arrive from the caller's 2s off-thread permission poll.
    //
    // `justDisabled` is the one thing the poll can't cover: `disableSelf()`
    // unbinds asynchronously, so for up to two seconds the system still reports
    // the service as enabled and the row would sit there insisting nothing
    // happened. Keyed on `serviceOn` so it clears itself the moment the poll
    // catches up — and again if the service is switched back on in Settings.
    var justDisabled by remember(serviceOn) { mutableStateOf(false) }
    val a11yOn = serviceOn && !justDisabled

    // The device-admin prompt is the one screen here that must NOT be launched
    // with FLAG_ACTIVITY_NEW_TASK. `DeviceAdminAdd` returns a result to whoever
    // asked, so it refuses to run in a task of its own and kills itself on
    // sight — visibly: the activity starts and is destroyed in the same frame,
    // so the Grant button looked completely dead.
    //
    //     W/SecDeviceAdminAdd: Cannot start ADD_DEVICE_ADMIN as a new task
    //
    // A result launcher is the fix and the proof: it can only start from the
    // host activity's task, so the flag can never creep back in. The result
    // itself is ignored — the caller's poll reports the grant either way, and
    // the user can also cancel by pressing back, which returns nothing.
    val adminPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // The switch: does the Mac get to drive this screen? Independent of
    // auto-clipboard, and of whether the service is running at all.
    if (a11yOn) {
        // SharedPreferences, not a binder call — served from the in-memory map
        // after the first load, so this one is fine to read in composition.
        var screenControl by remember { mutableStateOf(Prefs.screenControl(ctx)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(Icons.Outlined.TouchApp, Purple)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Mac screen control", color = Fg, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text(
                    if (screenControl)
                        "Mac can tap, swipe and type on this phone"
                    else
                        "Off — mirroring still streams, but the Mac can't touch it",
                    color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            DroidSwitch(checked = screenControl, onCheckedChange = { v ->
                screenControl = v
                Prefs.setScreenControl(ctx, v)
                // Mirrored into the dispatch path so a tap doesn't have to read
                // SharedPreferences mid-gesture.
                AccessibilityControl.enabled = v
            })
        }
        RowDivider()
    }

    // The service itself — the prerequisite for both switches above, and the
    // one thing to reach for when a banking app refuses to start.
    ServiceRow(
        icon     = Icons.Outlined.Accessibility,
        tint     = if (a11yOn) Ok else Dim,
        title    = "Accessibility service",
        subtitle = if (a11yOn)
            "Running. Powers auto clipboard and screen control."
        else
            "Off — auto clipboard and Mac screen control both need this",
        granted  = null,
        action   = if (a11yOn) "Turn off" else "Enable",
    ) {
        if (a11yOn) {
            // Optimistic, then corrected by the poll: `disableSelf` unbinds the
            // service asynchronously, so re-reading immediately still reports
            // it enabled.
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
    RowDivider()
    ServiceRow(
        icon     = Icons.Outlined.Lock,
        tint     = Blue,
        title    = "Lock Without Accessibility",
        subtitle = if (adminOn)
            "Mac's Lock button works with Screen Control off"
        else
            "Optional. Lets the Mac lock this screen when Screen Control is off",
        granted  = adminOn,
        action   = "Grant",
    ) {
        runCatching { adminPrompt.launch(LockAdmin.enableIntent(ctx)) }
            .onFailure {
                Toast.makeText(
                    ctx,
                    "Couldn't open the device-admin screen on this phone",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
    if (adminOn) {
        // Deactivating is only reachable here — the system's own screen for it
        // is buried, and leaving an admin the user can't easily revoke is not
        // a defensible place to leave them.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            // No local echo needed: `removeActiveAdmin` takes effect
            // synchronously, so the next poll tick reports it gone.
            TonalChip("Revoke lock permission", Dim) { LockAdmin.disable(ctx) }
        }
    }
}

@Composable
private fun ServiceRow(
    icon:     ImageVector,
    tint:     Color,
    title:    String,
    subtitle: String,
    granted:  Boolean?,
    action:   String,
    onClick:  () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        if (granted == true) {
            Box(
                modifier = Modifier
                    .background(Ok.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Ok, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("On", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            TonalChip(action, tint, onClick)
        }
    }
}

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
private fun UpdateRow(known: UpdateChecker.Release?) {
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
        is UpdateUi.Failed      -> stringResource(R.string.update_failed, s.message)
    }

    val action = when (state) {
        is UpdateUi.Available   -> if (canInstall) stringResource(R.string.update_action_download)
                                   else stringResource(R.string.update_action_allow)
        is UpdateUi.Ready       -> stringResource(R.string.update_action_install)
        else                    -> stringResource(R.string.update_action_check)
    }

    val busy = state is UpdateUi.Checking || state is UpdateUi.Downloading

    ServiceRow(
        icon     = Icons.Outlined.SystemUpdate,
        tint     = Blue,
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
                    }.onSuccess { state = UpdateUi.Ready(s.release, it) }
                        .onFailure { state = UpdateUi.Failed(it.message ?: "download failed") }
                }
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

/**
 * Whether the app looks for a new release on its own.
 *
 * Owns its own state rather than threading a pair through [SettingsTab]'s
 * already long parameter list — nothing else in the app reads this preference,
 * and the check that does reads it straight from [Prefs] at app open.
 */
@Composable
private fun AutoUpdateRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.autoCheckUpdates(ctx)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Outlined.Schedule, Dim)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Check automatically", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Looks for a new version when you open the app, at most once a day. " +
                    "It only tells you — nothing downloads or installs on its own.",
                color = Dim, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        DroidSwitch(checked = on, onCheckedChange = { v -> on = v; Prefs.setAutoCheckUpdates(ctx, v) })
    }
}

/** Every state [UpdateRow] can be in, and nothing else. */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object Current : UpdateUi
    data class Available(val release: UpdateChecker.Release) : UpdateUi
    data class Downloading(val release: UpdateChecker.Release, val percent: Int) : UpdateUi
    data class Ready(val release: UpdateChecker.Release, val apk: File) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Light / Dark / System, as a segmented control.
 *
 * A three-way choice with a live preview attached to it — every surface behind
 * this row repaints on tap — so it earns showing all options at once rather
 * than hiding two of them behind a dialog.
 */
@Composable
private fun ThemeRow(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.DarkMode, Blue)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Theme", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    when (mode) {
                        ThemeMode.LIGHT  -> "Always light"
                        ThemeMode.DARK   -> "Always dark"
                        ThemeMode.SYSTEM -> "Follows your phone's setting"
                    },
                    color = Dim, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Surface3)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ThemeMode.entries.forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Amber else Color.Transparent)
                        .clickable { onMode(option) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (option) {
                            ThemeMode.LIGHT  -> "Light"
                            ThemeMode.DARK   -> "Dark"
                            ThemeMode.SYSTEM -> "System"
                        },
                        color = if (selected) OnAmber else Dim,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Editable phone name + the address the Mac would reach it on.
 *
 * The name is committed on focus-loss rather than per keystroke: it goes out in
 * the `hello` frame, and rewriting it on every character would mean the stored
 * value spends most of its life as a half-typed prefix.
 */
@Composable
private fun DeviceIdentityRows() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    // Off the main thread, and seeded from the last answer.
    //
    // `localIpAddress()` enumerates every interface and asks each whether it is
    // up — a handful of ioctls, more with a tailnet up — and this row lives in
    // a LazyColumn item, so composing it ran the whole sweep on the composition
    // thread every time the row scrolled back into view. Two bugs deep now: it
    // was first keyed on `name`, which re-ran it per keystroke in the field
    // below.
    var resolved by remember { mutableStateOf(ConnectionManager.lastKnownLocalIp != null) }
    var localIp by remember { mutableStateOf(ConnectionManager.lastKnownLocalIp) }
    LaunchedEffect(Unit) {
        localIp = withContext(Dispatchers.IO) { ConnectionManager.localIpAddress() }
        resolved = true
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.PhoneAndroid, Ok)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("This phone", color = Fg, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text(
                    when {
                        localIp != null -> "Local IP: $localIp"
                        resolved        -> "No network address"
                        else            -> "Checking network…"
                    },
                    color = Dim, fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Device name", fontSize = 12.sp) },
            placeholder = { Text(ConnectionManager.hardwareName(), fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) Prefs.setDeviceName(ctx, name)
                },
        )
        Text(
            "Shown on your Mac. Takes effect on the next reconnect.",
            color = Dim, fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )
    }
}

/**
 * "Add tile" buttons, mirroring the reference's pair.
 *
 * `requestAddTileService` is API 33+; below that the OS has no way for an app to
 * offer a tile, so the row explains where to find them by hand rather than
 * showing a button that can't work.
 */
@Composable
private fun TileRows() {
    val ctx = LocalContext.current

    if (Build.VERSION.SDK_INT < 33) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
            Text(
                "Add DroidDock's Connection and Clipboard tiles by editing your " +
                    "Quick Settings panel.",
                color = Dim, fontSize = 12.sp, lineHeight = 16.sp
            )
        }
        return
    }

    val addTile: (Class<*>, String) -> Unit = { cls, label ->
        runCatching {
            val sm = ctx.getSystemService(android.app.StatusBarManager::class.java)
            sm?.requestAddTileService(
                android.content.ComponentName(ctx, cls),
                label,
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat),
                { it.run() },
                { /* result code — the system already told the user */ }
            )
        }.onFailure {
            Toast.makeText(ctx, "Add it from the Quick Settings editor", Toast.LENGTH_SHORT).show()
        }
    }

    ServiceRow(
        icon     = Icons.Outlined.LaptopMac,
        tint     = Blue,
        title    = "Connection tile",
        subtitle = "Connect or disconnect from Quick Settings",
        granted  = null,
        action   = "Add"
    ) { addTile(ConnectionTileService::class.java, "DroidDock") }
    RowDivider()
    ServiceRow(
        icon     = Icons.Outlined.ContentPaste,
        tint     = Purple,
        title    = "Clipboard tile",
        subtitle = "Send what you copied straight to the Mac",
        granted  = null,
        action   = "Add"
    ) { addTile(ClipTileService::class.java, "Send to Mac") }
    RowDivider()
    ServiceRow(
        icon     = Icons.Outlined.Accessibility,
        tint     = Amber,
        title    = "Accessibility tile",
        subtitle = "Switch screen control off in one tap — for banking apps",
        granted  = null,
        action   = "Add"
    ) { addTile(A11yTileService::class.java, "DroidDock Access") }
}

/**
 * Which tab the app opens on. "Auto" is the reference's "Dynamic" — Connect
 * while unpaired, Home once linked.
 */
@Composable
private fun DefaultTabRow(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "dynamic" to "Auto",
        "home" to "Home",
        "connect" to "Connect",
        "clipboard" to "Clipboard",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Dashboard, Orange)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Opening tab", color = Fg, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text(
                    if (selected == "dynamic") "Connect until paired, then Home"
                    else "Always open on ${options.firstOrNull { it.first == selected }?.second ?: "Home"}",
                    color = Dim, fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Surface3)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEach { (id, label) ->
                val on = id == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) Amber else Color.Transparent)
                        .clickable { onSelect(id) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (on) OnAmber else Dim,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun TonalChip(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DroidSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked         = checked,
        onCheckedChange = onCheckedChange,
        colors          = SwitchDefaults.colors(
            checkedThumbColor     = OnAmber,
            checkedTrackColor     = Amber,
            uncheckedThumbColor   = Dim,
            uncheckedTrackColor   = Surface3,
            uncheckedBorderColor  = Dim.copy(alpha = 0.4f)
        )
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = LineColor, thickness = 0.5.dp)
}

// ── Manual pair dialog ─────────────────────────────────────────────────────

@Composable
private fun ManualPairDialog(onDismiss: () -> Unit, onPair: (Pairing) -> Unit) {
    val ctx   = LocalContext.current
    var ip    by remember { mutableStateOf("") }
    var port  by remember { mutableStateOf("48484") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = Surface2,
        shape             = RoundedCornerShape(20.dp),
        title             = { Text("Pair manually", color = Fg, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Open Pair Device on the Mac and read the IP, port and token it shows.",
                    color = Dim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = ip, onValueChange = { ip = it }, singleLine = true,
                    label = { Text("Mac IP (e.g. 192.168.0.108)") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = port, onValueChange = { port = it }, singleLine = true,
                    label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Token") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ipt = ip.trim(); val tok = token.trim()
                val p   = port.trim().toIntOrNull() ?: 48484
                if (ipt.isEmpty() || tok.isEmpty()) {
                    Toast.makeText(ctx, "Enter the IP and token shown on the Mac", Toast.LENGTH_SHORT).show()
                } else {
                    onPair(Pairing(listOf(ipt), p, tok, "Mac"))
                }
            }) { Text("Pair", color = Amber, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Dim) }
        }
    )
}

// ── Pause dialog ───────────────────────────────────────────────────────────

@Composable
private fun PauseDialog(onDismiss: () -> Unit, onPause: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(20.dp),
        title = { Text("Pause DroidDock", color = Fg, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Stops auto-reconnect and tells your Mac to stop trying. " +
                            "The persistent notification stays silent.",
                    color = Dim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                PauseOption("Pause for 1 hour")  { onPause(60L * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Pause for 8 hours") { onPause(8L * 60 * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Until I resume")    { onPause(null) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Dim) }
        }
    )
}

@Composable
private fun PauseOption(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth().height(48.dp),
        shape     = RoundedCornerShape(12.dp),
        colors    = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
        border    = androidx.compose.foundation.BorderStroke(0.5.dp, Amber.copy(alpha = 0.4f))
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

// ── Feature guide ──────────────────────────────────────────────────────────

@Composable
private fun FeatureGuideScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Fg)
            }
            Spacer(Modifier.height(4.dp))
            Text("Feature Guide", color = Fg, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Text("Tap any card for step-by-step help.", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            GUIDE_SECTIONS.forEach { section ->
                GuideCard(section)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuideCard(section: GuideSection) {
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        color     = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(section.title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(section.subtitle, color = Dim, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    imageVector        = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint               = Amber,
                    modifier           = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(top = 12.dp)) {
                    section.steps.forEachIndexed { i, step ->
                        Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text("${i + 1}.", color = Amber, fontSize = 12.sp,
                                modifier = Modifier.width(22.dp))
                            Text(step, color = Dim, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

/// The runtime permissions the "Grant" button asks for, and the same list
/// `PermissionHealth` reads back — sharing it is what keeps the phone's own
/// Settings screen and the Mac's health panel from disagreeing about whether
/// the Phone group is complete.
private val PHONE_PERMS = PermissionHealth.PHONE_HEALTH_PERMS

/**
 * The current clipboard as plain text, or "" when there is nothing readable.
 *
 * `primaryClip` is non-null whenever a clip *description* exists, which includes
 * an emptied clip with zero items — and `getItemAt(0)` on that throws
 * IndexOutOfBoundsException. Android also refuses background reads outright
 * (13+, and every read on One UI when DroidDock isn't focused), which surfaces as
 * a SecurityException rather than a null.
 */
private fun readClipboardText(cm: ClipboardManager, ctx: Context): String = runCatching {
    val clip = cm.primaryClip ?: return@runCatching ""
    if (clip.itemCount == 0) return@runCatching ""
    clip.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}.getOrDefault("")

/** The permission reads that cost a binder round trip, gathered off the main
 *  thread in one pass so the UI applies them as a single state update. */
private data class PermissionSnapshot(
    val notifAccess: Boolean,
    val phonePerms: Boolean,
    val allFiles: Boolean,
    val clipA11y: Boolean,
    val overlayOk: Boolean,
    val deviceAdmin: Boolean,
)

private fun phonePermsGranted(ctx: Context): Boolean =
    PHONE_PERMS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun notifAccessGranted(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?.contains(ctx.packageName) == true

private fun clipAccessibilityEnabled(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains(ClipAccessibilityService::class.java.name) == true

private fun pauseSubtitle(until: Long): String =
    if (until == Long.MAX_VALUE) "Until you resume"
    else "Resumes " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(until))

private data class GuideSection(val title: String, val subtitle: String, val steps: List<String>)

private val GUIDE_SECTIONS = listOf(
    GuideSection("Quick Start", "The fastest way to get DroidDock working.",
        listOf("Open DroidDock on your Mac first, then keep this app open while you pair.",
            "Use QR for the first setup, or USB for the fastest detection.",
            "After pairing, set Battery to Unrestricted so reconnects stay reliable.",
            "Turn on Notification Access for alerts on Mac, and SMS · Contacts for the full link.",
            "From the home screen, use Pair, Send Clipboard, or the Mac's tabs.")),
    GuideSection("Pairing & Connection", "Connect your phone and Mac over Wi-Fi or USB.",
        listOf("Install DroidDock on your Mac and this app on Android.",
            "Use the same Wi-Fi network, or plug in a USB cable for the wired path.",
            "Open Pair Device on the Mac — it shows a QR code and manual IP/token.",
            "On Android, tap Pair With Mac and scan the QR, or choose Enter IP manually.",
            "Once paired, both devices auto-connect whenever both apps are open.")),
    GuideSection("File Transfer", "Send files between your phone and Mac.",
        listOf("Make sure both devices are connected.",
            "On Mac: drag a file onto DroidDock, or use Send To Phone in the Files tab.",
            "On Android: share from any app or use Send Clipboard/share-sheet actions.",
            "Progress shows on both devices in real time.",
            "Received files appear in your Downloads folder.")),
    GuideSection("Photos & Videos", "Browse and download your phone's photos.",
        listOf("Connect both devices and grant All-files access on Android.",
            "Open the Photos tab on Mac — thumbnails load automatically.",
            "Click any photo to preview; videos show a play badge.",
            "Use the download button to save the original to your Mac.")),
    GuideSection("Clipboard Sharing", "Copy on one device, paste on the other.",
        listOf("Connect both devices.",
            "Copy any text on your Mac — it's automatically available on Android.",
            "Android → Mac: use Send Clipboard Now, the Quick Settings tile, or text → ⋮ → Send to Mac.",
            "Works with text content. Images use the file transfer feature.")),
    GuideSection("Notifications", "See phone notifications on your Mac.",
        listOf("Grant Notification Access on Android.",
            "Connect both devices.",
            "Phone notifications appear on your Mac as they arrive.",
            "Reply or dismiss right from the Mac for supported apps.",
            "The NOTIFS tab badge shows the unread count.")),
    GuideSection("SMS & Messages", "Read and reply to texts from your Mac.",
        listOf("Connect both devices and grant the SMS permission.",
            "Open the Messages tab on Mac to see your conversations.",
            "Click a conversation to see the full thread.",
            "Type at the bottom and press Enter to send a reply.")),
    GuideSection("Screen Mirroring", "Mirror and control your phone from your Mac.",
        listOf("Wi-Fi mirror: pair the DroidDock phone app, then click 'Mirror via Wi-Fi' on Mac.",
            "ADB mirror: connect USB cable with Developer Options, click 'Mirror via ADB' on Mac.",
            "Your phone screen appears on your Mac — you can click, scroll and type.",
            "Close the mirror window on the Mac to stop."))
)
