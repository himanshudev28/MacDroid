package com.droiddock.app

import android.view.ViewGroup
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val ScanInk    = Color(0xFF0D0D12)
private val ScanPanel  = Color(0xFF14141B)
private val ScanFg     = Color(0xFFF0EFE9)
private val ScanDim    = Color(0xFF72728A)
private val ScanOk     = Color(0xFF34C759)
private val BracketAmber = Color(0xFFF5A623)

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

    Box(Modifier.fillMaxSize().background(ScanInk)) {

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

        // ── Dark overlay + glowing corner brackets ──
        ScannerOverlay()

        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ScanFg)
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector        = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint               = BracketAmber,
                    modifier           = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "DroidDock",
                    color      = ScanFg,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp) {
                Icon(Icons.Default.HelpOutline, contentDescription = "Help",
                    tint = ScanFg.copy(alpha = 0.55f), modifier = Modifier.size(22.dp))
            }
        }

        // ── Bottom: title + subtitle + status pill ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scan to Connect",
                color      = ScanFg,
                fontSize   = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Point your camera at the DroidDock QR code on\nyour Mac to pair instantly.",
                color     = ScanDim,
                fontSize  = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(28.dp))

            // Status pill
            Box(
                modifier = Modifier
                    .background(ScanPanel, RoundedCornerShape(50.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Looking for QR code",
                            color      = ScanFg,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick        = onManual,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint               = ScanDim,
                            modifier           = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Pair manually", color = ScanDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay() {
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
        val overlay = Color(0xCC0D0D12)

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
                drawPath(path, BracketAmber.copy(alpha = a),
                    style = Stroke(width = px, cap = StrokeCap.Round))
            }
        }

        // sharp bracket lines
        paths.forEach { path ->
            drawPath(path, BracketAmber, style = Stroke(width = thin, cap = StrokeCap.Round))
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

@Composable
private fun PulsingDot() {
    val inf = rememberInfiniteTransition(label = "dot")
    val alpha by inf.animateFloat(
        initialValue  = 0.45f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label         = "alpha"
    )
    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).background(ScanOk.copy(alpha = alpha * 0.30f), CircleShape))
        Box(Modifier.size(7.dp).background(ScanOk.copy(alpha = alpha), CircleShape))
    }
}
