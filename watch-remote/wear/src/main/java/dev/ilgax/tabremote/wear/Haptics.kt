package dev.ilgax.tabremote.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

object Haptics {
    fun success(context: Context) = vibrate(context, VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

    fun failure(context: Context) = vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))

    private fun vibrate(context: Context, effect: VibrationEffect) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
    }
}
