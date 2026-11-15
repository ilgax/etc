package dev.ilgax.utils.core.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The one accessibility service of the Utils app. It does nothing on its own: it forwards its lifecycle and
 * window-change events to the [A11yFeature]s the modules registered, and its capabilities (window content,
 * screenshots, overlay windows) are what they use.
 */
class UtilsAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        A11y.dispatchConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) A11y.dispatchEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        A11y.dispatchDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11y.dispatchDisconnected(this)
        super.onDestroy()
    }
}
