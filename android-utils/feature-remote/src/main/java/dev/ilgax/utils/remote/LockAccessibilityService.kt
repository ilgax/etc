package dev.ilgax.utils.remote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing on its own. Used on request to lock the screen, go home, read the foreground
 * package name and start apps (an accessibility service is allowed to start activities from the background).
 */
class LockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    companion object {
        @Volatile
        var instance: LockAccessibilityService? = null
            private set
    }
}
