package dev.ilgax.tabremote.protocol

/** Manufacturer data payload (after the company id): tabletId(4) | flags(1), bit0 = pairing mode. */
class Advertisement(val tabletId: ByteArray, val pairing: Boolean) {
    fun encode(): ByteArray = encode(tabletId, pairing)

    companion object {
        const val SIZE = Protocol.TABLET_ID_SIZE + 1

        fun encode(tabletId: ByteArray, pairing: Boolean): ByteArray {
            require(tabletId.size == Protocol.TABLET_ID_SIZE) { "bad tablet id" }
            return tabletId + byteArrayOf(if (pairing) 1 else 0)
        }

        fun decode(data: ByteArray?): Advertisement? {
            if (data == null || data.size < SIZE) return null
            return Advertisement(
                data.copyOfRange(0, Protocol.TABLET_ID_SIZE),
                (data[Protocol.TABLET_ID_SIZE].toInt() and 1) != 0,
            )
        }
    }
}
