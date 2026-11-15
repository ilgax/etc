package dev.ilgax.tabremote.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** One macro as the watch sees it: its stable id and display name. The steps never leave the tablet. */
data class MacroItem(val id: Int, val name: String)

/**
 * Macro list over BLE. Utils configures macros; the watch only lists names and asks to run one by id.
 *
 * - RUN_MACRO args: the id as 3 big-endian bytes ([encodeId]), so the whole frame is the usual 20 bytes.
 * - SYNC_MACROS args: the page index as 2 big-endian bytes ([encodePageArgs]). It grants one authenticated read of the
 *   MACROS characteristic, bound to that frame's nonce (like GET_OFFER / OFFER).
 * - MACROS payload (one page):
 *   `format(1) | version(4) | total(4) | page(2) | n(1) | n * (id(3) | nameLen(1) | name UTF-8) | hasMore(1) | tag(32)`.
 *   tag = HMAC-SHA256(key, "mcl" | nonce | everything before the tag). A page never exceeds [MAX_PAGE_BYTES] (long reads cap at 512).
 *   [version] changes whenever Utils edits the list, so the watch can stop after page 0 when it already has that version.
 *   A page index past the end is a valid empty page with hasMore = 0.
 */
object MacroList {
    const val FORMAT = 1
    const val MAX_ID = 0xFFFFFF
    const val MAX_NAME = 24
    const val TAG_SIZE = 32
    const val MAX_PAGE_BYTES = 480
    /** Hard cap the watch applies while paging, so a misbehaving tablet cannot keep it reading forever. */
    const val MAX_PAGES = 400
    private const val HEADER = 1 + 4 + 4 + 2 + 1
    private const val MAX_NAME_BYTES = MAX_NAME * 4
    private val PREFIX = "mcl".toByteArray(Charsets.US_ASCII)

    /** Control characters become spaces, the name is trimmed and cut to [MAX_NAME] code points. */
    fun cleanName(name: String): String {
        val t = buildString {
            for (ch in name) append(if (ch.isISOControl()) ' ' else ch)
        }.trim()
        val cut = if (t.codePointCount(0, t.length) > MAX_NAME) t.substring(0, t.offsetByCodePoints(0, MAX_NAME)) else t
        return cut.trim()
    }

    // region args

