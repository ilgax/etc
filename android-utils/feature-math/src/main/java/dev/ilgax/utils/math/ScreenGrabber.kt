package dev.ilgax.utils.math

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Wait before capturing so the S Pen Air Command panel (or the shade) has finished closing. */
object SolveTiming {
    const val CAPTURE_DELAY_MS = 300L

    /** The quick settings shade needs longer to collapse than the Air Command panel. */
    const val CAPTURE_DELAY_TILE_MS = 600L

    /** Re-circle: only our own floating card has to disappear. */
    const val CAPTURE_DELAY_RECIRCLE_MS = 150L
}

enum class CaptureMethod { Shizuku, Accessibility, None }

/** Fastest first: the accessibility service's takeScreenshot (no process spawn), else Shizuku's screencap. */
fun chooseMethod(shizukuReady: Boolean, accessibilityReady: Boolean): CaptureMethod = when {
    accessibilityReady -> CaptureMethod.Accessibility
    shizukuReady -> CaptureMethod.Shizuku
    else -> CaptureMethod.None
}

sealed interface GrabResult {
    /** [bitmap] may be a HARDWARE bitmap (accessibility path): fine to draw, use [ScreenGrabber.toSoftware] to read pixels. */
    data class Ok(val bitmap: Bitmap, val method: CaptureMethod, val how: String) : GrabResult
    data class Failed(val shizuku: ShizukuStatus, val accessibilityEnabled: Boolean, val detail: String?) : GrabResult
}

/** Takes the screenshot with the fastest available method and falls back when it fails. */
class ScreenGrabber(context: Context) {
    private val app = context.applicationContext

    fun shizukuStatus(): ShizukuStatus = ShizukuState.current(app)

    fun accessibilityEnabledInSettings(): Boolean = A11y.isEnabled(app)

    fun accessibilityConnected() = A11y.service.value != null

    fun method(): CaptureMethod = chooseMethod(shizukuStatus() == ShizukuStatus.Ready, accessibilityConnected())

    /** Binds the Shizuku service early when it is the capture path (the capture itself waits for the Air Command delay). */
    fun prewarm() {
        if (method() == CaptureMethod.Shizuku) ScreenshotClient.get(app).prewarm()
    }

    suspend fun grab(): GrabResult {
        val shizuku = shizukuStatus()
        var detail: String? = null

        // 1. Accessibility takeScreenshot: no process spawn, the result wraps the compositor's buffer.
        var connected = accessibilityConnected()
        var triedAccessibility = false
        suspend fun viaAccessibility(): GrabResult.Ok? {
            triedAccessibility = true
            try {
                return GrabResult.Ok(A11y.takeScreenshot(), CaptureMethod.Accessibility, "takeScreenshot")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                detail = listOfNotNull(detail, "Accessibility: ${t.message ?: t.javaClass.simpleName}").joinToString("; ")
                Log.w(TAG, "Accessibility capture failed", t)
            }
            return null
        }
        if (connected) viaAccessibility()?.let { return it }

        // 2. Shizuku: raw screencap, then PNG if the raw output cannot be parsed.
        if (shizuku == ShizukuStatus.Ready) {
            val client = ScreenshotClient.get(app)
            try {
                client.captureRaw()?.let { return GrabResult.Ok(it, CaptureMethod.Shizuku, "screencap raw") }
                Log.w(TAG, "raw screencap output not understood, falling back to PNG")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                detail = listOfNotNull(detail, "Shizuku: ${t.message ?: t.javaClass.simpleName}").joinToString("; ")
                Log.w(TAG, "Shizuku raw capture failed", t)
            }
            try {
                val png = client.capturePng()
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
                if (bmp != null) return GrabResult.Ok(bmp, CaptureMethod.Shizuku, "screencap -p")
                detail = listOfNotNull(detail, "screencap returned an unreadable PNG").joinToString("; ")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                detail = listOfNotNull(detail, "Shizuku: ${t.message ?: t.javaClass.simpleName}").joinToString("; ")
                Log.w(TAG, "Shizuku PNG capture failed", t)
            }
        }

        // 3. The service is enabled but not connected yet (right after our process started).
        if (!triedAccessibility) {
            connected = awaitAccessibility()
            if (connected) viaAccessibility()?.let { return it }
        }
        return GrabResult.Failed(shizuku, accessibilityEnabledInSettings(), detail)
    }

    /** The pixels of [bitmap] in a software bitmap: the same object unless it wraps a hardware buffer. */
    suspend fun toSoftware(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (bitmap.config != Bitmap.Config.HARDWARE) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("could not read the screenshot buffer")
    }

    /** The service may still be reconnecting right after our process started. */
    private suspend fun awaitAccessibility(): Boolean {
        if (accessibilityConnected()) return true
        if (!accessibilityEnabledInSettings()) return false
        repeat(10) {
            delay(100)
            if (accessibilityConnected()) return true
        }
        return false
    }

    private companion object {
        const val TAG = "ScreenGrabber"
    }
}
