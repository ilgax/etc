package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawScreencapTest {
    private fun header(w: Int, h: Int, format: Int = 1, colorSpace: Int? = null): ByteArray {
        val b = ByteBuffer.allocate(if (colorSpace == null) 12 else 16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(w).putInt(h).putInt(format)
        if (colorSpace != null) b.putInt(colorSpace)
        return b.array()
    }

    @Test fun twelveByteHeader() {
        val l = RawScreencap.parse(header(10, 4), 12L + 10 * 4 * 4)
        assertNotNull(l)
        assertEquals(12, l!!.headerSize); assertEquals(10, l.width); assertEquals(4, l.height); assertEquals(160L, l.pixelBytes)
    }

    @Test fun sixteenByteHeaderWithColorSpace() {
        val l = RawScreencap.parse(header(10, 4, colorSpace = 0), 16L + 10 * 4 * 4)
        assertEquals(16, l!!.headerSize)
    }

    @Test fun realTabletSize() {
        // 2800 x 1752 RGBA (Galaxy Tab S9 FE+), newer header
        val bytes = 16L + 2800L * 1752 * 4
        assertEquals(16, RawScreencap.parse(header(2800, 1752, colorSpace = 9), bytes)!!.headerSize)
        assertEquals(12, RawScreencap.parse(header(2800, 1752), 12L + 2800L * 1752 * 4)!!.headerSize)
    }

    @Test fun rgbxIsAccepted() {
        assertNotNull(RawScreencap.parse(header(8, 8, format = 2), 12L + 256))
    }

    @Test fun wrongByteCountsAreRejected() {
        val pixels = 10L * 4 * 4
        assertNull(RawScreencap.parse(header(10, 4), 12L + pixels - 1)) // truncated
        assertNull(RawScreencap.parse(header(10, 4), 12L + pixels + 1)) // trailing byte
        assertNull(RawScreencap.parse(header(10, 4), 12L + pixels + 8)) // neither header size
        assertNull(RawScreencap.parse(header(10, 4), 0))
        assertNull(RawScreencap.parse(header(10, 4), 14L + pixels))
    }

    @Test fun badHeadersAreRejected() {
        assertNull(RawScreencap.parse(header(0, 4), 12))
        assertNull(RawScreencap.parse(header(10, -1), 12))
        assertNull(RawScreencap.parse(header(100_000, 4), 12L + 100_000L * 16))
        assertNull(RawScreencap.parse(header(10, 4, format = 4), 12L + 80)) // RGB565 is 2 bytes per pixel
        assertNull(RawScreencap.parse(header(10, 4, format = 22), 12L + 320)) // FP16
        assertNull(RawScreencap.parse(ByteArray(8), 100)) // too short to hold a header
        assertNull(RawScreencap.parse("\u0089PNG\r\n\u001a\n....".toByteArray(Charsets.ISO_8859_1), 5000)) // a PNG is not raw output
    }

    @Test fun pixelBytesFromHeader() {
        assertEquals(10L * 4 * 4, RawScreencap.pixelBytes(header(10, 4)))
        assertNull(RawScreencap.pixelBytes(header(10, 4, format = 7)))
    }
}
