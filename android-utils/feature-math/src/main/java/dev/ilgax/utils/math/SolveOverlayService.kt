package dev.ilgax.utils.math

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Hosts the floating result card as a TYPE_APPLICATION_OVERLAY window when the accessibility overlay is not
 * available. A foreground service keeps the process (and the recognition running in it) alive after the
 * Solve activity has finished, and makes starting it from the visible activity legal on Android 12+.
 * It lives exactly as long as the card: closing the card stops it.
 */
class SolveOverlayService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.math_overlay_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = SolveController.get(this)
        if (intent?.action == ACTION_STOP) {
            controller.close()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!controller.attachToService(this)) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SolveController.get(this).onServiceGone(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "solve_overlay"
        private const val NOTIF_ID = 31
        private const val ACTION_STOP = "dev.ilgax.utils.math.STOP_OVERLAY"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SolveOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SolveOverlayService::class.java))
        }

        private fun notification(ctx: Context): Notification {
            val stop = PendingIntent.getService(
                ctx, 0, Intent(ctx, SolveOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_math)
                .setContentTitle(ctx.getString(R.string.math_overlay_notification_title))
                .setContentText(ctx.getString(R.string.math_overlay_notification_text))
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.math_card_close), stop).build())
                .build()
        }
    }
}
