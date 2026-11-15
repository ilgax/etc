package dev.ilgax.speakerkillswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SpeakerController.isEnabled(context)) return
        SpeakerController.log(context, "boot completed, starting service")
        try {
            ContextCompat.startForegroundService(context, Intent(context, KillSwitchService::class.java))
        } catch (t: Throwable) {
            SpeakerController.error(context, "boot start service", t)
        }
    }
}
