package dev.ilgax.utils.core.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide access to the single shared accessibility service ("Utils"): the feature registry the service
 * dispatches to, the connected-service state, and the few things modules do with it (global actions,
 * screenshots, overlay windows). Dispatch is plain Kotlin ([dispatchConnected] etc.), so it is unit-testable.
 */
object A11y {
    private val features = CopyOnWriteArrayList<A11yFeature>()
    private val _service = MutableStateFlow<AccessibilityService?>(null)

    /** The connected service, or null while it is off. Collect this instead of polling settings. */
    val service: StateFlow<AccessibilityService?> = _service.asStateFlow()

    /** Adds [feature]; if the service is already connected it gets [A11yFeature.onConnected] right away. */
    fun register(feature: A11yFeature) {
        if (features.addIfAbsent(feature)) _service.value?.let { feature.onConnected(it) }
    }

    fun unregister(feature: A11yFeature) { features.remove(feature) }

    internal fun dispatchConnected(service: AccessibilityService) {
        _service.value = service
        features.forEach { it.onConnected(service) }
    }

    internal fun dispatchEvent(event: AccessibilityEvent) {
        features.forEach { it.onEvent(event) }
    }

    internal fun dispatchDisconnected(service: AccessibilityService) {
        if (_service.value !== service) return
        _service.value = null
        features.forEach { it.onDisconnected() }
    }

    /** Test hook: forget every feature and the service. */
    internal fun resetForTest() { features.clear(); _service.value = null }

    fun performGlobalAction(action: Int): Boolean = _service.value?.performGlobalAction(action) ?: false

    /** True when "Utils" is switched on in Settings (it may still be a moment from connecting). */
    fun isEnabled(context: Context): Boolean {
        val flat = ComponentName(context, UtilsAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    /** Off the main thread, so a capture callback is not queued behind UI work. */
    private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "a11y-capture").apply { isDaemon = true } }

    /**
     * Screenshot of the default display as a HARDWARE bitmap wrapping the compositor's buffer (no pixel copy;
     * copy to software only when the pixels must be read). Retries once on the system rate limit.
     */
    suspend fun takeScreenshot(): Bitmap {
        val service = _service.value ?: throw ScreenshotFailed(-1, "accessibility service is not connected")
        try {
            return screenshotOnce(service)
        } catch (e: ScreenshotFailed) {
            if (e.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) throw e
        }
        delay(1_100)
        return screenshotOnce(service)
    }

    private suspend fun screenshotOnce(service: AccessibilityService): Bitmap = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close() // the bitmap holds its own reference
                    if (hw != null) cont.resume(hw) else cont.resumeWithException(ScreenshotFailed(-1, "could not wrap the screenshot buffer"))
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

    /**
     * A visual context bound to the service's accessibility-overlay window token on the default display, or null
     * while the service is off. Windows of type TYPE_ACCESSIBILITY_OVERLAY added through its WindowManager need
     * no "Display over other apps" permission.
     */
    fun overlayContext(): Context? {
        val s = _service.value ?: return null
        val display = s.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return s.createDisplayContext(display)
    }

    /** Adds [view] as an accessibility overlay window. False when the service is off. */
    fun addOverlay(view: View, params: WindowManager.LayoutParams): Boolean {
        val wm = overlayContext()?.getSystemService(WindowManager::class.java) ?: return false
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        wm.addView(view, params)
        return true
    }

    fun removeOverlay(view: View) {
        try { overlayContext()?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) } catch (_: Throwable) {}
    }

    /** The one helper every module's setup row uses to send the user to the accessibility settings. */
    fun openSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    /**
     * This app's App info page: on Android 13+ a sideloaded app's accessibility toggle stays greyed out until
     * ⋮ -> "Allow restricted settings" has been used there.
     */
    fun openAppInfo(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {}
    }
}
