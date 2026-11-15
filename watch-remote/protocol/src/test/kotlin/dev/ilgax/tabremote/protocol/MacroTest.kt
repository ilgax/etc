package dev.ilgax.tabremote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class MacroTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun macOf(key: ByteArray): (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }

    private val key = bytes(32)
    private val nonce = bytes(16)
    private val mac = macOf(key)

    private fun valid(r: MacroList.Result) = (r as MacroList.Result.Valid).page
    private fun failure(r: MacroList.Result) = (r as MacroList.Result.Invalid).failure

    private val turkish = "İstanbul Şişli Ğ ığdır çö"
    private fun items(n: Int, name: (Int) -> String = { "Macro $it" }) = List(n) { MacroItem(it + 1, name(it)) }

    /** Pulls every page the way the watch does. */
    private fun readAll(list: List<MacroItem>, version: Int = 77): Pair<List<MacroItem>, Int> {
        val got = ArrayList<MacroItem>()
        var page = 0
        var pages = 0
        while (true) {
            val n = bytes(16)
            val frame = MacroList.encode(mac, n, version, list, page)
            assertTrue("page $page is ${frame.size} bytes", frame.size <= 512)
            assertTrue(frame.size <= MacroList.MAX_PAGE_BYTES)
            val p = valid(MacroList.verify(mac, n, frame))
            assertEquals(page, p.page)
            assertEquals(version, p.version)
            assertEquals(list.size, p.total)
            got += p.items
            pages++
            if (!p.hasMore) break
            page++
        }
        return got to pages
    }

    @Test fun emptyListIsOneEmptyPage() {
        val (got, pages) = readAll(emptyList())
        assertTrue(got.isEmpty())
        assertEquals(1, pages)
    }

    @Test fun singleItem() {
        val (got, pages) = readAll(items(1))
        assertEquals(items(1), got)
        assertEquals(1, pages)
    }

    @Test fun manyItemsPageAndRoundTrip() {
        val list = items(1000)
        val (got, pages) = readAll(list)
        assertEquals(list, got)
        assertTrue("expected several pages, got $pages", pages > 5)
    }

    @Test fun longTurkishNamesRoundTrip() {
        val list = List(300) { MacroItem(it * 7 + 1, MacroList.cleanName("$turkish ${it}ŞĞİ")) }
        val (got, pages) = readAll(list)
        assertEquals(list, got)
        assertTrue(pages > 1)
        // 24 code points of 2-byte letters: names are cut by code points, never inside a character.
        assertTrue(got.all { it.name.codePointCount(0, it.name.length) <= MacroList.MAX_NAME })
    }

    @Test fun maxNamesOfFourByteCodePointsStillFit() {
        val emoji = "😀".repeat(24)
        val list = List(40) { MacroItem(it, emoji) }
        val (got, _) = readAll(list)
        assertEquals(list.size, got.size)
        assertEquals(emoji, got[0].name)
    }

    @Test fun overlongNamesAreCutOnEncode() {
        val long = "Ş".repeat(80)
        val frame = MacroList.encode(mac, nonce, 1, listOf(MacroItem(5, long)), 0)
        val p = valid(MacroList.verify(mac, nonce, frame))
        assertEquals("Ş".repeat(24), p.items.single().name)
    }

    @Test fun pageBeyondTheEndIsAnEmptyLastPage() {
        val frame = MacroList.encode(mac, nonce, 3, items(5), 9)
        val p = valid(MacroList.verify(mac, nonce, frame))
        assertEquals(9, p.page)
        assertTrue(p.items.isEmpty())
        assertFalse(p.hasMore)
        assertEquals(5, p.total)
    }

    @Test fun wrongNonceAndKeyRejected() {
        val f = MacroList.encode(mac, nonce, 1, items(3), 0)
        assertEquals(MacroList.Failure.AUTH_FAILED, failure(MacroList.verify(mac, bytes(16), f)))
        assertEquals(MacroList.Failure.AUTH_FAILED, failure(MacroList.verify(macOf(bytes(32)), nonce, f)))
    }

    @Test fun everyTamperedByteRejected() {
        val f = MacroList.encode(mac, nonce, 1, items(6) { turkish }, 0)
        for (i in f.indices) {
            val t = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("byte $i", MacroList.verify(mac, nonce, t) is MacroList.Result.Invalid)
        }
    }

    @Test fun truncatedAndExtendedRejected() {
        val f = MacroList.encode(mac, nonce, 1, items(6) { turkish }, 0)
        for (len in 0 until f.size) {
            assertTrue("len $len", MacroList.verify(mac, nonce, f.copyOf(len)) is MacroList.Result.Invalid)
        }
        assertTrue(MacroList.verify(mac, nonce, f + byteArrayOf(0)) is MacroList.Result.Invalid)
        // Dropping a byte from the middle and fixing nothing else must fail too.
        val cut = f.copyOfRange(0, 10) + f.copyOfRange(11, f.size)
        assertTrue(MacroList.verify(mac, nonce, cut) is MacroList.Result.Invalid)
    }

    @Test fun pageFromAnotherNonceCannotBeReplayed() {
        val a = MacroList.encode(mac, bytes(16), 1, items(2), 0)
        assertTrue(MacroList.verify(mac, nonce, a) is MacroList.Result.Invalid)
    }

    @Test fun validlySignedButMalformedBodiesRejected() {
        fun signed(body: ByteArray) = body + mac(("mcl".toByteArray() + nonce + body)).copyOf(MacroList.TAG_SIZE)
        // n = 1 but no item bytes
        val noItem = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0)
        assertEquals(MacroList.Failure.MALFORMED, failure(MacroList.verify(mac, nonce, signed(noItem))))
        // invalid UTF-8 in the name (0xC0 0x80)
        val badUtf = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 7, 2, 0xC0.toByte(), 0x80.toByte(), 0)
        assertEquals(MacroList.Failure.MALFORMED, failure(MacroList.verify(mac, nonce, signed(badUtf))))
        // hasMore = 2
        val badMore = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2)
        assertEquals(MacroList.Failure.MALFORMED, failure(MacroList.verify(mac, nonce, signed(badMore))))
        // unknown format
        val badFormat = byteArrayOf(9, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(MacroList.Failure.MALFORMED, failure(MacroList.verify(mac, nonce, signed(badFormat))))
        // trailing garbage after hasMore
        val trailing = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 5)
        assertEquals(MacroList.Failure.MALFORMED, failure(MacroList.verify(mac, nonce, signed(trailing))))
        // sanity: the same layout without the garbage is fine
        assertTrue(MacroList.verify(mac, nonce, signed(byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0))) is MacroList.Result.Valid)
    }

    @Test fun versionChangesWithRevisionAndContent() {
        val a = items(3)
        val v = MacroList.versionOf(1, a)
        assertEquals(v, MacroList.versionOf(1, a))
        assertNotEquals(v, MacroList.versionOf(2, a))
        assertNotEquals(v, MacroList.versionOf(1, a.dropLast(1)))
        assertNotEquals(v, MacroList.versionOf(1, a.mapIndexed { i, m -> if (i == 0) m.copy(name = "Other") else m }))
    }

    @Test fun cleanNameTrimsAndStripsControls() {
        assertEquals("a b", MacroList.cleanName("  a\nb \t"))
        assertEquals("", MacroList.cleanName("\n \r"))
    }

    @Test fun runMacroFraming() {
        for (id in listOf(0, 1, 255, 256, 65535, 70000, MacroList.MAX_ID)) {
            val f = CommandFrame.encode(mac, nonce, Command.RUN_MACRO, MacroList.encodeId(id))
            assertEquals(20, f.size) // still one default ATT payload
            val v = (CommandFrame.verify(mac, nonce, f) as CommandFrame.Result.Valid)
            assertEquals(Command.RUN_MACRO, v.command)
            assertEquals(id, MacroList.decodeId(v.args))
            val t = f.copyOf().also { it[2] = (it[2].toInt() xor 1).toByte() }
            assertTrue(CommandFrame.verify(mac, nonce, t) is CommandFrame.Result.Invalid)
        }
        assertEquals(3, MacroList.decodeId(byteArrayOf(0, 3)))
        assertNull(MacroList.decodeId(ByteArray(0)))
        assertNull(MacroList.decodeId(ByteArray(4)))
        assertThrows(IllegalArgumentException::class.java) { MacroList.encodeId(-1) }
        assertThrows(IllegalArgumentException::class.java) { MacroList.encodeId(MacroList.MAX_ID + 1) }
    }

    @Test fun syncMacrosFramingAndPageArgs() {
        for (page in listOf(0, 1, 300, 0xFFFF)) {
            val f = CommandFrame.encode(mac, nonce, Command.SYNC_MACROS, MacroList.encodePageArgs(page))
            val v = (CommandFrame.verify(mac, nonce, f) as CommandFrame.Result.Valid)
            assertEquals(Command.SYNC_MACROS, v.command)
            assertEquals(page, MacroList.decodePageArgs(v.args))
        }
        assertNull(MacroList.decodePageArgs(ByteArray(1)))
        assertNull(MacroList.decodePageArgs(ByteArray(3)))
    }

    @Test fun newIdsAndCodes() {
        assertEquals(Command.RUN_MACRO, Command.fromId(13))
        assertEquals(Command.SYNC_MACROS, Command.fromId(14))
        assertEquals(Status.MACRO_BUSY, Status.fromStatus(0x97))
        assertEquals(Status.MACRO_NOT_FOUND, Status.fromStatus(0x98))
        assertEquals(0x97, Status.MACRO_BUSY.code)
    }
}
