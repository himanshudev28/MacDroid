package com.droiddock.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.os.Build
import com.droiddock.app.ThemeMode
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockDivider
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.components.dockClickable
import com.droiddock.app.ui.setup.ToggleRow
import com.droiddock.app.ui.theme.Appearance
import com.droiddock.app.ui.theme.ColorSource
import com.droiddock.app.ui.theme.DockTheme
import com.droiddock.app.ui.theme.DroidDockTheme
import com.droiddock.app.ui.theme.GlassIntensity
import com.droiddock.app.ui.theme.MotionPersonality
import com.droiddock.app.ui.theme.NavLayout
import com.droiddock.app.ui.theme.UiStyle
import com.droiddock.app.ui.theme.dockPageBackground

private fun UiStyle.short() = when (this) {
    UiStyle.LIQUID_GLASS -> "Glass"
    UiStyle.MATERIAL_YOU -> "Material"
    UiStyle.MINIMAL_MONO -> "Mono"
    UiStyle.REACH -> "Reach"
}

private fun UiStyle.label() = when (this) {
    UiStyle.LIQUID_GLASS -> "Liquid Glass"
    UiStyle.MATERIAL_YOU -> "Material You"
    UiStyle.MINIMAL_MONO -> "Minimal Mono"
    UiStyle.REACH -> "Reach"
}

/**
 * Settings > Appearance rows (the body of the Appearance section).
 *
 * Stateless: [appearance] in, [onChange] out. Settings wires it to
 * `AppearanceStore`, so every control switches the whole app live.
 */
