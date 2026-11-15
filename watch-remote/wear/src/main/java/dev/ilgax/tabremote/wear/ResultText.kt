package dev.ilgax.tabremote.wear

import android.content.Context
import dev.ilgax.tabremote.protocol.Command

object ResultText {
    /** [label] is the slot's app label for OPEN_APP / SWITCH_APP. */
    fun describe(context: Context, result: Result, command: Command?, label: String? = null): String {
        val res = when (result) {
            Result.SUCCESS -> return success(context, command, label)
            Result.NOT_FOUND -> R.string.res_not_found
            Result.CONNECT_FAILED -> R.string.res_connect_failed
            Result.TIMEOUT -> R.string.res_timeout
            Result.BLUETOOTH_OFF -> R.string.res_bluetooth_off
            Result.NO_PERMISSION -> R.string.res_no_permission
            Result.NOT_PAIRED -> R.string.res_not_paired
            Result.CANCELLED -> R.string.res_cancelled
            Result.AUTH_FAILED -> R.string.res_auth_failed
            Result.RATE_LIMITED -> R.string.res_rate_limited
            Result.ACCESSIBILITY_DISABLED -> R.string.res_accessibility
            Result.PAIRING_REJECTED -> R.string.res_pairing_rejected
            Result.NOTHING_TO_CLOSE -> R.string.res_nothing_to_close
            Result.SLOT_EMPTY -> R.string.res_slot_empty
            Result.APP_START_FAILED -> R.string.res_app_start_failed
            Result.UNKNOWN_COMMAND, Result.FAILED, Result.UNKNOWN -> R.string.res_failed
        }
        return context.getString(res)
    }

    private fun success(context: Context, command: Command?, label: String?): String = when (command) {
        Command.LOCK -> context.getString(R.string.res_locked)
        Command.KILL_APP -> context.getString(R.string.res_closed)
        Command.OPEN_APP -> label?.let { context.getString(R.string.res_opened, it) } ?: context.getString(R.string.res_done)
        Command.SWITCH_APP -> label?.let { context.getString(R.string.res_switched, it) } ?: context.getString(R.string.res_done)
        else -> context.getString(R.string.res_done)
    }
}
