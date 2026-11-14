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
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.droiddock.app.t
import com.droiddock.app.ui.components.ButtonKind
import com.droiddock.app.ui.components.DockButton
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockIconBadge
import com.droiddock.app.ui.components.DockStatusPill
import com.droiddock.app.ui.components.TetherState
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.home.LinkBay
import com.droiddock.app.ui.setup.PHONE_PERMS
import com.droiddock.app.ui.setup.RestrictedSettingsHint
import com.droiddock.app.ui.setup.notifAccessGranted
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.dockEnter
import com.droiddock.app.ui.theme.dockEnterTransition
import com.droiddock.app.ui.theme.dockExitTransition
import com.droiddock.app.ui.theme.dockPageBackground
import com.droiddock.app.ui.theme.dockReveal
import kotlinx.coroutines.delay

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

/**
 * First run, told as one scene in the "Docking" language: the phone is the bay, the link to the Mac is the tether.
 *
 * - Welcome: the phone seats in its bay and the tether draws out toward a Mac that isn't there yet.
 * - Permissions: each permission is a module that docks into a slot rail as it's granted ("2 of 4 ready"); every one is
 *   optional. Sideloaded installs get the "Allow restricted settings" guide under notification access when Android
 *   greys that switch out.
 * - Done: the tether reaches the Mac and "Pair with your Mac" rises into the thumb zone.
 *
 * One-shot motion that holds its final frame (nothing loops), all through Motion tokens; with animations off every
 * piece renders at its final frame.
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
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
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
                        // Pages dock: a short travel in the direction of the step, seated by the spatial spring.
                        val dir = if (targetState > initialState) 1 else -1
                        val seat = motion.spatial<IntOffset>(IntOffset.VisibilityThreshold)
                        (slideInHorizontally(seat) { dir * it / 6 } + fadeIn(motion.page())) togetherWith
                            (slideOutHorizontally(motion.exit()) { -dir * it / 8 } + fadeOut(motion.exit()))
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

/** One-shot 0 → 1 after [delayMs], for drawing the tether in. Snaps to 1 with animations off. */
@Composable
private fun rememberDrawIn(delayMs: Long): Float {
    val motion = DockTheme.motion
    val p = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion.enabled) { p.snapTo(1f); return@LaunchedEffect }
        delay(delayMs)
        p.animateTo(1f, motion.smooth())
    }
    return p.value
}