@Composable
internal fun AppearanceRows(appearance: Appearance, onChange: ((Appearance) -> Appearance) -> Unit) {
    val sp = DockTheme.spacing
    // Visual style: four live thumbnails, each drawn in its own style's tokens.
    Column(Modifier.fillMaxWidth().padding(horizontal = sp.cardPadding, vertical = sp.m)) {
        Text(t("Visual style"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            t(appearance.style.label()),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = sp.m),
            horizontalArrangement = Arrangement.spacedBy(sp.s),
        ) {
            UiStyle.entries.forEach { s ->
                StyleThumb(
                    style = s, base = appearance, selected = s == appearance.style,
                    modifier = Modifier.weight(1f),
                ) { onChange { it.copy(style = s) } }
            }
        }
    }
    DockDivider()

    ChoiceRow(
        icon = Icons.Outlined.DarkMode, tone = Tone.SECONDARY,
        title = t("Theme"),
        subtitle = when (appearance.themeMode) {
            ThemeMode.LIGHT -> t("Always light")
            ThemeMode.DARK -> t("Always dark")
            ThemeMode.SYSTEM -> t("Follows your phone's setting")
        },
        options = ThemeMode.entries.map {
            it.name to when (it) {
                ThemeMode.SYSTEM -> t("System")
                ThemeMode.LIGHT -> t("Light")
                ThemeMode.DARK -> t("Dark")
            }
        },
        selected = appearance.themeMode.name,
        onSelect = { n -> onChange { it.copy(themeMode = ThemeMode.valueOf(n)) } },
    )
    DockDivider()

    // Phone colours need Android 12 dynamic colour; below that the row would
    // offer a choice that does nothing, so it is not shown at all.
    if (Build.VERSION.SDK_INT >= 31) {
        ChoiceRow(
            icon = Icons.Outlined.Palette, tone = Tone.PRIMARY,
            title = t("App colours"),
            subtitle = when (appearance.colorSource) {
                ColorSource.BRAND -> t("DroidDock violet and indigo")
                ColorSource.PHONE -> t("Follows your wallpaper (Material You)")
            },
            options = ColorSource.entries.map {
                it.name to when (it) {
                    ColorSource.BRAND -> t("DroidDock")
                    ColorSource.PHONE -> t("Phone colours")
                }
            },
            selected = appearance.colorSource.name,
            onSelect = { n -> onChange { it.copy(colorSource = ColorSource.valueOf(n)) } },
        )
        DockDivider()
    }

    ToggleRow(
        icon = Icons.Outlined.Contrast, tone = Tone.TERTIARY,
        title = t("AMOLED black"),
        subtitle = if (appearance.style.allowsAmoled) t("True black backgrounds in dark theme, for OLED screens")
        else t("Not available in Liquid Glass: glass needs colour behind it"),
        checked = appearance.amoled && appearance.style.allowsAmoled,
        enabled = appearance.style.allowsAmoled,
        onChecked = { v -> onChange { it.copy(amoled = v) } },
    )
    DockDivider()

    ChoiceRow(
        icon = Icons.Outlined.ViewQuilt, tone = Tone.PRIMARY,
        title = t("Navigation"),
        subtitle = when (appearance.layout) {
            NavLayout.TABS_4 -> t("Four tabs. Clipboard opens from Home")
            NavLayout.CLASSIC_5 -> t("Five tabs, as before")
            NavLayout.HUB -> t("A home hub of tiles, no tab bar")
        },
        options = NavLayout.entries.map {
            it.name to when (it) {
                NavLayout.TABS_4 -> t("4 tabs")
                NavLayout.CLASSIC_5 -> t("Classic 5")
                NavLayout.HUB -> t("Hub")
            }
        },
        selected = appearance.layout.name,
        onSelect = { n -> onChange { it.copy(layout = NavLayout.valueOf(n)) } },
    )
    DockDivider()

    ChoiceRow(
        icon = Icons.Outlined.Speed, tone = Tone.SECONDARY,
        title = t("Motion"),
        subtitle = when (appearance.motion) {
            MotionPersonality.CALM -> t("Slow and gentle")
            MotionPersonality.STANDARD -> t("Balanced")
            MotionPersonality.BOUNCY -> t("Springy and playful")
        },
        options = MotionPersonality.entries.map {
            it.name to when (it) {
                MotionPersonality.CALM -> t("Calm")
                MotionPersonality.STANDARD -> t("Standard")
                MotionPersonality.BOUNCY -> t("Bouncy")
            }
        },
        selected = appearance.motion.name,
        onSelect = { n -> onChange { it.copy(motion = MotionPersonality.valueOf(n)) } },
    )
    DockDivider()

    ToggleRow(
        icon = Icons.Outlined.AutoAwesome, tone = Tone.PRIMARY,
        title = t("Glow accent"),
        subtitle = t("A soft light on the main action and active controls"),
        checked = appearance.glow,
        onChecked = { v -> onChange { it.copy(glow = v) } },
    )

    if (appearance.style == UiStyle.LIQUID_GLASS) {
        DockDivider()
        ChoiceRow(
            icon = Icons.Outlined.BlurOn, tone = Tone.SECONDARY,
            title = t("Glass intensity"),
            subtitle = t("How much blur and tint the glass uses"),
            options = GlassIntensity.entries.map {
                it.name to when (it) {
                    GlassIntensity.SUBTLE -> t("Subtle")
                    GlassIntensity.STANDARD -> t("Standard")
                    GlassIntensity.STRONG -> t("Strong")
                }
            },
            selected = appearance.glassIntensity.name,
            onSelect = { n -> onChange { it.copy(glassIntensity = GlassIntensity.valueOf(n)) } },
        )
    }
    DockDivider()

    ToggleRow(
        icon = Icons.Outlined.Opacity, tone = Tone.NEUTRAL,
        title = t("Reduce transparency"),
        subtitle = t("Glass becomes solid. Easier to read, lighter on the battery"),
        checked = appearance.reduceTransparency,
        onChecked = { v -> onChange { it.copy(reduceTransparency = v) } },
    )
}

/**
 * Miniature of a style, drawn with that style's own tokens: nested theme with
 * blur off and transparency reduced, so a thumbnail never costs a RenderEffect.
 */
@Composable
private fun StyleThumb(
    style: UiStyle,
    base: Appearance,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val name = t(style.label())
    val shortName = t(style.short())
    val outer = MaterialTheme.colorScheme
    val ring = if (selected) outer.primary else DockTheme.colors.hairline
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = name
                this.selected = selected
            }
            .clip(shape)
            .dockClickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DroidDockTheme(
            appearance = base.copy(style = style, reduceTransparency = true, liveBlur = false),
            dynamicColor = true,
        ) {
            val c = DockTheme.colors
            val s = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(shape)
                    .dockPageBackground()
                    .border(if (selected) 2.dp else 1.dp, ring, shape)
                    .padding(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(22.dp).height(5.dp).clip(CircleShape).background(s.onSurface))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(c.card)
                            .padding(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(s.primary))
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(s.onSurfaceVariant.copy(alpha = 0.5f)))
                    }
                    Box(
                        Modifier.width(34.dp).height(10.dp).clip(CircleShape).background(s.primary),
                    )
                }
            }
        }
        Text(
            shortName, style = MaterialTheme.typography.labelSmall,
            color = if (selected) outer.primary else outer.onSurfaceVariant,
            maxLines = 1, softWrap = false,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
