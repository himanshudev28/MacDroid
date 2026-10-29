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

@Composable
internal fun PauseDialog(onDismiss: () -> Unit, onPause: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape            = RoundedCornerShape(20.dp),
        title = { Text(t("Pause DroidDock"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Stops auto-reconnect and tells your Mac to stop trying. " +
                            "The persistent notification stays silent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp
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
            TextButton(onClick = onDismiss) { Text(t("Cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    )
}

@Composable
internal fun PauseOption(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth().height(48.dp),
        shape     = RoundedCornerShape(12.dp),
        colors    = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border    = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

// ── Feature guide ──────────────────────────────────────────────────────────

internal fun pauseSubtitle(until: Long): String =
    if (until == Long.MAX_VALUE) "Until you resume"
    else "Resumes " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(until))
