package dev.ilgax.utils.core.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * What a module wants from the one shared [UtilsAccessibilityService]. Registered from the module's init with
 * [A11y.register]; all callbacks run on the main thread. Modules never reference each other: each registers its
 * own feature and keeps working if the others are absent.
 */
interface A11yFeature {
    fun onConnected(service: AccessibilityService) {}
    fun onEvent(event: AccessibilityEvent) {}
    fun onDisconnected() {}
}
