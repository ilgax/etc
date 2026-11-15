package dev.ilgax.utils.core.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus(
    /** Name used in diagnostics text, kept identical to the original Speaker Kill Switch output. */
    val diagName: String,
) {
    NotInstalled("NOT_INSTALLED"),
    NotRunning("NOT_RUNNING"),
    PermissionNeeded("NEEDS_PERMISSION"),
    Ready("READY"),
}

internal fun resolveShizukuStatus(binderAlive: Boolean, permissionGranted: Boolean, installed: Boolean) = when {
    binderAlive && permissionGranted -> ShizukuStatus.Ready
    binderAlive -> ShizukuStatus.PermissionNeeded
    installed -> ShizukuStatus.NotRunning
    else -> ShizukuStatus.NotInstalled
}

/** Process-wide view of Shizuku, shared by every module. Call [init] once from Application.onCreate. */
object ShizukuState {
    private const val TAG = "ShizukuState"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    private const val REQUEST_CODE = 1001

    private val _status = MutableStateFlow(ShizukuStatus.NotRunning)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    /** Live, blocking-safe check (cheap binder ping); also what the flow is derived from. */
    fun current(context: Context): ShizukuStatus {
        init(context)
        return try {
            val alive = Shizuku.pingBinder()
            val granted = alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val installed = alive || isInstalled(context)
            resolveShizukuStatus(alive, granted, installed)
        } catch (t: Throwable) {
            ShizukuStatus.NotRunning
        }
    }

    fun refresh() {
        val ctx = appContext ?: return
        _status.value = current(ctx)
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** Opens the Shizuku app; if it is not installed, its download page. Returns false if neither could open. */
    fun openShizukuApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** The single action a user can take for [status] (install / open / grant), or nothing when ready. */
    fun performAction(context: Context, status: ShizukuStatus) {
        when (status) {
            ShizukuStatus.PermissionNeeded -> requestPermission()
            ShizukuStatus.NotInstalled, ShizukuStatus.NotRunning -> openShizukuApp(context)
            ShizukuStatus.Ready -> Unit
        }
    }

    private fun isInstalled(context: Context) = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
