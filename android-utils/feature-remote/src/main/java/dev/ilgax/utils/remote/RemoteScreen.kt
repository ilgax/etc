@file:OptIn(ExperimentalLayoutApi::class)

package dev.ilgax.utils.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.asString
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.HeroSection
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.core.ui.components.WarningCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteScreen(
    state: RemoteUiState,
    actions: RemoteActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }
    val btPermission = state.checks.bluetoothPermission

    DetailScaffold(contentPadding, modifier, snackbar) {
        HeroSection(
            iconRes = R.drawable.ic_watch,
            headline = RemoteText.headline(state.service, btPermission).asString(),
            supporting = RemoteText.supporting(state.service, state.paired, btPermission, state.serviceDetail).asString(),
            mode = RemoteText.cardMode(state.service),
            switchLabel = stringResource(R.string.remote_title),
            onCheckedChange = actions.onListeningChange,
        )
        if (state.service == RemoteState.Service.ERROR || state.service == RemoteState.Service.NO_PERMISSION) {
            state.serviceDetail?.let { WarningCard(stringResource(R.string.remote_headline_error), it) }
        }

        SetupGroup(state.checks, actions.onSetup)

        SettingsGroup(title = stringResource(R.string.remote_group_watch)) {
            SettingsRow(
                label = stringResource(R.string.remote_watch_label),
                value = stringResource(if (state.paired) R.string.remote_watch_paired else R.string.remote_watch_not_paired),
                iconRes = R.drawable.ic_watch,
                trailing = {
                    if (state.paired) {
                        TextButton(onClick = { confirmUnpair = true }) { Text(stringResource(R.string.remote_btn_unpair)) }
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = actions.onStartPairing) { Text(stringResource(R.string.remote_btn_pair)) }
            }
        }

        SlotsGroup(state.slots, actions)

        SettingsGroup(title = stringResource(R.string.remote_group_test)) {
            Text(
                stringResource(R.string.remote_test_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fun run(command: Command, args: ByteArray = ByteArray(0), delayMs: Long = 0) {
                    scope.launch {
                        if (delayMs > 0) delay(delayMs)
                        val result = withContext(Dispatchers.Default) { actions.runCommand(command, args) }
                        snackbar.showSnackbar(context.getString(statusTextRes(result)))
                    }
                }
                TestButton(R.string.remote_test_lock) { run(Command.LOCK) }
                TestButton(R.string.remote_test_play_pause) { run(Command.PLAY_PAUSE) }
                TestButton(R.string.remote_test_vol_up) { run(Command.VOLUME_UP) }
                TestButton(R.string.remote_test_vol_down) { run(Command.VOLUME_DOWN) }
                TestButton(R.string.remote_test_ring) { run(Command.RING) }
                TestButton(R.string.remote_test_close_app) { run(Command.KILL_APP, delayMs = 3000) }
                for (i in 0 until Protocol.APP_SLOTS) {
                    TestButton(R.string.remote_test_open_slot, i + 1) { run(Command.OPEN_APP, byteArrayOf(i.toByte())) }
                }
                for (i in 0 until Protocol.APP_SLOTS) {
                    TestButton(R.string.remote_test_switch_slot, i + 1) { run(Command.SWITCH_APP, byteArrayOf(i.toByte())) }
                }
            }
        }

        Text(
            stringResource(if (state.forceStop) R.string.remote_killer_shizuku else R.string.remote_killer_background),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            stringResource(R.string.remote_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.remote_unpair_title)) },
            text = { Text(stringResource(R.string.remote_unpair_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; actions.onUnpair() }) { Text(stringResource(R.string.remote_btn_unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.remote_btn_cancel)) }
            },
        )
    }
}

@Composable
private fun TestButton(labelRes: Int, arg: Int? = null, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Text(if (arg == null) stringResource(labelRes) else stringResource(labelRes, arg), style = MaterialTheme.typography.labelLarge)
    }
}

private fun SetupItem.labelRes() = when (this) {
    SetupItem.BluetoothPermission -> R.string.remote_chk_bt_permission
    SetupItem.Notifications -> R.string.remote_chk_notifications
    SetupItem.BluetoothOn -> R.string.remote_chk_bluetooth_on
    SetupItem.Accessibility -> R.string.remote_chk_accessibility
    SetupItem.Battery -> R.string.remote_chk_battery
}

private fun SetupItem.actionRes() = when (this) {
    SetupItem.BluetoothPermission, SetupItem.Notifications -> R.string.remote_btn_grant
    SetupItem.BluetoothOn -> R.string.remote_btn_enable
    SetupItem.Accessibility -> R.string.remote_btn_open_settings
    SetupItem.Battery -> R.string.remote_btn_exempt
}

/** Checklist; collapses to one "All set" row once nothing is pending. */
@Composable
private fun SetupGroup(checks: SetupChecks, onSetup: (SetupItem) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SettingsGroup(title = stringResource(R.string.remote_group_setup)) {
        if (checks.allSet) {
            SettingsRow(
                label = stringResource(R.string.remote_setup_all_set),
                value = stringResource(R.string.remote_setup_all_set_detail),
                iconRes = CoreR.drawable.ic_check,
                iconTint = colors.primary,
            )
        } else {
            for (item in SetupItem.entries) {
                val done = checks.isDone(item)
                SettingsRow(
                    label = stringResource(if (done) R.string.remote_done else R.string.remote_todo),
                    iconRes = if (done) CoreR.drawable.ic_check else CoreR.drawable.ic_warning,
                    iconTint = if (done) colors.primary else colors.error,
                    trailing = if (done) null else ({
                        FilledTonalButton(onClick = { onSetup(item) }) { Text(stringResource(item.actionRes())) }
                    }),
                ) {
                    Text(stringResource(item.labelRes()), style = MaterialTheme.typography.bodyLarge)
                }
                if (item == SetupItem.Accessibility && !done) {
                    dev.ilgax.utils.core.ui.components.A11ySetupNote()
                }
            }
        }
    }
}

@Composable
private fun SlotsGroup(slots: List<SlotUi?>, actions: RemoteActions) {
    var picking by remember { mutableStateOf<Int?>(null) }
    SettingsGroup(title = stringResource(R.string.remote_group_slots)) {
        slots.forEachIndexed { i, slot ->
            SettingsRow(
                label = stringResource(R.string.remote_slot_title, i + 1),
                leading = { if (slot != null) AppIcon(slot.packageName, slot.label) else EmptySlotIcon() },
                trailing = {
                    Row {
                        if (slot != null) {
                            TextButton(onClick = { actions.onClearSlot(i) }) { Text(stringResource(R.string.remote_btn_clear)) }
                        }
                        FilledTonalButton(onClick = { picking = i }) { Text(stringResource(R.string.remote_btn_choose)) }
                    }
                },
            ) {
                Text(
                    slot?.label ?: stringResource(R.string.remote_slot_unset),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (slot != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    picking?.let { slotIndex ->
        AppPickerSheet(
            slot = slotIndex,
            loadApps = actions.loadApps,
            onPick = { pkg -> picking = null; actions.onPickSlot(slotIndex, pkg) },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun EmptySlotIcon() {
    Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
}

/** The app's launcher icon; a letter badge if it cannot be loaded (also what screenshot tests show). */
@Composable
fun AppIcon(packageName: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }
    val icon = bitmap
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Surface(modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text(
                label.take(1).uppercase(),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
