package dev.ilgax.utils.remote.macros

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.remote.R
import java.text.DateFormat
import java.util.Date

/** A macro in the list. [summary] is the short "Pause · Close app · Lock" line. */
data class MacroUi(val id: Int, val name: String, val stepCount: Int, val summary: String)

/** The "Macros" section of the watch remote screen: the list with Run, Add, and the run history. */
@Composable
fun MacroSection(
    macros: List<MacroUi>,
    runs: List<RunRecord>,
    running: RunningMacro?,
    onRun: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.macro_group)) {
        Text(
            stringResource(R.string.macro_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        if (macros.isEmpty()) {
            Text(
                stringResource(R.string.macro_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        for (m in macros) {
            val isRunning = running?.macroId == m.id
            SettingsRow(
                label = context.resources.getQuantityString(R.plurals.macro_step_count, m.stepCount, m.stepCount),
                iconRes = CoreR.drawable.ic_play_arrow,
                onClick = { onEdit(m.id) },
                trailing = {
                    FilledTonalButton(onClick = { onRun(m.id) }, enabled = m.stepCount > 0 && running == null) {
                        Text(stringResource(R.string.macro_btn_run))
                    }
                },
            ) {
                Text(m.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (isRunning) stringResource(R.string.macro_running, running!!.step + 1, running.total)
                    else m.summary.ifEmpty { stringResource(R.string.macro_no_steps) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onAdd) {
                UIcon(CoreR.drawable.ic_add, null, Modifier.size(18.dp))
                Text(stringResource(R.string.macro_btn_add), Modifier.padding(start = 8.dp))
            }
        }
    }
    if (runs.isNotEmpty()) RunHistory(runs)
}

/** Collapsed to one line; expands to the last runs, each of which expands to its per-step results. */
@Composable
fun RunHistory(runs: List<RunRecord>) {
    var open by remember { mutableStateOf(false) }
    var openRun by remember { mutableStateOf<Long?>(null) }
    val colors = MaterialTheme.colorScheme
    SettingsGroup {
        SettingsRow(
            label = stringResource(R.string.macro_history_title),
            iconRes = CoreR.drawable.ic_history,
            onClick = { open = !open },
            trailing = { UIcon(if (open) CoreR.drawable.ic_arrow_up else CoreR.drawable.ic_expand_more, null) },
        ) {
            Text(stringResource(R.string.macro_history_count, runs.size), style = MaterialTheme.typography.bodyLarge)
        }
        if (open) {
            for (r in runs) {
                Surface(
                    Modifier.fillMaxWidth().clickable { openRun = if (openRun == r.startedAt) null else r.startedAt },
                    shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLowest,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UIcon(if (r.ok) CoreR.drawable.ic_check else CoreR.drawable.ic_warning, null, tint = if (r.ok) colors.primary else colors.error)
                            Column(Modifier.weight(1f)) {
                                Text(r.macroName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(r.startedAt)) +
                                        " · " + stringResource(if (r.origin == Origin.Watch) R.string.macro_origin_watch else R.string.macro_origin_local),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (r.ok) stringResource(R.string.macro_run_ok) else stringResource(R.string.macro_run_failed_at, r.failedStep),
                                style = MaterialTheme.typography.labelLarge, color = if (r.ok) colors.primary else colors.error,
                            )
                        }
                        if (openRun == r.startedAt) {
                            for (s in r.steps) {
                                Text(
                                    "${s.index + 1}. ${s.summary} · " + when (s.status) {
                                        StepStatus.Ok -> stringResource(R.string.macro_step_ok)
                                        StepStatus.Failed -> stringResource(R.string.macro_step_failed, s.message ?: "")
                                        StepStatus.Skipped -> stringResource(R.string.macro_step_skipped, s.message ?: "")
                                        StepStatus.NotRun -> stringResource(R.string.macro_step_not_run)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.status == StepStatus.Failed) colors.error else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
