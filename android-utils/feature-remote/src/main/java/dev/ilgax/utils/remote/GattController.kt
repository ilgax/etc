package dev.ilgax.utils.remote

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.ilgax.tabremote.protocol.AppSlots
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.CommandFrame
import dev.ilgax.tabremote.protocol.MacroList
import dev.ilgax.utils.remote.macros.MacroEngine
import dev.ilgax.tabremote.protocol.OfferFrame
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.tabremote.protocol.Status
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * GATT server. Every callback is re-posted onto a single worker thread, so all state below is
 * confined to that thread.
 */
class GattController(
    private val context: Context,
    private val prefs: RemotePrefs,
    private val vault: KeyVault,
    private val executor: CommandExecutor,
    private val pairing: PairingSession,
    private val onReady: () -> Unit,
) {
    private val thread = HandlerThread("tabremote-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()
    private var server: BluetoothGattServer? = null

    private val devices = HashMap<String, BluetoothDevice>()
    private val nonces = HashMap<String, ByteArray>()
    private val mtus = HashMap<String, Int>()
    private val pairBuffers = HashMap<String, ByteArrayOutputStream>()
    private val appsGrants = HashSet<String>()
    private val appsSnapshots = HashMap<String, ByteArray>()
    /** GET_OFFER grants: one authenticated read of OFFER, bound to the nonce of the granting frame. */
    private val offerGrants = HashMap<String, ByteArray>()
    private val offerSnapshots = HashMap<String, ByteArray>()
    private val offerSubscribers = HashSet<String>()
    /** SYNC_MACROS grants: one authenticated read of MACROS, bound to the granting frame's nonce and the page it asked for. */
    private class MacroGrant(val nonce: ByteArray, val page: Int)
    private val macroGrants = HashMap<String, MacroGrant>()
    private val macroSnapshots = HashMap<String, ByteArray>()
    private var offerChar: BluetoothGattCharacteristic? = null
    private val failures = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()

    fun open(manager: BluetoothManager): Boolean {
        close()
        return try {
            val s = manager.openGattServer(context, callback) ?: return false
            server = s
            s.addService(buildService())
        } catch (e: SecurityException) {
            false
        }
    }

    fun close() {
        val s = server ?: return
        server = null
        try {
            s.close()
        } catch (_: SecurityException) {
        }
        handler.post {
            devices.clear(); nonces.clear(); mtus.clear(); pairBuffers.clear()
            appsGrants.clear(); appsSnapshots.clear()
            offerGrants.clear(); offerSnapshots.clear(); offerSubscribers.clear()
            macroGrants.clear(); macroSnapshots.clear()
        }
    }

    /** Content-free ping to subscribed watches: "ask for the offer". The offer itself is never notified. */
    fun notifyOffer() {
        handler.post {
            val s = server ?: return@post
            val ch = offerChar ?: return@post
            for (addr in offerSubscribers.toList()) {
                val device = devices[addr] ?: continue
                try {
                    s.notifyCharacteristicChanged(device, ch, false, byteArrayOf(Protocol.PING_OFFER))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    /** Content-free ping on the same channel: "the macro list changed". Old watches just treat it as an offer check. */
    fun notifyMacros() {
        handler.post {
            val s = server ?: return@post
            val ch = offerChar ?: return@post
            for (addr in offerSubscribers.toList()) {
                val device = devices[addr] ?: continue
                try {
                    s.notifyCharacteristicChanged(device, ch, false, byteArrayOf(Protocol.PING_MACROS))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    fun shutdown() {
        close()
        thread.quitSafely()
    }

    private fun buildService(): BluetoothGattService {
        val svc = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val read = BluetoothGattCharacteristic.PERMISSION_READ
        val write = BluetoothGattCharacteristic.PERMISSION_WRITE
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.NONCE_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.COMMAND_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, write),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.PAIR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                read or write,
            ),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.APPS_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        val offer = BluetoothGattCharacteristic(
            Protocol.OFFER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            read,
        )
        offer.addDescriptor(
            BluetoothGattDescriptor(Protocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        svc.addCharacteristic(offer)
        offerChar = offer
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.MACROS_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        return svc
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) onReady()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                val addr = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devices[addr] = device
                    nonces[addr] = newNonce()
                } else {
                    devices.remove(addr); nonces.remove(addr); mtus.remove(addr)
                    pairBuffers.remove(addr); appsGrants.remove(addr); appsSnapshots.remove(addr)
                    offerGrants.remove(addr); offerSnapshots.remove(addr); offerSubscribers.remove(addr)
                    macroGrants.remove(addr); macroSnapshots.remove(addr)
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) onReady() // re-assert advertising
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { mtus[device.address] = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            handler.post { handleRead(device, requestId, offset, characteristic.uuid) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val data = value?.copyOf() ?: ByteArray(0)
            handler.post { handleWrite(device, requestId, characteristic.uuid, preparedWrite, responseNeeded, offset, data) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val on = descriptor.uuid == Protocol.CCCD_UUID && device.address in offerSubscribers
                if (descriptor.uuid != Protocol.CCCD_UUID) {
                    respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                } else {
                    val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    respondSlice(device, requestId, offset, v)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val data = value?.copyOf() ?: ByteArray(0)
            handler.post {
                val ok = descriptor.uuid == Protocol.CCCD_UUID && !preparedWrite && offset == 0 && data.size == 2
                if (ok) {
                    if (data.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) offerSubscribers.add(device.address)
                    else offerSubscribers.remove(device.address)
                }
                if (responseNeeded) {
                    respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
                val buf = pairBuffers.remove(device.address)
                val status = if (execute && buf != null) {
                    pairing.onWatchKey(device.address, buf.toByteArray(), ::isConnected)
                } else {
                    Status.SUCCESS
                }
                respond(device, requestId, status.code, 0, null)
            }
        }
    }

    private fun isConnected(address: String) = devices.containsKey(address)

    private fun handleRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: java.util.UUID) {
        val addr = device.address
        when (uuid) {
            Protocol.NONCE_UUID -> {
                val nonce = nonces.getOrPut(addr) { newNonce() }
                respondSlice(device, requestId, offset, nonce)
            }
            Protocol.PAIR_UUID -> {
                val pub = pairing.tabletPublicKey()
                if (pub == null) respond(device, requestId, Status.PAIRING_REJECTED.code, offset, null)
                else respondSlice(device, requestId, offset, pub)
            }
            Protocol.APPS_UUID -> {
                val payload: ByteArray? = if (offset == 0) {
                    if (appsGrants.remove(addr)) {
                        AppSlots.encode(AppCatalog.slotLabels(context, prefs)).also { appsSnapshots[addr] = it }
                    } else null
                } else appsSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                // The stack truncates each response to MTU-1 bytes; drop the snapshot once the end is reached.
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) appsSnapshots.remove(addr)
            }
            Protocol.OFFER_UUID -> {
                // Only after an authenticated GET_OFFER; the tag binds the payload to that frame's nonce.
                val payload: ByteArray? = if (offset == 0) {
                    offerGrants.remove(addr)?.let { nonce ->
                        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                        try {
                            OfferFrame.encode(mac, nonce, UpdateHost.get(context).currentOffer())
                        } catch (e: Exception) {
                            null
                        }
                    }?.also { offerSnapshots[addr] = it }
                } else offerSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) offerSnapshots.remove(addr)
            }
            Protocol.MACROS_UUID -> {
                // Only after an authenticated SYNC_MACROS; the tag binds the page to that frame's nonce.
                val payload: ByteArray? = if (offset == 0) {
                    macroGrants.remove(addr)?.let { grant ->
                        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                        try {
                            val (version, items) = MacroEngine.get(context).store.watchView()
                            MacroList.encode(mac, grant.nonce, version, items, grant.page)
                        } catch (e: Exception) {
                            null
                        }
                    }?.also { macroSnapshots[addr] = it }
                } else macroSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) macroSnapshots.remove(addr)
            }
            else -> respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
        }
    }

    private fun respondSlice(device: BluetoothDevice, requestId: Int, offset: Int, full: ByteArray) {
        if (offset > full.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
        }
    }

    private fun handleWrite(
        device: BluetoothDevice, requestId: Int, uuid: java.util.UUID,
        prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
    ) {
        val addr = device.address
        val reply = { status: Int, echo: ByteArray? -> if (responseNeeded) respond(device, requestId, status, offset, echo) }
        when (uuid) {
            Protocol.COMMAND_UUID -> {
                if (prepared || offset != 0) {
                    reply(Status.FAILED.code, null)
                    return
                }
                reply(processCommand(addr, value).code, null)
            }
            Protocol.PAIR_UUID -> {
                if (prepared) {
                    val buf = pairBuffers.getOrPut(addr) { ByteArrayOutputStream() }
                    // Prepared writes arrive in order; reject gaps rather than guess.
                    if (offset != buf.size() || buf.size() + value.size > PAIR_MAX) {
                        pairBuffers.remove(addr)
                        reply(BluetoothGatt.GATT_INVALID_OFFSET, null)
                    } else {
                        buf.write(value)
                        reply(BluetoothGatt.GATT_SUCCESS, value)
                    }
                } else {
                    reply(if (offset != 0) BluetoothGatt.GATT_INVALID_OFFSET
                    else pairing.onWatchKey(addr, value, ::isConnected).code, null)
                }
            }
            else -> reply(BluetoothGatt.GATT_WRITE_NOT_PERMITTED, null)
        }
    }

    private fun processCommand(addr: String, frame: ByteArray): Status {
        val now = System.currentTimeMillis()
        if ((lockedUntil[addr] ?: 0L) > now) return Status.RATE_LIMITED
        val nonce = nonces.getOrPut(addr) { newNonce() }
        val status = try {
            verifyAndRun(addr, nonce, frame)
        } catch (e: Exception) {
            Status.FAILED
        }
        // One nonce per attempt, whatever the outcome.
        nonces[addr] = newNonce()
        if (status == Status.AUTH_FAILED) {
            val n = (failures[addr] ?: 0) + 1
            if (n >= MAX_FAILURES) {
                failures.remove(addr)
                lockedUntil[addr] = now + LOCKOUT_MS
            } else failures[addr] = n
        } else if (status == Status.SUCCESS) {
            failures.remove(addr)
        }
        return status
    }

    private fun verifyAndRun(addr: String, nonce: ByteArray, frame: ByteArray): Status {
        pairing.tryCommit(nonce, frame) { key ->
            vault.save(key)
            prefs.paired = true
            prefs.watchVersion = -1 // a new pairing may be a different watch
        }?.let { return it }

        if (!vault.has()) return Status.AUTH_FAILED
        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
        return when (val r = CommandFrame.verify(mac, nonce, frame)) {
            is CommandFrame.Result.Invalid -> r.status
            is CommandFrame.Result.Valid -> {
                val status = executor.execute(r.command, r.args)
                if (r.command == Command.SYNC_APPS && status == Status.SUCCESS) appsGrants.add(addr)
                if (r.command == Command.GET_OFFER && status == Status.SUCCESS) offerGrants[addr] = nonce
                if (r.command == Command.SYNC_MACROS && status == Status.SUCCESS) {
                    MacroList.decodePageArgs(r.args)?.let { macroGrants[addr] = MacroGrant(nonce, it) }
                }
                status
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (_: SecurityException) {
        }
    }

    private fun newNonce() = ByteArray(Protocol.NONCE_SIZE).also { random.nextBytes(it) }

    private companion object {
        const val DEFAULT_MTU = 23
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
        const val PAIR_MAX = 128
    }
}
