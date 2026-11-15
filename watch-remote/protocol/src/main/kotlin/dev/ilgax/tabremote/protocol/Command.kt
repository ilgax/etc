package dev.ilgax.tabremote.protocol

enum class Command(val id: Int) {
    PING(0),
    LOCK(1),
    PLAY_PAUSE(2),
    VOLUME_UP(3),
    VOLUME_DOWN(4),
    RING(5),
    KILL_APP(6),
    /** One arg byte: slot index 0..3. */
    OPEN_APP(7),
    SYNC_APPS(8),
    /** One arg byte: slot index 0..3. Closes the foreground app (if any), then opens the slot app. */
    SWITCH_APP(9);

    companion object {
        fun fromId(id: Int): Command? = entries.firstOrNull { it.id == id }
    }
}
