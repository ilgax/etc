package dev.ilgax.tabremote.protocol

/**
 * Status returned as the GATT write response status for COMMAND / PAIR writes.
 * App codes 0x90..0x98 stay in the ATT application-error range but clear Android's local
 * stack statuses (0x80..0x8F, e.g. 133 = GATT_ERROR).
 */
enum class Status(val code: Int) {
    SUCCESS(0x00),
    AUTH_FAILED(0x05),
    UNKNOWN_COMMAND(0x06),
    FAILED(0x90),
    ACCESSIBILITY_DISABLED(0x91),
    PAIRING_REJECTED(0x92),
    RATE_LIMITED(0x93),
    NOTHING_TO_CLOSE(0x94),
    SLOT_EMPTY(0x95),
    APP_START_FAILED(0x96),
    /** RUN_MACRO: another macro is still running; nothing was started. */
    MACRO_BUSY(0x97),
    /** RUN_MACRO: unknown macro id, or the macro has no steps. */
    MACRO_NOT_FOUND(0x98),
    UNKNOWN(-1);

    companion object {
        fun fromStatus(code: Int): Status = entries.firstOrNull { it != UNKNOWN && it.code == code } ?: UNKNOWN
    }
}
