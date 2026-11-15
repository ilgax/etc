package dev.ilgax.utils.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Stateful wrapper: reads the real checks, wires actions to the service, shows the sheets. */
@Composable
internal fun RemoteDetail(contentPadding: PaddingValues, modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { RemotePrefs(context) }
    val executor = remember { CommandExecutor(context) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = { tick++ }
        RemoteState.addListener(listener)
        onDispose { RemoteState.removeListener(listener) }
    }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    // Countdown and settings changed outside the app (accessibility, battery) show up within a second.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1000)
                tick++
            }
        }
    }

    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    val state = remember(tick, shizuku) {
        RemoteUiState(
            service = RemoteState.service,
            serviceDetail = RemoteState.serviceDetail,
            checks = RemoteControl.checks(context),
            paired = RemoteControl.isPaired(context),
            pairing = RemoteState.pairing,
            slots = List(Protocol.APP_SLOTS) { i ->
                prefs.slot(i)?.let { pkg -> AppCatalog.label(context, pkg)?.let { SlotUi(it, pkg) } }
            },
            forceStop = shizuku == ShizukuStatus.Ready,
        )
    }

    val btPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val actions = remember(context) {
        RemoteActions(
            onListeningChange = { on ->
                if (!RemoteControl.setListening(context, on) && on && RemoteControl.hasBluetoothPermission(context)) {
                    Toast.makeText(context, R.string.remote_start_refused, Toast.LENGTH_LONG).show()
                }
                tick++
            },
            onSetup = { item ->
                when (item) {
                    SetupItem.BluetoothPermission ->
                        btPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                    SetupItem.Notifications -> btPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    SetupItem.BluetoothOn -> context.startActivitySafe(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    SetupItem.Accessibility -> context.startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    SetupItem.Battery -> context.startActivitySafe(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
            },
            onStartPairing = {
                if (RemoteControl.startPairing(context) == RemoteControl.PairStart.MissingPrerequisites) {
                    Toast.makeText(context, R.string.remote_pair_prereq, Toast.LENGTH_LONG).show()
                }
            },
            onCancelPairing = { RemoteService.instance?.cancelPairing() },
            onConfirmPairing = { RemoteService.instance?.confirmPairing() },
            onUnpair = { RemoteControl.unpair(context); tick++ },
            onClearSlot = { prefs.setSlot(it, null); tick++ },
            onPickSlot = { slot, pkg -> prefs.setSlot(slot, pkg); tick++ },
            runCommand = { command, args -> executor.execute(command, args) },
            loadApps = { withContext(Dispatchers.IO) { AppCatalog.launchableApps(context) } },
        )
    }

    // Success is a local, transient step: shown when the paired flag flips on while a session was running.
    var sawPairing by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    if (state.pairing != RemoteState.PairingUi.Off) sawPairing = true
    if (sawPairing && state.pairing == RemoteState.PairingUi.Off && state.paired) {
        sawPairing = false
        showSuccess = true
    } else if (sawPairing && state.pairing == RemoteState.PairingUi.Off) {
        sawPairing = false
    }

    RemoteScreen(state, actions, contentPadding, modifier)

    val secondsLeft = remember(tick, state.pairing) {
        when (val p = state.pairing) {
            RemoteState.PairingUi.Off -> 0
            is RemoteState.PairingUi.Waiting -> secondsLeft(p.deadline)
            is RemoteState.PairingUi.Code -> secondsLeft(p.deadline)
        }
    }
    val step = pairingStep(state.pairing, secondsLeft) ?: if (showSuccess) PairingStep.Success else null
    if (step != null) {
        PairingSheet(
            step = step,
            onConfirm = actions.onConfirmPairing,
            onCancel = actions.onCancelPairing,
            onClose = { showSuccess = false },
        )
    }
}

private fun secondsLeft(deadline: Long) = maxOf(0L, (deadline - System.currentTimeMillis()) / 1000).toInt()

private fun Context.startActivitySafe(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(this, R.string.remote_no_handler, Toast.LENGTH_LONG).show()
    }
}
