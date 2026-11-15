package dev.ilgax.utils.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingsTest {
    @Test fun brightnessEndpointsStayInTheDocumentedRange() {
        assertEquals(1, Mappings.brightnessSetting(0)) // never a black screen
        assertEquals(255, Mappings.brightnessSetting(100))
        assertEquals(1, Mappings.brightnessSetting(-20))
        assertEquals(255, Mappings.brightnessSetting(400))
    }

    @Test fun brightnessIsMonotonicAndFollowsTheSliderCurve() {
        var previous = 0
        for (p in 0..100) {
            val v = Mappings.brightnessSetting(p)
            assertTrue("p=$p v=$v previous=$previous", v >= previous)
            assertTrue(v in 1..255)
            previous = v
        }
        // Halfway on the slider is far below half of the light output (the stock curve): 1/12 of the range.
        assertEquals(22, Mappings.brightnessSetting(50))
        assertTrue(Mappings.brightnessSetting(75) in 60..110)
    }

    @Test fun volumeIndexSpansTheStreamSteps() {
        assertEquals(0, Mappings.volumeIndex(0, 0, 15))
        assertEquals(15, Mappings.volumeIndex(100, 0, 15))
        assertEquals(8, Mappings.volumeIndex(50, 0, 15)) // 7.5 rounds up
        assertEquals(1, Mappings.volumeIndex(0, 1, 15)) // streams with a non-zero minimum
        assertEquals(15, Mappings.volumeIndex(150, 0, 15))
        assertEquals(3, Mappings.volumeIndex(40, 3, 3)) // degenerate range
    }
}
