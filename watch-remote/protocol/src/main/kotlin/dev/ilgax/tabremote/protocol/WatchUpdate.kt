package dev.ilgax.tabremote.protocol

import java.security.MessageDigest

/**
 * Watch app update offer: where to download the APK and what it must hash to.
 * [addresses] are dotted IPv4 strings (at most [OfferFrame.MAX_ADDRESSES]); [ageSec] is how long ago the user
 * tapped Update on the tablet.
 */
class UpdateOffer(
    val versionCode: Int,
    val port: Int,
    val token: ByteArray,
    val sha256: ByteArray,
    val ageSec: Int,
    val addresses: List<String>,
) {
    /** The only path the tablet's HTTP server answers. */
    fun path(): String = pathFor(token)

    fun url(address: String): String = "http://$address:$port${path()}"

    override fun equals(other: Any?): Boolean = other is UpdateOffer && versionCode == other.versionCode &&
        port == other.port && token.contentEquals(other.token) && sha256.contentEquals(other.sha256) &&
        ageSec == other.ageSec && addresses == other.addresses

    override fun hashCode(): Int = versionCode * 31 + port

    companion object {
        fun pathFor(token: ByteArray): String = "/watch-update/" + hex(token) + ".apk"
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}

/**
 * OFFER characteristic payload:
 * `format(1) | flags(1) | [versionCode(4) | port(2) | ageSec(2) | token(16) | sha256(32) | n(1) | n * ipv4(4)] | tag(32)`.
 * flags bit0 = an offer follows (otherwise "no offer"). tag = HMAC-SHA256(key, "ofr" | nonce | everything before the tag),
 * where nonce is the one the watch used for the GET_OFFER frame that granted this read. Fits one long read (< 512 bytes).
 */
object OfferFrame {
    const val FORMAT = 1
    const val MAX_ADDRESSES = 4
    const val TOKEN_SIZE = 16
    const val SHA_SIZE = 32
    const val TAG_SIZE = 32
    private const val HEADER = 2
    private const val FIXED = 4 + 2 + 2 + TOKEN_SIZE + SHA_SIZE + 1
    private val PREFIX = "ofr".toByteArray(Charsets.US_ASCII)

    enum class Failure { MALFORMED, AUTH_FAILED }

    sealed interface Result {
        /** [offer] is null for an authenticated "no offer". */
        class Valid(val offer: UpdateOffer?) : Result
        class Invalid(val failure: Failure) : Result
    }

    fun encode(mac: (ByteArray) -> ByteArray, nonce: ByteArray, offer: UpdateOffer?): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        val body = if (offer == null) {
            byteArrayOf(FORMAT.toByte(), 0)
        } else {
            require(offer.token.size == TOKEN_SIZE && offer.sha256.size == SHA_SIZE) { "bad token or hash" }
            require(offer.port in 1..0xFFFF && offer.ageSec in 0..0xFFFF && offer.versionCode >= 0) { "bad field" }
            require(offer.addresses.size in 1..MAX_ADDRESSES) { "bad address count" }
            val ips = offer.addresses.map { parseIpv4(it) ?: throw IllegalArgumentException("bad address") }
            byteArrayOf(FORMAT.toByte(), 1) + u32(offer.versionCode) + u16(offer.port) + u16(offer.ageSec) +
                offer.token + offer.sha256 + byteArrayOf(ips.size.toByte()) + ips.fold(ByteArray(0)) { a, b -> a + b }
        }
        return body + tag(mac, nonce, body)
    }

    /** Authenticates before parsing anything. */
    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, payload: ByteArray): Result {
        if (nonce.size != Protocol.NONCE_SIZE || payload.size < HEADER + TAG_SIZE) return Result.Invalid(Failure.MALFORMED)
        val body = payload.copyOfRange(0, payload.size - TAG_SIZE)
        val given = payload.copyOfRange(payload.size - TAG_SIZE, payload.size)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return Result.Invalid(Failure.AUTH_FAILED)
        if (body[0].toInt() != FORMAT) return Result.Invalid(Failure.MALFORMED)
        val present = (body[1].toInt() and 1) != 0
        if (!present) return if (body.size == HEADER) Result.Valid(null) else Result.Invalid(Failure.MALFORMED)
        if (body.size < HEADER + FIXED) return Result.Invalid(Failure.MALFORMED)
        var o = HEADER
        val version = readU32(body, o).also { o += 4 }
        val port = readU16(body, o).also { o += 2 }
        val age = readU16(body, o).also { o += 2 }
        val token = body.copyOfRange(o, o + TOKEN_SIZE).also { o += TOKEN_SIZE }
        val sha = body.copyOfRange(o, o + SHA_SIZE).also { o += SHA_SIZE }
        val n = body[o++].toInt() and 0xFF
        if (n !in 1..MAX_ADDRESSES || body.size != o + n * 4 || version < 0 || port == 0) {
            return Result.Invalid(Failure.MALFORMED)
        }
        val ips = List(n) { i -> (0 until 4).joinToString(".") { j -> (body[o + i * 4 + j].toInt() and 0xFF).toString() } }
        return Result.Valid(UpdateOffer(version, port, token, sha, age, ips))
    }

    /** Strict dotted quad; null for anything else. */
    fun parseIpv4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(TAG_SIZE)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun readU16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}

/** Args of HELLO and UPDATE_STATE. */
object UpdateCodec {
    const val MAX_VERSION = 0xFFFFFF

    /** versionCode as 3 big-endian bytes (fits [Protocol.MAX_ARGS]). */
    fun encodeVersion(versionCode: Int): ByteArray {
        require(versionCode in 0..MAX_VERSION) { "version out of range" }
        return byteArrayOf((versionCode ushr 16).toByte(), (versionCode ushr 8).toByte(), versionCode.toByte())
    }

    /** Null unless exactly 3 bytes. */
    fun decodeVersion(args: ByteArray): Int? =
        if (args.size != 3) null
        else ((args[0].toInt() and 0xFF) shl 16) or ((args[1].toInt() and 0xFF) shl 8) or (args[2].toInt() and 0xFF)

    /** True if the offered build is newer than what the watch runs (or the watch version is unknown). */
    fun isNewer(bundled: Int, reported: Int?): Boolean = reported == null || bundled > reported
}

/** Progress the watch reports with UPDATE_STATE: [phase] plus a [value] (percent for DOWNLOADING, [Failure] id for FAILED). */
class UpdateReport(val phase: Phase, val value: Int) {
    enum class Phase(val id: Int) {
        DOWNLOADING(1), VERIFYING(2), INSTALLING(3), FAILED(4);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id }
        }
    }

    enum class Failure(val id: Int) {
        UNKNOWN(0), NO_WIFI(1), DOWNLOAD(2), HASH_MISMATCH(3), INSTALL_BLOCKED(4), INSTALL_FAILED(5);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: UNKNOWN
        }
    }

    fun encode(): ByteArray = byteArrayOf(phase.id.toByte(), value.coerceIn(0, 255).toByte())

    companion object {
        fun decode(args: ByteArray): UpdateReport? {
            if (args.size != 2) return null
            val phase = Phase.fromId(args[0].toInt() and 0xFF) ?: return null
            return UpdateReport(phase, args[1].toInt() and 0xFF)
        }

        fun failed(reason: Failure) = UpdateReport(Phase.FAILED, reason.id)
    }
}
