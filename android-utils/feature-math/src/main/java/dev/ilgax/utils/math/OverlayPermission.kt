package dev.ilgax.utils.math

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * "Display over other apps" (SYSTEM_ALERT_WINDOW), needed for the TYPE_APPLICATION_OVERLAY result card when the
 * accessibility service is not available. Granted silently through the Shizuku UserService when possible;
 * otherwise the user is sent to the system settings page once.
 */
object OverlayPermission {
    private const val TAG = "OverlayPermission"

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** True when the app can show an application overlay now, after trying the silent Shizuku grant. */
    suspend fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        if (canDraw(app)) return true
        if (ShizukuState.current(app) != ShizukuStatus.Ready) return false
        try {
            ScreenshotClient.get(app).grantOverlayPermission()?.let { Log.w(TAG, "appops grant failed: $it") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "appops grant failed", t)
        }
        repeat(5) {
            if (canDraw(app)) return true
            delay(40)
        }
        return canDraw(app)
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
