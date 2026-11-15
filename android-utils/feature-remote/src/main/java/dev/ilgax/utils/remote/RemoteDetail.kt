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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.utils.core.action.AndroidRequirementEnv
import dev.ilgax.utils.core.action.RequirementChecker
import dev.ilgax.utils.core.action.RequirementFix
import dev.ilgax.utils.remote.macros.MacroDraft
import dev.ilgax.utils.remote.macros.MacroEditor
import dev.ilgax.utils.remote.macros.MacroEditorActions
import dev.ilgax.utils.remote.macros.MacroEngine
import dev.ilgax.utils.remote.macros.MacroSummary
import dev.ilgax.utils.remote.macros.MacroUi
import dev.ilgax.utils.remote.macros.Origin
import dev.ilgax.utils.remote.macros.TriggerResult
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stateful wrapper: reads the real checks, wires actions to the service, shows the sheets. */
@Composable
internal fun RemoteDetail(contentPadding: PaddingValues, modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { RemotePrefs(context) }
    val executor = remember { CommandExecutor(context) }
    val updates = remember { UpdateHost.get(context) }
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
    val engine = remember { MacroEngine.get(context) }
    val macroData by engine.store.state.collectAsStateWithLifecycle()
    val runs by engine.log.runs.collectAsStateWithLifecycle()
    val running by engine.runner.running.collectAsStateWithLifecycle()
    val checker = remember { RequirementChecker(AndroidRequirementEnv(context)) }
    /** Working copy and the version it started from, while the macro editor is open. */
    var editing by remember { mutableStateOf<Pair<MacroDraft, MacroDraft>?>(null) }
    val state = remember(tick, shizuku, macroData, runs, running) {
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
            watchApp = updates.bundled()?.let {
                WatchAppUi(
                    it.versionName, it.versionCode, updates.watchVersion,
                    UpdateHost.stateOf(updates.snapshot(), System.currentTimeMillis()),
                )
            },
            macros = macroData.macros.map { MacroUi(it.id, it.name, it.steps.size, MacroSummary.of(context, engine.registry, it)) },
            runs = runs,
            runningMacro = running,
        )
    }

    val scope = rememberCoroutineScope()
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
                    SetupItem.Accessibility -> dev.ilgax.utils.core.a11y.A11y.openSettings(context)
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
            onUpdateWatch = {
                // The offer is only fetched over BLE, so the listener must be running.
                if (!RemoteService.isRunning()) RemoteControl.setListening(context, true)
                scope.launch {
                    val result = withContext(Dispatchers.IO) { updates.start() }
                    result.messageRes()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    tick++
                }
            },
            onCancelUpdate = { updates.stop(); tick++ },
            onAddMacro = { MacroDraft.blank().let { editing = it to it } },
            onEditMacro = { id -> engine.store.get(id)?.let { m -> MacroDraft.of(m).let { editing = it to it } } },
            onRunMacro = { id ->
                val macro = engine.store.get(id)
                if (macro != null) {
                    // A "close current app" step would otherwise close this app: give time to switch away.
                    val delayMs = if (macro.steps.any { it.actionId == "remote.close_app" }) 3_000L else 0L
                    val message = when (engine.runner.trigger(macro, Origin.Local, delayMs)) {
                        TriggerResult.Started -> if (delayMs > 0) R.string.macro_started_delayed else R.string.macro_started
                        TriggerResult.Busy -> R.string.macro_busy
                        is TriggerResult.Invalid -> R.string.macro_invalid
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
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

    val edit = editing
    if (edit != null) {
        val editorActions = remember(context, engine) {
            MacroEditorActions(
                onChange = { d -> editing = editing?.let { d to it.second } },
                onSave = {
                    editing?.let { (d, _) ->
                        try {
                            engine.store.save(d.toMacro())
                            editing = null
                        } catch (e: IllegalStateException) {
                            Toast.makeText(context, R.string.macro_store_readonly, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onCancel = { editing = null },
                onDuplicate = {
                    editing?.let { (d, _) ->
                        try {
                            val saved = engine.store.save(d.toMacro())
                            engine.store.duplicate(saved.id)
                            editing = null
                        } catch (e: IllegalStateException) {
                            Toast.makeText(context, R.string.macro_store_readonly, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDelete = {
                    editing?.let { (d, _) -> runCatching { engine.store.delete(d.id) } }
                    editing = null
                },
                onFix = { kind -> RequirementFix.open(context, kind) },
                loadApps = actions.loadApps,
            )
        }
        MacroEditor(
            draft = edit.first, original = edit.second, registry = engine.registry, checker = checker,
            slotLabels = List(Protocol.APP_SLOTS) { AppCatalog.label(context, prefs.slot(it)) },
            actions = editorActions, contentPadding = contentPadding, modifier = modifier, refreshKey = tick,
        )
    } else {
        RemoteScreen(state, actions, contentPadding, modifier)
    }

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

private fun UpdateHost.StartResult.messageRes(): Int? = when (this) {
    UpdateHost.StartResult.STARTED -> null
    UpdateHost.StartResult.NO_APK -> R.string.remote_app_no_apk
    UpdateHost.StartResult.NO_ADDRESS -> R.string.remote_app_no_address
    UpdateHost.StartResult.FAILED -> R.string.remote_app_start_failed
}

private fun secondsLeft(deadline: Long) = maxOf(0L, (deadline - System.currentTimeMillis()) / 1000).toInt()

private fun Context.startActivitySafe(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(this, R.string.remote_no_handler, Toast.LENGTH_LONG).show()
    }
}
