package dev.ilgax.utils.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A labelled card (28dp) holding rows (16dp) on a slightly different tone. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

/** Rows sit one step brighter than their group card in light theme and one step higher in dark theme. */
@Composable
private fun RowSurface(modifier: Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
        content = content,
    )
}

/** Label above a value, optional leading icon and trailing slot. [value] may be rich content. */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconTint: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    RowSurface(if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (leading != null) {
                leading()
            } else if (iconRes != null) {
                UIcon(iconRes, null, tint = iconTint.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                value()
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SettingsRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconTint: Color = Color.Unspecified,
) {
    SettingsRow(label, modifier, iconRes, onClick, trailing, iconTint) {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Whole-row toggle: title + supporting text on the left, switch on the right. */
@Composable
fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    val haptic = LocalHapticFeedback.current
    RowSurface(
        modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = {
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onCheckedChange(it)
            },
        ),
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (iconRes != null) {
                UIcon(iconRes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** The sideload explanation shown under every checklist row that asks for the shared "Utils" accessibility service. */
@androidx.compose.runtime.Composable
fun A11ySetupNote(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Column(modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            androidx.compose.ui.res.stringResource(dev.ilgax.utils.core.R.string.a11y_setup_note),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton(onClick = { dev.ilgax.utils.core.a11y.A11y.openAppInfo(context) }) {
            Text(androidx.compose.ui.res.stringResource(dev.ilgax.utils.core.R.string.a11y_open_app_info))
        }
    }
}
