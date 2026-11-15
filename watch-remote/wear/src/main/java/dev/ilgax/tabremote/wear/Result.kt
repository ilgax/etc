package dev.ilgax.tabremote.wear

import dev.ilgax.tabremote.protocol.Status

/** Outcome of a remote operation: local failures plus the tablet's protocol statuses. */
enum class Result {
    SUCCESS, NOT_FOUND, CONNECT_FAILED, TIMEOUT, BLUETOOTH_OFF, NO_PERMISSION, NOT_PAIRED, CANCELLED,
    AUTH_FAILED, UNKNOWN_COMMAND, FAILED, ACCESSIBILITY_DISABLED, PAIRING_REJECTED, RATE_LIMITED,
    NOTHING_TO_CLOSE, SLOT_EMPTY, APP_START_FAILED, MACRO_BUSY, MACRO_NOT_FOUND, UNKNOWN;

    companion object {
        fun from(status: Status): Result = when (status) {
            Status.SUCCESS -> SUCCESS
            Status.AUTH_FAILED -> AUTH_FAILED
            Status.UNKNOWN_COMMAND -> UNKNOWN_COMMAND
            Status.FAILED -> FAILED
            Status.ACCESSIBILITY_DISABLED -> ACCESSIBILITY_DISABLED
            Status.PAIRING_REJECTED -> PAIRING_REJECTED
            Status.RATE_LIMITED -> RATE_LIMITED
            Status.NOTHING_TO_CLOSE -> NOTHING_TO_CLOSE
            Status.SLOT_EMPTY -> SLOT_EMPTY
            Status.APP_START_FAILED -> APP_START_FAILED
            Status.MACRO_BUSY -> MACRO_BUSY
            Status.MACRO_NOT_FOUND -> MACRO_NOT_FOUND
            Status.UNKNOWN -> UNKNOWN
        }
    }
}
