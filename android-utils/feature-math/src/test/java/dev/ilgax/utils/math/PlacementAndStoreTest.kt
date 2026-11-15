package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.CardPlacement
import dev.ilgax.utils.math.normalize.CardSide
import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.PxPoint
import dev.ilgax.utils.math.normalize.PxRect
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class PlacementAndStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun place(anchor: PxRect, cw: Float = 400f, ch: Float = 300f) =
        CardPlacement.place(anchor, containerWidth = 1000f, containerHeight = 800f, cardWidth = cw, cardHeight = ch, margin = 16f, gap = 12f)

    @Test fun prefersBelow() {
        val p = place(PxRect(300f, 100f, 700f, 200f))
        assertEquals(CardSide.Below, p.side)
        assertEquals(300f, p.x) ; assertEquals(212f, p.y)
    }
    @Test fun goesAboveWhenNoRoomBelow() {
        val p = place(PxRect(300f, 600f, 700f, 700f))
        assertEquals(CardSide.Above, p.side)
        assertEquals(288f, p.y)
    }
    @Test fun goesToTheSideWhenTallAnchor() {
        val p = place(PxRect(50f, 20f, 300f, 780f))
        assertEquals(CardSide.Right, p.side)
        assertEquals(312f, p.x)
    }
    @Test fun leftWhenOnlyLeftHasRoom() {
        val p = place(PxRect(700f, 20f, 980f, 780f))
        assertEquals(CardSide.Left, p.side)
        assertEquals(700f - 12f - 400f, p.x)
    }
    @Test fun clampedOnScreen() {
        val p = place(PxRect(0f, 100f, 60f, 160f))
        assertTrue(p.x >= 16f && p.x + 400f <= 1000f - 16f)
        val q = place(PxRect(950f, 100f, 1000f, 160f))
        assertTrue(q.x + 400f <= 1000f - 16f)
    }
    @Test fun neverOffScreenWhenCardIsHuge() {
        val p = place(PxRect(100f, 100f, 200f, 200f), cw = 2000f, ch = 2000f)
        assertEquals(16f, p.x); assertEquals(16f, p.y)
    }

    @Test fun placeInRespectsBoundsOffset() {
        // usable area starts below a 50 px status bar and ends above a 40 px nav bar
        val bounds = PxRect(0f, 50f, 1000f, 760f)
        val p = CardPlacement.placeIn(PxRect(300f, 100f, 700f, 200f), bounds, 400f, 300f, 16f, 12f)
        assertEquals(CardSide.Below, p.side)
        assertEquals(300f, p.x); assertEquals(212f, p.y)
        // an anchor low on the screen puts the card above it, never under the nav bar
        val q = CardPlacement.placeIn(PxRect(300f, 600f, 700f, 700f), bounds, 400f, 300f, 16f, 12f)
        assertEquals(CardSide.Above, q.side)
        assertEquals(288f, q.y)
        assertTrue(q.y >= bounds.top + 16f)
    }
    @Test fun placeInOffsetsHorizontally() {
        val bounds = PxRect(100f, 0f, 1100f, 800f)
        val p = CardPlacement.placeIn(PxRect(150f, 20f, 190f, 60f), bounds, 400f, 300f, 16f, 12f)
        assertTrue(p.x >= 116f) // clamped to the left edge of the bounds plus margin, not to x=16
    }
    @Test fun clampIntoKeepsCardOnScreen() {
        val bounds = PxRect(0f, 50f, 1000f, 760f)
        assertEquals(PxPoint(16f, 66f), CardPlacement.clampInto(-300f, -20f, 400f, 300f, bounds, 16f))
        assertEquals(PxPoint(584f, 444f), CardPlacement.clampInto(5000f, 5000f, 400f, 300f, bounds, 16f))
        assertEquals(PxPoint(500f, 300f), CardPlacement.clampInto(500f, 300f, 400f, 300f, bounds, 16f))
        // bigger than the area: pinned to the top-left margin rather than thrown off screen
        assertEquals(PxPoint(16f, 66f), CardPlacement.clampInto(300f, 300f, 2000f, 2000f, bounds, 16f))
    }

    @Test fun correctionsStoreRoundTrip() {
        val store = CorrectionsStore(tmp.newFolder("c"))
        assertEquals(0, store.count())
        store.save("a1", byteArrayOf(1, 2, 3), "x=\"1\"", "x=2", "m", 1000L)
        store.save("a1", byteArrayOf(9), "x=\"1\"", "x=3", "m", 2000L) // same session replaces
        store.save("b2", byteArrayOf(4), "o", "c", "m", 3000L)
        assertEquals(2, store.count())
        val bos = ByteArrayOutputStream()
        assertEquals(2, store.exportZip(bos))
        val names = HashMap<String, String>()
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(names.keys.containsAll(listOf("a1/crop.png", "a1/meta.json", "b2/crop.png", "corrections.jsonl")))
        assertTrue(names.getValue("a1/meta.json").contains("\"corrected\":\"x=3\""))
        assertTrue(names.getValue("a1/meta.json").contains("\"original\":\"x=\\\"1\\\"\""))
        assertEquals(2, names.getValue("corrections.jsonl").trim().lines().size)
        store.deleteAll()
        assertEquals(0, store.count())
    }

    @Test fun pipeline() {
        val r = SolvePipeline.run("15.13+13x=16x", DotMeaning.Multiply)
        assertTrue(r is SolveResult.Solved)
        assertEquals("x = 65", (r as SolveResult.Solved).answers[0].plain)
        assertTrue(SolvePipeline.run("   ", DotMeaning.Multiply) is SolveResult.Failed)
    }

    @Test fun captureMethodChoice() {
        // fastest first: takeScreenshot needs no process spawn
        assertEquals(CaptureMethod.Accessibility, chooseMethod(true, true))
        assertEquals(CaptureMethod.Shizuku, chooseMethod(true, false))
        assertEquals(CaptureMethod.Accessibility, chooseMethod(false, true))
        assertEquals(CaptureMethod.None, chooseMethod(false, false))
    }
}
