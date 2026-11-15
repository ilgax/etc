package dev.ilgax.utils.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.ui.components.UIcon

/** Modal bottom sheet hosting [PairingPanel]. Dismissing it cancels pairing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(
    step: PairingStep,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (step is PairingStep.Success) onClose() else onCancel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PairingPanel(step, onConfirm, onCancel, onClose, Modifier.navigationBarsPadding())
    }
}

/** Waiting with countdown, then the big code to compare, then success. */
@Composable
fun PairingPanel(
    step: PairingStep,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val title = when (step) {
            is PairingStep.Waiting -> R.string.remote_pair_waiting_title
            is PairingStep.Code -> R.string.remote_pair_code_title
            PairingStep.Success -> R.string.remote_pair_success_title
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        when (step) {
            is PairingStep.Waiting -> {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp)
                Text(stringResource(R.string.remote_pair_waiting_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Countdown(step.secondsLeft)
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.remote_btn_cancel)) }
            }
            is PairingStep.Code -> {
                val description = stringResource(R.string.remote_pair_code_cd, step.code.replace(" ", ", "))
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text(
                        step.code,
                        Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                    )
                }
                Text(
                    stringResource(if (step.confirmed) R.string.remote_pair_confirmed_body else R.string.remote_pair_code_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Countdown(step.secondsLeft)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.remote_btn_cancel)) }
                    Button(onClick = onConfirm, enabled = !step.confirmed) { Text(stringResource(R.string.remote_btn_confirm)) }
                }
            }
            PairingStep.Success -> {
                Surface(Modifier.size(64.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        UIcon(CoreR.drawable.ic_check, null)
                    }
                }
                Text(stringResource(R.string.remote_pair_success_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Button(onClick = onClose) { Text(stringResource(R.string.remote_btn_close)) }
            }
        }
    }
}

@Composable
private fun Countdown(seconds: Int) {
    Text(
        stringResource(R.string.remote_pair_seconds_left, seconds),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Searchable list of launchable apps with their icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    slot: Int,
    loadApps: suspend () -> List<AppCatalog.App>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps by produceState<List<AppCatalog.App>?>(null) { value = loadApps() }
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.9f).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.remote_choose_app_title, slot + 1), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.remote_search_apps)) },
                shape = MaterialTheme.shapes.large,
            )
            val shown = apps?.filter { it.label.contains(query.trim(), ignoreCase = true) }
            if (shown == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            } else {
                LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
                    items(shown, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AppIcon(app.packageName, app.label)
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
