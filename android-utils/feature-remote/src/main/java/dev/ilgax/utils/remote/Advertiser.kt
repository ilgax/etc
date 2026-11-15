package dev.ilgax.utils.remote

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import dev.ilgax.tabremote.protocol.Advertisement
import dev.ilgax.tabremote.protocol.Protocol

/**
 * Advertisement size: flags (3) + 128-bit service UUID (2+16) + manufacturer data (2+2+5) = 30 bytes,
 * within the 31-byte limit, so no scan response is needed.
 */
class Advertiser(private val adapter: BluetoothAdapter, private val onFailure: (Int) -> Unit) {
    private var callback: AdvertiseCallback? = null

    fun start(tabletId: ByteArray, pairing: Boolean): Boolean {
        stop()
        try {
            val advertiser = adapter.bluetoothLeAdvertiser ?: return false
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
                .addManufacturerData(Protocol.COMPANY_ID, Advertisement.encode(tabletId, pairing))
                .build()
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) = onFailure(errorCode)
            }
            callback = cb
            advertiser.startAdvertising(settings, data, cb)
            return true
        } catch (e: SecurityException) {
            return false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }
}
