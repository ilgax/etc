package dev.ilgax.speakerkillswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class KillSwitchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager
    private var pendingFull = false

    private val debounced = Runnable {
        val full = pendingFull
        pendingFull = false
        SpeakerController.enforce(this, "event", full)
    }

    private fun schedule(full: Boolean) {
        if (full) pendingFull = true
        handler.removeCallbacks(debounced)
        handler.postDelayed(debounced, 100)
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            schedule(false)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            SpeakerController.log(c, "BECOMING_NOISY")
            if (SpeakerController.pauseOnDisconnect(c)) SpeakerController.pauseMedia(c, "becoming noisy")
            schedule(true)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            SpeakerController.log(this@KillSwitchService, "devices added: ${added.joinToString { "${it.type}:${it.productName}" }}")
            schedule(true)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            SpeakerController.log(this@KillSwitchService, "devices removed: ${removed.joinToString { "${it.type}:${it.productName}" }}")
            if (removed.any { it.isSink && SpeakerController.isBluetoothDevice(it) } &&
                SpeakerController.pauseOnDisconnect(this@KillSwitchService)
            ) SpeakerController.pauseMedia(this@KillSwitchService, "bluetooth output removed")
            schedule(true)
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        SpeakerController.invalidateBinder()
        SpeakerController.log(this, "Shizuku binder received")
        handler.post { schedule(true) }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        SpeakerController.invalidateBinder()
        SpeakerController.log(this, "Shizuku binder dead")
        handler.post { schedule(true) }
    }

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Kill switch status", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        live = true
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
        am.registerAudioDeviceCallback(deviceCallback, handler)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SpeakerController.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        SpeakerController.enforce(this, "service start", true)
        return START_STICKY
    }

    override fun onDestroy() {
        live = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
        am.unregisterAudioDeviceCallback(deviceCallback)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIF_ID = 1
        @Volatile private var live = false

        private fun buildNotification(ctx: Context): Notification {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_speaker_off)
                .setContentTitle("Speaker kill switch ON")
                .setContentText(SpeakerController.statusLine)
                .setStyle(Notification.BigTextStyle().bigText(SpeakerController.statusLine))
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }

        fun refreshNotification(ctx: Context) {
            if (!live) return
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ctx))
            } catch (_: Throwable) {}
        }
    }
}
