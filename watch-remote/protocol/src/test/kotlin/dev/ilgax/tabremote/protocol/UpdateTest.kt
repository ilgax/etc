package dev.ilgax.tabremote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class UpdateTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun macOf(key: ByteArray): (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private val key = bytes(32)
    private val nonce = bytes(16)
    private val apk = bytes(50_000)

    private fun offer(ips: List<String> = listOf("192.168.43.1", "10.0.0.5")) =
        UpdateOffer(2, 40123, bytes(16), sha(apk), 12, ips)

    private fun valid(r: OfferFrame.Result) = (r as OfferFrame.Result.Valid).offer
    private fun failure(r: OfferFrame.Result) = (r as OfferFrame.Result.Invalid).failure

    @Test fun offerRoundTrip() {
        val o = offer(listOf("192.168.43.1", "10.0.0.5", "172.16.1.2", "192.168.1.77"))
        val f = OfferFrame.encode(macOf(key), nonce, o)
        assertTrue(f.size < 512)
        assertEquals(o, valid(OfferFrame.verify(macOf(key), nonce, f)))
    }

    @Test fun noOfferRoundTrip() {
        val f = OfferFrame.encode(macOf(key), nonce, null)
        assertNull(valid(OfferFrame.verify(macOf(key), nonce, f)))
    }

    @Test fun wrongNonceRejected() {
        val f = OfferFrame.encode(macOf(key), nonce, offer())
        assertEquals(OfferFrame.Failure.AUTH_FAILED, failure(OfferFrame.verify(macOf(key), bytes(16), f)))
    }

    @Test fun wrongKeyRejected() {
        val f = OfferFrame.encode(macOf(key), nonce, offer())
        assertEquals(OfferFrame.Failure.AUTH_FAILED, failure(OfferFrame.verify(macOf(bytes(32)), nonce, f)))
    }

    @Test fun everyTamperedByteRejected() {
        val f = OfferFrame.encode(macOf(key), nonce, offer())
        for (i in f.indices) {
            val t = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("byte $i", OfferFrame.verify(macOf(key), nonce, t) is OfferFrame.Result.Invalid)
        }
    }

    @Test fun truncatedAndExtendedRejected() {
        val f = OfferFrame.encode(macOf(key), nonce, offer())
        for (len in 0 until f.size) {
            assertTrue("len $len", OfferFrame.verify(macOf(key), nonce, f.copyOf(len)) is OfferFrame.Result.Invalid)
        }
        assertTrue(OfferFrame.verify(macOf(key), nonce, f + byteArrayOf(0)) is OfferFrame.Result.Invalid)
    }

    @Test fun offerBoundToNoOfferCannotBeSwapped() {
        // A "no offer" frame from the same nonce must not verify as an offer, and vice versa.
        val none = OfferFrame.encode(macOf(key), nonce, null)
        val some = OfferFrame.encode(macOf(key), nonce, offer())
        assertNull(valid(OfferFrame.verify(macOf(key), nonce, none)))
        assertNotNull(valid(OfferFrame.verify(macOf(key), nonce, some)))
        assertTrue(OfferFrame.verify(macOf(key), nonce, some.copyOf(none.size)) is OfferFrame.Result.Invalid)
    }

    @Test fun badOfferFieldsRefusedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) { OfferFrame.encode(macOf(key), nonce, offer(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            OfferFrame.encode(macOf(key), nonce, offer(List(5) { "10.0.0.$it" }))
        }
        assertThrows(IllegalArgumentException::class.java) { OfferFrame.encode(macOf(key), nonce, offer(listOf("10.0.0.256"))) }
        assertThrows(IllegalArgumentException::class.java) { OfferFrame.encode(macOf(key), nonce, offer(listOf("fe80::1"))) }
    }

    @Test fun ipv4Parsing() {
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), OfferFrame.parseIpv4("10.0.0.1"))
        assertNull(OfferFrame.parseIpv4("10.0.0"))
        assertNull(OfferFrame.parseIpv4("10.0.0.1.2"))
        assertNull(OfferFrame.parseIpv4("a.b.c.d"))
        assertNull(OfferFrame.parseIpv4("1.2.3.-4"))
        assertNull(OfferFrame.parseIpv4(""))
    }

    @Test fun urlAndPath() {
        val o = offer()
        assertTrue(o.path().startsWith("/watch-update/") && o.path().endsWith(".apk"))
        assertEquals("http://10.0.0.5:40123${o.path()}", o.url("10.0.0.5"))
    }

    @Test fun versionArgs() {
        assertEquals(3, UpdateCodec.encodeVersion(2).size)
        assertEquals(2, UpdateCodec.decodeVersion(UpdateCodec.encodeVersion(2)))
        assertEquals(0xABCDEF, UpdateCodec.decodeVersion(UpdateCodec.encodeVersion(0xABCDEF)))
        assertNull(UpdateCodec.decodeVersion(byteArrayOf(1, 2)))
        assertThrows(IllegalArgumentException::class.java) { UpdateCodec.encodeVersion(-1) }
        assertTrue(UpdateCodec.isNewer(2, null))
        assertTrue(UpdateCodec.isNewer(2, 1))
        assertFalse(UpdateCodec.isNewer(2, 2))
    }

    @Test fun helloFitsACommandFrame() {
        val f = CommandFrame.encode(macOf(key), nonce, Command.HELLO, UpdateCodec.encodeVersion(2))
        assertEquals(20, f.size)
        val v = CommandFrame.verify(macOf(key), nonce, f) as CommandFrame.Result.Valid
        assertEquals(Command.HELLO, v.command)
        assertEquals(2, UpdateCodec.decodeVersion(v.args))
    }

    @Test fun reportRoundTrip() {
        val r = UpdateReport.decode(UpdateReport(UpdateReport.Phase.DOWNLOADING, 42).encode())!!
        assertEquals(UpdateReport.Phase.DOWNLOADING, r.phase)
        assertEquals(42, r.value)
        assertEquals(UpdateReport.Failure.HASH_MISMATCH.id, UpdateReport.decode(UpdateReport.failed(UpdateReport.Failure.HASH_MISMATCH).encode())!!.value)
        assertNull(UpdateReport.decode(byteArrayOf(99, 0)))
        assertNull(UpdateReport.decode(byteArrayOf(1)))
    }

    // region fetch

    private fun tmp() = File.createTempFile("upd", ".apk").also { it.deleteOnExit() }
    private fun body(data: ByteArray, length: Long = data.size.toLong()) = UpdateFetch.Body(ByteArrayInputStream(data), length)

    @Test fun fetchTriesAddressesInOrderAndStopsAtFirstSuccess() {
        val o = offer(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"))
        val opened = ArrayList<String>()
        val file = tmp()
        val r = UpdateFetch.fetch(o, { url ->
            opened += url
            if (url.contains("1.1.1.1")) throw IOException("no route") else body(apk)
        }, file)
        assertEquals("2.2.2.2", (r as UpdateFetch.Outcome.Ok).address)
        assertEquals(listOf(o.url("1.1.1.1"), o.url("2.2.2.2")), opened)
        assertArrayEquals(apk, file.readBytes())
    }

    @Test fun fetchMidStreamFailureFallsThrough() {
        val o = offer(listOf("1.1.1.1", "2.2.2.2"))
        val file = tmp()
        val r = UpdateFetch.fetch(o, { url ->
            if (url.contains("1.1.1.1")) body(apk.copyOf(1000), apk.size.toLong()) else body(apk)
        }, file)
        assertEquals("2.2.2.2", (r as UpdateFetch.Outcome.Ok).address)
        assertArrayEquals(apk, file.readBytes())
    }

    @Test fun fetchHashMismatchAbortsWithoutTryingOthersAndDeletesFile() {
        val o = offer(listOf("1.1.1.1", "2.2.2.2"))
        val opened = ArrayList<String>()
        val file = tmp()
        val evil = apk.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val r = UpdateFetch.fetch(o, { url -> opened += url; body(evil) }, file)
        assertEquals(UpdateFetch.Outcome.HashMismatch, r)
        assertEquals(1, opened.size)
        assertFalse(file.exists())
    }

    @Test fun fetchAllUnreachable() {
        val o = offer(listOf("1.1.1.1", "2.2.2.2"))
        val file = tmp()
        val r = UpdateFetch.fetch(o, { throw IOException("x") }, file)
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), (r as UpdateFetch.Outcome.Unreachable).attempts)
        assertFalse(file.exists())
    }

    @Test fun fetchRefusesOversizeAndReportsProgress() {
        val o = offer(listOf("1.1.1.1"))
        assertTrue(UpdateFetch.fetch(o, { body(apk) }, tmp(), maxBytes = 100) is UpdateFetch.Outcome.Unreachable)
        val seen = ArrayList<Int>()
        UpdateFetch.fetch(o, { body(apk) }, tmp(), onProgress = { seen += it })
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    // endregion
}
