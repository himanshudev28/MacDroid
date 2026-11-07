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

class MainActivity : ComponentActivity() {
    private val pairFlow = MutableStateFlow<String?>(null)

    /** Theme choice, hoisted here so the Settings picker can change it live
     *  rather than only on next launch. */

    override fun onStart() {
        super.onStart()
        // Coming to the foreground ends any long reconnect backoff.
        ConnectionManager.wake(applicationContext)
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
        // Only on a fresh launch. A recreation (rotation, theme change) hands back the
        // same intent, and re-reading it would re-offer a link the user already handled.
        if (savedInstanceState == null) {
            intent?.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
                ?.let { pairFlow.value = it }
        }
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
        // Consumed: a later recreation must not see this link again.
        setIntent(Intent(intent).setData(null))
    }
}

/** The one QR-scan configuration: QR only, silent, free to rotate. */
internal fun scanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setBeepEnabled(false)
    setOrientationLocked(false)
}

/** A preference mirrored into Compose state: reads once, and every [set] updates the UI and persists. */
@Stable
internal class PrefState<T>(initial: T, private val write: (T) -> Unit) {
    var value by mutableStateOf(initial)
        private set
    fun set(v: T) { value = v; write(v) }
}

@Composable
internal fun <T> rememberPref(read: () -> T, write: (T) -> Unit): PrefState<T> =
    remember { PrefState(read(), write) }

