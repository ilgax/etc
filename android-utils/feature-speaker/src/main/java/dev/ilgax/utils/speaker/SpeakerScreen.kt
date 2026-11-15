package dev.ilgax.utils.speaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.asString
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.DiagnosticsBlock
import dev.ilgax.utils.core.ui.components.HeroSection
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.ShizukuBanner
import dev.ilgax.utils.core.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SpeakerUiState(
    val enabled: Boolean,
    val pauseOnDisconnect: Boolean,
    val shizuku: ShizukuStatus,
    val statusLine: String,
    val diagnostics: String,
)

data class SpeakerActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onPauseChange: (Boolean) -> Unit,
    val onReapply: () -> Unit,
)

/** Stateful wrapper: wires [SpeakerScreen] to the controller and polls diagnostics while visible. */
@Composable
internal fun SpeakerDetail(contentPadding: PaddingValues, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val enabled by SpeakerController.enabledFlow.collectAsStateWithLifecycle()
    val statusLine by SpeakerController.statusFlow.collectAsStateWithLifecycle()
    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    var pause by remember { mutableStateOf(SpeakerController.pauseOnDisconnect(context)) }
    var diagnostics by remember { mutableStateOf("") }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SpeakerController.retryPendingRelease(context)
            while (true) {
                ShizukuState.refresh()
                diagnostics = withContext(Dispatchers.IO) { SpeakerController.diagnostics(context) }
                delay(2000)
            }
        }
    }

    SpeakerScreen(
        state = SpeakerUiState(enabled, pause, shizuku, statusLine, diagnostics),
        actions = SpeakerActions(
            onEnabledChange = { SpeakerController.setEnabled(context, it) },
            onPauseChange = { pause = it; SpeakerController.setPauseOnDisconnect(context, it) },
            onReapply = {
                SpeakerController.log(context, "manual re-apply")
                SpeakerController.enforce(context, "manual", true)
            },
        ),
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun SpeakerScreen(
    state: SpeakerUiState,
    actions: SpeakerActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    DetailScaffold(contentPadding, modifier) {
        ShizukuBanner(state.shizuku)
        HeroSection(
            iconRes = R.drawable.ic_speaker_off,
            headline = SpeakerText.headline(state.enabled).asString(),
            supporting = SpeakerText.supporting(state.enabled, state.shizuku).asString(),
            mode = if (state.enabled) CardMode.On else CardMode.Off,
            switchLabel = stringResource(R.string.speaker_title),
            onCheckedChange = actions.onEnabledChange,
        )
        SettingsGroup(title = stringResource(R.string.speaker_group_options)) {
            SwitchRow(
                title = stringResource(R.string.speaker_pause_title),
                supporting = stringResource(R.string.speaker_pause_summary),
                checked = state.pauseOnDisconnect,
                onCheckedChange = actions.onPauseChange,
                iconRes = CoreR.drawable.ic_pause,
            )
        }
        SettingsGroup(title = stringResource(R.string.speaker_group_status)) {
            SettingsRow(
                label = stringResource(R.string.speaker_shizuku_label),
                value = SpeakerText.shizukuLabel(state.shizuku).asString(),
                iconRes = CoreR.drawable.ic_info,
            )
            SettingsRow(
                label = stringResource(R.string.speaker_service_label),
                value = state.statusLine,
                iconRes = CoreR.drawable.ic_check,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = actions.onReapply, enabled = state.enabled) {
                Text(stringResource(R.string.speaker_reapply))
            }
        }
        DiagnosticsBlock(state.diagnostics)
        Text(
            stringResource(R.string.speaker_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
