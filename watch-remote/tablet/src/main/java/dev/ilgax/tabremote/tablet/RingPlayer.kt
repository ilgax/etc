package dev.ilgax.tabremote.tablet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Loops an alarm sound at max alarm volume. Main thread only. */
object RingPlayer {
    private const val AUTO_STOP_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }
    private var player: MediaPlayer? = null
    private var audio: AudioManager? = null
    private var savedVolume = -1

    fun toggle(context: Context): Boolean {
        if (player != null) {
            stop()
            return true
        }
        return start(context.applicationContext)
    }

    private fun start(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        for (uri in candidates(context)) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.prepare()
                try {
                    savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                } catch (_: SecurityException) {
                    savedVolume = -1
                }
                mp.start()
                player = mp
                audio = am
                TabletState.ringing = true
                main.postDelayed(autoStop, AUTO_STOP_MS)
                return true
            } catch (_: Exception) {
                mp.release()
            }
        }
        return false
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        val mp = player ?: return
        player = null
        try {
            mp.stop()
        } catch (_: IllegalStateException) {
        }
        mp.release()
        if (savedVolume >= 0) {
            try {
                audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: SecurityException) {
            }
        }
        savedVolume = -1
        TabletState.ringing = false
    }

    private fun candidates(context: Context): List<Uri> = listOfNotNull(
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()
}
