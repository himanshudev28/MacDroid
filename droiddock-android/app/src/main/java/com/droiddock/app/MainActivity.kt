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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Ink        = Color(0xFF0D0D12)
private val Surface1   = Color(0xFF14141B)
private val Surface2   = Color(0xFF1C1C26)
private val Surface3   = Color(0xFF222230)
private val Amber      = Color(0xFFF5A623)
private val AmberDim   = Color(0xFFCC7B0E)
private val Ok         = Color(0xFF34C759)
private val Bad        = Color(0xFFFF453A)
private val Fg         = Color(0xFFF0EFE9)
private val Dim        = Color(0xFF72728A)
private val LineColor  = Color(0xFF22222F)
private val Purple     = Color(0xFFAA84FF)
private val Blue       = Color(0xFF5B8FFF)
private val Orange     = Color(0xFFF0934C)

class MainActivity : ComponentActivity() {
    private val pairFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs.load(this) != null) BridgeService.start(this)
        intent?.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
            ?.let { pairFlow.value = it }
        setContent {
            val pairUri by pairFlow.collectAsState()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background      = Ink,
                    surface         = Surface1,
                    surfaceVariant  = Surface2,
                    primary         = Amber,
                    onPrimary       = Color(0xFF1A0E00),
                    secondary       = Ok,
                    onSecondary     = Color(0xFF002210),
                    onBackground    = Fg,
                    onSurface       = Fg,
                    outline         = LineColor,
                    outlineVariant  = LineColor.copy(alpha = 0.6f),
                )
            ) { DroidDockScreen(pairUri) { pairFlow.value = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("droiddock://pair") }
            ?.let { pairFlow.value = it }
    }
}

