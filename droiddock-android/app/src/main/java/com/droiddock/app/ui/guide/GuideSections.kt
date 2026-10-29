package com.droiddock.app.ui.guide

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
import com.droiddock.app.ui.settings.*
import com.droiddock.app.ui.setup.*

internal data class GuideSection(val title: String, val subtitle: String, val steps: List<String>)

internal val GUIDE_SECTIONS = listOf(
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
