package dev.ilgax.utils.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.CardMode

/** Colors shared by the home card and the detail hero so a module looks the same in both places. */
internal data class ToneColors(val container: Color, val content: Color, val badge: Color, val onBadge: Color)

@Composable
internal fun toneFor(mode: CardMode): ToneColors {
    val cs = MaterialTheme.colorScheme
    val target = when (mode) {
        CardMode.On -> ToneColors(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary)
        CardMode.Off, CardMode.Busy ->
            ToneColors(cs.surfaceContainerHigh, cs.onSurface, cs.secondaryContainer, cs.onSecondaryContainer)
        CardMode.Unavailable ->
            ToneColors(cs.surfaceContainerLow, cs.onSurface.copy(alpha = 0.62f), cs.surfaceVariant, cs.onSurfaceVariant.copy(alpha = 0.7f))
    }
    return ToneColors(
        animateColorAsState(target.container, label = "container").value,
        animateColorAsState(target.content, label = "content").value,
        animateColorAsState(target.badge, label = "badge").value,
        animateColorAsState(target.onBadge, label = "onBadge").value,
    )
}

/** Rounded tonal square holding a module icon. */
@Composable
internal fun IconBadge(@DrawableRes iconRes: Int, tone: ToneColors, size: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(size.dp),
        shape = RoundedCornerShape((size * 0.34f).dp),
        color = tone.badge,
        contentColor = tone.onBadge,
    ) {
        Box(contentAlignment = Alignment.Center) {
            UIcon(iconRes, null, Modifier.size((size * 0.46f).dp))
        }
    }
}

/** Switch that turns into a progress ring while [busy]; reserves a fixed box so the layout never jumps. */
@Composable
internal fun SwitchOrProgress(
    checked: Boolean,
    busy: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size((64 * scale).dp, (48 * scale).dp), contentAlignment = Alignment.Center) {
        Crossfade(busy, label = "busy") { isBusy ->
            if (isBusy) {
                CircularProgressIndicator(
                    Modifier
                        .size(28.dp)
                        .semantics { this.contentDescription = contentDescription },
                    strokeWidth = 3.dp,
                )
            } else {
                UtilsSwitch(checked, onCheckedChange, contentDescription, Modifier.scale(scale), enabled)
            }
        }
    }
}

@Composable
fun ModuleCard(
    title: String,
    status: String,
    @DrawableRes iconRes: Int,
    mode: CardMode,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showSwitch: Boolean = true,
) {
    val tone = toneFor(mode)
    val elevation = if (mode == CardMode.On) 2.dp else 0.dp
    val statusColor = if (mode == CardMode.Unavailable) tone.content else tone.content.copy(alpha = 0.78f)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = tone.container, contentColor = tone.content),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(iconRes, tone, size = 56)
            Column(Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showSwitch) SwitchOrProgress(
                checked = mode == CardMode.On,
                busy = mode == CardMode.Busy,
                enabled = mode == CardMode.On || mode == CardMode.Off,
                contentDescription = title,
                onCheckedChange = { onToggle() },
                scale = 1.1f,
            )
        }
    }
}
