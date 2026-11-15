package dev.ilgax.tabremote.wear

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import dev.ilgax.tabremote.protocol.Advertisement
import dev.ilgax.tabremote.protocol.AppSlots
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.CommandFrame
import dev.ilgax.tabremote.protocol.Hmac
import dev.ilgax.tabremote.protocol.Pairing
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.tabremote.protocol.Status
import java.security.KeyPair

interface PairingListener {
    /** Both sides now show this code; the user must compare and then call [RemoteClient.confirmPairing]. */
    fun onCode(code: String)

    /** The tablet user has not confirmed yet; the watch stays connected so Confirm can be pressed again. */
    fun onTabletNotConfirmed()

    fun onFinished(result: Result)
}

/**
 * BLE central. All state lives on one worker thread; GATT operations are strictly serialised
 * (one job at a time, one GATT op at a time). Callbacks to callers are delivered on the main thread.
 */
class RemoteClient private constructor(context: Context) {
    private val context = context.applicationContext
    private val prefs = WatchPrefs(this.context)
    private val vault = KeyVault()
    private val thread = HandlerThread("tabremote-client").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val queue = ArrayDeque<Job>()
    private var current: Job? = null

    private var gatt: BluetoothGatt? = null
    private var ready = false
    private var mtuRequested = false
    private var connectedTabletId: ByteArray? = null
    private var nonceCh: BluetoothGattCharacteristic? = null
    private var commandCh: BluetoothGattCharacteristic? = null
    private var pairCh: BluetoothGattCharacteristic? = null
    private var appsCh: BluetoothGattCharacteristic? = null

    private var pendingOp: ((Int, ByteArray?) -> Unit)? = null
    private var connectDone: ((Result?) -> Unit)? = null
    private var connectTarget: ConnectTarget? = null
    private var stopScan: (() -> Unit)? = null

    private var awaiting: AwaitingConfirm? = null
    private var pairJob: Job? = null

    private class ConnectTarget(val device: BluetoothDevice, val tabletId: ByteArray, var retried: Boolean = false)
    private class AwaitingConfirm(val key: ByteArray, val tabletId: ByteArray, val listener: PairingListener)

    private val opTimeout = Runnable { completeOp(STATUS_TIMEOUT, null) }
    private val connectTimeout = Runnable { connectFinish(Result.CONNECT_FAILED, teardown = true) }
    private val idle = Runnable { if (current == null && queue.isEmpty() && awaiting == null) teardown() }

    // region public API

    val isPaired: Boolean get() = prefs.tabletId != null && vault.has()

    fun send(command: Command, args: ByteArray = ByteArray(0), callback: (Result) -> Unit) {
        enqueue(callback) { signedCommand(command, args) { finish(it) } }
    }

    /** Sends SYNC_APPS, then reads the slot labels and stores them. */
    fun syncApps(callback: (Result) -> Unit) {
        enqueue(callback) {
            withConnection(pairing = false) { err ->
                if (err != null) return@withConnection finish(err)
                ensureMtu {
                    sendSigned(this@RemoteClient::vaultMac, Command.SYNC_APPS, ByteArray(0)) { r ->
                        if (r != Result.SUCCESS) return@sendSigned finish(r)
                        val ch = appsCh ?: return@sendSigned finish(Result.CONNECT_FAILED)
                        op(OP_TIMEOUT_MS, { read(it, ch) }) { st, value ->
                            if (st != 0 || value == null) return@op finish(mapStatus(st))
                            prefs.slotLabels = AppSlots.decode(value)
                            finish(Result.SUCCESS)
                        }
                    }
                }
            }
        }
    }

    fun startPairing(listener: PairingListener) {
        handler.post {
            val job = Job({ listener.onFinished(it) }) {
                teardown() // pairing always uses a fresh connection
                withConnection(pairing = true) { err ->
                    if (err != null) return@withConnection finish(err)
                    ensureMtu { exchangeKeys(listener) }
                }
            }
            pairJob = job
            queue.add(job)
            next()
        }
    }

    fun confirmPairing() = handler.post {
        val a = awaiting ?: return@post
        val job = pairJob ?: return@post
        job.arm(CONFIRM_TIMEOUT_MS)
        job.sendSigned({ Hmac.sha256(a.key, it) }, Command.PING, ByteArray(0)) { r ->
            when (r) {
                Result.SUCCESS -> {
                    try {
                        vault.save(a.key)
                        prefs.tabletId = a.tabletId
                        connectedTabletId = a.tabletId
                    } catch (e: Exception) {
                        awaiting = null
                        return@sendSigned job.finish(Result.FAILED)
                    }
                    awaiting = null
                    job.finish(Result.SUCCESS)
                    syncApps { }
                }
                Result.PAIRING_REJECTED -> {
                    job.arm(PAIRING_WINDOW_MS)
                    main.post { a.listener.onTabletNotConfirmed() }
                }
                else -> {
                    awaiting = null
                    job.finish(r)
                }
            }
        }
    }

