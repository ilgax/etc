package dev.ilgax.tabremote.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** ECDH P-256 key agreement plus numeric-comparison code. */
object Pairing {
    const val PUBLIC_KEY_SIZE = 65
    private const val COORD = 32
    private val SALT = "tabremote-pair-v1".toByteArray(Charsets.US_ASCII)
    private val CODE_LABEL = "code".toByteArray(Charsets.US_ASCII)

    class Derived(val key: ByteArray, val code: String)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encodePublicKey(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(point.affineX) + fixed(point.affineY)
    }

    fun decodePublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == PUBLIC_KEY_SIZE && bytes[0] == 0x04.toByte()) { "bad public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORD, PUBLIC_KEY_SIZE))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val field = (params.curve.field as java.security.spec.ECFieldFp).p
        val onCurve = x < field && y < field &&
            y.pow(2).mod(field) == (x.pow(3) + params.curve.a * x + params.curve.b).mod(field)
        require(onCurve) { "point not on curve" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** Both sides call this with their own private key and the peer's public key; byte order is fixed (watch, tablet). */
    fun derive(own: PrivateKey, peer: PublicKey, watchPub: ByteArray, tabletPub: ByteArray): Derived {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
        val key = Hmac.sha256(SALT, shared + watchPub + tabletPub)
        return Derived(key, code(key))
    }

    fun code(key: ByteArray): String {
        val h = Hmac.sha256(key, CODE_LABEL)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 1_000_000L).toString().padStart(6, '0')
    }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(COORD)
        val len = minOf(raw.size, COORD)
        System.arraycopy(raw, raw.size - len, out, COORD - len, len)
        return out
    }
}
