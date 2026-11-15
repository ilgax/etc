package dev.ilgax.utils.math

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-process side of the screenshot UserService. The service is a Shizuku *daemon* (its process survives
 * between uses), bound eagerly when the Utils process starts ([prewarm]) and never unbound after a capture;
 * it is only dropped when a call fails, so the next call starts a fresh one.
 */
class ScreenshotClient private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile private var service: IScreenshotService? = null
    private var connection: ServiceConnection? = null

    /** Starts binding so the first capture does not pay for it. Failures are ignored here. */
    fun prewarm() {
        scope.launch { mutex.withLock { runCatching { connect() } } }
    }

    /**
     * One raw `screencap` as a software bitmap, or null when the output is not a raw capture this parser
     * understands (the caller then uses [capturePng]). Throws on a service failure.
     */
    suspend fun captureRaw(): Bitmap? = call("screencapRaw") {
        withContext(Dispatchers.IO) {
            val pfd: ParcelFileDescriptor = connect().screencapRaw()
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { RawScreencap.readBitmap(it) }
        }
    }

    /** One `screencap -p` as PNG bytes. Throws on any failure. */
    suspend fun capturePng(): ByteArray = call("screencap -p") {
        withContext(Dispatchers.IO) {
            val pfd: ParcelFileDescriptor = connect().screencap()
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    /** `appops set <package> SYSTEM_ALERT_WINDOW allow` as the shell user. Null on success, else the reason. */
    suspend fun grantOverlayPermission(): String? = call("appops") {
        withContext(Dispatchers.IO) { connect().grantOverlayPermission(app.packageName).ifEmpty { null } }
    }

    private suspend fun <T> call(what: String, block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
            unbindLocked()
            throw t
        }
    }

    private suspend fun connect(): IScreenshotService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        unbindLocked()
        return withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            val s = IScreenshotService.Stub.asInterface(binder)
                            service = s
                            if (cont.isActive) cont.resume(s)
                        } else if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("UserService binder is not alive"))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                connection = conn
                try {
                    Shizuku.bindUserService(serviceArgs(), conn)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }
    }

    private fun serviceArgs() = Shizuku.UserServiceArgs(ComponentName(app.packageName, ScreenshotUserService::class.java.name))
        .daemon(true)
        .processNameSuffix("screenshot")
        .debuggable(BuildConfig.DEBUG)
        .version(appVersionCode())

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int = try {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
    } catch (t: Throwable) {
        1
    }

    private fun unbindLocked() {
        val conn = connection
        connection = null
        service = null
        if (conn != null) {
            try {
                Shizuku.unbindUserService(serviceArgs(), conn, true)
            } catch (t: Throwable) {
                Log.w(TAG, "unbindUserService failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotClient"
        private const val BIND_TIMEOUT_MS = 10_000L

        @Volatile private var instance: ScreenshotClient? = null

        fun get(context: Context): ScreenshotClient =
            instance ?: synchronized(this) { instance ?: ScreenshotClient(context).also { instance = it } }
    }
}
