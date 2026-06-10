package com.droiddock.app

import android.view.ViewGroup
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val ScanInk = Color(0xFF080A0D)
private val ScanPanel = Color(0xFF141820)
private val ScanFg = Color(0xFFE9E6DF)
private val ScanDim = Color(0xFF8B909A)
private val ScanOk = Color(0xFF79D68B)
private val BracketBlue = Color(0xFF6B9FFF)

@Composable
fun ScanScreen(
    onResult: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val bvRef = remember { mutableStateOf<BarcodeView?>(null) }
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

        // ── camera preview ──
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

        // ── dark overlay + glowing corner brackets ──
        ScannerOverlay()

        // ── top bar ──
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("←", color = ScanFg, fontSize = 22.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 17.sp)
                Spacer(Modifier.width(5.dp))
                Text("DroidDock", color = ScanFg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHelp) {
                Text("Help", color = ScanFg.copy(alpha = 0.65f), fontSize = 14.sp)
            }
        }

        // ── bottom: title + subtitle + status pill ──
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scan to Connect",
                color = ScanFg, fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Point your camera at the DroidDock QR code on\nyour Mac to pair instantly.",
                color = ScanDim, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 21.sp
            )
            Spacer(Modifier.height(28.dp))

            // pill
            Box(
                Modifier
                    .background(ScanPanel, RoundedCornerShape(50.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot()
                        Spacer(Modifier.width(8.dp))
                        Text("Looking for Mac", color = ScanFg,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = onManual,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("⌨  Pair Manually", color = ScanDim, fontSize = 13.sp)
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
        val scanW    = size.width * 0.76f
        val left     = (size.width - scanW) / 2f
        val top      = size.height * 0.15f
        val right    = left + scanW
        val bottom   = top + scanW   // square
        val cR       = with(density) { 20.dp.toPx() }
        val arm      = with(density) { 34.dp.toPx() }
        val thin     = with(density) { 2.8.dp.toPx() }
        val overlay  = Color(0xCC080A0D)

        // ── mask panels around viewfinder ──
        drawRect(overlay, Offset.Zero, Size(size.width, top))
        drawRect(overlay, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(overlay, Offset(0f, top), Size(left, scanW))
        drawRect(overlay, Offset(right, top), Size(size.width - right, scanW))

        val paths = cornerPaths(left, top, right, bottom, arm, cR)

        // ── glow layers (no BlurMaskFilter → works on all hw-accel configs) ──
        val glowLayers = listOf(
            Pair(with(density) { 22.dp.toPx() }, 0.04f),
            Pair(with(density) { 14.dp.toPx() }, 0.07f),
            Pair(with(density) { 8.dp.toPx()  }, 0.12f),
            Pair(with(density) { 4.dp.toPx()  }, 0.22f),
        )
        for ((w, a) in glowLayers) {
            paths.forEach { path ->
                drawPath(path, BracketBlue.copy(alpha = a),
                    style = Stroke(width = w, cap = StrokeCap.Round))
            }
        }

        // ── sharp bracket lines ──
        paths.forEach { path ->
            drawPath(path, BracketBlue, style = Stroke(width = thin, cap = StrokeCap.Round))
        }
    }
}

/** Four L-shaped corner bracket Paths that follow the viewfinder's rounded corners. */
private fun cornerPaths(
    left: Float, top: Float, right: Float, bottom: Float,
    arm: Float, cR: Float
): List<Path> = listOf(
    // top-left
    Path().apply {
        moveTo(left, top + arm)
        lineTo(left, top + cR)
        arcTo(Rect(left, top, left + cR * 2, top + cR * 2), 180f, 90f, false)
        lineTo(left + arm, top)
    },
    // top-right
    Path().apply {
        moveTo(right - arm, top)
        lineTo(right - cR, top)
        arcTo(Rect(right - cR * 2, top, right, top + cR * 2), 270f, 90f, false)
        lineTo(right, top + arm)
    },
    // bottom-left
    Path().apply {
        moveTo(left, bottom - arm)
        lineTo(left, bottom - cR)
        arcTo(Rect(left, bottom - cR * 2, left + cR * 2, bottom), 180f, -90f, false)
        lineTo(left + arm, bottom)
    },
    // bottom-right
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
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = LinearEasing), RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).background(ScanOk.copy(alpha = alpha * 0.3f), CircleShape))
        Box(Modifier.size(7.dp).background(ScanOk.copy(alpha = alpha), CircleShape))
    }
}