// ---------------------------------------------------------------- Welcome

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val draw = rememberDrawIn(delayMs = 220)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CenteredScroll(Modifier.weight(1f).fillMaxWidth()) {
            // The phone seats first; the tether then draws out toward a Mac that isn't paired yet. LINKED because
            // DockTether draws `progress` only on a linked line (CONNECTING would loop its dashes: nothing here loops).
            LinkBay(
                state = TetherState.LINKED,
                description = t("Your phone, on your Mac"),
                progress = draw,
                showMac = false,
                badge = 56.dp,
                modifier = Modifier.widthIn(max = 380.dp).dockEnter(0, riseDp = 16f),
            )
            Spacer(Modifier.height(40.dp))
            Text(
                t("Your phone, on your Mac"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.dockEnter(2).semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                t("Notifications, files, clipboard and calls move between your phone and your Mac, privately over your own Wi-Fi."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.dockEnter(3),
            )
            Spacer(Modifier.height(24.dp))
            // What travels on the tether, each seating in turn. One accent: tonal badges, no per-feature colours.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FeatureMark(Icons.Rounded.Notifications, t("Alerts"), Modifier.dockEnter(4))
                FeatureMark(Icons.Rounded.Description, t("Files"), Modifier.dockEnter(5))
                FeatureMark(Icons.Rounded.ContentPaste, t("Clipboard"), Modifier.dockEnter(6))
            }
        }
        DockButton(
            t("Get started"), onNext, kind = ButtonKind.PRIMARY, glow = true, fullWidth = true,
            icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.dockEnter(6),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeatureMark(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DockIconBadge(icon, tone = Tone.PRIMARY, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------- Permissions

@Immutable
private data class PermItem(val id: String, val icon: ImageVector, val title: String, val why: String)

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
        if (Build.VERSION.SDK_INT >= 33) add(PermItem("notif", Icons.Rounded.Notifications,
            t("Notifications"), t("Transfer progress, call alerts and connection status.")))
        add(PermItem("listener", Icons.Rounded.NotificationsActive,
            t("Notification access"), t("Show your phone's notifications on the Mac.")))
        add(PermItem("phone", Icons.Rounded.Call,
            t("Calls & messages"), t("Answer calls and read texts from your Mac.")))
        add(PermItem("camera", Icons.Rounded.CameraAlt,
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
    val ready = items.count { granted[it.id] == true }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Text(
                t("Turn on what you need"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, modifier = Modifier.dockEnter(0).semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                t("Each permission powers one feature. All of them are optional, and you can change them later in Settings."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, modifier = Modifier.dockEnter(1),
            )
            Spacer(Modifier.height(20.dp))
            SlotRail(items, granted, ready, Modifier.dockEnter(2))
            Spacer(Modifier.height(20.dp))
            items.forEachIndexed { i, item ->
                val on = granted[item.id] == true
                PermRow(item, on, { request(item.id) }, Modifier.dockEnter(3 + i)) {
                    // Sideloaded on Android 13+: the notification-access switch stays greyed until "Allow restricted settings".
                    if (item.id == "listener") RestrictedSettingsHint(switchOff = !on)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        DockButton(
            t("Continue"), onNext, kind = ButtonKind.PRIMARY, glow = ready == items.size, fullWidth = true,
            icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.dockEnter(3 + items.size),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The bay's module rail: one slot per permission. An empty slot is a hairline outline; a granted permission docks its
 * module into the slot in the current signature (pop, rise, wipe…). Spoken as one line ("2 of 4 ready").
 */
@Composable
private fun SlotRail(items: List<PermItem>, granted: Map<String, Boolean>, ready: Int, modifier: Modifier = Modifier) {
    val shape = DockTheme.shapes.badge
    val counter = t("{n} of {total} ready", "n" to ready, "total" to items.size)
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = counter },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            Box(Modifier.size(44.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(shape).border(1.dp, DockTheme.colors.hairline, shape))
                DockIconBadge(item.icon, tone = Tone.PRIMARY, size = 44.dp, modifier = Modifier.dockReveal(granted[item.id] == true))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            counter, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun PermRow(
    item: PermItem,
    isGranted: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val s = MaterialTheme.colorScheme
    val haptics = DockTheme.haptics
    // Buzz when a row flips to granted while on screen, not on first composition.
    var seen by remember { mutableStateOf(isGranted) }
    LaunchedEffect(isGranted) { if (isGranted && !seen) haptics.confirm(); seen = isGranted }

    DockCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockIconBadge(item.icon, tone = if (isGranted) Tone.PRIMARY else Tone.NEUTRAL, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = s.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(item.why, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            val enter = dockEnterTransition()
            val exit = dockExitTransition()
            AnimatedContent(
                targetState = isGranted,
                transitionSpec = { enter togetherWith exit },
                contentAlignment = Alignment.Center,
                label = "perm-${item.id}",
            ) { g ->
                if (g) DockStatusPill(t("On"))
                else DockButton(
                    t("Allow"), onAllow, kind = ButtonKind.TONAL, height = 48.dp, horizontalPadding = 16.dp,
                    modifier = Modifier.semantics { contentDescription = "${t("Allow")}: ${item.title}" },
                )
            }
        }
        below()
    }
}

// ---------------------------------------------------------------- Done

@Composable
private fun DonePage(onFinish: (Boolean) -> Unit) {
    val s = MaterialTheme.colorScheme
    val haptics = DockTheme.haptics
    val draw = rememberDrawIn(delayMs = 160)
    // One confirm as the tether reaches the Mac (not on a restored page that is already drawn).
    var buzzed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draw >= 1f) { if (draw >= 1f && !buzzed) { buzzed = true; haptics.confirm() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CenteredScroll(Modifier.weight(1f).fillMaxWidth()) {
            // The tether draws all the way to the Mac (LINKED: the only state that draws `progress`).
            LinkBay(
                state = TetherState.LINKED,
                description = t("You're all set"),
                progress = draw,
                badge = 56.dp,
                modifier = Modifier.widthIn(max = 380.dp).dockEnter(0, riseDp = 16f),
            )
            Spacer(Modifier.height(40.dp))
            Text(
                t("You're all set"),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineBreak = LineBreak.Heading),
                color = s.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.dockEnter(2).semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                t("Open DroidDock on your Mac and scan the QR code it shows to pair."),
                style = MaterialTheme.typography.bodyLarge, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.dockEnter(3),
            )
        }
        // The one action rises into the thumb zone last.
        DockButton(
            t("Pair with your Mac"), { onFinish(true) }, kind = ButtonKind.PRIMARY, glow = true, fullWidth = true,
            icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.dockEnter(4, riseDp = 24f),
        )
        Spacer(Modifier.height(8.dp))
        DockButton(t("Later"), { onFinish(false) }, kind = ButtonKind.GHOST, fullWidth = true, modifier = Modifier.dockEnter(5))
        Spacer(Modifier.height(16.dp))
    }
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
    val label = t("Step {n} of {total}", "n" to step + 1, "total" to count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        for (i in 0 until count) {
            val w by animateDpAsState(if (i == step) 22.dp else 8.dp, motion.chrome(), label = "dot-w")
            val c by animateColorAsState(if (i <= step) s.primary else s.onSurface.copy(alpha = 0.18f), motion.fade(), label = "dot-c")
            Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape).background(c))
        }
    }
}
