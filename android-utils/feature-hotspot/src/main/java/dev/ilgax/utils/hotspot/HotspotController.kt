package dev.ilgax.utils.hotspot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * App-process side of the hotspot module. Binds the Shizuku UserService on demand, keeps it while a
 * screen is visible and drops it ~30 s after the last use. All binder calls run off the main thread and
 * every failure ends up in [state] (readable error + diagnostics) instead of being thrown.
 */
class HotspotController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex() // serialises binder calls and (un)binding

    private val _state = MutableStateFlow(HotspotUiState(shizuku = ShizukuState.current(app)))
    val state: StateFlow<HotspotUiState> = _state.asStateFlow()

    private val lock = Any()
    private var holders = 0
    private var pollJob: Job? = null
    private var idleJob: Job? = null

    @Volatile private var service: IHotspotService? = null
    private var connection: ServiceConnection? = null

    init {
        scope.launch {
            ShizukuState.status.collect { status ->
                _state.update { it.copy(shizuku = status) }
                if (status != ShizukuStatus.Ready) mutex.withLock { unbindLocked() }
            }
        }
    }

    /** A screen needing live data became visible: poll while at least one holder exists. */
    fun acquire() = synchronized(lock) {
        holders++
        idleJob?.cancel()
        if (pollJob?.isActive != true) pollJob = scope.launch { pollLoop() }
    }

    fun release() = synchronized(lock) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            pollJob?.cancel()
            _state.update { it.copy(passphrase = null) } // do not keep the password around while nobody looks
            scheduleIdleUnbind()
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            refresh()
            val transitional = _state.value.phase.let { it == HotspotPhase.TurningOn || it == HotspotPhase.TurningOff }
            delay(if (transitional) 1500 else 5000)
        }
    }

    /** One status read; password only while a screen holds the controller. */
    suspend fun refresh() {
        val withPassword = synchronized(lock) { holders > 0 }
        call(markBusy = false, withPassword = withPassword) { it.status(withPassword) }
    }

    suspend fun start() = scope.async { call(true, false) { it.start() } }.await()
    suspend fun stop() = scope.async { call(true, false) { it.stop() } }.await()

    /** Starts when off, stops when on or turning on. Returns the state afterwards. */
    suspend fun toggle(): HotspotUiState {
        scope.async {
            refresh()
            val phase = _state.value.phase
            if (phase == HotspotPhase.On || phase == HotspotPhase.TurningOn) stop() else start()
        }.await()
        return _state.value
    }

    private suspend fun call(markBusy: Boolean, withPassword: Boolean, op: (IHotspotService) -> HotspotStatus) {
        mutex.withLock {
            val shizuku = ShizukuState.current(app)
            _state.update { it.copy(shizuku = shizuku) }
            if (shizuku != ShizukuStatus.Ready) return
            if (markBusy) _state.update { it.copy(busy = true, error = null) }
            try {
                val status = withContext(Dispatchers.IO) { op(connect()) }
                _state.update { status.applyTo(it, keepPassword = withPassword) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "hotspot call failed", t)
                unbindLocked()
                _state.update {
                    it.copy(error = "${t.javaClass.simpleName}: ${t.message ?: "no message"}", diagnostics = controllerDiagnostics(t, it.diagnostics))
                }
            } finally {
                if (markBusy) _state.update { it.copy(busy = false) }
                if (synchronized(lock) { holders == 0 }) scheduleIdleUnbind()
            }
        }
    }

    private suspend fun connect(): IHotspotService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        unbindLocked()
        return withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            val s = IHotspotService.Stub.asInterface(binder)
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

    private fun serviceArgs() = Shizuku.UserServiceArgs(ComponentName(app.packageName, HotspotUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("hotspot")
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
            if (synchronized(lock) { holders == 0 }) mutex.withLock { unbindLocked() }
        }
    }

    private fun controllerDiagnostics(t: Throwable, previous: String): String {
        val frames = t.stackTrace.take(6).joinToString("\n") { "    at $it" }
        return "App-side error: $t\n$frames" + if (previous.isBlank()) "" else "\n\n--- last service log ---\n$previous"
    }

    companion object {
        private const val TAG = "HotspotController"
        private const val BIND_TIMEOUT_MS = 15_000L
        private const val IDLE_UNBIND_MS = 30_000L

        @Volatile private var instance: HotspotController? = null

        fun get(context: Context): HotspotController =
            instance ?: synchronized(this) { instance ?: HotspotController(context).also { instance = it } }
    }
}

/** Merges a service answer into the UI state. */
internal fun HotspotStatus.applyTo(previous: HotspotUiState, keepPassword: Boolean): HotspotUiState {
    val open = securityType == 0
    return previous.copy(
        apState = apState,
        ssid = ssid ?: previous.ssid,
        hidden = if (ssid != null) hidden else previous.hidden,
        securityType = if (securityType >= 0) securityType else previous.securityType,
        passphrase = when {
            open -> null
            passphrase != null -> passphrase
            keepPassword -> previous.passphrase
            else -> null
        },
        bands = if (bands != 0) bands else previous.bands,
        clientCount = clientCount,
        upstreamSsid = upstreamSsid,
        upstreamFrequency = upstreamFrequency,
        error = errorName,
        diagnostics = diagnostics,
    )
}
