package dev.ilgax.utils.math

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * `screencap` without `-p` writes a small little-endian header (width, height, format and, on newer Android
 * versions, a colour space: 12 or 16 bytes) followed by the pixels. The header size is not announced, so it
 * is told apart by the byte count. Only 8-bit RGBA/RGBX layouts are read here; anything else (RGB565, FP16,
 * 10-bit) is rejected so the caller falls back to the PNG path.
 */
object RawScreencap {
    const val FORMAT_RGBA_8888 = 1
    const val FORMAT_RGBX_8888 = 2
    private const val BYTES_PER_PIXEL = 4
    private const val MAX_SIDE = 16_384
    const val MIN_HEADER = 12
    const val MAX_HEADER = 16

    /** [headerSize] bytes precede the pixels, which are [pixelBytes] long. */
    data class Layout(val width: Int, val height: Int, val format: Int, val headerSize: Int) {
        val pixelBytes get() = width.toLong() * height * BYTES_PER_PIXEL
    }

    /** The size of the pixel block a (width, height, format) header announces, or null if unusable. */
    fun pixelBytes(head: ByteArray): Long? {
        val l = dimensions(head) ?: return null
        return l.first.toLong() * l.second * BYTES_PER_PIXEL
    }

    private fun dimensions(head: ByteArray): Pair<Int, Int>? {
        if (head.size < MIN_HEADER) return null
        val w = int(head, 0)
        val h = int(head, 4)
        val format = int(head, 8)
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) return null
        if (format != FORMAT_RGBA_8888 && format != FORMAT_RGBX_8888) return null
        return w to h
    }

    /**
     * Validates [head] (at least the first 12 bytes of the output) against the [total] number of bytes that
     * came out of screencap. Null when neither header size matches, so a short read or an odd format is a
     * fallback to PNG instead of a garbled bitmap.
     */
    fun parse(head: ByteArray, total: Long): Layout? {
        val (w, h) = dimensions(head) ?: return null
        val format = int(head, 8)
        val pixels = w.toLong() * h * BYTES_PER_PIXEL
        for (header in intArrayOf(MIN_HEADER, MAX_HEADER)) {
            if (total == header + pixels) return Layout(w, h, format, header)
        }
        return null
    }

    /**
     * Reads a whole raw capture from [input] into one array sized from the first 12 bytes (one pass, no
     * intermediate copies) and returns the software bitmap, or null if the stream is not a raw capture.
     */
    fun readBitmap(input: InputStream): Bitmap? {
        val head = ByteArray(MIN_HEADER)
        if (!readFully(input, head, 0, MIN_HEADER)) return null
        val pixels = pixelBytes(head) ?: return null
        if (pixels > Int.MAX_VALUE - 64) return null
        // Room for the 4 extra header bytes plus one byte to notice trailing garbage.
        val body = ByteArray(pixels.toInt() + (MAX_HEADER - MIN_HEADER) + 1)
        var n = 0
        while (n < body.size) {
            val r = input.read(body, n, body.size - n)
            if (r < 0) break
            n += r
        }
        val layout = parse(head, MIN_HEADER.toLong() + n) ?: return null
        val offset = layout.headerSize - MIN_HEADER
        val bmp = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(body, offset, layout.pixelBytes.toInt()).slice())
        bmp.setHasAlpha(false) // the screen is opaque; RGBX leaves the alpha byte undefined
        return bmp
    }

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Boolean {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun int(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
