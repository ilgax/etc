package dev.ilgax.utils.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ilgax.utils.R
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.UtilModule
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.components.ModuleCard
import dev.ilgax.utils.core.ui.components.ShizukuBanner
import dev.ilgax.utils.core.ui.components.UIcon

/** Window width from which the list and the detail sit side by side. */
private val TwoPaneBreakpoint = 840.dp
private val ListPaneWidth = 380.dp
private val SingleColumnMaxWidth = 640.dp

/**
 * The whole shell, free of any Android service so it can be rendered from fake modules.
 * [selectedId] null means "home" on narrow windows and "first module" on wide ones.
 */
@Composable
fun UtilsRoot(
    modules: List<UtilModule>,
    shizuku: ShizukuStatus,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onToggle: (UtilModule) -> Unit,
    modifier: Modifier = Modifier,
    onShizukuAction: (() -> Unit)? = null,
) {
    // Surface (not just a background) so text defaults to onBackground in both themes.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneBreakpoint) {
            TwoPane(modules, shizuku, selectedId ?: modules.firstOrNull()?.id, onSelect, onToggle, onShizukuAction)
        } else {
            SinglePane(modules, shizuku, selectedId, onSelect, onToggle, onShizukuAction)
        }
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SinglePane(
    modules: List<UtilModule>,
    shizuku: ShizukuStatus,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onToggle: (UtilModule) -> Unit,
    onShizukuAction: (() -> Unit)?,
) {
    val selected = modules.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selected != null) { onSelect(null) }
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "pane",
    ) { module ->
        if (module == null) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                HomeList(modules, shizuku, null, onSelect, onToggle, onShizukuAction, padding, Modifier)
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(module.title, style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = { onSelect(null) }) {
                                UIcon(CoreR.drawable.ic_arrow_back, stringResource(CoreR.string.back))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { padding -> module.Detail(padding, Modifier) }
        }
    }
}

@Composable
private fun TwoPane(
    modules: List<UtilModule>,
    shizuku: ShizukuStatus,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onToggle: (UtilModule) -> Unit,
    onShizukuAction: (() -> Unit)?,
) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val selected = modules.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxSize()) {
        HomeList(
            modules, shizuku, selectedId, { onSelect(it) }, onToggle, onShizukuAction,
            PaddingValues(start = insets.calculateStartPadding(dir), top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding()),
            Modifier.width(ListPaneWidth).fillMaxHeight(),
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (selected != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = insets.calculateTopPadding() + 24.dp, start = 24.dp, end = 24.dp + insets.calculateEndPadding(dir), bottom = 8.dp),
                ) {
                    Text(selected.title, style = MaterialTheme.typography.headlineMedium)
                    Text(selected.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.animation.Crossfade(selected, label = "detail") { module ->
                        module.Detail(
                            PaddingValues(end = insets.calculateEndPadding(dir), bottom = insets.calculateBottomPadding()),
                            Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeList(
    modules: List<UtilModule>,
    shizuku: ShizukuStatus,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onToggle: (UtilModule) -> Unit,
    onShizukuAction: (() -> Unit)?,
    padding: PaddingValues,
    modifier: Modifier,
) {
    val dir = LocalLayoutDirection.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = SingleColumnMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(dir),
                end = 16.dp + padding.calculateEndPadding(dir),
                top = 24.dp + padding.calculateTopPadding(),
                bottom = 24.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shizuku != ShizukuStatus.Ready) {
                item(key = "shizuku") { ShizukuBanner(shizuku, onAction = onShizukuAction) }
            }
            items(modules, key = { it.id }) { module ->
                val card by module.cardState.collectAsStateWithLifecycle()
                ModuleCard(
                    title = module.title,
                    status = card.status,
                    iconRes = module.iconRes,
                    mode = card.mode,
                    selected = module.id == selectedId,
                    onClick = { onSelect(module.id) },
                    onToggle = { onToggle(module) },
                )
            }
        }
    }
}
