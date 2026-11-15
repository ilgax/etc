package dev.ilgax.utils.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.ilgax.utils.core.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of one shell command run through Shizuku. */
class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs allow-listed system commands as the shell user through a Shizuku UserService (bound on first use, kept until a
 * call fails). Used by the macro steps that change settings, radios or Do Not Disturb.
 */
class ShizukuShell private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    @Volatile private var service: IShellService? = null
    private var connection: ServiceConnection? = null

    /** Null when Shizuku is not ready or the service failed; [ShellResult] otherwise (a non-zero exit is still a result). */
    suspend fun run(vararg argv: String): ShellResult? {
        if (ShizukuState.current(app) != ShizukuStatus.Ready) return null
        return mutex.withLock {
            try {
                val raw = withContext(Dispatchers.IO) { connect().run(arrayOf(*argv), TIMEOUT_S) }
                parse(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "${argv.firstOrNull()} failed", t)
                unbindLocked()
                null
            }
        }
    }

    private suspend fun connect(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        unbindLocked()
        return withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            val s = IShellService.Stub.asInterface(binder)
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

    private fun serviceArgs() = Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
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
        private const val TAG = "ShizukuShell"
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val TIMEOUT_S = 10

        @Volatile private var instance: ShizukuShell? = null

        fun get(context: Context): ShizukuShell =
            instance ?: synchronized(this) { instance ?: ShizukuShell(context).also { instance = it } }

        /** "exit\noutput" from the service. */
        internal fun parse(raw: String): ShellResult {
            val nl = raw.indexOf('\n')
            val code = (if (nl < 0) raw else raw.substring(0, nl)).trim().toIntOrNull() ?: -1
            return ShellResult(code, if (nl < 0) "" else raw.substring(nl + 1))
        }
    }
}