    fun encodeId(id: Int): ByteArray {
        require(id in 0..MAX_ID) { "macro id out of range" }
        return byteArrayOf((id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
    }

    /** Null unless 2 or 3 bytes (a 2-byte id is accepted for forward flexibility). */
    fun decodeId(args: ByteArray): Int? = when (args.size) {
        3 -> ((args[0].toInt() and 0xFF) shl 16) or ((args[1].toInt() and 0xFF) shl 8) or (args[2].toInt() and 0xFF)
        2 -> ((args[0].toInt() and 0xFF) shl 8) or (args[1].toInt() and 0xFF)
        else -> null
    }

    fun encodePageArgs(page: Int): ByteArray {
        require(page in 0..0xFFFF) { "page out of range" }
        return byteArrayOf((page ushr 8).toByte(), page.toByte())
    }

    /** Null unless exactly 2 bytes. */
    fun decodePageArgs(args: ByteArray): Int? =
        if (args.size != 2) null else ((args[0].toInt() and 0xFF) shl 8) or (args[1].toInt() and 0xFF)

    // endregion

    /**
     * A 32-bit version of the list. [revision] is Utils' edit counter; mixing it in means any edit (even one that does not
     * change a name) is a new version, while a cleared app-data reset still differs from an old cache because the names are hashed too.
     */
    fun versionOf(revision: Int, items: List<MacroItem>): Int {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(byteArrayOf((revision ushr 24).toByte(), (revision ushr 16).toByte(), (revision ushr 8).toByte(), revision.toByte()))
        for (item in items) {
            md.update(byteArrayOf((item.id ushr 16).toByte(), (item.id ushr 8).toByte(), item.id.toByte()))
            md.update(cleanName(item.name).toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        val d = md.digest()
        return ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
    }

    /** Greedy split into pages that each fit [MAX_PAGE_BYTES]. Always at least one (possibly empty) page. */
    fun paginate(items: List<MacroItem>): List<List<MacroItem>> {
        val pages = ArrayList<List<MacroItem>>()
        var current = ArrayList<MacroItem>()
        var size = HEADER + 1 + TAG_SIZE
        for (item in items) {
            val itemSize = 4 + cleanName(item.name).toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && (size + itemSize > MAX_PAGE_BYTES || current.size >= 255)) {
                pages += current
                current = ArrayList()
                size = HEADER + 1 + TAG_SIZE
            }
            current += item
            size += itemSize
        }
        pages += current
        return pages
    }

    /** One page of [items] (the whole list; paging happens here so server and tests agree on the split). */
    fun encode(mac: (ByteArray) -> ByteArray, nonce: ByteArray, version: Int, items: List<MacroItem>, page: Int): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        require(page in 0..0xFFFF) { "bad page" }
        require(items.all { it.id in 0..MAX_ID }) { "bad macro id" }
        val pages = paginate(items)
        val slice = pages.getOrNull(page) ?: emptyList()
        val out = java.io.ByteArrayOutputStream()
        out.write(FORMAT)
        out.write(u32(version))
        out.write(u32(items.size))
        out.write(page ushr 8); out.write(page)
        out.write(slice.size)
        for (item in slice) {
            val name = cleanName(item.name).toByteArray(Charsets.UTF_8)
            out.write(u24(item.id))
            out.write(name.size)
            out.write(name)
        }
        out.write(if (page + 1 < pages.size) 1 else 0)
        val body = out.toByteArray()
        return body + tag(mac, nonce, body)
    }

    class Page(val version: Int, val total: Int, val page: Int, val items: List<MacroItem>, val hasMore: Boolean)

    enum class Failure { MALFORMED, AUTH_FAILED }

    sealed interface Result {
        class Valid(val page: Page) : Result
        class Invalid(val failure: Failure) : Result
    }

    /** Authenticates before parsing anything. */
    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, payload: ByteArray): Result {
        if (nonce.size != Protocol.NONCE_SIZE || payload.size < HEADER + 1 + TAG_SIZE || payload.size > 512) {
            return Result.Invalid(Failure.MALFORMED)
        }
        val body = payload.copyOfRange(0, payload.size - TAG_SIZE)
        val given = payload.copyOfRange(payload.size - TAG_SIZE, payload.size)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return Result.Invalid(Failure.AUTH_FAILED)
        if ((body[0].toInt() and 0xFF) != FORMAT) return Result.Invalid(Failure.MALFORMED)
        var o = 1
        val version = readU32(body, o).also { o += 4 }
        val total = readU32(body, o).also { o += 4 }
        val page = readU16(body, o).also { o += 2 }
        val n = body[o++].toInt() and 0xFF
        if (total < 0) return Result.Invalid(Failure.MALFORMED)
        val items = ArrayList<MacroItem>(n)
        repeat(n) {
            if (o + 4 > body.size) return Result.Invalid(Failure.MALFORMED)
            val id = (readU16(body, o) shl 8) or (body[o + 2].toInt() and 0xFF)
            val len = body[o + 3].toInt() and 0xFF
            o += 4
            if (len > MAX_NAME_BYTES || o + len > body.size) return Result.Invalid(Failure.MALFORMED)
            val name = strictUtf8(body, o, len) ?: return Result.Invalid(Failure.MALFORMED)
            o += len
            items += MacroItem(id, name)
        }
        if (o + 1 != body.size) return Result.Invalid(Failure.MALFORMED)
        val more = body[o].toInt()
        if (more != 0 && more != 1) return Result.Invalid(Failure.MALFORMED)
        return Result.Valid(Page(version, total, page, items, more == 1))
    }

    private fun strictUtf8(b: ByteArray, off: Int, len: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, len)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(TAG_SIZE)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun readU16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}
