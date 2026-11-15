package dev.ilgax.utils.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class RemoteService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: RemotePrefs
    private lateinit var executor: CommandExecutor
    private lateinit var pairing: PairingSession
    private lateinit var gatt: GattController
    private var advertiser: Advertiser? = null
    private var stackOpen = false
    private var receiverRegistered = false

    private val stateListener: () -> Unit = { notifyForeground() }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    closeStack()
                    RemoteState.service = RemoteState.Service.BLUETOOTH_OFF
                }
                BluetoothAdapter.STATE_ON -> openStack()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = RemotePrefs(this)
        executor = CommandExecutor(this, AppKiller.create(this))
        pairing = PairingSession { main.post { refreshAdvertising() } }
        gatt = GattController(this, prefs, KeyVault(), executor, pairing) { main.post { refreshAdvertising() } }
        RemoteState.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            fail(RemoteState.Service.NO_PERMISSION, null)
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermissions(this)) {
            fail(RemoteState.Service.NO_PERMISSION, null)
            return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(btReceiver, filter)
            }
            receiverRegistered = true
            if (!openStack()) return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAIR -> pairing.start()
            ACTION_STOP_RING -> executor.stopRing()
        }
        return START_STICKY
    }

    /** Returns false when the service had to stop itself. */
    private fun openStack(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            fail(RemoteState.Service.ERROR, getString(R.string.remote_err_no_adapter))
            return false
        }
        if (!adapter.isEnabled) {
            RemoteState.service = RemoteState.Service.BLUETOOTH_OFF
            return true
        }
        closeStack()
        advertiser = Advertiser(adapter) { code ->
            main.post {
                RemoteState.serviceDetail = getString(R.string.remote_err_advertise, code)
                RemoteState.service = RemoteState.Service.ERROR
            }
        }
        stackOpen = true
        if (!gatt.open(getSystemService(BluetoothManager::class.java))) {
            stackOpen = false
            fail(RemoteState.Service.ERROR, getString(R.string.remote_err_gatt))
            return false
        }
        RemoteState.serviceDetail = null
        RemoteState.service = RemoteState.Service.RUNNING
        return true
    }

    private fun closeStack() {
        advertiser?.stop()
        advertiser = null
        gatt.close()
        stackOpen = false
    }

    /** Called once the GATT service is registered, when pairing mode changes, and after disconnects. */
    private fun refreshAdvertising() {
        if (!stackOpen) return
        val ok = advertiser?.start(prefs.tabletId, pairing.isActive) ?: return
        if (!ok) {
            RemoteState.serviceDetail = getString(R.string.remote_err_advertise, -1)
            RemoteState.service = RemoteState.Service.ERROR
        }
    }

    private fun fail(status: RemoteState.Service, detail: String?) {
        RemoteState.serviceDetail = detail
        RemoteState.service = status
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        RemoteState.removeListener(stateListener)
        pairing.cancel()
        if (receiverRegistered) unregisterReceiver(btReceiver)
        closeStack()
        gatt.shutdown()
        RingPlayer.stop()
        if (RemoteState.service == RemoteState.Service.RUNNING || RemoteState.service == RemoteState.Service.BLUETOOTH_OFF) {
            RemoteState.service = RemoteState.Service.STOPPED
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun startPairing() = pairing.start()
    fun cancelPairing() = pairing.cancel()
    fun confirmPairing() = pairing.confirm()

    /** Pings subscribed watches that an update offer is available (they then fetch it authenticated). */
    fun notifyOffer() = gatt.notifyOffer()

    private fun notifyForeground() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.remote_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(getString(R.string.remote_notification_title))
            .setOngoing(true)
            .setContentIntent(open)
        if (RemoteState.ringing) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP_RING), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_remote), getString(R.string.remote_stop_ringing), stop).build())
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PAIR = "dev.ilgax.utils.remote.PAIR"
        const val ACTION_STOP_RING = "dev.ilgax.utils.remote.STOP_RING"

        @Volatile
        var instance: RemoteService? = null
            private set

        fun isRunning() = instance != null

        fun hasBluetoothPermissions(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

        /** Returns false if the system refused (e.g. background start restrictions). */
        fun start(context: Context, action: String? = null): Boolean = try {
            context.startForegroundService(Intent(context, RemoteService::class.java).setAction(action))
            true
        } catch (e: Exception) {
            false
        }
    }
}
