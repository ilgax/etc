package dev.ilgax.utils.remote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.a11y.A11yFeature

/**
 * The remote's use of the shared "Utils" accessibility service: the lock screen / home global actions,
 * starting apps from the service context, and tracking which app is in front (for KILL_APP / SWITCH_APP).
 */
object RemoteA11y : A11yFeature {
    @Volatile private var lastPackage: String? = null

    /** The connected service, or null: the shared state behind every "accessibility disabled" answer. */
    fun service(): AccessibilityService? = A11y.service.value

    override fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onDisconnected() { lastPackage = null }

    fun lockScreen(): Boolean = A11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    fun goHome(): Boolean = A11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** The active window's package, else the last window-state change the service saw. */
    fun foregroundPackage(): String? =
        service()?.rootInActiveWindow?.packageName?.toString() ?: lastPackage
}
