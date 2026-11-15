package dev.ilgax.utils.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Content column width on big screens; keeps detail screens readable on a 12-inch tablet. */
val ContentMaxWidth = 640.dp

/**
 * Shared frame of every detail screen: scrolls under the system bars, centres a width-limited column
 * and hosts the snackbar. [contentPadding] carries the insets handed down by the shell.
 */
@Composable
fun DetailScaffold(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(contentPadding)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
        if (snackbarHostState != null) {
            SnackbarHost(
                snackbarHostState,
                Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}
