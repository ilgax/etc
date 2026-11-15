@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.ilgax.utils.remote.macros

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.action.ActionGroup
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.CoreActions
import dev.ilgax.utils.core.action.ParamSpec
import dev.ilgax.utils.core.action.ParamValues
import dev.ilgax.utils.core.action.Requirement
import dev.ilgax.utils.core.action.RequirementChecker
import dev.ilgax.utils.core.action.RequirementKind
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.remote.AppCatalog
import dev.ilgax.utils.remote.AppIcon
import dev.ilgax.utils.remote.AppPickerSheet
import dev.ilgax.utils.remote.R

/** Everything the editor needs besides the draft; plain callbacks so it can be rendered from fixtures. */
class MacroEditorActions(
    val onChange: (MacroDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onFix: (RequirementKind) -> Unit = {},
    val loadApps: suspend () -> List<AppCatalog.App> = { emptyList() },
)

@Composable
fun MacroEditor(
    draft: MacroDraft,
    original: MacroDraft,
    registry: ActionRegistry,
    checker: RequirementChecker,
    /** Labels of the four app slots (null = empty). */
    slotLabels: List<String?>,
    actions: MacroEditorActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Re-evaluates the requirement chips after returning from a settings screen. */
    refreshKey: Int = 0,
    startWithPicker: Boolean = false,
) {
    var adding by remember { mutableStateOf(startWithPicker) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = draft != original
    BackHandler { if (dirty) confirmDiscard = true else actions.onCancel() }

    val macro = draft.toMacro()
    val issues = MacroRules.validate(macro, registry)
    val totalWait = MacroRules.totalWaitMs(macro, registry)
    val waitTooLong = totalWait > MacroRules.MAX_TOTAL_WAIT_MS
    val canSave = issues.none { it !is MacroIssue.NoSteps }

    DetailScaffold(contentPadding, modifier, snackbar) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) confirmDiscard = true else actions.onCancel() }) {
                UIcon(CoreR.drawable.ic_close, stringResource(R.string.remote_btn_cancel))
            }
            Text(
                stringResource(if (draft.id == 0) R.string.macro_editor_new else R.string.macro_editor_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = actions.onSave, enabled = canSave) { Text(stringResource(R.string.macro_btn_save)) }
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { actions.onChange(draft.copy(name = it.take(MacroRules.MAX_NAME * 2))) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.macro_name)) },
            supportingText = { Text("${draft.name.codePointCount(0, draft.name.length)}/${MacroRules.MAX_NAME}") },
            isError = draft.name.isNotEmpty() && issues.any { it == MacroIssue.NameTooLong },
            shape = MaterialTheme.shapes.large,
        )

        Surface(
            Modifier.fillMaxWidth().clickable { actions.onChange(draft.copy(stopOnFailure = !draft.stopOnFailure)) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.macro_stop_on_failure), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (draft.stopOnFailure) R.string.macro_stop_on_failure_on else R.string.macro_stop_on_failure_off),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.stopOnFailure, onCheckedChange = null)
            }
        }

        SettingsGroup(title = stringResource(R.string.macro_steps_title)) {
            if (draft.steps.isEmpty()) {
                Text(
                    stringResource(R.string.macro_steps_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            draft.steps.forEachIndexed { i, step ->
                val action = registry.find(step.actionId)
                StepCard(
                    index = i, count = draft.steps.size, step = step, action = action, checker = checker,
                    slotLabels = slotLabels, refreshKey = refreshKey, actions = actions,
                    onStep = { updated -> actions.onChange(draft.copy(steps = draft.steps.map { if (it.uid == step.uid) updated else it })) },
                    onMove = { delta ->
                        val j = i + delta
                        if (j in draft.steps.indices) {
                            val list = draft.steps.toMutableList()
                            list[i] = list[j].also { list[j] = list[i] }
                            actions.onChange(draft.copy(steps = list))
                        }
                    },
                    onDuplicate = {
                        val list = draft.steps.toMutableList()
                        list.add(i + 1, step.copy(uid = MacroDraft.nextUid()))
                        actions.onChange(draft.copy(steps = list))
                    },
                    onDelete = { actions.onChange(draft.copy(steps = draft.steps.filter { it.uid != step.uid })) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.macro_wait_total, CoreActions.formatSeconds(totalWait.toInt()), (MacroRules.MAX_TOTAL_WAIT_MS / 1000).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (waitTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { adding = true }) {
                    UIcon(CoreR.drawable.ic_add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.macro_add_step), Modifier.padding(start = 8.dp))
                }
            }
        }

        if (draft.id != 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = actions.onDuplicate) { Text(stringResource(R.string.macro_btn_duplicate)) }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.macro_btn_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (adding) {
        AddStepSheet(
            groups = registry.grouped(),
            onPick = { action ->
                adding = false
                val params = action.params.associate { it.key to it.default }
                actions.onChange(draft.copy(steps = draft.steps + StepDraft(MacroDraft.nextUid(), action.id, params)))
            },
            onDismiss = { adding = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.macro_delete_title)) },
            text = { Text(stringResource(R.string.macro_delete_confirm, draft.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text(stringResource(R.string.macro_btn_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.remote_btn_cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.macro_discard_title)) },
            text = { Text(stringResource(R.string.macro_discard_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; actions.onCancel() }) { Text(stringResource(R.string.macro_btn_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.macro_btn_keep_editing)) } },
        )
    }
}

@Composable
private fun StepCard(
    index: Int, count: Int, step: StepDraft, action: ActionType?, checker: RequirementChecker,
    slotLabels: List<String?>, refreshKey: Int, actions: MacroEditorActions,
    onStep: (StepDraft) -> Unit, onMove: (Int) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLowest) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIcon(action?.iconRes ?: CoreR.drawable.ic_warning, null, tint = if (action == null) colors.error else colors.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(action?.label?.resolve(LocalContext.current) ?: stringResource(R.string.macro_unknown_step, step.actionId), style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) { UIcon(CoreR.drawable.ic_arrow_up, stringResource(R.string.macro_move_up)) }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { UIcon(CoreR.drawable.ic_expand_more, stringResource(R.string.macro_move_down)) }
                IconButton(onClick = onDuplicate) { UIcon(CoreR.drawable.ic_content_copy, stringResource(R.string.macro_btn_duplicate)) }
                IconButton(onClick = onDelete) { UIcon(CoreR.drawable.ic_delete, stringResource(R.string.macro_btn_delete)) }
            }
            if (action != null) {
                val values = ParamValues.of(action.params, step.params)
                for (spec in action.params) {
                    ParamEditor(spec, step.params[spec.key] ?: spec.default, slotLabels, actions.loadApps) { v ->
                        onStep(step.copy(params = step.params + (spec.key to v)))
                    }
                }
                val missing = remember(refreshKey, step, action) { checker.missing(action, values) }
                for (req in missing) RequirementRow(req, actions.onFix)
                for (w in action.warnings(values)) WarningLine(w.resolve(LocalContext.current))
            }
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UIcon(CoreR.drawable.ic_warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Needs Shizuku" / "Needs permission: Modify system settings or Shizuku", with a button to the screen that fixes it. */
@Composable
private fun RequirementRow(req: Requirement, onFix: (RequirementKind) -> Unit) {
    val text = requirementText(req)
    Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f, fill = false)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UIcon(CoreR.drawable.ic_warning, null, Modifier.size(16.dp))
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(onClick = { onFix(req.anyOf.first()) }) { Text(stringResource(R.string.macro_btn_grant)) }
    }
}

@Composable
fun requirementText(req: Requirement): String {
    val parts = req.anyOf.map { kind ->
        when (kind) {
            RequirementKind.Shizuku -> stringResource(CoreR.string.req_shizuku)
            RequirementKind.Accessibility -> stringResource(CoreR.string.req_accessibility)
            RequirementKind.WriteSettings -> stringResource(R.string.macro_req_permission, stringResource(CoreR.string.req_write_settings))
            RequirementKind.NotificationPolicy -> stringResource(R.string.macro_req_permission, stringResource(CoreR.string.req_notification_policy))
            RequirementKind.Bluetooth -> stringResource(CoreR.string.req_bluetooth)
        }
    }
    return stringResource(R.string.macro_needs, parts.joinToString(stringResource(R.string.macro_or)))
}

@Composable
private fun ParamEditor(
    spec: ParamSpec, value: String, slotLabels: List<String?>, loadApps: suspend () -> List<AppCatalog.App>,
    onValue: (String) -> Unit,
) {
    val context = LocalContext.current
    val label = spec.label.resolve(context)
    Column(Modifier.padding(end = 10.dp)) {
        when (spec) {
            is ParamSpec.Percent -> SliderParam(label, "${value.toIntOrNull() ?: spec.initial}%", (value.toIntOrNull() ?: spec.initial).toFloat(), 0f..100f, 0) { onValue(it.toInt().toString()) }
            is ParamSpec.IntRange -> SliderParam(label, value, (value.toIntOrNull() ?: spec.initial).toFloat(), spec.min.toFloat()..spec.max.toFloat(), 0) { onValue(it.toInt().toString()) }
            is ParamSpec.Seconds -> {
                val ms = value.toIntOrNull() ?: spec.initialMs
                SliderParam(
                    label, "${CoreActions.formatSeconds(ms)} s", ms.toFloat(), spec.minMs.toFloat()..spec.maxMs.toFloat(),
                    0,
                ) { onValue((Math.round(it / spec.stepMs) * spec.stepMs).coerceIn(spec.minMs, spec.maxMs).toString()) }
            }
            is ParamSpec.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = value == "true", onCheckedChange = { onValue(it.toString()) })
            }
            is ParamSpec.Choice -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in spec.options) {
                        FilterChip(selected = value == o.value, onClick = { onValue(o.value) }, label = { Text(o.label.resolve(context)) })
                    }
                }
            }
            is ParamSpec.AppSlot -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until spec.slots) {
                        val name = slotLabels.getOrNull(i)
                        FilterChip(
                            selected = value == i.toString(), onClick = { onValue(i.toString()) },
                            label = { Text(if (name == null) "${i + 1}" else "${i + 1} · $name") },
                        )
                    }
                }
            }
            is ParamSpec.AppPackage -> {
                var picking by remember { mutableStateOf(false) }
                val appLabel = remember(value) { AppCatalog.label(context, value.ifEmpty { null }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (appLabel != null) AppIcon(value, appLabel, Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            appLabel ?: if (value.isEmpty()) stringResource(R.string.macro_choose_app_hint) else value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (appLabel == null && value.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    FilledTonalButton(onClick = { picking = true }) { Text(stringResource(R.string.remote_btn_choose)) }
                }
                if (picking) {
                    AppPickerSheet(
                        slot = 0, title = stringResource(R.string.macro_choose_app_title), loadApps = loadApps,
                        onPick = { picking = false; onValue(it) }, onDismiss = { picking = false },
                    )
                }
            }
            is ParamSpec.Text -> OutlinedTextField(
                value = value, onValueChange = { onValue(it.take(spec.maxLength)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
                placeholder = { if (spec.url) Text("https://") },
                isError = value.isNotEmpty() && spec.error(value) != null,
                keyboardOptions = KeyboardOptions(keyboardType = if (spec.url) KeyboardType.Uri else KeyboardType.Text),
                shape = MaterialTheme.shapes.large,
            )
        }
    }
}

@Composable
private fun SliderParam(label: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shown, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range, steps = steps.coerceAtLeast(0))
    }
}

/** Bottom sheet around [AddStepPanel]. */
@Composable
fun AddStepSheet(groups: List<Pair<ActionGroup, List<ActionType>>>, onPick: (ActionType) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AddStepPanel(groups, onPick, Modifier.fillMaxHeight(0.9f).navigationBarsPadding())
    }
}

/** Every step, grouped by category with its icon. */
@Composable
fun AddStepPanel(groups: List<Pair<ActionGroup, List<ActionType>>>, onPick: (ActionType) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.macro_add_step_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            for ((group, actions) in groups) {
                item(key = "h-${group.name}") {
                    Text(
                        stringResource(group.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(actions.size, key = { actions[it].id }) { i ->
                    val action = actions[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(action) }.heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        UIcon(action.iconRes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(action.label.resolve(context), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
