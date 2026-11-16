package dev.ilgax.utils.math

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.ui.components.copyToClipboard
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.normalize.PxRect
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Owns one Solve result after the lasso: recognition, solving, the card state and the floating window that
 * shows it. Process-wide, so all of it keeps running after the full-screen [SolveActivity] has finished (that
 * is the point: the PDF underneath is usable again while the card is up).
 *
 * Which window shows the card ([showWindow]):
 *  1. accessibility service connected: TYPE_ACCESSIBILITY_OVERLAY from that service (no permission, no
 *     foreground service, no notification);
 *  2. else "Display over other apps" granted: TYPE_APPLICATION_OVERLAY hosted by [SolveOverlayService];
 *  3. else not possible: the activity keeps showing the card inside itself (the old, blocking behaviour).
 * Main thread only, except where noted.
 */
class SolveController private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer = Recognizer.get(app)
    private val prefs = MathPrefs.get(app)
    private val corrections = CorrectionsStore(File(app.filesDir, "math/corrections"))

    val floating = FloatingState()

    private var host: FloatingCardWindow? = null
    private var hostService: SolveOverlayService? = null
    private var timings = SolveTimings()
    private var generation = 0

    private var sessionId = newSessionId()
    private var crop: Bitmap? = null
    private var cropPng: ByteArray? = null
    private var originalLatex = ""
    private var initialText = ""
    private var target: Char? = null
    /** Percentages the recognised text contained, for the "Write %20 as 20/100" step after the card text was normalised. */
    private var percents: List<dev.ilgax.utils.math.normalize.PercentUse> = emptyList()
    private var lastSavedText: String? = null
    private var recognizeJob: Job? = null
    private var solveJob: Job? = null

    /** The handlers every host (floating window or the activity fallback) wires to the card. */
    val actions = OverlayActions(
        onLatex = ::onLatex,
        onToggleSteps = { floating.card = floating.card?.let { it.copy(stepsOpen = !it.stepsOpen) } },
        onCopy = ::onCopy,
        onRetry = ::recircle,
        onClose = ::close,
        onMinimize = {
            floating.card = floating.card?.let { it.copy(minimized = !it.minimized, editing = false) }
            host?.setEditing(false)
            host?.requestPlacement()
        },
        onEditing = ::onEditing,
    )

    /**
     * A new Solve launch replaces whatever is showing. Runs in [SolveActivity.onCreate], well before the capture,
     * so a floating card (and a re-circle's own card) is gone from the screen before the screenshot is taken.
     */
    fun beginNewSolve(newTimings: SolveTimings) {
        close()
        timings = newTimings
        timings.onMark = { _ -> main.post(::refreshTimings) }
        sessionId = newSessionId()
    }

    /** Starts recognition of [cropped] (the circled area); the card appears at once as "Reading…". */
    fun start(cropped: Bitmap, anchor: PxRect) {
        crop = cropped
        cropPng = null
        floating.anchor = anchor
        recognize(cropped)
    }

    /** Shows the card in a floating window. False means no overlay is possible: keep the activity and show the card there. */
    fun showWindow(context: Context): Boolean {
        if (A11y.service.value != null) {
            return try {
                host = newHost(app, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.show() }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "accessibility overlay failed", t)
                host = null
                false
            }
        }
        if (!OverlayPermission.canDraw(app)) return false
        return try {
            SolveOverlayService.start(context)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the overlay service", t)
            false
        }
    }

    /** [SolveOverlayService] is up: put the application overlay window in it. False: nothing to show, the service stops. */
    fun attachToService(service: SolveOverlayService): Boolean {
        if (floating.card == null) return false
        hostService = service
        return try {
            host = newHost(service, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.show() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "application overlay failed", t)
            hostService = null
            host = null
            false
        }
    }

    fun onServiceGone(service: SolveOverlayService) {
        if (hostService === service) {
            host?.remove()
            host = null
            hostService = null
        }
    }

    private fun newHost(base: Context, type: Int) = FloatingCardWindow(base, type, floating, actions, onEditingEnded = { onEditing(false) })

    /** Removes the card and cancels its work. */
    fun close() {
        generation++
        recognizeJob?.cancel()
        solveJob?.cancel()
        host?.remove()
        host = null
        if (hostService != null) { hostService = null; SolveOverlayService.stop(app) }
        floating.card = null
        floating.anchor = null
        crop = null
        cropPng = null
        timings.onMark = null
    }

    /**
     * Re-circle: a fresh capture. The new activity removes this card in its onCreate, before the screenshot (the
     * card stays visible until then so the app still has a visible window when it starts the activity from here).
     */
    fun recircle() {
        val intent = Intent(app, SolveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SolveActivity.EXTRA_RECIRCLE, true)
        if (host == null) { close(); return } // fallback mode: the activity handles Re-circle itself
        try { app.startActivity(intent) } catch (t: Throwable) { Log.w(TAG, "re-circle could not start", t) }
    }

    private fun onEditing(on: Boolean) {
        val card = floating.card ?: return
        if (card.editing == on) return
        floating.card = card.copy(editing = on)
        host?.setEditing(on)
    }

    private fun recognize(bitmap: Bitmap) {
        recognizeJob?.cancel()
        solveJob?.cancel()
        lastSavedText = null
        floating.card = CardUi(reading = true)
        val t = timings
        recognizeJob = scope.launch {
            try {
                val rec = recognizer.recognize(bitmap, t)
                originalLatex = rec.latex
                val n = LatexNormalizer.normalize(rec.latex, prefs.dot.value)
                target = n.target
                percents = n.percents
                initialText = n.latex
                if (n.latex.isBlank()) {
                    floating.card = CardUi(reading = false, message = app.getString(R.string.math_card_no_text), timings = timingsText())
                } else {
                    // The recognised text is on screen now (plain text; KaTeX takes over when it has painted).
                    floating.card = CardUi(latex = n.latex, reading = false, timings = timingsText())
                    solve(n.latex, immediately = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t2: Throwable) {
                floating.card = CardUi(reading = false, message = app.getString(R.string.math_card_recognition_failed, t2.message ?: t2.javaClass.simpleName))
            }
        }
    }

    private fun onLatex(text: String) {
        floating.card = floating.card?.copy(latex = text, copied = false, message = null)
        solve(text, immediately = false)
    }

    /** Edits re-solve after a short pause; the first solve runs at once. */
    private fun solve(text: String, immediately: Boolean) {
        solveJob?.cancel()
        val t = timings
        solveJob = scope.launch {
            if (!immediately) delay(DEBOUNCE_MS)
            val result = withContext(Dispatchers.Default) { SolvePipeline.run(text, prefs.dot.value, target, prefs.angles.value, percents) }
            if (immediately) t.mark(Stage.SolveDone)
            floating.card = floating.card?.copy(result = result, timings = timingsText())
            if (!immediately) logCorrection(text, result)
        }
    }

    private fun timingsText(): String? = if (prefs.showTimings.value) timings.line() else null

    /** The KaTeX view marked a stage on another thread: refresh the line (and log the summary once the run is complete). */
    private fun refreshTimings() {
        val card = floating.card ?: return
        val line = timingsText()
        if (line != card.timings) floating.card = card.copy(timings = line)
        if (timings.has(Stage.KatexRendered)) timings.logSummary()
    }

    /** An edit that solves is a training example: crop + what the model said + what the user fixed it to. */
    private suspend fun logCorrection(text: String, result: SolveResult) {
        val trimmed = text.trim()
        if (!prefs.corrections.value || result !is SolveResult.Solved || trimmed == initialText.trim() || trimmed == lastSavedText) return
        val bmp = crop ?: return
        lastSavedText = trimmed
        val session = sessionId
        withContext(Dispatchers.IO) {
            val png = cropPng ?: ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { cropPng = it }
            corrections.save(session, png, originalLatex, trimmed, recognizer.selected()?.name ?: "unknown", System.currentTimeMillis())
        }
    }

    private fun onCopy() {
        val card = floating.card ?: return
        val answer = (card.result as? SolveResult.Solved)?.answers?.joinToString("\n") { it.plain }.orEmpty()
        copyToClipboard(app, "Equation", listOf(answer, card.latex).filter { it.isNotBlank() }.joinToString("\n"))
        floating.card = card.copy(copied = true)
    }

    companion object {
        private const val TAG = "SolveController"
        private const val DEBOUNCE_MS = 400L

        private fun newSessionId() = UUID.randomUUID().toString().replace("-", "").take(16)

        @Volatile private var instance: SolveController? = null
        fun get(context: Context): SolveController = instance ?: synchronized(this) { instance ?: SolveController(context).also { instance = it } }
    }
}