@Composable
private fun DroidDockScreen(pairUri: String? = null, clearPairUri: () -> Unit = {}) {
    val ctx         = LocalContext.current
    val connected   by ConnectionManager.connected.collectAsState()
    val macName     by ConnectionManager.macName.collectAsState()
    val event       by ConnectionManager.lastEvent.collectAsState()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsState()
    val isPaused    = pausedUntil != 0L
    val macCaps     by ConnectionManager.macCaps.collectAsState()

    var paired      by remember { mutableStateOf(Prefs.load(ctx) != null) }
    var notifAccess by remember { mutableStateOf(notifAccessGranted(ctx)) }
    var phonePerms  by remember { mutableStateOf(phonePermsGranted(ctx)) }
    var allFiles    by remember { mutableStateOf(FileRepo.hasAllFiles()) }
    var clipA11y    by remember { mutableStateOf(clipAccessibilityEnabled(ctx)) }
    var clipAuto    by remember { mutableStateOf(Prefs.clipboardAuto(ctx)) }
    var overlayOk   by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var autoMirror  by remember { mutableStateOf(Prefs.autoMirror(ctx)) }
    var showManual  by remember { mutableStateOf(false) }
    var showGuide   by remember { mutableStateOf(false) }
    var showPause   by remember { mutableStateOf(false) }
    var sending         by remember { mutableStateOf(false) }
    val activeTransfers by TransferManager.activeTransfers.collectAsState()
    val recentTransfers by TransferManager.recentTransfers.collectAsState()
    var currentTab      by remember { mutableStateOf("home") }

    // The "macfs" tab only exists while the connected Mac advertises the "macfs" cap
    // (Phase 19). If it disappears — reconnect to an older Mac build — bounce off the
    // tab immediately rather than leaving it selected but absent from the nav bar.
    LaunchedEffect(macCaps) {
        if (currentTab == "macfs" && !macCaps.contains("macfs")) currentTab = "home"
    }

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
        Prefs.save(ctx, pairing)
        paired = true
        BridgeService.start(ctx)
        ConnectionManager.restart(ctx)
        Toast.makeText(ctx, "Paired with ${pairing.macName}", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            notifAccess = notifAccessGranted(ctx)
            phonePerms  = phonePermsGranted(ctx)
            allFiles    = FileRepo.hasAllFiles()
            clipA11y    = clipAccessibilityEnabled(ctx)
            overlayOk   = Settings.canDrawOverlays(ctx)
            kotlinx.coroutines.delay(2000)
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
                val navItems = buildList {
                    add(Triple("home",     "Home",     Icons.Filled.Home))
                    add(Triple("connect",  "Connect",  Icons.Outlined.WifiTethering))
                    add(Triple("files",    "Files",    Icons.Outlined.Folder))
                    // Phase 19 — entirely absent (not just disabled) unless the connected
                    // Mac advertised the "macfs" cap in its `welcome`.
                    if (macCaps.contains("macfs")) {
                        add(Triple("macfs", "Mac Files", Icons.Outlined.LaptopMac))
                    }
                    add(Triple("mirror",   "Mirror",   Icons.Outlined.ScreenShare))
                    add(Triple("settings", "Settings", Icons.Outlined.Settings))
                }
                navItems.forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = currentTab == id,
                        onClick  = { currentTab = id },
                        icon     = { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) },
                        label    = { Text(label, fontSize = 10.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Color(0xFF1A0E00),
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
            when (currentTab) {
                "home" -> HomeTab(
                    connected   = connected,
                    isPaused    = isPaused,
                    paired      = paired,
                    macName     = macName,
                    event       = event,
                    pausedUntil = pausedUntil,
                    onPause     = { showPause = true },
                    onResume    = { ConnectionManager.resume(ctx) },
                    onGoToConnect = { currentTab = "connect" },
                    onGoToMirror  = { currentTab = "mirror" },
                    onGoToFiles   = { currentTab = "files" },
                )
                "connect" -> ConnectTab(
                    connected   = connected,
                    paired      = paired,
                    macName     = macName,
                    onScan      = launchScan,
                    onManual    = { showManual = true },
                    onForget    = {
                        Prefs.clear(ctx)
                        ConnectionManager.shutdown()
                        BridgeService.stop(ctx)
                        paired = false
                        Toast.makeText(ctx, "Forgot this Mac", Toast.LENGTH_SHORT).show()
                    },
                )
                "files" -> FilesTab(
                    connected       = connected,
                    sending         = sending,
                    activeTransfers = activeTransfers,
                    recentTransfers = recentTransfers,
                    onSendFile = { if (!sending) filePicker.launch("*/*") },
                    onSendClipboard = {
                        val cm   = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
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
                )
                "macfs" -> MacFilesTab(connected = connected)
                "mirror" -> MirrorTab(
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
                )
                "settings" -> SettingsTab(
                    clipA11y    = clipA11y,
                    clipAuto    = clipAuto,
                    notifAccess = notifAccess,
                    phonePerms  = phonePerms,
                    allFiles    = allFiles,
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
                )
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
    event:        String?,
    pausedUntil:  Long,
    onPause:      () -> Unit,
    onResume:     () -> Unit,
    onGoToConnect: () -> Unit,
    onGoToMirror:  () -> Unit,
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
                Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFF2A1900),
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

        Spacer(Modifier.height(16.dp))

        Text(
            text          = "SYNC & SHARING",
            color         = Dim,
            fontSize      = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.SemiBold,
            modifier      = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        val features = listOf(
            FeatureTile("Notifications", "Phone alerts on Mac", Icons.Outlined.Notifications, Blue, connected),
            FeatureTile("Messages",      "SMS from your Mac",   Icons.Outlined.Message,       Ok,   connected),
            FeatureTile("Calls",         "Call log & alerts",   Icons.Outlined.Call,          Ok,   connected),
            FeatureTile("Clipboard",     "Copy between devices",Icons.Outlined.ContentCopy,   Purple, connected),
            FeatureTile("Files",         "Transfer anything",   Icons.Outlined.Folder,        Orange, true),
            FeatureTile("Photos",        "Browse phone gallery",Icons.Outlined.Photo,         Blue, connected),
            FeatureTile("Camera",        "Use phone as webcam", Icons.Outlined.Videocam,      Orange, true),
            FeatureTile("Screen Mirror", "Project to Mac",      Icons.Outlined.ScreenShare,   Purple, true),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            features.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { tile ->
                        FeatureTileCard(tile = tile, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private data class FeatureTile(
    val title:   String,
    val status:  String,
    val icon:    ImageVector,
    val tint:    Color,
    val active:  Boolean,
)

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
    val headline = when {
        isPaused  -> "Paused"
        connected -> macName ?: "Mac"
        paired    -> "Searching…"
        else      -> "Not paired"
    }
    val subline = when {
        isPaused  -> pauseSubtitle(pausedUntil)
        connected -> if (event.isNullOrBlank()) "Connected · Wi-Fi" else "Connected · $event"
        paired    -> "Waiting for Mac to come online"
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
                            color = Color(0xFF1A0E00))
                    }
                }
            }
        }
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

@Composable
private fun FeatureTileCard(tile: FeatureTile, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(22.dp),
        color    = Surface1,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(tile.tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(tile.icon, null, tint = tile.tint, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (tile.active) Ok else Dim.copy(alpha = 0.3f), CircleShape)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(tile.status, color = Dim, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Connection", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(4.dp))
        Text("Pair or manage your Mac link", color = Dim, fontSize = 13.sp)

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
                        color      = Color(0xFF1A0E00)
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
                        contentColor = Color(0xFF1A0E00),
                        disabledContainerColor = Amber.copy(alpha = 0.4f),
                        disabledContentColor = Color(0xFF1A0E00).copy(alpha = 0.5f)
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

private fun macFsEntryPath(base: String, entry: JSONObject): String {
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
                    onClick = { path = path.substringBeforeLast('/', "") },
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
    onEnableClip:     () -> Unit,
    onToggleClipAuto: (Boolean) -> Unit,
    onEnableNotif:    () -> Unit,
    onGrantPhonePerms: () -> Unit,
    onGrantFiles:     () -> Unit,
    onBattery:        () -> Unit,
    onOpenGuide:      () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Settings", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp)

        Spacer(Modifier.height(20.dp))

        SectionCard("PERMISSIONS") {
            AutoClipRow(
                a11yOn   = clipA11y,
                auto     = clipAuto,
                onEnable = onEnableClip,
                onToggle = onToggleClipAuto,
            )
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

        Spacer(Modifier.height(12.dp))

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

        Spacer(Modifier.height(12.dp))

        Text(
            text     = "DroidDock · 0.9.1",
            color    = Dim.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        Spacer(Modifier.height(24.dp))
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
            Text("Auto Clipboard", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    !a11yOn -> "Enable accessibility to auto-send copies to Mac"
                    auto    -> "On — copies on this phone appear on Mac instantly"
                    else    -> "Manual — use share sheet to push copies"
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
            checkedThumbColor     = Color(0xFF1A0E00),
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

private val PHONE_PERMS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.CALL_PHONE
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
