package dev.ilgax.utils.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.R
import dev.ilgax.utils.core.ui.theme.MonoStyle

/** Collapsible monospace log with a Copy button; the text never contains secrets (callers' contract). */
@Composable
fun DiagnosticsBlock(text: String, modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val context = LocalContext.current
    val label = stringResource(R.string.diagnostics)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand)) {
                        expanded = !expanded
                    }
                    .heightIn(min = 64.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { copyToClipboard(context, label, text) }) {
                    UIcon(R.drawable.ic_content_copy, stringResource(R.string.diagnostics_copy))
                }
                UIcon(R.drawable.ic_expand_more, null, Modifier.padding(end = 12.dp).rotate(rotation), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Surface(
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    SelectionContainer {
                        Text(
                            text.ifBlank { stringResource(R.string.diagnostics_empty) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
