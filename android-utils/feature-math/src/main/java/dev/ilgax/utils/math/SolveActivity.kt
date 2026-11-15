package dev.ilgax.utils.math

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.lifecycleScope
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.theme.UtilsTheme
import dev.ilgax.utils.math.normalize.PxRect
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The blocking phase of Solve, and nothing else. Fully transparent until the screenshot exists: wait
 * [SolveTiming.CAPTURE_DELAY_MS] (while the model session, the KaTeX WebView and the capture path warm up in
 * parallel), capture, show the frozen picture and take a lasso. As soon as the lasso is finished the result card
 * is handed to [SolveController], which shows it as a floating window, and this activity finishes so the app
 * underneath is live again. Only when no overlay window is possible does the card stay inside this activity.
 */
class SolveActivity : ComponentActivity() {
    private val state = OverlayState()
    private val controller by lazy { SolveController.get(this) }
    private val grabber by lazy { ScreenGrabber(this) }
    private val recognizer by lazy { Recognizer.get(this) }
    private val prefs by lazy { MathPrefs.get(this) }
    private val timings = SolveTimings()

    private var screenshot: Bitmap? = null
    private var softScreenshot: Deferred<Bitmap>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.isNavigationBarContrastEnforced = false

        SolveTimings.current = timings
        timings.mark(Stage.ActivityStart)
        timings.mark(Stage.ProcessStart, System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()))

        // Everything below starts now, in parallel, and overlaps the 300 ms Air Command close delay.
        controller.beginNewSolve(timings)          // removes a card from a previous Solve before any screenshot
        recognizer.hold(timings)                   // ONNX session + warm-up run, on a background thread
        grabber.prewarm()                          // Shizuku UserService bind (when that is the capture path)
        window.decorView.post { KatexPool.warm(this) } // WebView + KaTeX page, loaded offscreen
        val overlayReady = lifecycleScope.async(Dispatchers.IO) { overlayReady() }

        val delayMs = when {
            intent.getBooleanExtra(EXTRA_RECIRCLE, false) -> SolveTiming.CAPTURE_DELAY_RECIRCLE_MS
            intent.getBooleanExtra(EXTRA_FROM_TILE, false) -> SolveTiming.CAPTURE_DELAY_TILE_MS
            else -> SolveTiming.CAPTURE_DELAY_MS
        }
        lifecycleScope.launch {
            delay(delayMs)
            if (!overlayReady.await() && !prefs.overlayAsked.value) {
                state.overlayPrompt = true // once: explain, then send the user to the settings page
                show()
            } else {
                capture()
            }
        }
    }

    /** Can the card float? Accessibility overlay needs nothing; the application overlay needs the permission. */
    private suspend fun overlayReady(): Boolean =
        grabber.accessibilityConnected() || OverlayPermission.ensure(this)

    private suspend fun capture() {
        timings.mark(Stage.CaptureRequested)
        when (val result = grabber.grab()) {
            is GrabResult.Ok -> {
                timings.captureMethod = "${result.how}"
                timings.mark(Stage.CaptureDone)
                screenshot = result.bitmap
                state.screenshot = result.bitmap.asImageBitmap()
                timings.mark(Stage.BitmapReady)
                // Reading pixels needs a software bitmap; the copy happens while the user is still circling.
                softScreenshot = lifecycleScope.async { grabber.toSoftware(result.bitmap) }
                show()
            }
            is GrabResult.Failed -> {
                state.problem = CaptureProblem(result.detail, result.shizuku != ShizukuStatus.Ready)
                show()
            }
        }
    }

    /** Nothing is composed (so nothing is drawn) until there is a bitmap or a problem to show. */
    private fun show() {
        val actions = OverlayActions(
            onLasso = ::onLasso,
            onLatex = controller.actions.onLatex,
            onToggleSteps = controller.actions.onToggleSteps,
            onCopy = controller.actions.onCopy,
            onRetry = ::onRetry,
            onClose = { controller.close(); finish() },
            onMinimize = controller.actions.onMinimize,
            onEditing = {}, // inside the activity the keyboard works as usual
            onGrantOverlay = {
                prefs.setOverlayAsked(true)
                startActivity(OverlayPermission.settingsIntent(this))
                finish()
            },
            onSkipOverlay = {
                prefs.setOverlayAsked(true)
                state.overlayPrompt = false
                lifecycleScope.launch { capture() }
            },
            onOpenShizuku = { ShizukuState.openShizukuApp(this); finish() },
            onOpenAccessibility = {
                A11y.openSettings(this)
                finish()
            },
        )
        setContent { UtilsTheme { SolveOverlay(state, controller.floating, actions) } }
    }

    private fun onLasso(points: List<Offset>, container: IntSize) {
        val bmp = screenshot ?: return
        val soft = softScreenshot ?: return
        if (points.size < 8 || container.width == 0 || container.height == 0) return
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        if (maxX - minX < MIN_LASSO_PX || maxY - minY < MIN_LASSO_PX) { state.lasso = emptyList(); return }
        timings.mark(Stage.LassoDone)
        state.lasso = points
        // window px -> bitmap px, bounding box plus a small margin
        val sx = bmp.width.toFloat() / container.width
        val sy = bmp.height.toFloat() / container.height
        val margin = (max(maxX - minX, maxY - minY) * sx * 0.06f).coerceIn(10f, 48f)
        val l = (minX * sx - margin).toInt().coerceIn(0, bmp.width - 1)
        val t = (minY * sy - margin).toInt().coerceIn(0, bmp.height - 1)
        val r = (maxX * sx + margin).toInt().coerceIn(l + 1, bmp.width)
        val b = (maxY * sy + margin).toInt().coerceIn(t + 1, bmp.height)
        val origin = IntArray(2).also { window.decorView.getLocationOnScreen(it) }
        val anchor = PxRect(minX + origin[0], minY + origin[1], maxX + origin[0], maxY + origin[1])
        lifecycleScope.launch {
            val cropped = try {
                Bitmap.createBitmap(soft.await(), l, t, r - l, b - t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t2: Throwable) {
                state.screenshot = null
                state.problem = CaptureProblem(t2.message ?: t2.javaClass.simpleName, false)
                return@launch
            }
            controller.start(cropped, anchor)
            // Unfreeze: the card floats over the live app. Only if that is impossible does it stay in here.
            if (controller.showWindow(this@SolveActivity)) finish()
        }
    }

    /** Fallback mode only (the card is inside this activity): back to the lasso. */
    private fun onRetry() {
        controller.close()
        state.lasso = emptyList()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        recognizer.unhold()
        state.screenshot = null
        screenshot = null
        softScreenshot = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_TILE = "from_tile"
        const val EXTRA_RECIRCLE = "recircle"
        private const val MIN_LASSO_PX = 24f
    }
}
