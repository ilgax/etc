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
    SWITCH_APP(9),
    /** 3 arg bytes: the watch app's versionCode, big-endian (see [UpdateCodec.encodeVersion]). Also tells the tablet the watch speaks the update protocol. */
    HELLO(10),
    /** Grants one authenticated read of the OFFER characteristic; the payload is bound to this frame's nonce. */
    GET_OFFER(11),
    /** 2 arg bytes: [UpdateReport] phase and value, so the tablet can show the watch's update progress. */
    UPDATE_STATE(12);

    companion object {
        fun fromId(id: Int): Command? = entries.firstOrNull { it.id == id }
    }
}
