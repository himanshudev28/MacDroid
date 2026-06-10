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
import org.json.JSONObject

// ── Palette (aligned with the Mac app) ───────────────────────────────────
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs.load(this) != null) BridgeService.start(this)
        setContent {
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
            ) { DroidDockScreen() }
        }
    }
}

@Composable
private fun DroidDockScreen() {
    val ctx         = LocalContext.current
    val connected   by ConnectionManager.connected.collectAsState()
    val macName     by ConnectionManager.macName.collectAsState()
    val event       by ConnectionManager.lastEvent.collectAsState()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsState()
    val isPaused    = pausedUntil != 0L

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
    var showScan    by remember { mutableStateOf(false) }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showScan = true
        else Toast.makeText(ctx, "Camera permission needed to scan QR", Toast.LENGTH_SHORT).show()
    }

    val applyPairing: (Pairing) -> Unit = { pairing ->
        Prefs.save(ctx, pairing)
        paired = true
        BridgeService.start(ctx)
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

    val handleQr: (String) -> Unit = { qrText ->
        runCatching {
            val o   = JSONObject(qrText)
            val ips = mutableListOf<String>()
            val arr = o.optJSONArray("ips")
            if (arr != null) for (i in 0 until arr.length()) ips.add(arr.getString(i))
            require(ips.isNotEmpty() && o.has("token"))
            Pairing(ips, o.optInt("port", 48484), o.getString("token"), o.optString("name", "Mac"))
        }.onSuccess { pairing ->
            applyPairing(pairing)
        }.onFailure {
            Toast.makeText(ctx, "Not a DroidDock QR code", Toast.LENGTH_SHORT).show()
        }
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

    // ── Full-screen overlays take priority ───────────────────────────
    if (showScan) {
        ScanScreen(
            onResult = { qrText -> showScan = false; handleQr(qrText) },
            onManual = { showScan = false; showManual = true },
            onBack   = { showScan = false },
            onHelp   = { showScan = false; showGuide = true }
        )
        return
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

    // ── Main screen ───────────────────────────────────────────────────
    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── App bar ──────────────────────────────────────────────
            AppHeader()

            Spacer(Modifier.height(20.dp))

            // ── Connection status card ────────────────────────────────
            ConnectionCard(
                connected   = connected,
                isPaused    = isPaused,
                paired      = paired,
                macName     = macName,
                event       = event,
                pausedUntil = pausedUntil,
                onPause     = { showPause = true },
                onResume    = { ConnectionManager.resume(ctx) }
            )

            Spacer(Modifier.height(16.dp))

            // ── Pair / re-pair button ─────────────────────────────────
            Button(
                onClick = {
                    val hasCam = ctx.checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    if (hasCam) showScan = true
                    else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Amber),
                shape    = RoundedCornerShape(14.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = if (paired) "Re-pair with Mac" else "Pair with Mac",
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
                TextButton(onClick = { showManual = true }) {
                    Text("Enter IP manually", color = Amber, fontSize = 12.sp)
                }
                if (paired) {
                    TextButton(onClick = {
                        Prefs.clear(ctx)
                        ConnectionManager.shutdown()
                        BridgeService.stop(ctx)
                        paired = false
                        Toast.makeText(ctx, "Forgot this Mac", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Forget this Mac", color = Dim, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Permissions / services ────────────────────────────────
            SectionCard("Essential Services") {
                AutoClipRow(
                    a11yOn   = clipA11y,
                    auto     = clipAuto,
                    onEnable = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        Toast.makeText(ctx, "Find DroidDock Clipboard and turn it on", Toast.LENGTH_LONG).show()
                    },
                    onToggle = { v -> clipAuto = v; Prefs.setClipboardAuto(ctx, v) }
                )
                RowDivider()
                AutoMirrorRow(
                    overlayOk = overlayOk,
                    auto      = autoMirror,
                    onEnable  = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                        Toast.makeText(ctx, "Allow DroidDock to display over other apps", Toast.LENGTH_LONG).show()
                    },
                    onToggle = { v ->
                        autoMirror = v; Prefs.setAutoMirror(ctx, v)
                        if (!v) MirrorService.stop(ctx)
                    }
                )
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.Notifications,
                    tint     = Blue,
                    title    = "Notification Access",
                    subtitle = "Show phone notifications on your Mac",
                    granted  = notifAccess,
                    action   = "Enable"
                ) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.Message,
                    tint     = Ok,
                    title    = "SMS · Contacts · Calls",
                    subtitle = "Texts, contacts and call alerts on Mac",
                    granted  = phonePerms,
                    action   = "Grant"
                ) { permLauncher.launch(PHONE_PERMS) }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.FolderOpen,
                    tint     = Orange,
                    title    = "All-files Access",
                    subtitle = "Browse and transfer your phone's files",
                    granted  = allFiles,
                    action   = "Grant"
                ) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 30) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }
                }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.BatteryChargingFull,
                    tint     = Dim,
                    title    = "Background (Battery)",
                    subtitle = "Keep the link alive when screen is off",
                    granted  = null,
                    action   = "Allow"
                ) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Quick actions ─────────────────────────────────────────
            SectionCard("Quick Actions") {
                ServiceRow(
                    icon     = Icons.Default.ContentCopy,
                    tint     = Purple,
                    title    = "Send Clipboard to Mac",
                    subtitle = "Push whatever you copied right now",
                    granted  = null,
                    action   = "Send"
                ) {
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
                }
                RowDivider()
                ServiceRow(
                    icon     = Icons.Outlined.LibraryBooks,
                    tint     = Amber,
                    title    = "Feature Guide",
                    subtitle = "Step-by-step help for every feature",
                    granted  = null,
                    action   = "Open"
                ) { showGuide = true }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── App header ────────────────────────────────────────────────────────────
@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Amber.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint               = Amber,
                modifier           = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("DroidDock", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp)
            Text("Phone ↔ Mac bridge", color = Dim, fontSize = 11.sp)
        }
    }
}

