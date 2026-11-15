package dev.ilgax.utils.math

import dev.ilgax.utils.math.recognition.CpuTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolveTimingsTest {
    private fun marks(vararg p: Pair<Stage, Long>) = mapOf(*p)

    @Test fun fullLine() {
        val m = marks(
            Stage.CaptureRequested to 1000, Stage.CaptureDone to 1170, Stage.BitmapReady to 1180,
            Stage.LassoDone to 3000, Stage.SessionReady to 1500, Stage.EncoderDone to 3190, Stage.DecoderDone to 3420,
            Stage.SolveDone to 3423, Stage.KatexRendered to 3463,
        )
        assertEquals("cap 180 · ocr 420 (enc 190, dec 230, 14 tok) · solve 3 · tex 40 ms", SolveTimings.format(m, 14))
    }

    @Test fun modelLoadWaitIsShownWhenTheSessionWasNotReadyAtTheLasso() {
        val m = marks(
            Stage.CaptureRequested to 0, Stage.BitmapReady to 90,
            Stage.LassoDone to 1000, Stage.SessionReady to 1300, Stage.EncoderDone to 1500, Stage.DecoderDone to 1700,
        )
        assertEquals("cap 90 · ocr 700 (load 300, enc 200, dec 200) ms", SolveTimings.format(m))
    }

    @Test fun partialRunsLeaveStagesOut() {
        assertNull(SolveTimings.format(emptyMap()))
        assertEquals("cap 250 ms", SolveTimings.format(marks(Stage.CaptureRequested to 10, Stage.CaptureDone to 260)))
        // capture done without bitmap ready still reports the capture
        assertEquals("cap 5 ms", SolveTimings.format(marks(Stage.CaptureRequested to 0, Stage.CaptureDone to 5)))
        assertEquals("ocr 300 ms", SolveTimings.format(marks(Stage.LassoDone to 0, Stage.DecoderDone to 300)))
        assertEquals("ocr 300 (14 tok) ms", SolveTimings.format(marks(Stage.LassoDone to 0, Stage.DecoderDone to 300), 14))
    }

    @Test fun marksKeepTheFirstValueAndTheClockIsInjectable() {
        var now = 100L
        val t = SolveTimings { now }
        t.mark(Stage.CaptureRequested)
        now = 280
        t.mark(Stage.BitmapReady)
        now = 999
        t.mark(Stage.BitmapReady) // ignored
        assertEquals("cap 180 ms", t.line())
        assertEquals(setOf(Stage.CaptureRequested, Stage.BitmapReady), t.snapshot().keys)
    }

    @Test fun onMarkFiresOncePerStage() {
        val seen = ArrayList<Stage>()
        val t = SolveTimings { 5L }
        t.onMark = { seen += it }
        t.mark(Stage.LassoDone); t.mark(Stage.LassoDone); t.mark(Stage.SolveDone)
        assertEquals(listOf(Stage.LassoDone, Stage.SolveDone), seen)
    }

    @Test fun threadChoice() {
        // Galaxy Tab S9 FE+ style: 4 big + 4 little cores -> 4
        assertEquals(4, CpuTopology.chooseThreads(listOf(2400000L, 2400000, 2400000, 2400000, 2000000, 2000000, 2000000, 2000000), 8))
        // two big cores and six slow ones -> only the big pair
        assertEquals(2, CpuTopology.chooseThreads(listOf(2800000L, 2800000, 1000000, 1000000, 1000000, 1000000, 1000000, 1000000), 8))
        // unknown topology: cores, capped at four
        assertEquals(4, CpuTopology.chooseThreads(emptyList(), 8))
        assertEquals(2, CpuTopology.chooseThreads(emptyList(), 2))
        assertEquals(1, CpuTopology.chooseThreads(listOf(0L, 0L), 1))
    }
}
