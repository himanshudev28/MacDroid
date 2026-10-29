package com.droiddock.app

import android.view.ViewGroup
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockIconButton
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.theme.DockTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.util.concurrent.atomic.AtomicBoolean

/** The camera view stays dark whatever the style; only the chrome around it is themed. */
private val CameraScrim = Color.Black.copy(alpha = 0.62f)

@Composable
fun ScanScreen(
    onResult: (String) -> Unit,
    onManual: () -> Unit,
    onBack:   () -> Unit,
    onHelp:   () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val bvRef  = remember { mutableStateOf<BarcodeView?>(null) }
    val decoded = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> bvRef.value?.resume()
                Lifecycle.Event.ON_PAUSE  -> bvRef.value?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            bvRef.value?.pause()
        }
    }

    val signal = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera preview ──
        AndroidView(
            factory = { ctx ->
                BarcodeView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setDecoderFactory(
                        DefaultDecoderFactory(listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                    )
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult?) {
                            val text = result?.text ?: return
                            if (decoded.compareAndSet(false, true)) post { onResult(text) }
                        }
                        override fun possibleResultPoints(r: MutableList<com.google.zxing.ResultPoint>?) {}
                    })
                    bvRef.value = this
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        resume()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Dim overlay + corner brackets in the style's accent ──
        ScannerOverlay(signal)

        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = DockTheme.spacing.s, vertical = DockTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Back"), onBack)
            Spacer(Modifier.weight(1f))
            DockIconButton(Icons.AutoMirrored.Rounded.HelpOutline, t("Help"), onHelp)
        }

        // ── Bottom: themed card with title, status, manual fallback ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(DockTheme.spacing.gutter)
        ) {
            DockCard {
              Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t("Scan to Connect"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    t("Point your camera at the DroidDock QR code on your Mac to pair instantly."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = DockTheme.spacing.s),
                )
                DockStatusPill(
                    t("Looking for QR code"),
                    modifier = Modifier.padding(top = DockTheme.spacing.l),
                )
                DockButton(
                    text = t("Pair manually"), onClick = onManual,
                    kind = ButtonKind.GHOST, icon = Icons.Rounded.Keyboard,
                    modifier = Modifier.padding(top = DockTheme.spacing.xs),
                )
              }
            }
        }
    }
}

@Composable
private fun ScannerOverlay(signal: Color) {
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val scanW   = size.width * 0.76f
        val left    = (size.width - scanW) / 2f
        val top     = size.height * 0.15f
        val right   = left + scanW
        val bottom  = top + scanW
        val cR      = with(density) { 20.dp.toPx() }
        val arm     = with(density) { 34.dp.toPx() }
        val thin    = with(density) { 2.8.dp.toPx() }
        val overlay = CameraScrim

        // mask panels around viewfinder
        drawRect(overlay, Offset.Zero, Size(size.width, top))
        drawRect(overlay, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(overlay, Offset(0f, top), Size(left, scanW))
        drawRect(overlay, Offset(right, top), Size(size.width - right, scanW))

        val paths = cornerPaths(left, top, right, bottom, arm, cR)

        // glow halos
        val glowLayers = listOf(
            22f to 0.04f, 14f to 0.07f, 8f to 0.13f, 4f to 0.24f
        )
        for ((dpW, a) in glowLayers) {
            val px = with(density) { dpW.dp.toPx() }
            paths.forEach { path ->
                drawPath(path, signal.copy(alpha = a),
                    style = Stroke(width = px, cap = StrokeCap.Round))
            }
        }

        // sharp bracket lines
        paths.forEach { path ->
            drawPath(path, signal, style = Stroke(width = thin, cap = StrokeCap.Round))
        }
    }
}

private fun cornerPaths(
    left: Float, top: Float, right: Float, bottom: Float,
    arm: Float, cR: Float
): List<Path> = listOf(
    Path().apply {
        moveTo(left, top + arm)
        lineTo(left, top + cR)
        arcTo(Rect(left, top, left + cR * 2, top + cR * 2), 180f, 90f, false)
        lineTo(left + arm, top)
    },
    Path().apply {
        moveTo(right - arm, top)
        lineTo(right - cR, top)
        arcTo(Rect(right - cR * 2, top, right, top + cR * 2), 270f, 90f, false)
        lineTo(right, top + arm)
    },
    Path().apply {
        moveTo(left, bottom - arm)
        lineTo(left, bottom - cR)
        arcTo(Rect(left, bottom - cR * 2, left + cR * 2, bottom), 180f, -90f, false)
        lineTo(left + arm, bottom)
    },
    Path().apply {
        moveTo(right - arm, bottom)
        lineTo(right - cR, bottom)
        arcTo(Rect(right - cR * 2, bottom - cR * 2, right, bottom), 90f, -90f, false)
        lineTo(right, bottom - arm)
    }
)
