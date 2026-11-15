package dev.ilgax.utils.remote

import android.annotation.SuppressLint
import android.content.Context
import dev.ilgax.tabremote.protocol.UpdateOffer
import dev.ilgax.tabremote.protocol.UpdateReport
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

/** Progress of a watch app update as the detail screen shows it. */
sealed interface WatchUpdateState {
    data object Idle : WatchUpdateState
    /** Server is up and the offer is available; the watch has not started downloading. */
    data class Waiting(val secondsLeft: Int) : WatchUpdateState
    data class Downloading(val percent: Int) : WatchUpdateState
    /** Fully downloaded (or the watch says so); the watch installs, possibly asking for a tap. */
    data object Installing : WatchUpdateState
    data class Failed(val failure: UpdateReport.Failure) : WatchUpdateState
    data object TimedOut : WatchUpdateState
}

/** Plain data behind [WatchUpdateState]. */
class UpdateSnapshot(
    val ended: UpdateServer.Reason?,
    val bytesServed: Long,
    val total: Long,
    val deadline: Long,
    val report: UpdateReport?,
)

/**
 * Tablet side of the watch app updater: the bundled APK, the one-shot HTTP server, the current offer and what the
 * watch last told us. The offer is only ever handed out through the authenticated GET_OFFER/OFFER exchange.
 */
class UpdateHost private constructor(context: Context) {
    private val context = context.applicationContext
    private val prefs = RemotePrefs(this.context)
    private val random = SecureRandom()

    class Bundled(val versionCode: Int, val versionName: String)
    private class Payload(val size: Long, val sha256: ByteArray)

    private class Session(
        val server: UpdateServer,
        val port: Int,
        val token: ByteArray,
        val sha256: ByteArray,
        val size: Long,
        val versionCode: Int,
        val addresses: List<String>,
        val issuedAt: Long,
        val deadline: Long,
    ) {
        @Volatile var ended: UpdateServer.Reason? = null
        @Volatile var report: UpdateReport? = null
    }

    enum class StartResult { STARTED, NO_APK, NO_ADDRESS, FAILED }

    private var bundledInfo: Bundled? = null
    private var bundledRead = false
    private var payload: Payload? = null
    @Volatile private var session: Session? = null

    /** Version of the watch APK inside this app, or null if the build did not bundle one. */
    @Synchronized
    fun bundled(): Bundled? {
        if (!bundledRead) {
            bundledRead = true
            bundledInfo = try {
                val p = Properties().apply { context.assets.open(PROPS_ASSET).use { load(it) } }
                Bundled(p.getProperty("versionCode").toInt(), p.getProperty("versionName"))
            } catch (e: Exception) {
                null
            }
        }
        return bundledInfo
    }

    /** Last version the watch reported with HELLO, or null (never, or an old watch build). */
    val watchVersion: Int? get() = prefs.watchVersion.takeIf { it >= 0 }

    fun clearWatchVersion() {
        prefs.watchVersion = -1
        RemoteState.poke()
    }

    /** Starts the server and makes the offer available. Blocking (hashes the APK once); call off the main thread. */
    @Synchronized
    fun start(): StartResult {
        stopLocked()
        val info = bundled() ?: return StartResult.NO_APK
        val data = try {
            payload ?: hashBundled().also { payload = it }
        } catch (e: IOException) {
            return StartResult.NO_APK
        }
        val addresses = UpdateAddresses.collect()
        if (addresses.isEmpty()) return StartResult.NO_ADDRESS
        val token = ByteArray(TOKEN_SIZE).also { random.nextBytes(it) }
        lateinit var server: UpdateServer
        server = UpdateServer(
            path = UpdateOffer.pathFor(token),
            length = data.size,
            open = { context.assets.open(APK_ASSET) },
            listener = object : UpdateServer.Listener {
                override fun onFinished(reason: UpdateServer.Reason) {
                    session?.takeIf { it.server === server }?.ended = reason
                    RemoteState.poke()
                }
            },
        )
        val port = server.start()
        if (port < 0) return StartResult.FAILED
        val now = System.currentTimeMillis()
        session = Session(server, port, token, data.sha256, data.size, info.versionCode, addresses, now, now + UpdateServer.DEFAULT_TIMEOUT_MS)
        RemoteState.poke()
        RemoteService.instance?.notifyOffer()
        return StartResult.STARTED
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        session?.server?.stop()
        session = null
        RemoteState.poke()
    }

    /** The offer to hand to an authenticated watch, or null when no update is being offered right now. */
    fun currentOffer(): UpdateOffer? {
        val s = session ?: return null
        if (s.ended != null || !s.server.isRunning) return null
        val age = ((System.currentTimeMillis() - s.issuedAt) / 1000).toInt().coerceIn(0, 0xFFFF)
        return UpdateOffer(s.versionCode, s.port, s.token, s.sha256, age, s.addresses)
    }

    /** HELLO. Called on the GATT thread after the frame authenticated. */
    fun onHello(versionCode: Int) {
        prefs.watchVersion = versionCode
        val s = session
        if (s != null && s.ended == UpdateServer.Reason.COMPLETED && versionCode >= s.versionCode) session = null
        RemoteState.poke()
    }

    /** UPDATE_STATE. Called on the GATT thread after the frame authenticated. */
    fun onReport(report: UpdateReport) {
        session?.report = report
        RemoteState.poke()
    }

    fun snapshot(): UpdateSnapshot? {
        val s = session ?: return null
        return UpdateSnapshot(s.ended, s.server.bytesServed, s.size, s.deadline, s.report)
    }

    private fun hashBundled(): Payload {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open(APK_ASSET).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        return Payload(size, md.digest())
    }

    companion object {
        const val APK_ASSET = "watch-update.apk"
        const val PROPS_ASSET = "watch-update.properties"
        private const val TOKEN_SIZE = 16

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: UpdateHost? = null

        fun get(context: Context): UpdateHost =
            instance ?: synchronized(this) { instance ?: UpdateHost(context).also { instance = it } }

        /** Pure mapping from the session facts to what the screen shows. */
        fun stateOf(s: UpdateSnapshot?, now: Long): WatchUpdateState {
            if (s == null) return WatchUpdateState.Idle
            val r = s.report
            if (r != null && r.phase == UpdateReport.Phase.FAILED) {
                return WatchUpdateState.Failed(UpdateReport.Failure.fromId(r.value))
            }
            return when (s.ended) {
                UpdateServer.Reason.TIMEOUT -> WatchUpdateState.TimedOut
                UpdateServer.Reason.STOPPED -> WatchUpdateState.Idle
                UpdateServer.Reason.COMPLETED -> WatchUpdateState.Installing
                null -> when {
                    r != null && (r.phase == UpdateReport.Phase.INSTALLING || r.phase == UpdateReport.Phase.VERIFYING) ->
                        WatchUpdateState.Installing
                    s.bytesServed > 0 || r?.phase == UpdateReport.Phase.DOWNLOADING -> {
                        val pct = if (s.total > 0) (s.bytesServed * 100 / s.total).toInt().coerceIn(0, 100) else 0
                        WatchUpdateState.Downloading(pct)
                    }
                    else -> WatchUpdateState.Waiting(maxOf(0L, (s.deadline - now) / 1000).toInt())
                }
            }
        }
    }
}
