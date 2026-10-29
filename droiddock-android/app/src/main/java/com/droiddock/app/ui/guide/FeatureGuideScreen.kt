package com.droiddock.app.ui.guide

import com.droiddock.app.ui.theme.dockItemAnimation
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.droiddock.app.t
import com.droiddock.app.ui.components.DockCard
import com.droiddock.app.ui.components.DockScaffold
import com.droiddock.app.ui.theme.DockTheme

@Composable
internal fun FeatureGuideScreen(onBack: () -> Unit) {
    DockScaffold(
        title = t("Feature Guide"),
        subtitle = t("Tap any card for step-by-step help."),
        onBack = onBack,
        backLabel = t("Back"),
        drawPageBackground = true,
    ) {
        items(GUIDE_SECTIONS.size, key = { GUIDE_SECTIONS[it].title }, contentType = { "guide" }) { i ->
            GuideCard(GUIDE_SECTIONS[i], dockItemAnimation())
        }
    }
}

@Composable
internal fun GuideCard(section: GuideSection, modifier: Modifier = Modifier) {
    var open by rememberSaveable(section.title) { mutableStateOf(false) }
    val sp = DockTheme.spacing
    val s = MaterialTheme.colorScheme
    DockCard(modifier, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t(section.title), style = MaterialTheme.typography.titleMedium, color = s.onSurface)
                Text(t(section.subtitle), style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant)
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (open) t("Collapse") else t("Expand"),
                tint = s.primary,
            )
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(top = sp.m), verticalArrangement = Arrangement.spacedBy(sp.s)) {
                section.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(24.dp).background(s.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = s.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(sp.m))
                        Text(
                            t(step), style = MaterialTheme.typography.bodyMedium,
                            color = s.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
