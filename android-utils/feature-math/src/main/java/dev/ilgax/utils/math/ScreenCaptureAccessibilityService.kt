package dev.ilgax.utils.math

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fallback screenshot source ("Utils – screen capture for Solve"). Does nothing on its own; it only exists
 * so [takeScreenshot] is allowed. It listens to no events.
 */
class ScreenCaptureAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) instance = null
        _connected.value = false
    }

    /** Screenshot of the default display as a software ARGB_8888 bitmap. Retries once on the system rate limit. */
    suspend fun screenshot(): Bitmap {
        try {
            return once()
        } catch (e: ScreenshotFailed) {
            if (e.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) throw e
        }
        delay(1_100)
        return once()
    }

    private suspend fun once(): Bitmap = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (soft != null) cont.resume(soft) else cont.resumeWithException(ScreenshotFailed(-1, "could not read the screenshot buffer"))
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }

            override fun onFailure(errorCode: Int) {
                cont.resumeWithException(ScreenshotFailed(errorCode, "takeScreenshot failed with code $errorCode"))
            }
        })
    }

    class ScreenshotFailed(val code: Int, message: String) : Exception(message)

    companion object {
        @Volatile
        var instance: ScreenCaptureAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
