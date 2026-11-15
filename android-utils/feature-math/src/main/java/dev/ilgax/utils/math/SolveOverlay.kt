package dev.ilgax.utils.math

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.ui.components.UIcon
import dev.ilgax.utils.math.normalize.CardPlacement
import dev.ilgax.utils.math.normalize.PxRect
import dev.ilgax.utils.math.solver.SolveResult

/** What the result card shows. [result] is null while reading or when there is nothing to solve. */
data class CardUi(
    val latex: String = "",
    val reading: Boolean = true,
    /** Recognition problem shown instead of a result. */
    val message: String? = null,
    val result: SolveResult? = null,
    val stepsOpen: Boolean = false,
    val copied: Boolean = false,
)

/** Why the screenshot failed; drives the explanation screen. */
data class CaptureProblem(val detail: String?, val shizukuNeedsAttention: Boolean)

class OverlayState {
    var screenshot by mutableStateOf<ImageBitmap?>(null)
    var lasso by mutableStateOf<List<Offset>>(emptyList())
    var anchor by mutableStateOf<PxRect?>(null)
    var card by mutableStateOf<CardUi?>(null)
    var problem by mutableStateOf<CaptureProblem?>(null)
}

class OverlayActions(
    val onLasso: (points: List<Offset>, container: IntSize) -> Unit = { _, _ -> },
    val onLatex: (String) -> Unit = {},
    val onToggleSteps: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onOpenShizuku: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
)

/** The frozen screenshot, lasso drawing and result card; also the explanation screen when capture fails. */
@Composable
fun SolveOverlay(state: OverlayState, actions: OverlayActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        val shot = state.screenshot
        val problem = state.problem
        if (shot != null) {
            Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.14f))) // light dim
            LassoLayer(state.lasso, actions.onLasso, Modifier.fillMaxSize())
            if (state.card == null && state.lasso.isEmpty()) HintPill(Modifier.align(Alignment.TopCenter))
            val card = state.card
            val anchor = state.anchor
            if (card != null && anchor != null) PlacedCard(anchor) { ResultCard(card, actions) }
        } else if (problem != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CaptureProblemCard(problem, actions)
            }
        }
    }
}

@Composable
private fun HintPill(modifier: Modifier) {
    Surface(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Text(stringResource(R.string.math_overlay_hint), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** Freehand lasso with the pen or a finger. A new stroke replaces the old one. */
@Composable
private fun LassoLayer(points: List<Offset>, onLasso: (List<Offset>, IntSize) -> Unit, modifier: Modifier) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shown = if (live.isNotEmpty()) live else points
    val primary = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { 3.dp.toPx() }
    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
                    val pts = ArrayList<Offset>().apply { add(down.position) }
                    live = pts.toList()
                    val id = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        pts.add(change.position)
                        live = pts.toList()
                    }
                    live = emptyList()
                    onLasso(pts, size)
                }
            },
    ) {
        size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        if (shown.size > 1) {
            val path = Path().apply {
                moveTo(shown[0].x, shown[0].y)
                for (i in 1 until shown.size) lineTo(shown[i].x, shown[i].y)
                close()
            }
            drawPath(path, primary.copy(alpha = 0.10f))
            drawPath(path, Color.White, style = Stroke(stroke * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, primary, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Puts [content] beside [anchor] on the side with room and keeps it on screen (and above the keyboard). */
@Composable
private fun PlacedCard(anchor: PxRect, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.toPx() }
    val gap = with(density) { 14.dp.toPx() }
    val maxCardWidth = with(density) { 420.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize().imePadding().windowInsetsPadding(WindowInsets.safeDrawing)) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val cardWidth = minOf(maxCardWidth, w - 2 * margin.toInt()).coerceAtLeast(0)
        val placeable = measurables.first().measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth, maxHeight = (h - 2 * margin).toInt().coerceAtLeast(0)))
        val p = CardPlacement.place(anchor, w.toFloat(), h.toFloat(), placeable.width.toFloat(), placeable.height.toFloat(), margin, gap)
        layout(w, h) { placeable.place(p.x.toInt(), p.y.toInt()) }
    }
}

@Composable
fun ResultCard(card: CardUi, actions: OverlayActions, modifier: Modifier = Modifier) {
    val renderer = LocalKatexRenderer.current
    Surface(
        modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 12.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.math_card_title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = actions.onClose) { UIcon(CoreR.drawable.ic_close, stringResource(R.string.math_card_close)) }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (card.reading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.math_card_reading), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val solved = card.result as? SolveResult.Solved
                    renderer(
                        KatexContent(
                            preview = card.latex.takeIf { it.isNotBlank() },
                            answers = solved?.answers?.map { it.latex }.orEmpty(),
                            notes = solved?.notes.orEmpty(),
                            steps = if (card.stepsOpen) solved?.steps.orEmpty() else emptyList(),
                        ),
                        Modifier,
                    )
                    val failed = card.result as? SolveResult.Failed
                    if (card.message != null) {
                        Text(card.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else if (failed != null) {
                        Column {
                            Text(stringResource(R.string.math_card_cant_solve), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.math_card_cant_solve_reason, failed.reason), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        value = card.latex,
                        onValueChange = actions.onLatex,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.math_card_latex)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                }
                CardButtons(card, actions)
            }
        }
    }
}

@Composable
private fun CardButtons(card: CardUi, actions: OverlayActions) {
    val solved = card.result as? SolveResult.Solved
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (solved != null && solved.steps.isNotEmpty()) {
            val rotation by animateFloatAsState(if (card.stepsOpen) 180f else 0f, label = "steps")
            TextButton(onClick = actions.onToggleSteps) {
                Text(stringResource(R.string.math_card_steps))
                UIcon(CoreR.drawable.ic_expand_more, null, Modifier.rotate(rotation))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = actions.onRetry) {
            UIcon(CoreR.drawable.ic_refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.math_card_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = actions.onCopy, enabled = !card.reading) {
            UIcon(if (card.copied) CoreR.drawable.ic_check else CoreR.drawable.ic_content_copy, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(if (card.copied) R.string.math_card_copied else R.string.math_card_copy))
        }
    }
}

@Composable
private fun CaptureProblemCard(problem: CaptureProblem, actions: OverlayActions, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(24.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                UIcon(CoreR.drawable.ic_warning, null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.math_fail_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.math_fail_body), style = MaterialTheme.typography.bodyMedium)
            if (problem.detail != null) {
                Text(
                    stringResource(R.string.math_fail_detail, problem.detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = actions.onOpenShizuku, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.math_fail_open_shizuku)) }
            OutlinedButton(onClick = actions.onOpenAccessibility, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.math_fail_open_a11y)) }
            TextButton(onClick = actions.onClose, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.math_fail_close)) }
        }
    }
}