// ── Connection status card ─────────────────────────────────────────────────
@Composable
private fun ConnectionCard(
    connected:   Boolean,
    isPaused:    Boolean,
    paired:      Boolean,
    macName:     String?,
    event:       String?,
    pausedUntil: Long,
    onPause:     () -> Unit,
    onResume:    () -> Unit,
) {
    val stateColor: Color
    val stateIcon:  ImageVector
    val headline:   String
    val subline:    String

    when {
        isPaused -> {
            stateColor = Amber
            stateIcon  = Icons.Default.PauseCircle
            headline   = "Paused"
            subline    = pauseSubtitle(pausedUntil)
        }
        connected -> {
            stateColor = Ok
            stateIcon  = Icons.Default.CheckCircle
            headline   = macName ?: "Mac"
            subline    = if (event.isNullOrBlank()) "Connected" else "Connected · $event"
        }
        paired -> {
            stateColor = Amber
            stateIcon  = Icons.Outlined.Wifi
            headline   = "Searching for Mac…"
            subline    = event ?: "Waiting for Mac to come online"
        }
        else -> {
            stateColor = Dim
            stateIcon  = Icons.Outlined.PhoneAndroid
            headline   = "Not paired"
            subline    = "Pair with your Mac to get started"
        }
    }

    // Ambient glow animation when connected
    val glowAlpha by if (connected && !isPaused) {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue   = 0.10f,
            targetValue    = 0.20f,
            animationSpec  = infiniteRepeatable(tween(1600, easing = EaseInOutSine), RepeatMode.Reverse),
            label          = "glowAlpha"
        )
    } else remember { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(18.dp),
        colors   = CardDefaults.cardColors(containerColor = Surface1),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            // Ambient top-edge color bar
            if (glowAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = glowAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // State icon badge
                AnimatedContent(
                    targetState = stateIcon,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "stateIcon"
                ) { icon ->
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(stateColor.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = stateColor,
                            modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = headline,
                        transitionSpec = {
                            slideInVertically { -it / 2 } + fadeIn() togetherWith
                                    slideOutVertically { it / 2 } + fadeOut()
                        },
                        label = "headline"
                    ) { text ->
                        Text(text, color = if (isPaused) Amber else Fg,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
                            shape           = RoundedCornerShape(10.dp),
                            contentPadding  = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            elevation       = ButtonDefaults.filledTonalButtonElevation(0.dp)
                        ) {
                            Text("Resume", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.PowerSettingsNew,
                                contentDescription = "Pause",
                                tint = Dim, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Section card wrapper ───────────────────────────────────────────────────
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text          = title.uppercase(),
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

// ── Auto clipboard row ────────────────────────────────────────────────────
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

// ── Auto mirror row ───────────────────────────────────────────────────────
@Composable
private fun AutoMirrorRow(
    overlayOk: Boolean,
    auto:      Boolean,
    onEnable:  () -> Unit,
    onToggle:  (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Default.ScreenShare, Blue)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Auto Screen / Camera", color = Fg, fontSize = 14.sp,
                fontWeight = FontWeight.Medium)
            Text(
                when {
                    !overlayOk -> "Allow 'Display over other apps' to start without a prompt"
                    auto       -> "On — camera instant; screen reuses existing permission"
                    else       -> "Off — phone prompt required each time"
                },
                color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        if (!overlayOk) {
            TonalChip("Enable", Blue, onEnable)
        } else {
            DroidSwitch(checked = auto, onCheckedChange = onToggle)
        }
    }
}

// ── Generic service row ───────────────────────────────────────────────────
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
                    Icon(Icons.Default.Check, contentDescription = null, tint = Ok,
                        modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("On", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            TonalChip(action, tint, onClick)
        }
    }
}

// ── Small components ──────────────────────────────────────────────────────
@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
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
        title = {
            Text("Pause DroidDock", color = Fg, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    "Stops auto-reconnect and tells your Mac to stop trying. " +
                            "The persistent notification stays silent.",
                    color = Dim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                PauseOption("Pause for 1 hour")     { onPause(60L * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Pause for 8 hours")    { onPause(8L * 60 * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Until I resume")        { onPause(null) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(section.title, color = Fg, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
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
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "${i + 1}.",
                                color    = Amber,
                                fontSize = 12.sp,
                                modifier = Modifier.width(22.dp)
                            )
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
        listOf("Connect both devices over USB or wireless ADB.",
            "Tap Mirror in the Mac sidebar.",
            "Your phone screen appears on your Mac — you can click, scroll and type.",
            "Close the mirror window on the Mac to stop."))
)
