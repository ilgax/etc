package dev.ilgax.tabremote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class ProtocolTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun macOf(key: ByteArray): (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }

    private val key = bytes(32)
    private val nonce = bytes(16)

    private fun valid(r: CommandFrame.Result) = r as CommandFrame.Result.Valid
    private fun status(r: CommandFrame.Result) = (r as CommandFrame.Result.Invalid).status

    @Test fun roundTrip() {
        val args = byteArrayOf(1, 2, 3)
        val f = CommandFrame.encode(macOf(key), nonce, Command.VOLUME_UP, args)
        assertEquals(20, f.size)
        val v = valid(CommandFrame.verify(macOf(key), nonce, f))
        assertEquals(Command.VOLUME_UP, v.command)
        assertArrayEquals(args, v.args)
    }

    @Test fun roundTripNoArgs() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.PING)
        assertEquals(17, f.size)
        assertEquals(Command.PING, valid(CommandFrame.verify(macOf(key), nonce, f)).command)
    }

    @Test fun wrongKeyRejected() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.LOCK)
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(bytes(32)), nonce, f)))
    }

    @Test fun tamperedRejected() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.LOCK, byteArrayOf(9))
        for (i in f.indices) {
            val t = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals("byte $i", Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, t)))
        }
    }

    @Test fun replayWithDifferentNonceRejected() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.LOCK)
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), bytes(16), f)))
    }

    @Test fun badLengthsRejected() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.LOCK)
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, f.copyOf(16))))
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, ByteArray(0))))
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, ByteArray(21))))
        assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, f + 0)))
    }

    @Test fun validlySignedUnknownCommand() {
        // Hand-build a frame with command id 99, correctly signed.
        val mac = macOf(key)
        val body = byteArrayOf(99)
        val tag = mac("cmd".toByteArray() + nonce + body).copyOf(16)
        assertEquals(Status.UNKNOWN_COMMAND, status(CommandFrame.verify(mac, nonce, body + tag)))
    }

    @Test fun allCommandIdsMap() {
        Command.entries.forEach { assertEquals(it, Command.fromId(it.id)) }
        assertNull(Command.fromId(15))
        assertNull(Command.fromId(-1))
        assertEquals(Status.FAILED, Status.fromStatus(0x90))
        assertEquals(Status.UNKNOWN, Status.fromStatus(0x7F))
        for (local in listOf(0x80, 0x81, 0x85, 133, 0x8F)) assertEquals(Status.UNKNOWN, Status.fromStatus(local))
        assertTrue(Status.entries.filter { it.code > 6 }.all { it.code in 0x90..0x9F })
        Status.entries.filter { it != Status.UNKNOWN }.forEach { assertEquals(it, Status.fromStatus(it.code)) }
    }

    @Test fun pairingAgrees() {
        val w = Pairing.generateKeyPair()
        val t = Pairing.generateKeyPair()
        val wb = Pairing.encodePublicKey(w.public)
        val tb = Pairing.encodePublicKey(t.public)
        val a = Pairing.derive(w.private, Pairing.decodePublicKey(tb), wb, tb)
        val b = Pairing.derive(t.private, Pairing.decodePublicKey(wb), wb, tb)
        assertArrayEquals(a.key, b.key)
        assertEquals(a.code, b.code)
        assertEquals(32, a.key.size)
        val m = Pairing.generateKeyPair()
        val mb = Pairing.encodePublicKey(m.public)
        val c = Pairing.derive(m.private, Pairing.decodePublicKey(tb), mb, tb)
        assertFalse(a.key.contentEquals(c.key))
    }

    @Test fun publicKeyRoundTrip() {
        repeat(20) {
            val kp = Pairing.generateKeyPair()
            val enc = Pairing.encodePublicKey(kp.public)
            assertEquals(65, enc.size)
            assertEquals(0x04, enc[0].toInt())
            assertArrayEquals(enc, Pairing.encodePublicKey(Pairing.decodePublicKey(enc)))
        }
    }

    @Test fun invalidPublicKeyRejected() {
        val enc = Pairing.encodePublicKey(Pairing.generateKeyPair().public)
        assertThrows(IllegalArgumentException::class.java) { Pairing.decodePublicKey(enc.copyOf(64)) }
        val off = enc.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { Pairing.decodePublicKey(off) }
    }

    @Test fun codeAlwaysSixDigits() {
        repeat(500) {
            val c = Pairing.code(bytes(32))
            assertEquals(6, c.length)
            assertTrue(c.all { it.isDigit() })
        }
    }

    @Test fun advertisementRoundTrip() {
        val id = bytes(4)
        for (p in listOf(true, false)) {
            val enc = Advertisement.encode(id, p)
            assertEquals(5, enc.size)
            val d = Advertisement.decode(enc)!!
            assertArrayEquals(id, d.tabletId)
            assertEquals(p, d.pairing)
        }
        assertNull(Advertisement.decode(ByteArray(4)))
        assertNull(Advertisement.decode(null))
    }

    @Test fun newCommandsAndStatuses() {
        assertEquals(Command.KILL_APP, Command.fromId(6))
        assertEquals(Command.OPEN_APP, Command.fromId(7))
        assertEquals(Command.SYNC_APPS, Command.fromId(8))
        assertEquals(Command.SWITCH_APP, Command.fromId(9))
        assertEquals(Command.HELLO, Command.fromId(10))
        assertEquals(Command.GET_OFFER, Command.fromId(11))
        assertEquals(Command.UPDATE_STATE, Command.fromId(12))
        assertNull(Command.fromId(15))
        assertEquals(Status.NOTHING_TO_CLOSE, Status.fromStatus(0x94))
        assertEquals(Status.SLOT_EMPTY, Status.fromStatus(0x95))
        assertEquals(Status.APP_START_FAILED, Status.fromStatus(0x96))
    }

    @Test fun openAppFrameWithSlotArg() {
        for (slot in 0..3) {
            val f = CommandFrame.encode(macOf(key), nonce, Command.OPEN_APP, byteArrayOf(slot.toByte()))
            assertEquals(18, f.size)
            val v = valid(CommandFrame.verify(macOf(key), nonce, f))
            assertEquals(Command.OPEN_APP, v.command)
            assertArrayEquals(byteArrayOf(slot.toByte()), v.args)
            // Changing the slot invalidates the tag.
            val t = f.copyOf().also { it[1] = (slot xor 1).toByte() }
            assertEquals(Status.AUTH_FAILED, status(CommandFrame.verify(macOf(key), nonce, t)))
        }
    }

    @Test fun switchAppFrameWithSlotArg() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.SWITCH_APP, byteArrayOf(2))
        assertEquals(18, f.size)
        val v = valid(CommandFrame.verify(macOf(key), nonce, f))
        assertEquals(Command.SWITCH_APP, v.command)
        assertArrayEquals(byteArrayOf(2), v.args)
    }

    @Test fun appSlotsRoundTrip() {
        val labels = listOf("Spotify", "", "YouTube", "Maps")
        assertEquals(labels, AppSlots.decode(AppSlots.encode(labels)))
    }

    @Test fun appSlotsEmptyAndShort() {
        assertEquals(List(4) { "" }, AppSlots.decode(AppSlots.encode(emptyList())))
        assertEquals(List(4) { "" }, AppSlots.decode(null))
        assertEquals(List(4) { "" }, AppSlots.decode(ByteArray(0)))
        assertEquals(listOf("a", "", "", ""), AppSlots.decode("a".toByteArray()))
        assertEquals(3, AppSlots.encode(listOf(null, null, null, null)).size)
    }

    @Test fun appSlotsTruncation() {
        val long = "x".repeat(40)
        assertEquals("x".repeat(24), AppSlots.decode(AppSlots.encode(listOf(long)))[0])
        val emoji = "\uD83D\uDE00".repeat(30)
        val d = AppSlots.decode(AppSlots.encode(listOf(emoji)))[0]
        assertEquals(24, d.codePointCount(0, d.length))
    }

    @Test fun appSlotsNonAscii() {
        val labels = listOf("Müzik", "日本語アプリ", "Яндекс", "")
        assertEquals(labels, AppSlots.decode(AppSlots.encode(labels)))
    }

    @Test fun appSlotsMalformed() {
        val d = AppSlots.decode(byteArrayOf(0xFF.toByte(), 0xC3.toByte(), '\n'.code.toByte(), 'b'.code.toByte()))
        assertEquals(4, d.size)
        assertEquals("b", d[1])
        assertEquals(4, AppSlots.decode("a\nb\nc\nd\ne\nf".toByteArray()).size)
        assertEquals("a b", AppSlots.decode(AppSlots.encode(listOf("a\nb")))[0])
    }
}
