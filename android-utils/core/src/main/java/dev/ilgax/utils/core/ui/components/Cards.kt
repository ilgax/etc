package dev.ilgax.utils.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.R
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** Error-toned card for things the user should not miss (open network, failures). */
@Composable
fun WarningCard(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            UIcon(R.drawable.ic_warning, null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Compact banner telling the user what Shizuku still needs. Draws nothing when [status] is Ready.
 * [onAction] defaults to the real install/open/grant action.
 */
@Composable
fun ShizukuBanner(status: ShizukuStatus, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    if (status == ShizukuStatus.Ready) return
    val context = LocalContext.current
    val (title, detail, action) = when (status) {
        ShizukuStatus.NotInstalled -> Triple(R.string.shizuku_not_installed, R.string.shizuku_not_installed_detail, R.string.shizuku_action_install)
        ShizukuStatus.NotRunning -> Triple(R.string.shizuku_not_running, R.string.shizuku_not_running_detail, R.string.shizuku_action_open)
        else -> Triple(R.string.shizuku_permission_needed, R.string.shizuku_permission_needed_detail, R.string.shizuku_action_grant)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                UIcon(R.drawable.ic_info, null, Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onAction?.invoke() ?: ShizukuState.performAction(context, status) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) { Text(stringResource(action)) }
            }
        }
    }
}
