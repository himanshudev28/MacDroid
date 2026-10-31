package com.droiddock.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.ui.setup.PHONE_PERMS
import com.droiddock.app.ui.setup.notifAccessGranted
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockPageBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * First-run state. Kept in its own small prefs file so it never collides with
 * the pairing store. Anyone already paired (an upgrade from an older build)
 * never sees onboarding: MainActivity checks `paired` first.
 */
object Onboarding {
    private const val FILE = "droiddock_onboarding"
    private const val KEY = "done_v1"
    fun isDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markDone(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

// The three things that travel phone → Mac in the hero. The same colours mark the feature pills.
private val NotifRed = Color(0xFFFF5A5F)
private val FileBlue = Color(0xFF3B82F6)
private val ClipOrange = Color(0xFFFF9F0A)
private val CallGreen = Color(0xFF30C26B)
private val AccessPurple = Color(0xFF8B5CF6)

/**
 * First-run flow: Welcome (phone → Mac hand-off animation) → Permissions
 * (each one optional, rows tick themselves green as they are granted) → Done
 * (check draws, a ring of dots bursts once). Ported from the Mac app's
 * OnboardingHero / OnboardingDoneMark so both apps open the same way.
 *
 * One-shot motion that holds its final frame: nothing loops. With system
 * animations off, every piece renders at its final frame.
 *
 * @param onFinish `pairNow` = the user tapped "Pair with your Mac".
 */
@Composable
fun OnboardingFlow(onFinish: (pairNow: Boolean) -> Unit, initialStep: Int = 0) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    val motion = DockTheme.motion
    BackHandler(enabled = step > 0) { step-- }

    Box(Modifier.fillMaxSize().dockPageBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(step, 3)
                Spacer(Modifier.weight(1f))
                if (step < 2) {
                    DockButton(
                        t("Skip"), onClick = { if (step == 0) onFinish(false) else step = 2 },
                        kind = ButtonKind.GHOST, height = 48.dp,
                    )
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (!motion.enabled) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        val dir = if (targetState > initialState) 1 else -1
                        val slide = motion.spatial<IntOffset>(IntOffset.VisibilityThreshold)
                        (slideInHorizontally(slide) { dir * it / 5 } + fadeIn(tween(motion.pageMs, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(slide) { -dir * it / 5 } + fadeOut(tween(120)))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth().widthIn(max = 560.dp),
                label = "onboarding",
            ) { s ->
                when (s) {
                    0 -> WelcomePage(onNext = { step = 1 })
                    1 -> PermissionsPage(onNext = { step = 2 })
                    else -> DonePage(onFinish)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Welcome

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val s = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CenteredScroll(Modifier.weight(1f).fillMaxWidth()) {
            HandoffHero(Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(300f / 150f))
            Spacer(Modifier.height(36.dp))
            Text(
                t("Your phone, on your Mac"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.rise(180).semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                t("Notifications, files, clipboard and calls move between your phone and your Mac, privately over your own Wi-Fi."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.rise(260),
            )
            Spacer(Modifier.height(24.dp))
            // One pill per chip, each appearing as its chip lands on the Mac.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeaturePill(Icons.Rounded.Notifications, t("Alerts"), NotifRed, Modifier.rise(HeroTimeline.land(0)))
                FeaturePill(Icons.Rounded.Description, t("Files"), FileBlue, Modifier.rise(HeroTimeline.land(1)))
                FeaturePill(Icons.Rounded.ContentPaste, t("Clipboard"), ClipOrange, Modifier.rise(HeroTimeline.land(2)))
            }
        }
        DockButton(
            t("Get started"), onNext, kind = ButtonKind.PRIMARY, glow = true, fullWidth = true,
            icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.rise(HeroTimeline.land(2) + 120),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Hero timeline in ms. Shared with the pills so they land in step with the chips. */
private object HeroTimeline {
    const val DEVICES_IN = 350
    const val ARC_START = 80
    const val ARC_MS = 380
    const val FIRST_LEAVE = 330
    const val STAGGER = 150
    const val TRAVEL = 720
    const val POP_MS = 300
    const val GLOW_MS = 520
    fun leave(i: Int) = FIRST_LEAVE + i * STAGGER
    fun land(i: Int) = leave(i) + TRAVEL - 110
    val TOTAL = land(2) + POP_MS + GLOW_MS
}

/**
 * A phone and a Mac. A dashed arc draws between them, then three chips
 * (a notification, a file, a clipboard item) fly along it and settle on the
 * Mac's screen, which keeps them. Plays once (~1.9 s), then holds.
 * Drawn on one Canvas from a single clock: one Animatable, no recomposition per frame.
 */
@Composable
private fun HandoffHero(modifier: Modifier = Modifier) {
    val motion = DockTheme.motion
    val total = HeroTimeline.TOTAL.toFloat()
    val clock = remember { Animatable(if (motion.enabled) 0f else total) }
    LaunchedEffect(Unit) {
        if (motion.enabled && clock.value < total) clock.animateTo(total, tween(HeroTimeline.TOTAL, easing = LinearEasing))
    }
    val s = MaterialTheme.colorScheme
    val d = DockTheme.colors
    val icons = listOf(
        rememberVectorPainter(Icons.Rounded.NotificationsActive),
        rememberVectorPainter(Icons.Rounded.Description),
        rememberVectorPainter(Icons.Rounded.ContentPaste),
    )
    val tints = listOf(NotifRed, FileBlue, ClipOrange)
    val body = d.raised
    val rim = s.outlineVariant
    val screen = d.card
    val phoneA = s.primary
    val phoneB = s.tertiary
    val arcColor = s.outline

    Canvas(modifier.clearAndSetSemantics { }) {
        val ms = clock.value
        val k = size.width / 300f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)

        val inP = ease(ms / HeroTimeline.DEVICES_IN)
        translate(top = (1f - inP) * 10.dp.toPx()) {
            val a = inP
            // Phone.
            val phoneTl = p(46f - 22f, 84f - 40f)
            val phoneSz = Size(44f * k, 80f * k)
            drawRoundRect(body.copy(alpha = body.alpha * a), phoneTl, phoneSz, CornerRadius(11f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), phoneTl, phoneSz, CornerRadius(11f * k), style = Stroke(1.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(phoneA.copy(alpha = 0.95f * a), phoneB.copy(alpha = 0.7f * a)), phoneTl.y, phoneTl.y + phoneSz.height),
                phoneTl + Offset(3f * k, 3f * k), Size(phoneSz.width - 6f * k, phoneSz.height - 6f * k), CornerRadius(8f * k),
            )
            drawCircle(Color.Black.copy(alpha = 0.35f * a), 2f * k, p(46f, 84f - 40f + 7f))

            // Mac: screen, bezel and base.
            val macTl = p(232f - 58f, 80f - 37f)
            val macSz = Size(116f * k, 74f * k)
            drawRoundRect(body.copy(alpha = body.alpha * a), macTl, macSz, CornerRadius(7f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), macTl, macSz, CornerRadius(7f * k), style = Stroke(1.dp.toPx()))
            drawRoundRect(screen.copy(alpha = screen.alpha * a), macTl + Offset(4f * k, 4f * k),
                Size(macSz.width - 8f * k, macSz.height - 8f * k), CornerRadius(4f * k))
            val baseTl = p(232f - 70f, 80f + 37f)
            drawRoundRect(body.copy(alpha = body.alpha * a), baseTl, Size(140f * k, 6f * k), CornerRadius(3f * k))
            drawRoundRect(rim.copy(alpha = 0.7f * a), baseTl, Size(140f * k, 6f * k), CornerRadius(3f * k), style = Stroke(1.dp.toPx()))

            // Glow ring once everything has landed.
            val g = ((ms - HeroTimeline.land(2) - HeroTimeline.POP_MS * 0.5f) / HeroTimeline.GLOW_MS).coerceIn(0f, 1f)
            if (g > 0f && g < 1f) {
                val grow = ease(g) * 12.dp.toPx()
                drawRoundRect(
                    phoneA.copy(alpha = 0.45f * (1f - g)),
                    macTl - Offset(grow, grow), Size(macSz.width + grow * 2, macSz.height + grow * 2),
                    CornerRadius(7f * k + grow), style = Stroke(2.dp.toPx()),
                )
            }
        }

        // Arc, drawn on with a dashed stroke.
        val arcP = ease((ms - HeroTimeline.ARC_START) / HeroTimeline.ARC_MS)
        if (arcP > 0f) {
            val arc = Path().apply {
                moveTo(46f * k, 84f * k)
                quadraticTo(150f * k, 16f * k, 232f * k, 74f * k)
            }
            val pm = PathMeasure().apply { setPath(arc, false) }
            val seg = Path()
            pm.getSegment(0f, pm.length * arcP, seg, true)
            drawPath(
                seg, arcColor.copy(alpha = 0.6f),
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * k, 6f * k))),
            )
        }

        // Landed tiles on the Mac's screen: fixed slots, popping in on arrival.
        val slotY = 80f
        for (i in 0..2) {
            val slotX = 232f + (i - 1) * 28f
            val lp = ((ms - HeroTimeline.land(i)) / HeroTimeline.POP_MS).coerceIn(0f, 1f)
            if (lp <= 0f) continue
            val sc = 0.7f + 0.3f * easeOutBack(lp)
            drawTile(icons[i], tints[i], p(slotX, slotY), 22f * k * sc, lp)
        }

        // Chips in flight.
        for (i in 0..2) {
            val f = (ms - HeroTimeline.leave(i)) / HeroTimeline.TRAVEL
            if (f <= 0f || f >= 1f) continue
            val tt = FastOutSlowInEasing.transform(f)
            val from = Offset(46f, 84f)
            val to = Offset(232f + (i - 1) * 28f, slotY)
            val ctrl = Offset((from.x + to.x) / 2f + (i - 1) * 8f, 10f + i * 6f)
            val u = 1f - tt
            val x = u * u * from.x + 2 * u * tt * ctrl.x + tt * tt * to.x
            val y = u * u * from.y + 2 * u * tt * ctrl.y + tt * tt * to.y
            val alpha = when {
                f < 0.18f -> f / 0.18f
                f > 0.84f -> (1f - f) / 0.16f
                else -> 1f
            }
            val sc = if (f < 0.4f) 0.9f + 0.18f * (f / 0.4f) else 1.08f - 0.25f * ((f - 0.4f) / 0.6f)
            drawTile(icons[i], tints[i], p(x, y), 26f * k * sc, alpha, shadow = true)
        }
    }
}

private fun DrawScope.drawTile(icon: VectorPainter, tint: Color, center: Offset, side: Float, alpha: Float, shadow: Boolean = false) {
    val tl = Offset(center.x - side / 2f, center.y - side / 2f)
    val r = CornerRadius(side * 0.28f)
    if (shadow) drawRoundRect(Color.Black.copy(alpha = 0.16f * alpha), tl + Offset(0f, side * 0.08f), Size(side, side), r)
    drawRoundRect(tint.copy(alpha = alpha), tl, Size(side, side), r)
    val ic = side * 0.52f
    translate(center.x - ic / 2f, center.y - ic / 2f) {
        with(icon) { draw(Size(ic, ic), alpha = alpha, colorFilter = ColorFilter.tint(Color.White)) }
    }
}

// ---------------------------------------------------------------- Permissions

@Immutable
private data class PermItem(val id: String, val icon: ImageVector, val tint: Color, val title: String, val why: String)

private fun readGrants(ctx: Context): Map<String, Boolean> {
    fun has(p: String) = runCatching { ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    return mapOf(
        "notif" to (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)),
        "phone" to PHONE_PERMS.all { has(it) },
        "listener" to runCatching { notifAccessGranted(ctx) }.getOrDefault(false),
        "camera" to has(Manifest.permission.CAMERA),
    )
}

private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun PermissionsPage(onNext: () -> Unit) {
    val ctx = LocalContext.current
    val s = MaterialTheme.colorScheme
    val items = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(PermItem("notif", Icons.Rounded.Notifications, NotifRed,
            t("Notifications"), t("Transfer progress, call alerts and connection status.")))
        add(PermItem("listener", Icons.Rounded.NotificationsActive, AccessPurple,
            t("Notification access"), t("Show your phone's notifications on the Mac.")))
        add(PermItem("phone", Icons.Rounded.Call, CallGreen,
            t("Calls & messages"), t("Answer calls and read texts from your Mac.")))
        add(PermItem("camera", Icons.Rounded.CameraAlt, FileBlue,
            t("Camera"), t("Scan the QR code your Mac shows to pair.")))
    }
    var granted by remember { mutableStateOf(readGrants(ctx)) }
    val refresh = { granted = readGrants(ctx) }
    // Notification access is granted in system Settings: re-read on every return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh() } }

    // A second tap on a refused permission goes to App info: Android stops showing the dialog after two refusals.
    val asked = remember { mutableStateListOf<String>() }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val findIt = t("Find DroidDock and turn it on")
    val request: (String) -> Unit = { id ->
        val again = id in asked
        asked.add(id)
        when (id) {
            "notif" -> when {
                again -> openAppSettings(ctx)
                Build.VERSION.SDK_INT >= 33 -> single.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            "camera" -> if (again) openAppSettings(ctx) else single.launch(Manifest.permission.CAMERA)
            "phone" -> if (again) openAppSettings(ctx) else multi.launch(PHONE_PERMS)
            "listener" -> {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                Toast.makeText(ctx, findIt, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Text(
                t("Turn on what you need"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, modifier = Modifier.rise(60).semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                t("Each permission powers one feature. All of them are optional, and you can change them later in Settings."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, modifier = Modifier.rise(120),
            )
            Spacer(Modifier.height(24.dp))
            items.forEachIndexed { i, item ->
                PermRow(item, granted[item.id] == true, { request(item.id) }, Modifier.rise(180 + i * 70))
                Spacer(Modifier.height(12.dp))
            }
        }
        val all = items.all { granted[it.id] == true }
        DockButton(
            t("Continue"), onNext, kind = ButtonKind.PRIMARY, glow = all, fullWidth = true,
            icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.rise(180 + items.size * 70),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermRow(item: PermItem, isGranted: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val motion = DockTheme.motion
    val haptics = DockTheme.haptics
    // Buzz when a row flips to granted while on screen, not on first composition.
    var seen by remember { mutableStateOf(isGranted) }
    LaunchedEffect(isGranted) { if (isGranted && !seen) haptics.confirm(); seen = isGranted }

    DockCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.verticalGradient(listOf(item.tint, item.tint.copy(alpha = 0.78f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = s.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(item.why, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = isGranted,
                transitionSpec = {
                    (scaleIn(motion.pop(), initialScale = 0.6f) + fadeIn(motion.fade())) togetherWith
                        (scaleOut(motion.exit(), targetScale = 0.6f) + fadeOut(motion.exit()))
                },
                contentAlignment = Alignment.Center,
                label = "perm-${item.id}",
            ) { g ->
                if (g) GrantedCheck()
                else DockButton(
                    t("Allow"), onAllow, kind = ButtonKind.TONAL, height = 40.dp, horizontalPadding = 16.dp,
                    modifier = Modifier.semantics { contentDescription = "${t("Allow")}: ${item.title}" },
                )
            }
        }
    }
}

/** Green disc whose checkmark draws itself. */
@Composable
private fun GrantedCheck() {
    val motion = DockTheme.motion
    val ok = DockTheme.colors.success
    val draw = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion.enabled) { delay(90); draw.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } }
    val label = t("Allowed")
    Canvas(Modifier.size(36.dp).semantics { contentDescription = label }) {
        drawCircle(ok)
        drawCheck(draw.value, Color.White, 2.6.dp.toPx())
    }
}

private fun DrawScope.drawCheck(progress: Float, color: Color, width: Float) {
    if (progress <= 0f) return
    val path = Path().apply {
        moveTo(size.width * 0.30f, size.height * 0.52f)
        lineTo(size.width * 0.45f, size.height * 0.66f)
        lineTo(size.width * 0.71f, size.height * 0.36f)
    }
    val pm = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- Done

@Composable
private fun DonePage(onFinish: (Boolean) -> Unit) {
    val s = MaterialTheme.colorScheme
    val haptics = DockTheme.haptics
    LaunchedEffect(Unit) { delay(220); haptics.confirm() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CenteredScroll(Modifier.weight(1f).fillMaxWidth()) {
            DoneMark()
            Spacer(Modifier.height(12.dp))
            Text(
                t("You're all set"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.rise(260).semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                t("Open DroidDock on your Mac and scan the QR code it shows to pair."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.rise(330),
            )
        }
        DockButton(
            t("Pair with your Mac"), { onFinish(true) }, kind = ButtonKind.PRIMARY, glow = true, fullWidth = true,
            icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.rise(400),
        )
        Spacer(Modifier.height(8.dp))
        DockButton(t("Later"), { onFinish(false) }, kind = ButtonKind.GHOST, fullWidth = true, modifier = Modifier.rise(450))
        Spacer(Modifier.height(16.dp))
    }
}

/** A green tile settles, its checkmark draws, and one ring of dots bursts out once. */
@Composable
private fun DoneMark() {
    val motion = DockTheme.motion
    val s = MaterialTheme.colorScheme
    val ok = DockTheme.colors.success
    val land = remember { Animatable(if (motion.enabled) 0f else 1f) }
    val draw = remember { Animatable(if (motion.enabled) 0f else 1f) }
    val burst = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion.enabled) return@LaunchedEffect
        launch { land.animateTo(1f, spring(0.55f, 380f)) }
        launch { delay(140); draw.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
        launch { delay(200); burst.animateTo(1f, tween(700, easing = LinearEasing)) }
    }
    val colors = listOf(ok, s.primary, ClipOrange, Color(0xFFFF4F9A), Color(0xFF2EC4D6))
    Canvas(Modifier.size(180.dp).clearAndSetSemantics { }) {
        val c = center
        // Burst: dots ride out on an ease-out, fully visible early, fading by the end.
        val b = burst.value
        if (b > 0f && b < 1f) {
            val r = (46f + 40f * ease(b)).dp.toPx()
            val a = if (b < 0.15f) b / 0.15f else (1f - b) / 0.85f
            for (i in 0 until 12) {
                val ang = i / 12f * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                val dot = (if (i % 2 == 0) 3.6f else 2.6f).dp.toPx()
                drawCircle(colors[i % colors.size].copy(alpha = a), dot, Offset(c.x + cos(ang) * r, c.y + sin(ang) * r))
            }
            // A soft ring under the dots.
            drawCircle(ok.copy(alpha = 0.25f * (1f - b)), r * 0.92f, c, style = Stroke(2.dp.toPx()))
        }
        val l = land.value
        val side = 88.dp.toPx() * (0.86f + 0.14f * l)
        val tl = Offset(c.x - side / 2f, c.y - side / 2f)
        drawRoundRect(
            Brush.verticalGradient(listOf(ok, ok.copy(alpha = 0.72f)), tl.y, tl.y + side),
            tl, Size(side, side), CornerRadius(side * 0.3f), alpha = l.coerceIn(0f, 1f),
        )
        // drawCheck works in the current draw size, so shrink the area to the tile.
        translate(tl.x, tl.y) { drawIntoTile(Size(side, side)) { drawCheck(draw.value, Color.White, 7.dp.toPx()) } }
    }
}

/** Runs [block] with the draw size set to [sz] (top-left already translated). */
private fun DrawScope.drawIntoTile(sz: Size, block: DrawScope.() -> Unit) {
    val dx = size.width - sz.width
    val dy = size.height - sz.height
    // inset(right, bottom) shrinks the drawing area to the tile.
    inset(0f, 0f, dx, dy) { block() }
}

// ---------------------------------------------------------------- Shared bits

/** Centred while it fits; scrolls when it does not (landscape, large font scale). */
@Composable
private fun CenteredScroll(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun PageDots(step: Int, count: Int) {
    val s = MaterialTheme.colorScheme
    val motion = DockTheme.motion
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp)) {
        for (i in 0 until count) {
            val w by animateDpAsState(if (i == step) 22.dp else 8.dp, motion.chrome(), label = "dot-w")
            val c by animateColorAsState(if (i <= step) s.primary else s.onSurface.copy(alpha = 0.18f), motion.fade(), label = "dot-c")
            Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape).background(c))
        }
    }
}

/** Fade + rise after [delayMs]. Draw-phase only; a no-op with animations off. */
@Composable
private fun Modifier.rise(delayMs: Int, riseDp: Float = 14f): Modifier {
    val motion = DockTheme.motion
    if (!motion.enabled) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, spring(0.86f, 200f))
    }
    return this.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * riseDp.dp.toPx()
    }
}

private fun ease(x: Float): Float = FastOutSlowInEasing.transform(x.coerceIn(0f, 1f))

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val v = x - 1f
    return 1f + c3 * v.pow(3) + c1 * v.pow(2)
}
