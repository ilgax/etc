package dev.ilgax.tabremote.protocol

import java.util.UUID

/** Wire constants shared by the tablet (peripheral) and the watch (central). */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("2110caf5-f06e-4820-8faa-11c0635cf5b6")
    val NONCE_UUID: UUID = UUID.fromString("8738c58a-3e11-4dab-9e21-b8bbf9b4f146")
    val COMMAND_UUID: UUID = UUID.fromString("671b7846-7889-467f-9d0f-6838b8992bb7")
    val PAIR_UUID: UUID = UUID.fromString("25608067-cd0d-42d7-8e9c-610a27ad1458")

    val APPS_UUID: UUID = UUID.fromString("5b0d1f6e-7c3a-4e92-9a51-3f8e2d6b4c07")

    const val COMPANY_ID = 0xFFFF
    const val TABLET_ID_SIZE = 4
    const val NONCE_SIZE = 16
    const val TAG_SIZE = 16
    const val MAX_ARGS = 3
    const val APP_SLOTS = 4
    const val KEY_SIZE = 32
    const val PAIRING_WINDOW_MS = 2 * 60 * 1000L
}
