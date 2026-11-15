package dev.ilgax.tabremote.protocol

import java.security.MessageDigest

/** Frame layout: cmdId(1) | args(0..3) | tag(16). Fits the default 20-byte ATT payload. */
object CommandFrame {
    private val PREFIX = "cmd".toByteArray(Charsets.US_ASCII)
    const val MIN_SIZE = 1 + Protocol.TAG_SIZE
    const val MAX_SIZE = 1 + Protocol.MAX_ARGS + Protocol.TAG_SIZE

    sealed interface Result {
        class Valid(val command: Command, val args: ByteArray) : Result
        class Invalid(val status: Status) : Result
    }

    /** [mac] computes a full HMAC-SHA256 over its input. */
    fun encode(
        mac: (ByteArray) -> ByteArray,
        nonce: ByteArray,
        cmd: Command,
        args: ByteArray = ByteArray(0),
    ): ByteArray {
        require(args.size <= Protocol.MAX_ARGS) { "too many args" }
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        val body = byteArrayOf(cmd.id.toByte()) + args
        return body + tag(mac, nonce, body)
    }

    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, frame: ByteArray): Result {
        if (frame.size !in MIN_SIZE..MAX_SIZE || nonce.size != Protocol.NONCE_SIZE) {
            return Result.Invalid(Status.AUTH_FAILED)
        }
        val body = frame.copyOfRange(0, frame.size - Protocol.TAG_SIZE)
        val given = frame.copyOfRange(frame.size - Protocol.TAG_SIZE, frame.size)
        val expected = tag(mac, nonce, body)
        if (!MessageDigest.isEqual(expected, given)) return Result.Invalid(Status.AUTH_FAILED)
        val cmd = Command.fromId(body[0].toInt() and 0xFF) ?: return Result.Invalid(Status.UNKNOWN_COMMAND)
        return Result.Valid(cmd, body.copyOfRange(1, body.size))
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)
}
