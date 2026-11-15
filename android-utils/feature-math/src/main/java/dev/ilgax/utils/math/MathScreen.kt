package dev.ilgax.utils.math

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.InfoPill
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.SwitchRow
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.core.ui.components.WarningCard
import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.recognition.ManifestException
import dev.ilgax.utils.math.solver.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ModelRow(val id: String, val name: String, val bundled: Boolean, val sizeMb: String, val selected: Boolean)

data class MathUiState(
    val shizuku: ShizukuStatus,
    val accessibilityOn: Boolean,
    val models: List<ModelRow>,
    val dot: DotMeaning,
    val corrections: Boolean,
    val correctionCount: Int,
    val importError: String? = null,
    val tryText: String = "",
) {
    val method get() = chooseMethod(shizuku == ShizukuStatus.Ready, accessibilityOn)
}

class MathActions(
    val onSolveNow: () -> Unit = {},
    val onShizukuAction: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onDeleteModel: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onDot: (DotMeaning) -> Unit = {},
    val onCorrections: (Boolean) -> Unit = {},
    val onExport: () -> Unit = {},
    val onDeleteCorrections: () -> Unit = {},
    val onTryText: (String) -> Unit = {},
)

/** Stateful wrapper: reads settings and system state, runs the file pickers. */
@Composable
internal fun MathDetail(contentPadding: PaddingValues, modifier: Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { MathPrefs.get(app) }
    val recognizer = remember { Recognizer.get(app) }
    val grabber = remember { ScreenGrabber(app) }
    val store = remember { CorrectionsStore(File(app.filesDir, "math/corrections")) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    val connected by ScreenCaptureAccessibilityService.connected.collectAsStateWithLifecycle()
    val dot by prefs.dot.collectAsStateWithLifecycle()
    val corrections by prefs.corrections.collectAsStateWithLifecycle()
    val selected by prefs.selectedModel.collectAsStateWithLifecycle()
    val models by recognizer.models.collectAsStateWithLifecycle()
    var count by remember { mutableIntStateOf(store.count()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var tryText by rememberSaveable { mutableStateOf("") }
    var settingsEnabled by remember { mutableStateOf(grabber.accessibilityEnabledInSettings()) }

    LifecycleStartEffect(Unit) {
        ShizukuState.refresh()
        settingsEnabled = grabber.accessibilityEnabledInSettings()
        count = store.count()
        scope.launch(Dispatchers.IO) { recognizer.refreshModels() }
        onStopOrDispose {}
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importError = null
            try {
                val m = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { recognizer.import(it) } }
                snackbar.showSnackbar(context.getString(R.string.math_model_imported_ok, m.name))
            } catch (e: ManifestException) {
                importError = e.message
            } catch (t: Throwable) {
                importError = t.message ?: t.javaClass.simpleName
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { store.exportZip(it) } }
            snackbar.showSnackbar(context.getString(R.string.math_corrections_exported, n))
        }
    }

    val state = MathUiState(
        shizuku = shizuku,
        accessibilityOn = connected || (settingsEnabled && grabber.accessibilityConnected()),
        models = models.map { ModelRow(it.id, it.name, it.bundled, "%.0f".format(it.sizeBytes / 1_048_576.0), it.id == (models.firstOrNull { m -> m.id == selected }?.id ?: models.firstOrNull()?.id)) },
        dot = dot,
        corrections = corrections,
        correctionCount = count,
        importError = importError,
        tryText = tryText,
    )
    MathScreen(
        state = state,
        actions = MathActions(
            onSolveNow = { context.startActivity(MathModule.launchIntent(context)) },
            onShizukuAction = { ShizukuState.performAction(context, shizuku) },
            onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onSelectModel = { recognizer.select(it) },
            onDeleteModel = { id -> scope.launch { recognizer.delete(id) } },
            onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onDot = prefs::setDot,
            onCorrections = prefs::setCorrections,
            onExport = { exportLauncher.launch("solve-corrections.zip") },
            onDeleteCorrections = { store.deleteAll(); count = 0 },
            onTryText = { tryText = it },
        ),
        contentPadding = contentPadding,
        modifier = modifier,
        snackbar = snackbar,
    )
}

@Composable
fun MathScreen(
    state: MathUiState,
    actions: MathActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
) {
    DetailScaffold(contentPadding, modifier, snackbar) {
        StatusCard(state, actions)
        SetupGroup(state, actions)
        ModelGroup(state, actions)
        DotGroup(state, actions)
        CorrectionsGroup(state, actions)
        TryGroup(state, actions)
        Text(
            stringResource(R.string.math_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun StatusCard(state: MathUiState, actions: MathActions) {
    val ready = state.method != CaptureMethod.None
    val tone = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val on = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = tone, contentColor = on) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (ready) R.string.math_headline_ready else R.string.math_headline_not_ready), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(
                        when (state.method) {
                            CaptureMethod.Shizuku -> R.string.math_supporting_shizuku
                            CaptureMethod.Accessibility -> R.string.math_supporting_a11y
                            CaptureMethod.None -> R.string.math_supporting_none
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(onClick = actions.onSolveNow, enabled = ready) { Text(stringResource(R.string.math_solve_now)) }
        }
    }
}

@Composable
private fun SetupGroup(state: MathUiState, actions: MathActions) {
    SettingsGroup(title = stringResource(R.string.math_group_setup)) {
        SettingsRow(
            label = stringResource(R.string.math_capture_active),
            value = stringResource(
                when (state.method) {
                    CaptureMethod.Shizuku -> R.string.math_method_shizuku
                    CaptureMethod.Accessibility -> R.string.math_method_accessibility
                    CaptureMethod.None -> R.string.math_method_none
                },
            ),
            iconRes = R.drawable.ic_math,
            trailing = if (state.method != CaptureMethod.None) ({ InfoPill(stringResource(R.string.state_ready_pill)) }) else null,
        )
        SettingsRow(
            label = stringResource(R.string.math_setup_shizuku),
            value = stringResource(
                when (state.shizuku) {
                    ShizukuStatus.Ready -> R.string.math_setup_shizuku_ready
                    ShizukuStatus.PermissionNeeded -> R.string.math_setup_shizuku_permission
                    ShizukuStatus.NotRunning -> R.string.math_setup_shizuku_not_running
                    ShizukuStatus.NotInstalled -> R.string.math_setup_shizuku_not_installed
                },
            ),
            iconRes = CoreR.drawable.ic_shield,
            trailing = if (state.shizuku != ShizukuStatus.Ready) ({
                FilledTonalButton(onClick = actions.onShizukuAction) {
                    Text(stringResource(if (state.shizuku == ShizukuStatus.PermissionNeeded) R.string.math_action_shizuku_grant else R.string.math_action_shizuku_open))
                }
            }) else null,
        )
        SettingsRow(
            label = stringResource(R.string.math_setup_a11y),
            value = stringResource(if (state.accessibilityOn) R.string.math_setup_a11y_on else R.string.math_setup_a11y_off),
            iconRes = CoreR.drawable.ic_visibility,
            trailing = if (!state.accessibilityOn) ({
                FilledTonalButton(onClick = actions.onOpenAccessibility) { Text(stringResource(R.string.math_action_a11y)) }
            }) else null,
        )
        SettingsRow(
            label = stringResource(R.string.math_setup_air_command),
            value = stringResource(R.string.math_setup_air_command_steps),
            iconRes = CoreR.drawable.ic_info,
        )
    }
}

@Composable
private fun ModelGroup(state: MathUiState, actions: MathActions) {
    SettingsGroup(title = stringResource(R.string.math_group_model)) {
        for (m in state.models) {
            SettingsRow(
                label = stringResource(if (m.bundled) R.string.math_model_bundled else R.string.math_model_imported),
                onClick = { actions.onSelectModel(m.id) },
                leading = { RadioButton(selected = m.selected, onClick = null) },
                trailing = {
                    if (!m.bundled) {
                        IconButton(onClick = { actions.onDeleteModel(m.id) }) {
                            UIcon(CoreR.drawable.ic_close, stringResource(R.string.math_model_delete))
                        }
                    } else {
                        Text(stringResource(R.string.math_model_size, m.sizeMb), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) { Text(m.name, style = MaterialTheme.typography.bodyLarge) }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onImport) { Text(stringResource(R.string.math_model_import)) }
            Text(stringResource(R.string.math_model_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.importError?.let { WarningCard(stringResource(R.string.math_model_import_error), it) }
}

@Composable
private fun DotGroup(state: MathUiState, actions: MathActions) {
    SettingsGroup(title = stringResource(R.string.math_group_dot)) {
        for ((meaning, title, hint) in listOf(
            Triple(DotMeaning.Multiply, R.string.math_dot_multiply, R.string.math_dot_multiply_hint),
            Triple(DotMeaning.Decimal, R.string.math_dot_decimal, R.string.math_dot_decimal_hint),
        )) {
            SettingsRow(
                label = stringResource(title),
                onClick = { actions.onDot(meaning) },
                leading = { RadioButton(selected = state.dot == meaning, onClick = null) },
            ) { Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CorrectionsGroup(state: MathUiState, actions: MathActions) {
    SettingsGroup(title = stringResource(R.string.math_group_corrections)) {
        SwitchRow(
            title = stringResource(R.string.math_corrections_save),
            supporting = stringResource(R.string.math_corrections_save_hint),
            checked = state.corrections,
            onCheckedChange = actions.onCorrections,
        )
        SettingsRow(
            label = pluralStringResource(R.plurals.math_corrections_count, state.correctionCount, state.correctionCount),
            modifier = Modifier,
        ) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.onExport, enabled = state.correctionCount > 0) { Text(stringResource(R.string.math_corrections_export)) }
                TextButton(onClick = actions.onDeleteCorrections, enabled = state.correctionCount > 0) { Text(stringResource(R.string.math_corrections_delete)) }
            }
        }
    }
}

@Composable
private fun TryGroup(state: MathUiState, actions: MathActions) {
    val renderer = LocalKatexRenderer.current
    SettingsGroup(title = stringResource(R.string.math_group_try)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.tryText,
                onValueChange = actions.onTryText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.math_try_label)) },
                placeholder = { Text(stringResource(R.string.math_try_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 1,
                maxLines = 4,
            )
            val result = remember(state.tryText, state.dot) { if (state.tryText.isBlank()) null else SolvePipeline.run(state.tryText, state.dot) }
            if (result != null) {
                val solved = result as? SolveResult.Solved
                renderer(
                    KatexContent(
                        preview = null,
                        answers = solved?.answers?.map { it.latex }.orEmpty(),
                        notes = solved?.notes.orEmpty(),
                        steps = solved?.steps.orEmpty(),
                    ),
                    Modifier,
                )
                if (result is SolveResult.Failed) {
                    Text(stringResource(R.string.math_card_cant_solve), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.math_card_cant_solve_reason, result.reason), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