/** Tabs a saved `defaultTab` may legitimately point at in [layout]; anything else (a legacy id) falls back to Home. */
internal fun validTabs(layout: NavLayout): Set<String> = buildSet {
    add(Dest.HOME); add(Dest.FILES); add(Dest.CONTROL); add(Dest.SETTINGS)
    if (layout == NavLayout.CLASSIC_5) add(Dest.CLIPBOARD)
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
    val macInfo     by ConnectionManager.macInfo.collectAsStateWithLifecycle()
    val knownDevices by ConnectionManager.knownDevices.collectAsStateWithLifecycle()

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
    val clipAuto = rememberPref({ Prefs.clipboardAuto(ctx) }, { Prefs.setClipboardAuto(ctx, it) })
    var overlayOk   by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    val autoMirror = rememberPref({ Prefs.autoMirror(ctx) }, { Prefs.setAutoMirror(ctx, it) })
    val expandNet = rememberPref({ Prefs.expandNetworking(ctx) }, { Prefs.setExpandNetworking(ctx, it) })
    val clipKeepHistory = rememberPref({ Prefs.clipboardHistory(ctx) }, { Prefs.setClipboardHistory(ctx, it) })
    val defaultTab = rememberPref({ Prefs.defaultTab(ctx) }, { Prefs.setDefaultTab(ctx, it) })
    val crashNotify = rememberPref({ Prefs.notifyOnCrash(ctx) }, { Prefs.setNotifyOnCrash(ctx, it) })
    var showManual  by rememberSaveable { mutableStateOf(false) }
    var showGuide   by rememberSaveable { mutableStateOf(false) }
    var showPause   by rememberSaveable { mutableStateOf(false) }
    // A deep link waiting for the user's Pair / Cancel. Saveable so the sheet survives rotation.
    var pendingLink by rememberSaveable { mutableStateOf<String?>(null) }
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
                else -> if (saved in validTabs(appearance.layout)) saved else "home"
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
            // The callback runs on an OkHttp / IO thread; Toast and Compose state need the main one.
            Handler(Looper.getMainLooper()).post {
                sending = false
                Toast.makeText(
                    ctx,
                    if (ok) t("File sent to Mac") else (err ?: t("Send failed")),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Pairing can change from outside this screen (a widget, a tile, the service, another
    // activity), so re-derive from the stored active pairing whenever the device list changes.
    LaunchedEffect(knownDevices) {
        val active = Prefs.load(ctx)
        activeToken = active?.token
        paired = active != null
    }

    val applyPairing: (Pairing, Boolean) -> Unit = { pairing, fromQr ->
        BridgeService.start(ctx)
        LinkSecurity.clearProblems()
        ConnectionManager.onPaired(ctx, pairing, fromQr)
        paired = true
        activeToken = pairing.token
        Toast.makeText(ctx, t("Paired with {name}", "name" to pairing.macName), Toast.LENGTH_SHORT).show()
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

    // A deep link is untrusted input: never pair from it directly. Valid links wait for the
    // user's confirmation; links without a key fingerprint are refused outright.
    LaunchedEffect(pairUri) {
        pairUri ?: return@LaunchedEffect
        clearPairUri()
        when (evaluateDeepLink(pairUri)) {
            is DeepLinkDecision.Confirm -> pendingLink = pairUri
            DeepLinkDecision.RefusedNoFingerprint -> Toast.makeText(
                ctx, t("This link can't be verified. Scan the QR code from inside DroidDock instead."), Toast.LENGTH_LONG
            ).show()
            DeepLinkDecision.Invalid -> Toast.makeText(ctx, t("Not a DroidDock QR code"), Toast.LENGTH_SHORT).show()
        }
    }
    val pendingPairing = remember(pendingLink) {
        (pendingLink?.let { evaluateDeepLink(it) } as? DeepLinkDecision.Confirm)?.pairing
    }
    if (pendingPairing != null) {
        DeepLinkConfirmSheet(
            pairing = pendingPairing,
            onCancel = { pendingLink = null },
            onPair = { pendingLink = null; applyPairing(pendingPairing, false) },
        )
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleQr(it) }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanLauncher.launch(scanOptions())
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
        if (hasCam) scanLauncher.launch(scanOptions())
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
        // Dialog flags are cleared on the way in: they live in rememberSaveable state that
        // would otherwise still be true when the guide closes and re-open the sheet.
        LaunchedEffect(Unit) { showManual = false; showPause = false }
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
                onStopSearching = { ConnectionManager.disconnect(ctx) },
                onGoToConnect = { currentTab = "connect" },
                onNavigate = { currentTab = it },
                settingsBadge = newRelease != null,
            )
        },
    ) { tab ->
        when (tab) {
            "home" -> HomeDest(
                layout = appearance.layout, paired = paired, activeToken = activeToken,
                onPause = { showPause = true },
                onPickDevice = { device ->
                    ConnectionManager.connectTo(ctx, device)
                    activeToken = device.token
                    paired = true
                },
                onForgetActive = { forgetActiveMac() },
                onGoToConnect = { currentTab = "connect" },
                onGoToFiles = { currentTab = "files" },
            )
            "connect" -> ConnectDest(
                paired = paired, onBack = { currentTab = "home" },
                onScan = launchScan, onManual = { showManual = true }, onForget = { forgetActiveMac() },
            )
            "files" -> FilesDest(sending = sending, onSendFile = { if (!sending) filePicker.launch("*/*") })
            "clipboard" -> ClipboardDest(keepHistory = clipKeepHistory)
            "control" -> ControlDest(overlayOk = overlayOk, autoMirror = autoMirror)
            "settings" -> SettingsDest(
                perms = PermissionSnapshot(notifAccess, phonePerms, allFiles, clipA11y, overlayOk, deviceAdmin),
                clipAuto = clipAuto, expandNet = expandNet, crashNotify = crashNotify, defaultTab = defaultTab,
                themeMode = themeMode, onThemeMode = onThemeMode, pitchBlack = pitchBlack, onPitchBlack = onPitchBlack,
                onOpenConnect = { currentTab = "connect" },
                onGrantPhonePerms = { permLauncher.launch(PHONE_PERMS) },
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
    onStopSearching: () -> Unit,
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
        // Not connected and not paused = the phone is searching: offer the way to stop it.
        else -> Triple(t("Stop searching"), Icons.Rounded.Pause, onStopSearching)
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

