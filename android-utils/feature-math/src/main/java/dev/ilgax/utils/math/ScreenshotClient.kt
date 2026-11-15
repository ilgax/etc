package dev.ilgax.utils.math

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * App-process side of the screenshot UserService: binds on demand, keeps the binding for [IDLE_UNBIND_MS]
 * after the last use and returns PNG bytes. Same lifecycle as the hotspot controller.
 */
class ScreenshotClient private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var idleJob: Job? = null

    @Volatile private var service: IScreenshotService? = null
    private var connection: ServiceConnection? = null

    /** Starts binding so the first capture does not pay for it. Failures are ignored here. */
    fun prewarm() {
        scope.launch { mutex.withLock { runCatching { connect() } } ; scheduleIdleUnbind() }
    }

    /** One `screencap -p` as PNG bytes. Throws on any failure (caller falls back to accessibility). */
    suspend fun capturePng(): ByteArray = mutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                val pfd: ParcelFileDescriptor = connect().screencap()
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "screencap failed", t)
            unbindLocked()
            throw t
        } finally {
            scheduleIdleUnbind()
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
        .daemon(false)
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

    private fun scheduleIdleUnbind() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNBIND_MS)
            mutex.withLock { unbindLocked() }
        }
    }

    companion object {
        private const val TAG = "ScreenshotClient"
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val IDLE_UNBIND_MS = 30_000L

        @Volatile private var instance: ScreenshotClient? = null

        fun get(context: Context): ScreenshotClient =
            instance ?: synchronized(this) { instance ?: ScreenshotClient(context).also { instance = it } }
    }
}
