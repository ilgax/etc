package dev.ilgax.utils.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.CardMode

/** Top of every detail screen: big icon, state headline, one supporting line and the primary switch. */
@Composable
fun HeroSection(
    @DrawableRes iconRes: Int,
    headline: String,
    supporting: String,
    mode: CardMode,
    switchLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = toneFor(mode)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Column(Modifier.padding(24.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(iconRes, tone, size = 72)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    SwitchOrProgress(
                        checked = mode == CardMode.On,
                        busy = mode == CardMode.Busy,
                        enabled = mode == CardMode.On || mode == CardMode.Off,
                        contentDescription = switchLabel,
                        onCheckedChange = onCheckedChange,
                        scale = 1.35f,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, style = MaterialTheme.typography.headlineMedium)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tone.content.copy(alpha = if (mode == CardMode.Unavailable) 1f else 0.8f),
                )
            }
        }
    }
}
