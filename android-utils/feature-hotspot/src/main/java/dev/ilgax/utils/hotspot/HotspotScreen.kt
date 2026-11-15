package dev.ilgax.utils.hotspot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.asString
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.DiagnosticsBlock
import dev.ilgax.utils.core.ui.components.HeroSection
import dev.ilgax.utils.core.ui.components.InfoPill
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.ShizukuBanner
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.core.ui.components.WarningCard
import dev.ilgax.utils.core.ui.components.copyToClipboard
import kotlinx.coroutines.launch

/** Stateful wrapper: keeps the controller polling while the screen is on screen. */
@Composable
internal fun HotspotDetail(controller: HotspotController, contentPadding: PaddingValues, modifier: Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(controller) {
        controller.acquire()
        onStopOrDispose { controller.release() }
    }
    HotspotScreen(
        state = state,
        onToggle = { scope.launch { controller.toggle() } },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun HotspotScreen(
    state: HotspotUiState,
    onToggle: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenSettings: (Context) -> Boolean = ::openHotspotSettings,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailableText = stringResource(R.string.hotspot_settings_unavailable)
    var revealed by rememberSaveable { mutableStateOf(false) }

    DetailScaffold(contentPadding, modifier, snackbar) {
        ShizukuBanner(state.shizuku)
        val mode = HotspotText.cardMode(state)
        HeroSection(
            iconRes = R.drawable.ic_wifi_tethering,
            headline = HotspotText.headline(state).asString(),
            supporting = HotspotText.supporting(state).asString(),
            mode = mode,
            switchLabel = stringResource(R.string.hotspot_title),
            onCheckedChange = { onToggle() },
        )
        if (state.isOpenNetwork) {
            WarningCard(stringResource(R.string.hotspot_open_warning_title), stringResource(R.string.hotspot_open_warning_body))
        }
        state.error?.let {
            WarningCard(stringResource(R.string.hotspot_error_title), stringResource(R.string.hotspot_error_body, it))
        }

        SettingsGroup(title = stringResource(R.string.hotspot_group_network)) {
            SettingsRow(
                label = stringResource(R.string.hotspot_name),
                iconRes = R.drawable.ic_wifi_tethering,
                trailing = if (state.hidden) ({ InfoPill(stringResource(R.string.hotspot_hidden)) }) else null,
            ) {
                ValueText(state.ssid ?: stringResource(R.string.hotspot_value_loading))
            }
            SettingsRow(
                label = stringResource(R.string.hotspot_security),
                value = if (state.securityType < 0) stringResource(R.string.hotspot_value_loading) else stringResource(state.security.labelRes),
                iconRes = CoreR.drawable.ic_shield,
            )
            SettingsRow(
                label = stringResource(R.string.hotspot_band),
                value = HotspotText.bandRow(state).ifEmpty { stringResource(R.string.hotspot_value_loading) },
                iconRes = CoreR.drawable.ic_band,
            )
            if (!state.isOpenNetwork) {
                PasswordRow(
                    passphrase = state.passphrase,
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    onCopy = { state.passphrase?.let { copyToClipboard(context, "hotspot password", it, sensitive = true) } },
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.hotspot_group_connections)) {
            SettingsRow(
                label = stringResource(R.string.hotspot_devices_label),
                value = HotspotText.clientsRow(state).asString(),
                iconRes = CoreR.drawable.ic_group,
            )
            SettingsRow(
                label = stringResource(R.string.hotspot_upstream_label),
                value = HotspotText.upstreamRow(state).asString(),
                iconRes = CoreR.drawable.ic_wifi,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = {
                if (!onOpenSettings(context)) scope.launch { snackbar.showSnackbar(unavailableText) }
            }) {
                UIcon(CoreR.drawable.ic_open_in_new, null, Modifier.size(18.dp))
                Text(stringResource(R.string.hotspot_settings_button), Modifier.padding(start = 8.dp))
            }
        }

        DiagnosticsBlock(state.diagnostics)
        Text(
            stringResource(R.string.hotspot_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ValueText(text: String, mono: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = if (mono) FontFamily.Monospace else null,
    )
}

@Composable
private fun PasswordRow(passphrase: String?, revealed: Boolean, onToggleReveal: () -> Unit, onCopy: () -> Unit) {
    SettingsRow(
        label = stringResource(R.string.hotspot_password),
        iconRes = CoreR.drawable.ic_key,
        trailing = if (passphrase == null) null else ({
            Row {
                IconButton(onClick = onToggleReveal) {
                    UIcon(
                        if (revealed) CoreR.drawable.ic_visibility_off else CoreR.drawable.ic_visibility,
                        stringResource(if (revealed) R.string.hotspot_password_hide else R.string.hotspot_password_show),
                    )
                }
                IconButton(onClick = onCopy) {
                    UIcon(CoreR.drawable.ic_content_copy, stringResource(R.string.hotspot_password_copy))
                }
            }
        }),
    ) {
        when {
            passphrase == null -> ValueText(stringResource(R.string.hotspot_value_loading))
            revealed -> ValueText(passphrase, mono = true)
            else -> ValueText(stringResource(R.string.hotspot_password_masked))
        }
    }
}

/** Samsung's hidden hotspot settings page; false when this device does not have it. */
internal fun openHotspotSettings(context: Context): Boolean = try {
    context.startActivity(
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$WifiApSettingsActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (t: Exception) {
    false
}
