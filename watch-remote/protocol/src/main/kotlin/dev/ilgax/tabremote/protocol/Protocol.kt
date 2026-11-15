package dev.ilgax.tabremote.protocol

import java.util.UUID

/** Wire constants shared by the tablet (peripheral) and the watch (central). */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("2110caf5-f06e-4820-8faa-11c0635cf5b6")
    val NONCE_UUID: UUID = UUID.fromString("8738c58a-3e11-4dab-9e21-b8bbf9b4f146")
    val COMMAND_UUID: UUID = UUID.fromString("671b7846-7889-467f-9d0f-6838b8992bb7")
    val PAIR_UUID: UUID = UUID.fromString("25608067-cd0d-42d7-8e9c-610a27ad1458")

    val APPS_UUID: UUID = UUID.fromString("5b0d1f6e-7c3a-4e92-9a51-3f8e2d6b4c07")

    /** Readable (long read) + notify. Read: authenticated update offer after GET_OFFER. Notify: unauthenticated "check for an offer" ping. */
    val OFFER_UUID: UUID = UUID.fromString("9c4e7a12-3b58-4f6d-8e21-6a0d5b7c9f34")
    /** Readable (long read): one authenticated page of the macro list after SYNC_MACROS. Absent on Utils builds without macros. */
    val MACROS_UUID: UUID = UUID.fromString("e1c2a7d4-5b39-4a86-bd10-72f3c8a94e55")

    /** Standard Client Characteristic Configuration descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val COMPANY_ID = 0xFFFF
    const val TABLET_ID_SIZE = 4
    const val NONCE_SIZE = 16
    const val TAG_SIZE = 16
    const val MAX_ARGS = 3
    const val APP_SLOTS = 4
    /** First byte of an OFFER notification: "check for an update offer". */
    const val PING_OFFER: Byte = 1
    /** First byte of an OFFER notification: "the macro list changed, sync it". Old watches treat any ping as an offer check. */
    const val PING_MACROS: Byte = 2
    const val KEY_SIZE = 32
    const val PAIRING_WINDOW_MS = 2 * 60 * 1000L
}
