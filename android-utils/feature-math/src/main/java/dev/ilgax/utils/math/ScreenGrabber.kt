package dev.ilgax.utils.math

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
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
}

enum class CaptureMethod { Shizuku, Accessibility, None }

/** Primary: Shizuku when Ready; fallback: the accessibility service. */
fun chooseMethod(shizukuReady: Boolean, accessibilityReady: Boolean): CaptureMethod = when {
    shizukuReady -> CaptureMethod.Shizuku
    accessibilityReady -> CaptureMethod.Accessibility
    else -> CaptureMethod.None
}

sealed interface GrabResult {
    data class Ok(val bitmap: Bitmap, val method: CaptureMethod) : GrabResult
    data class Failed(val shizuku: ShizukuStatus, val accessibilityEnabled: Boolean, val detail: String?) : GrabResult
}

/** Takes the screenshot with the best available method and falls back when it fails. */
class ScreenGrabber(context: Context) {
    private val app = context.applicationContext

    fun shizukuStatus(): ShizukuStatus = ShizukuState.current(app)

    fun accessibilityEnabledInSettings(): Boolean {
        val flat = ComponentName(app, ScreenCaptureAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    fun accessibilityConnected() = ScreenCaptureAccessibilityService.instance != null

    fun method(): CaptureMethod = chooseMethod(shizukuStatus() == ShizukuStatus.Ready, accessibilityConnected())

    /** Starts the Shizuku bind early (the capture itself waits for [SolveTiming.CAPTURE_DELAY_MS]). */
    fun prewarm() {
        if (shizukuStatus() == ShizukuStatus.Ready) ScreenshotClient.get(app).prewarm()
    }

    suspend fun grab(): GrabResult {
        val shizuku = shizukuStatus()
        var detail: String? = null
        if (shizuku == ShizukuStatus.Ready) {
            try {
                val png = ScreenshotClient.get(app).capturePng()
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
                if (bmp != null) return GrabResult.Ok(bmp, CaptureMethod.Shizuku)
                detail = "screencap returned an unreadable PNG"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                detail = "Shizuku: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, "Shizuku capture failed, trying accessibility", t)
            }
        }
        val service = awaitAccessibility()
        if (service != null) {
            try {
                return GrabResult.Ok(service.screenshot(), CaptureMethod.Accessibility)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                detail = listOfNotNull(detail, "Accessibility: ${t.message ?: t.javaClass.simpleName}").joinToString("; ")
                Log.w(TAG, "Accessibility capture failed", t)
            }
        }
        return GrabResult.Failed(shizuku, accessibilityEnabledInSettings(), detail)
    }

    /** The service may still be reconnecting right after our process started. */
    private suspend fun awaitAccessibility(): ScreenCaptureAccessibilityService? {
        ScreenCaptureAccessibilityService.instance?.let { return it }
        if (!accessibilityEnabledInSettings()) return null
        repeat(10) {
            delay(100)
            ScreenCaptureAccessibilityService.instance?.let { return it }
        }
        return null
    }

    private companion object {
        const val TAG = "ScreenGrabber"
    }
}