    fun cancelPairing() = handler.post {
        val job = pairJob ?: return@post
        awaiting = null
        pairJob = null
        if (current === job) {
            job.fail(Result.CANCELLED, teardown = true)
        } else if (queue.remove(job)) {
            main.post { job.callback(Result.CANCELLED) }
        }
    }

    fun unpair() = handler.post {
        teardown()
        vault.delete()
        prefs.clearPairing()
    }

    // endregion

    // region jobs

    private inner class Job(val callback: (Result) -> Unit, val body: Job.() -> Unit) {
        var done = false
        private val watchdog = Runnable { fail(Result.TIMEOUT, teardown = true) }

        fun arm(ms: Long) {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, ms)
        }

        fun fail(r: Result, teardown: Boolean) {
            stopScan?.invoke()
            if (teardown) teardown()
            finish(r)
        }

        fun finish(r: Result) {
            if (done) return
            done = true
            handler.removeCallbacks(watchdog)
            if (r == Result.TIMEOUT || r == Result.CONNECT_FAILED) teardown()
            if (pairJob === this) pairJob = null
            if (current === this) current = null
            main.post { callback(r) }
            next()
        }

        fun start() {
            arm(JOB_TIMEOUT_MS)
            try {
                body()
            } catch (e: Exception) {
                fail(Result.FAILED, teardown = true)
            }
        }
    }

    private fun enqueue(callback: (Result) -> Unit, body: Job.() -> Unit): Job {
        val job = Job(callback, body)
        handler.post {
            queue.add(job)
            next()
        }
        return job
    }

    private fun next() {
        if (current != null) return
        val job = queue.removeFirstOrNull()
        if (job == null) {
            handler.removeCallbacks(idle)
            handler.postDelayed(idle, IDLE_MS)
            return
        }
        handler.removeCallbacks(idle)
        current = job
        job.start()
    }

    private fun Job.signedCommand(command: Command, args: ByteArray, done: (Result) -> Unit) {
        attempt(command, args, 0, done)
    }

    /** Retries once on a fresh connection if a reused (possibly stale) connection fails. */
    private fun Job.attempt(command: Command, args: ByteArray, n: Int, done: (Result) -> Unit) {
        val reused = ready
        withConnection(pairing = false) { err ->
            if (err != null) return@withConnection done(err)
            sendSigned(this@RemoteClient::vaultMac, command, args) { r ->
                if (n == 0 && reused && (r == Result.TIMEOUT || r == Result.CONNECT_FAILED)) {
                    teardown()
                    attempt(command, args, 1, done)
                } else {
                    done(r)
                }
            }
        }
    }

    private fun vaultMac(data: ByteArray): ByteArray = vault.mac(data) ?: throw IllegalStateException("no key")

    /** Nonce read, then a signed COMMAND write. [mac] may throw; that maps to FAILED. */
    private fun Job.sendSigned(mac: (ByteArray) -> ByteArray, cmd: Command, args: ByteArray, done: (Result) -> Unit) {
        val nCh = nonceCh
        val cCh = commandCh
        if (nCh == null || cCh == null) return done(Result.CONNECT_FAILED)
        op(OP_TIMEOUT_MS, { read(it, nCh) }) { st, nonce ->
            if (st != 0 || nonce == null) return@op done(mapStatus(st))
            val frame = try {
                CommandFrame.encode(mac, nonce, cmd, args)
            } catch (e: Exception) {
                return@op done(Result.FAILED)
            }
            op(OP_TIMEOUT_MS, { write(it, cCh, frame) }) { st2, _ -> done(mapStatus(st2)) }
        }
    }

    private fun Job.exchangeKeys(listener: PairingListener) {
        val pCh = pairCh ?: return finish(Result.CONNECT_FAILED)
        val tabletId = connectedTabletId ?: return finish(Result.CONNECT_FAILED)
        val kp: KeyPair = Pairing.generateKeyPair()
        val watchPub = Pairing.encodePublicKey(kp.public)
        op(OP_TIMEOUT_MS, { write(it, pCh, watchPub) }) { st, _ ->
            if (st != 0) return@op finish(mapStatus(st))
            op(OP_TIMEOUT_MS, { read(it, pCh) }) { st2, tabletPub ->
                if (st2 != 0 || tabletPub == null) return@op finish(mapStatus(st2))
                try {
                    val peer = Pairing.decodePublicKey(tabletPub)
                    val d = Pairing.derive(kp.private, peer, watchPub, tabletPub)
                    awaiting = AwaitingConfirm(d.key, tabletId, listener)
                    arm(PAIRING_WINDOW_MS)
                    main.post { listener.onCode(d.code) }
                } catch (e: Exception) {
                    finish(Result.PAIRING_REJECTED)
                }
            }
        }
    }

    private fun mapStatus(st: Int): Result = when (st) {
        0 -> Result.SUCCESS
        STATUS_TIMEOUT -> Result.TIMEOUT
        STATUS_LOCAL -> Result.CONNECT_FAILED
        // Android's local stack failures (0x80..0x8F, incl. 133 = GATT_ERROR) are transport errors, never app results.
        in LOCAL_STACK_MIN..LOCAL_STACK_MAX -> Result.CONNECT_FAILED
        else -> Result.from(Status.fromStatus(st))
    }

    // endregion

    // region GATT plumbing

    private fun op(timeoutMs: Long, start: (BluetoothGatt) -> Boolean, done: (Int, ByteArray?) -> Unit) {
        val g = gatt
        if (g == null) return done(STATUS_LOCAL, null)
        pendingOp = done
        handler.postDelayed(opTimeout, timeoutMs)
        val started = try {
            start(g)
        } catch (e: SecurityException) {
            false
        }
        if (!started) completeOp(STATUS_LOCAL, null)
    }

    private fun completeOp(status: Int, value: ByteArray?) {
        val d = pendingOp ?: return
        pendingOp = null
        handler.removeCallbacks(opTimeout)
        d(status, value)
    }

    private fun read(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean = try {
        g.readCharacteristic(ch)
    } catch (e: SecurityException) {
        false
    }

    private fun requestMtu(g: BluetoothGatt): Boolean = try {
        g.requestMtu(247)
    } catch (e: SecurityException) {
        false
    }

    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                g.writeCharacteristic(ch)
            }
        }
    } catch (e: SecurityException) {
        false
    }

    /** Requests a bigger MTU once per connection; continues whatever the outcome. */
    private fun ensureMtu(then: () -> Unit) {
        if (mtuRequested) return then()
        mtuRequested = true
        op(MTU_TIMEOUT_MS, { requestMtu(it) }) { _, _ -> then() }
    }

    private fun checkReady(): Result? {
        val granted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.NO_PERMISSION
        return if (adapterEnabled()) null else Result.BLUETOOTH_OFF
    }

    private fun adapterEnabled(): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    /** Ensures a ready connection to the paired tablet (or to one in pairing mode). */
    private fun Job.withConnection(pairing: Boolean, done: (Result?) -> Unit) {
        checkReady()?.let { return done(it) }
        val stored = prefs.tabletId
        if (!pairing) {
            if (stored == null || !vault.has()) return done(Result.NOT_PAIRED)
            if (ready && gatt != null && connectedTabletId?.contentEquals(stored) == true) return done(null)
            teardown()
        }
        scan(pairing, stored) { err, device, id ->
            if (err != null || device == null || id == null) return@scan done(err ?: Result.NOT_FOUND)
            connect(ConnectTarget(device, id), done)
        }
    }

    private fun scan(pairing: Boolean, wanted: ByteArray?, found: (Result?, BluetoothDevice?, ByteArray?) -> Unit) {
        val scanner = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        } ?: return found(Result.BLUETOOTH_OFF, null, null)
        var finished = false
        lateinit var cb: ScanCallback
        fun stop() {
            handler.removeCallbacksAndMessages(SCAN_TOKEN)
            stopScan = null
            try {
                scanner.stopScan(cb)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
        }
        cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (finished) return@post
                    val adv = Advertisement.decode(
                        result.scanRecord?.getManufacturerSpecificData(Protocol.COMPANY_ID),
                    ) ?: return@post
                    val ok = if (pairing) adv.pairing else wanted != null && adv.tabletId.contentEquals(wanted)
                    if (!ok) return@post
                    finished = true
                    stop()
                    found(null, result.device, adv.tabletId)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (finished) return@post
                    finished = true
                    stop()
                    found(Result.CONNECT_FAILED, null, null)
                }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        stopScan = { finished = true; stop() }
        handler.postAtTime({
            if (!finished) {
                finished = true
                stop()
                found(Result.NOT_FOUND, null, null)
            }
        }, SCAN_TOKEN, android.os.SystemClock.uptimeMillis() + SCAN_TIMEOUT_MS)
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: SecurityException) {
            finished = true
            stop()
            found(Result.NO_PERMISSION, null, null)
        }
    }

    private fun connect(target: ConnectTarget, done: (Result?) -> Unit) {
        closeGatt()
        connectTarget = target
        connectDone = done
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt = try {
            target.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        }
        if (gatt == null) connectFinish(Result.CONNECT_FAILED, teardown = true)
    }

    private fun connectFinish(result: Result?, teardown: Boolean = false) {
        val d = connectDone ?: return
        connectDone = null
        handler.removeCallbacks(connectTimeout)
        if (teardown) teardown()
        d(result)
    }

    /** Status 133 during connect/discovery: retry once on the same device. */
    private fun retryOrFail(g: BluetoothGatt, status: Int) {
        val target = connectTarget
        val done = connectDone
        if (target != null && done != null && !target.retried && status == GATT_ERROR_133) {
            target.retried = true
            closeGatt()
            handler.postDelayed({
                if (connectDone === done) {
                    gatt = try {
                        target.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } catch (e: SecurityException) {
                        null
                    }
                    if (gatt == null) connectFinish(Result.CONNECT_FAILED, teardown = true)
                }
            }, RETRY_DELAY_MS)
        } else {
            connectFinish(Result.CONNECT_FAILED, teardown = true)
        }
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        ready = false
        try {
            g.disconnect()
        } catch (_: SecurityException) {
        }
        try {
            g.close()
        } catch (_: SecurityException) {
        }
    }

    /** Drops the connection without invoking any caller callbacks. */
    private fun teardown() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(opTimeout)
        connectDone = null
        pendingOp = null
        connectTarget = null
        awaiting = null
        mtuRequested = false
        connectedTabletId = null
        nonceCh = null; commandCh = null; pairCh = null; appsCh = null
        closeGatt()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    try { g.close() } catch (_: SecurityException) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    val started = try { g.discoverServices() } catch (e: SecurityException) { false }
                    if (!started) connectFinish(Result.CONNECT_FAILED, teardown = true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    if (connectDone != null) {
                        retryOrFail(g, status)
                    } else {
                        // Link lost while idle or mid-operation.
                        val d = pendingOp
                        val waiting = awaiting != null
                        val job = pairJob
                        teardown()
                        d?.invoke(STATUS_LOCAL, null)
                        if (waiting) job?.finish(Result.CONNECT_FAILED)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt || connectDone == null) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) return@post retryOrFail(g, status)
                val svc = g.getService(Protocol.SERVICE_UUID)
                nonceCh = svc?.getCharacteristic(Protocol.NONCE_UUID)
                commandCh = svc?.getCharacteristic(Protocol.COMMAND_UUID)
                pairCh = svc?.getCharacteristic(Protocol.PAIR_UUID)
                appsCh = svc?.getCharacteristic(Protocol.APPS_UUID)
                if (nonceCh == null || commandCh == null || pairCh == null || appsCh == null) {
                    return@post connectFinish(Result.CONNECT_FAILED, teardown = true)
                }
                ready = true
                connectedTabletId = connectTarget?.tabletId
                connectFinish(null)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { if (g === gatt) completeOp(status, null) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { if (g === gatt) completeOp(status, value) }
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val value = ch.value?.copyOf()
            handler.post { if (g === gatt) completeOp(status, value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (g === gatt) completeOp(status, null) }
        }
    }

    // endregion

    companion object {
        private const val STATUS_TIMEOUT = -2
        private const val STATUS_LOCAL = -1
        private const val GATT_ERROR_133 = 133
        private const val LOCAL_STACK_MIN = 0x80
        private const val LOCAL_STACK_MAX = 0x8F
        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val OP_TIMEOUT_MS = 4_000L
        private const val MTU_TIMEOUT_MS = 2_500L
        private const val RETRY_DELAY_MS = 300L
        private const val JOB_TIMEOUT_MS = 20_000L
        private const val CONFIRM_TIMEOUT_MS = 10_000L
        private const val PAIRING_WINDOW_MS = Protocol.PAIRING_WINDOW_MS
        private const val IDLE_MS = 10_000L
        private val SCAN_TOKEN = Any()

        @Volatile
        @SuppressLint("StaticFieldLeak") // holds the application context only
        private var instance: RemoteClient? = null

        fun get(context: Context): RemoteClient =
            instance ?: synchronized(this) { instance ?: RemoteClient(context).also { instance = it } }
    }
}
