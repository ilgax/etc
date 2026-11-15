package dev.ilgax.utils.math

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.lifecycleScope
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.components.copyToClipboard
import dev.ilgax.utils.core.ui.theme.UtilsTheme
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.normalize.PxRect
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * The Solve overlay. Fully transparent until the screenshot exists: wait [SolveTiming.CAPTURE_DELAY_MS],
 * capture, then show the frozen picture, take a lasso, recognise, solve and show the card.
 */
class SolveActivity : ComponentActivity() {
    private val state = OverlayState()
    private val grabber by lazy { ScreenGrabber(this) }
    private val recognizer by lazy { Recognizer.get(this) }
    private val prefs by lazy { MathPrefs.get(this) }
    private val corrections by lazy { CorrectionsStore(File(filesDir, "math/corrections")) }
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)

    private var screenshot: Bitmap? = null
    private var cropPng: ByteArray? = null
    private var crop: Bitmap? = null
    private var originalLatex = ""
    private var initialText = ""
    private var target: Char? = null
    private var recognizeJob: Job? = null
    private var solveJob: Job? = null
    private var lastSavedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.isNavigationBarContrastEnforced = false
        recognizer.hold()
        grabber.prewarm()
        val delayMs = if (intent.getBooleanExtra(EXTRA_FROM_TILE, false)) SolveTiming.CAPTURE_DELAY_TILE_MS else SolveTiming.CAPTURE_DELAY_MS
        lifecycleScope.launch {
            delay(delayMs)
            when (val result = grabber.grab()) {
                is GrabResult.Ok -> {
                    screenshot = result.bitmap
                    state.screenshot = result.bitmap.asImageBitmap()
                    show()
                }
                is GrabResult.Failed -> {
                    state.problem = CaptureProblem(result.detail, result.shizuku != ShizukuStatus.Ready)
                    show()
                }
            }
        }
    }

    /** Nothing is composed (so nothing is drawn) until there is a bitmap or a problem to show. */
    private fun show() {
        setContent {
            UtilsTheme {
                SolveOverlay(state, OverlayActions(
                    onLasso = ::onLasso,
                    onLatex = ::onLatex,
                    onToggleSteps = { state.card = state.card?.let { it.copy(stepsOpen = !it.stepsOpen) } },
                    onCopy = ::onCopy,
                    onRetry = ::onRetry,
                    onClose = { finish() },
                    onOpenShizuku = { ShizukuState.openShizukuApp(this); finish() },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                ))
            }
        }
    }

    private fun onLasso(points: List<Offset>, container: IntSize) {
        val bmp = screenshot ?: return
        if (points.size < 8 || container.width == 0 || container.height == 0) return
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        if (maxX - minX < MIN_LASSO_PX || maxY - minY < MIN_LASSO_PX) { state.lasso = emptyList(); return }
        state.lasso = points
        state.anchor = PxRect(minX, minY, maxX, maxY)
        // window px -> bitmap px, bounding box plus a small margin
        val sx = bmp.width.toFloat() / container.width
        val sy = bmp.height.toFloat() / container.height
        val margin = (max(maxX - minX, maxY - minY) * sx * 0.06f).coerceIn(10f, 48f)
        val l = (minX * sx - margin).toInt().coerceIn(0, bmp.width - 1)
        val t = (minY * sy - margin).toInt().coerceIn(0, bmp.height - 1)
        val r = (maxX * sx + margin).toInt().coerceIn(l + 1, bmp.width)
        val b = (maxY * sy + margin).toInt().coerceIn(t + 1, bmp.height)
        val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        crop = cropped
        cropPng = null
        recognize(cropped)
    }

    private fun recognize(bitmap: Bitmap) {
        recognizeJob?.cancel()
        solveJob?.cancel()
        lastSavedText = null
        state.card = CardUi(reading = true)
        recognizeJob = lifecycleScope.launch {
            try {
                val rec = recognizer.recognize(bitmap)
                originalLatex = rec.latex
                val n = LatexNormalizer.normalize(rec.latex, prefs.dot.value)
                target = n.target
                initialText = n.latex
                if (n.latex.isBlank()) {
                    state.card = CardUi(reading = false, message = getString(R.string.math_card_no_text))
                } else {
                    state.card = CardUi(latex = n.latex, reading = false)
                    solve(n.latex, immediately = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                state.card = CardUi(reading = false, message = getString(R.string.math_card_recognition_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun onLatex(text: String) {
        state.card = state.card?.copy(latex = text, copied = false, message = null)
        solve(text, immediately = false)
    }

    /** Edits re-solve after a short pause; the first solve runs at once. */
    private fun solve(text: String, immediately: Boolean) {
        solveJob?.cancel()
        solveJob = lifecycleScope.launch {
            if (!immediately) delay(DEBOUNCE_MS)
            val result = withContext(Dispatchers.Default) { SolvePipeline.run(text, prefs.dot.value, target) }
            state.card = state.card?.copy(result = result)
            if (!immediately) logCorrection(text, result)
        }
    }

    /** An edit that solves is a training example: crop + what the model said + what the user fixed it to. */
    private suspend fun logCorrection(text: String, result: SolveResult) {
        val trimmed = text.trim()
        if (!prefs.corrections.value || result !is SolveResult.Solved || trimmed == initialText.trim() || trimmed == lastSavedText) return
        val bmp = crop ?: return
        lastSavedText = trimmed
        withContext(Dispatchers.IO) {
            val png = cropPng ?: ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { cropPng = it }
            corrections.save(sessionId, png, originalLatex, trimmed, recognizer.selected()?.name ?: "unknown", System.currentTimeMillis())
        }
    }

    private fun onCopy() {
        val card = state.card ?: return
        val answer = (card.result as? SolveResult.Solved)?.answers?.joinToString("\n") { it.plain }.orEmpty()
        copyToClipboard(this, "Equation", listOf(answer, card.latex).filter { it.isNotBlank() }.joinToString("\n"))
        state.card = card.copy(copied = true)
    }

    private fun onRetry() {
        recognizeJob?.cancel()
        solveJob?.cancel()
        state.card = null
        state.lasso = emptyList()
        state.anchor = null
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        recognizer.unhold()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_TILE = "from_tile"
        private const val DEBOUNCE_MS = 400L
        private const val MIN_LASSO_PX = 24f
    }
}
