package dev.ilgax.tabremote.wear

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.UpdateFetch
import dev.ilgax.tabremote.protocol.UpdateOffer
import dev.ilgax.tabremote.protocol.UpdateReport
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the app shows about a watch app update. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    /** An authenticated offer is waiting for the user's tap. */
    class Available(val offer: UpdateOffer) : UpdateUi
    class Downloading(val version: Int, val percent: Int) : UpdateUi
    class Installing(val version: Int) : UpdateUi
    /** The system wants a tap on its install dialog; it is launched from the visible app. */
    class Confirm(val version: Int) : UpdateUi
    /** "Install unknown apps" is not allowed for this app; show the exact adb fix. */
    data object NeedsInstallPermission : UpdateUi
    class Failed(val failure: UpdateReport.Failure, val detail: String? = null) : UpdateUi
}

/**
 * Downloads an offered APK over Wi-Fi and installs it with PackageInstaller.
 *
 * Choice: an offer is only ever created by tapping "Update watch app" on the tablet, so it is the user's
 * request. If that tap was recent ([AUTO_WINDOW_SEC]) and Tab Remote is open, the update starts by itself;
 * an older offer (the tablet keeps it for 10 minutes) waits for a tap on "Update to vX". Either way nothing is
 * installed unless the APK's SHA-256 equals the hash in the HMAC-authenticated offer.
 */
class WatchUpdater private constructor(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile var state: UpdateUi = UpdateUi.Idle
        private set
    @Volatile var listener: ((UpdateUi) -> Unit)? = null
    @Volatile private var pendingConfirm: Intent? = null
    private var busy = false
    private var lastReportedPercent = -1

    val isBusy: Boolean get() = synchronized(this) { busy }

    /** [auto]: the caller is the visible app and may start right away if the offer is fresh. */
    fun onOffer(offer: UpdateOffer, auto: Boolean) {
        synchronized(this) { if (busy) return }
        if (auto && offer.ageSec <= AUTO_WINDOW_SEC) start(offer) else set(UpdateUi.Available(offer))
    }

    fun start(offer: UpdateOffer) {
        synchronized(this) {
            if (busy) return
            busy = true
        }
        lastReportedPercent = -1
        set(UpdateUi.Downloading(offer.versionCode, 0))
        Thread({ run(offer) }, "tabremote-update").start()
    }

    fun dismiss() {
        if (!isBusy) set(UpdateUi.Idle)
    }

    /** The system's install confirmation, if one is waiting. Launch it from a visible activity. */
    fun takePendingConfirm(): Intent? = synchronized(this) { pendingConfirm.also { pendingConfirm = null } }

    // region flow

    private fun run(offer: UpdateOffer) {
        val file = File(context.cacheDir, "update.apk")
        try {
            if (!context.packageManager.canRequestPackageInstalls()) return fail(UpdateReport.Failure.INSTALL_BLOCKED)
            report(UpdateReport(UpdateReport.Phase.DOWNLOADING, 0))
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val latch = CountDownLatch(1)
            var wifi: Network? = null
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wifi = network; latch.countDown() }
                override fun onUnavailable() { latch.countDown() }
            }
            try {
                // Wear routes the default network through the phone/Bluetooth proxy; ask for the Wi-Fi radio itself.
                cm.requestNetwork(request, callback, WIFI_TIMEOUT_MS.toInt())
                latch.await(WIFI_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
                val network = wifi ?: return fail(UpdateReport.Failure.NO_WIFI)
                val outcome = UpdateFetch.fetch(offer, { url -> open(network, url) }, file, onProgress = ::progress)
                when (outcome) {
                    is UpdateFetch.Outcome.Ok -> Unit
                    UpdateFetch.Outcome.HashMismatch -> return fail(UpdateReport.Failure.HASH_MISMATCH)
                    is UpdateFetch.Outcome.Unreachable -> return fail(UpdateReport.Failure.DOWNLOAD)
                }
            } finally {
                try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
            }
            // fetch() only returns Ok when the hash matched, so this is the verified-then-install step.
            report(UpdateReport(UpdateReport.Phase.VERIFYING, 100))
            install(offer.versionCode, file)
        } catch (e: Exception) {
            fail(UpdateReport.Failure.UNKNOWN, e.message)
        } finally {
            file.delete()
        }
    }

    private fun open(network: Network, url: String): UpdateFetch.Body {
        val c = network.openConnection(URL(url)) as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = false
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            val stream = object : FilterInputStream(c.inputStream) {
                override fun close() {
                    try { super.close() } finally { c.disconnect() }
                }
            }
            return UpdateFetch.Body(stream, c.contentLengthLong)
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    private fun progress(percent: Int) {
        val v = (state as? UpdateUi.Downloading)?.version ?: return
        if (percent / PROGRESS_STEP == lastReportedPercent / PROGRESS_STEP && percent < 100) return
        lastReportedPercent = percent
        set(UpdateUi.Downloading(v, percent))
        if (percent % 25 == 0 || percent >= 100) report(UpdateReport(UpdateReport.Phase.DOWNLOADING, percent))
    }

    private fun install(version: Int, apk: File) {
        set(UpdateUi.Installing(version))
        // The new APK replaces this process, so give the INSTALLING report a moment to reach the tablet first.
        val sent = CountDownLatch(1)
        RemoteClient.get(context).send(Command.UPDATE_STATE, UpdateReport(UpdateReport.Phase.INSTALLING, 0).encode()) { sent.countDown() }
        sent.await(SEND_WAIT_MS, TimeUnit.MILLISECONDS)

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName) // can only ever replace this app
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("tabremote.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the system fills in the status extras
            )
            session.commit(result.intentSender)
        }
    }

    // endregion

    // region results (from InstallResultReceiver)

    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                synchronized(this) { pendingConfirm = confirm }
                set(UpdateUi.Confirm((state as? UpdateUi.Installing)?.version ?: 0))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                synchronized(this) { busy = false }
                set(UpdateUi.Idle) // normally unreachable: the update restarts this process
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(UpdateReport.Failure.INSTALL_BLOCKED, message)
            else -> fail(UpdateReport.Failure.INSTALL_FAILED, message)
        }
    }

    private fun fail(reason: UpdateReport.Failure, detail: String? = null) {
        synchronized(this) { busy = false; pendingConfirm = null }
        report(UpdateReport.failed(reason))
        set(if (reason == UpdateReport.Failure.INSTALL_BLOCKED) UpdateUi.NeedsInstallPermission else UpdateUi.Failed(reason, detail))
    }

    // endregion

    private fun report(r: UpdateReport) = RemoteClient.get(context).reportUpdate(r)

    private fun set(s: UpdateUi) {
        state = s
        main.post { listener?.invoke(s) }
    }

    companion object {
        /** The adb command that allows this app to install packages; shown verbatim on the watch. */
        const val ADB_FIX = "adb shell appops set dev.ilgax.tabremote.wear REQUEST_INSTALL_PACKAGES allow"
        const val AUTO_WINDOW_SEC = 180
        private const val WIFI_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val SEND_WAIT_MS = 4_000L
        private const val PROGRESS_STEP = 5

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: WatchUpdater? = null

        fun get(context: Context): WatchUpdater =
            instance ?: synchronized(this) { instance ?: WatchUpdater(context).also { instance = it } }

        fun versionCode(context: Context): Int =
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }
}
