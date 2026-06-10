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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import org.json.JSONObject

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF12151A)
private val Amber = Color(0xFFFFB454)
private val Ok = Color(0xFF79D68B)
private val Dim = Color(0xFF8B909A)
private val Fg = Color(0xFFE9E6DF)
private val Blue = Color(0xFF5B9BFF)
private val Purple = Color(0xFFB78BFF)
private val Orange = Color(0xFFF0A35E)
private val Line = Color(0xFF20242C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.load(this) != null) BridgeService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink, surface = Panel,
                    primary = Amber, onPrimary = Color(0xFF1A1206),
                    onBackground = Fg, onSurface = Fg
                )
            ) {
                Screen()
            }
        }
    }
}

@Composable
private fun Screen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val connected by ConnectionManager.connected.collectAsState()
    val macName by ConnectionManager.macName.collectAsState()
    val event by ConnectionManager.lastEvent.collectAsState()
    val pausedUntil by ConnectionManager.pausedUntil.collectAsState()
    val isPaused = pausedUntil != 0L
    var paired by remember { mutableStateOf(Prefs.load(ctx) != null) }
    var notifAccess by remember { mutableStateOf(notifAccessGranted(ctx)) }
    var phonePerms by remember { mutableStateOf(phonePermsGranted(ctx)) }
    var allFiles by remember { mutableStateOf(FileRepo.hasAllFiles()) }
    var clipA11y by remember { mutableStateOf(clipAccessibilityEnabled(ctx)) }
    var clipAuto by remember { mutableStateOf(Prefs.clipboardAuto(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var autoMirror by remember { mutableStateOf(Prefs.autoMirror(ctx)) }
    var showManual by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showPause by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showScan = true
        else Toast.makeText(ctx, "Camera permission needed to scan QR", Toast.LENGTH_SHORT).show()
    }

    // Shared "we have a pairing now" path — used by both QR scan and manual entry.
    val applyPairing: (Pairing) -> Unit = { pairing ->
        Prefs.save(ctx, pairing)
        paired = true
        BridgeService.start(ctx)
        Toast.makeText(ctx, "Paired with ${pairing.macName}", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            notifAccess = notifAccessGranted(ctx)
            phonePerms = phonePermsGranted(ctx)
            allFiles = FileRepo.hasAllFiles()
            clipA11y = clipAccessibilityEnabled(ctx)
            overlayOk = Settings.canDrawOverlays(ctx)
            kotlinx.coroutines.delay(2000)
        }
    }

    // Shared QR parse + pairing — used by both scan screen and manual entry
    val handleQr: (String) -> Unit = { qrText ->
        runCatching {
            val o = JSONObject(qrText)
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
        ManualPairDialog(
            onDismiss = { showManual = false },
            onPair = { pairing ->
                showManual = false
                applyPairing(pairing)
            }
        )
    }

    if (showPause) {
        PauseDialog(
            onDismiss = { showPause = false },
            onPause = { durationMs ->
                showPause = false
                ConnectionManager.pause(ctx, durationMs)
            }
        )
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Spacer(Modifier.height(20.dp))

            // ---- branded header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp)
                        .background(Amber.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("🔗", fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("DroidDock", color = Fg, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Phone ↔ Mac bridge", color = Dim, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- connection hero ----
            val stateColor = when {
                isPaused -> Amber
                connected -> Ok
                paired -> Amber
                else -> Dim
            }
            val stateEmoji = when {
                isPaused -> "⏸"
                connected -> "✓"
                paired -> "📡"
                else -> "📱"
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp).background(stateColor.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text(stateEmoji, fontSize = 22.sp, color = stateColor) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                isPaused -> "Paused"
                                connected -> macName ?: "Mac"
                                paired -> "Searching for Mac…"
                                else -> "Not paired"
                            },
                            color = if (isPaused) Amber else Fg,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                isPaused -> pauseSubtitle(pausedUntil)
                                connected -> "Connected • $event"
                                paired -> event
                                else -> "Pair with your Mac to get started"
                            },
                            color = Dim, fontSize = 12.sp, lineHeight = 15.sp
                        )
                    }
                    // Power toggle: pause when active, resume when paused.
                    if (paired) {
                        Spacer(Modifier.width(8.dp))
                        if (isPaused) {
                            Button(
                                onClick = { ConnectionManager.resume(ctx) },
                                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("Resume", color = Color(0xFF1A1206),
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { showPause = true },
                                shape = CircleShape,
                                modifier = Modifier.size(44.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("⏻", color = Dim, fontSize = 18.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- primary action ----
            Button(
                onClick = {
                    val hasCam = ctx.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (hasCam) showScan = true
                    else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                },
                Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(if (paired) "Re-pair with Mac" else "Pair with Mac",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Row(
                Modifier.fillMaxWidth(),
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

            Spacer(Modifier.height(14.dp))

            // ---- essential services ----
            SectionCard("ESSENTIAL SERVICES") {
                AutoClipRow(
                    a11yOn = clipA11y,
                    auto = clipAuto,
                    onEnable = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        Toast.makeText(
                            ctx, "Find “DroidDock Clipboard” and turn it on", Toast.LENGTH_LONG
                        ).show()
                    },
                    onToggle = { v ->
                        clipAuto = v
                        Prefs.setClipboardAuto(ctx, v)
                    }
                )
                RowDivider()
                AutoMirrorRow(
                    overlayOk = overlayOk,
                    auto = autoMirror,
                    onEnable = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        }
                        Toast.makeText(
                            ctx, "Allow DroidDock to display over other apps", Toast.LENGTH_LONG
                        ).show()
                    },
                    onToggle = { v ->
                        autoMirror = v
                        Prefs.setAutoMirror(ctx, v)
                        if (!v) MirrorService.stop(ctx) // release any kept-alive session
                    }
                )
                RowDivider()
                ServiceRow(
                    "🔔", Blue, "Notification Access",
                    "Show phone notifications on your Mac",
                    notifAccess, "Enable"
                ) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
                RowDivider()
                ServiceRow(
                    "💬", Ok, "SMS · Contacts · Calls",
                    "Texts, contacts and call alerts on Mac",
                    phonePerms, "Grant"
                ) { permLauncher.launch(PHONE_PERMS) }
                RowDivider()
                ServiceRow(
                    "📁", Orange, "All-files Access",
                    "Browse and transfer your phone's files",
                    allFiles, "Grant"
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
                    "🔋", Dim, "Background (Battery)",
                    "Keep the link alive when the screen is off",
                    null, "Allow"
                ) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- quick actions ----
            SectionCard("QUICK ACTIONS") {
                ServiceRow(
                    "📋", Purple, "Send Clipboard to Mac",
                    "Push whatever you copied right now",
                    null, "Send"
                ) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
                    "📖", Amber, "Feature Guide",
                    "Step-by-step help for every feature",
                    null, "Open"
                ) { showGuide = true }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private val PHONE_PERMS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.CALL_PHONE
)

private fun phonePermsGranted(ctx: Context): Boolean =
    PHONE_PERMS.all {
        ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun notifAccessGranted(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?.contains(ctx.packageName) == true

private fun clipAccessibilityEnabled(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains(ClipAccessibilityService::class.java.name) == true

/** Clipboard auto/manual control: Enable button until the Accessibility service is on,
 *  then an Auto/Manual switch. (Manual mode keeps the existing "Send" / share-sheet paths.) */
@Composable
private fun AutoClipRow(
    a11yOn: Boolean,
    auto: Boolean,
    onEnable: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).background(Purple.copy(alpha = 0.15f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Text("📋", fontSize = 17.sp) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Auto Clipboard", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    !a11yOn -> "Enable accessibility to auto-send copies to Mac"
                    auto -> "On — copies on this phone appear on Mac instantly"
                    else -> "Manual — use Send / share sheet to push copies"
                },
                color = Dim, fontSize = 11.sp, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        if (!a11yOn) {
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(containerColor = Purple.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                elevation = null
            ) { Text("Enable", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        } else {
            Switch(
                checked = auto,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A1206),
                    checkedTrackColor = Amber,
                    uncheckedThumbColor = Dim,
                    uncheckedTrackColor = Panel,
                    uncheckedBorderColor = Dim.copy(alpha = 0.5f)
                )
            )
        }
    }
}

/** Auto mirroring/camera: grant "Display over other apps", then a switch. When on, the
 *  Mac can start screen/camera without a per-time prompt on the phone. */
@Composable
private fun AutoMirrorRow(
    overlayOk: Boolean,
    auto: Boolean,
    onEnable: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).background(Blue.copy(alpha = 0.15f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Text("🖥️", fontSize = 17.sp) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Auto Screen / Camera", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    !overlayOk -> "Allow 'Display over other apps' to start without a prompt"
                    auto -> "On — camera starts instantly; screen asks once, then reuses"
                    else -> "Off — tap a prompt on the phone each time"
                },
                color = Dim, fontSize = 11.sp, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        if (!overlayOk) {
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(containerColor = Blue.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                elevation = null
            ) { Text("Enable", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        } else {
            Switch(
                checked = auto,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A1206),
                    checkedTrackColor = Amber,
                    uncheckedThumbColor = Dim,
                    uncheckedTrackColor = Panel,
                    uncheckedBorderColor = Dim.copy(alpha = 0.5f)
                )
            )
        }
    }
}

/** A titled rounded card that groups related rows. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                title, color = Dim, fontSize = 10.sp, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            )
            content()
        }
    }
}

/** One row: icon badge + title/subtitle, and either a "✓ On" pill (when [granted]
 *  is true) or an accent action button. Pass [granted] = null for pure actions. */
@Composable
private fun ServiceRow(
    emoji: String,
    accent: Color,
    title: String,
    subtitle: String,
    granted: Boolean?,
    action: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 17.sp) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Dim, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (granted == true) {
            Box(
                Modifier.background(Ok.copy(alpha = 0.14f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) { Text("✓ On", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                elevation = null
            ) { Text(action, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Manual pairing — type the IP / port / token shown by "Pair Device" on the Mac.
 *  An alternative to QR for networks where the camera scan is awkward. */
@Composable
private fun ManualPairDialog(onDismiss: () -> Unit, onPair: (Pairing) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("48484") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ipt = ip.trim()
                val tok = token.trim()
                val p = port.trim().toIntOrNull() ?: 48484
                if (ipt.isEmpty() || tok.isEmpty()) {
                    Toast.makeText(
                        ctx, "Enter the IP and token shown on the Mac", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onPair(Pairing(listOf(ipt), p, tok, "Mac"))
                }
            }) { Text("PAIR", color = Amber) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Dim) } },
        title = { Text("Pair manually", color = Fg) },
        text = {
            Column {
                Text(
                    "Open Pair Device on the Mac and read the IP, port and token it shows.",
                    color = Dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it }, singleLine = true,
                    label = { Text("Mac IP (e.g. 192.168.0.108)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it }, singleLine = true,
                    label = { Text("Port") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Token") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = Panel
    )
}

/** Subtitle shown under "Paused": either the resume time or "Until you resume". */
private fun pauseSubtitle(until: Long): String =
    if (until == Long.MAX_VALUE) "Until you resume"
    else "Resumes " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(until))

/** Pause sheet — stop reconnecting for a while (and tell the Mac to stop too). */
@Composable
private fun PauseDialog(onDismiss: () -> Unit, onPause: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Dim) } },
        title = { Text("Pause DroidDock", color = Fg) },
        text = {
            Column {
                Text(
                    "Stops auto-reconnect and mDNS scanning, and tells your Mac to stop trying. " +
                        "DroidDock keeps its persistent notification (it stays silent).",
                    color = Dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                PauseOption("Pause for 1 hour") { onPause(60L * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Pause for 8 hours") { onPause(8L * 60 * 60 * 1000) }
                Spacer(Modifier.height(8.dp))
                PauseOption("Until I resume") { onPause(null) }
            }
        },
        containerColor = Panel
    )
}

@Composable
private fun PauseOption(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(4.dp)
    ) { Text(label, color = Amber, fontSize = 13.sp) }
}

private data class GuideSection(val title: String, val subtitle: String, val steps: List<String>)

private val GUIDE_SECTIONS = listOf(
    GuideSection(
        "Quick Start", "The fastest way to get DroidDock working and keep it reliable.",
        listOf(
            "Open DroidDock on your Mac first, then keep this app open on Android while you pair.",
            "Use QR for the first setup, or connect USB for the fastest detection and charging at the same time.",
            "After pairing, set Battery to Unrestricted on Android so reconnects, notifications and clipboard stay ready.",
            "Turn on Notification Access for alerts on Mac, and the SMS · Contacts · Calls permission for the full link.",
            "From the home screen, use Pair, Send Clipboard, or the Mac's tabs depending on what you want to do."
        )
    ),
    GuideSection(
        "Pairing & Connection", "Connect your phone and Mac over Wi-Fi or USB.",
        listOf(
            "Install DroidDock on your Mac and this app on your Android.",
            "Use the same Wi-Fi network, or plug in a USB cable for the wired path.",
            "Open Pair Device on the Mac. It shows a QR code and the manual IP / token.",
            "On Android, tap Pair With Mac and scan the QR, or choose Enter IP manually and type what the Mac shows.",
            "Once paired, your devices auto-connect whenever both apps are open."
        )
    ),
    GuideSection(
        "File Transfer", "Send files between your phone and Mac.",
        listOf(
            "Make sure both devices are connected.",
            "On Mac: drag a file onto the DroidDock window, or use Send To Phone in the Files tab.",
            "On Android: share from any app, or use the Send Clipboard / share-sheet actions.",
            "Transfer progress shows on both devices in real time.",
            "Received files appear in your Downloads folder."
        )
    ),
    GuideSection(
        "Photos & Videos", "Browse and download your phone's photos and videos.",
        listOf(
            "Connect both devices and grant All-files (or media) access on Android.",
            "Open the Photos tab on Mac — thumbnails load automatically.",
            "Click any photo to preview it; videos show a play badge and a download button.",
            "Use the download button on a tile to save the original to your Mac."
        )
    ),
    GuideSection(
        "File Browser", "Browse your phone's storage from your Mac.",
        listOf(
            "Connect both devices and grant All-files access on Android.",
            "Open the Files tab on Mac.",
            "Navigate folders just like on your phone, and filter the current folder with the search box.",
            "Download a file, rename it, or delete it right from the row."
        )
    ),
    GuideSection(
        "Clipboard Sharing", "Copy on one device, paste on the other.",
        listOf(
            "Connect both devices.",
            "Copy any text on your Mac — it's automatically available on Android.",
            "Android → Mac: use Send Clipboard Now, the Quick Settings tile, or select text → ⋮ → Send to Mac.",
            "Works with text content. Images and files use the file transfer feature."
        )
    ),
    GuideSection(
        "Notifications", "See your phone notifications on your Mac.",
        listOf(
            "Grant Notification Access on Android (Enable Notification Access button).",
            "Connect both devices.",
            "Phone notifications appear on your Mac as they arrive.",
            "Reply or dismiss right from the Mac for apps that support it.",
            "The NOTIFS tab badge shows the unread count."
        )
    ),
    GuideSection(
        "SMS & Messages", "Read and reply to texts from your Mac.",
        listOf(
            "Connect both devices and grant the SMS permission on Android.",
            "Open the Messages tab on Mac to see your conversations.",
            "Click a conversation to see the full thread.",
            "Type at the bottom and press Enter to send a reply.",
            "New messages sync in real time."
        )
    ),
    GuideSection(
        "Contacts", "Browse your phone contacts on your Mac.",
        listOf(
            "Connect both devices and grant the Contacts permission on Android.",
            "Open the Contacts tab on Mac.",
            "Search or scroll to find a contact.",
            "Start a call or open Messages straight from a contact."
        )
    ),
    GuideSection(
        "Phone Calls", "Make calls from your Mac using your phone.",
        listOf(
            "Connect both devices and grant the Phone permission on Android.",
            "On Mac, use the Contacts tab to find a contact.",
            "Click the phone icon to start a call.",
            "The call is placed through your Android phone — audio stays on your phone."
        )
    ),
    GuideSection(
        "Screen Mirroring", "Mirror and control your phone from your Mac.",
        listOf(
            "Connect both devices over USB (or wireless ADB).",
            "Tap Mirror in the Mac sidebar.",
            "Your phone screen appears on your Mac — you can click, scroll and type.",
            "Close the mirror window on the Mac to stop."
        )
    )
)

@Composable
private fun FeatureGuideScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("← Back", color = Amber, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            Text("FEATURE GUIDE", color = Fg, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Open any card for step-by-step help.", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            GUIDE_SECTIONS.forEach { section ->
                GuideCard(section)
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GuideCard(section: GuideSection) {
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(section.title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(section.subtitle, color = Dim, fontSize = 12.sp)
                }
                TextButton(onClick = { open = !open }) {
                    Text(if (open) "▲" else "▼", color = Amber, fontSize = 14.sp)
                }
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                section.steps.forEachIndexed { i, step ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${i + 1}.", color = Amber, fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp))
                        Text(step, color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
