package dev.ilgax.utils.math

import android.accessibilityservice.AccessibilityService
import android.content.Context
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.a11y.A11yFeature

/**
 * Solve's use of the shared "Utils" accessibility service: [A11y.takeScreenshot] (see [ScreenGrabber]) and the
 * accessibility overlay window of the result card. When the service goes away its window goes with it, so the
 * card state is dropped too.
 */
class MathA11y(private val context: Context) : A11yFeature {
    override fun onConnected(service: AccessibilityService) = Unit

    override fun onDisconnected() {
        SolveController.get(context).close()
    }
}
