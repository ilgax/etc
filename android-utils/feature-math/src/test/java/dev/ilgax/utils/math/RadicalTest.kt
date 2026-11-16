package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadicalTest {
    @Test fun oneRadical() {
        val r = solved("\\sqrt{x+3}=5")
        assertEquals("x = 22", r.answers[0].plain)
        assertTrue(r.captions().toString(), "Square both sides" in r.captions())
        assertEquals("x+3=25", r.steps.first { it.caption == "Square both sides" }.latex)
    }
    @Test fun extraneousRootIsRejectedInTheCheckStep() {
        val r = solved("\\sqrt{2x+3}=x")
        assertEquals("x = 3", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Check x = -1") && it.contains("rejected") })
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Rejected x = -1") })
        assertTrue(r.captions().any { it.startsWith("Check x = 3") && it.endsWith("ok") })
    }
    @Test fun twoRadicalsTwoSquarings() {
        val r = solved("\\sqrt{x+5}-\\sqrt{x}=1")
        assertEquals("x = 4", r.answers[0].plain)
        assertEquals(2, r.captions().count { it.startsWith("Square both sides") })
        assertEquals("\\sqrt{x}=2", r.steps.first { it.caption == "Isolate the remaining root" }.latex)
    }
    @Test fun twoRadicalsSecondRootRejects() {
        val r = solved("\\sqrt{2x+1}+\\sqrt{x}=5")
        assertEquals("x = 4", r.answers[0].plain)
        assertTrue(r.notes.any { it.startsWith("Rejected x = 144") })
    }
    @Test fun twoRadicalsSum() = assertEquals("x = 9", answerOf("\\sqrt{x}+\\sqrt{x+7}=7"))
    @Test fun twoRadicalsEqual() = assertEquals("x = 4", answerOf("\\sqrt{x+1}=\\sqrt{2x-3}"))
    @Test fun squareRootCannotBeNegative() {
        val r = solved("\\sqrt{x}=-2")
        assertEquals("No solution", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("never negative") })
    }
    @Test fun cubeRoot() = assertEquals("x = 9", answerOf("\\sqrt[3]{x-1}=2"))
    @Test fun cubeRootOfANegativeNumberIsFine() = assertEquals("x = -9", answerOf("\\sqrt[3]{x+1}=-2"))
    @Test fun radicalBecomesAQuadratic() = assertEquals("x = 5/2 = 2.5", answerOf("\\sqrt{x^2-4}=x-1"))
    @Test fun radicalInADenominator() = assertEquals("x = 1/4 = 0.25", answerOf("\\frac{1}{\\sqrt{x}}=2"))
    @Test fun domainStepIsShown() {
        val r = solved("\\sqrt{x+3}=5")
        assertEquals("x+3\\ge0", r.steps.first { it.caption.startsWith("Domain") }.latex)
    }
    @Test fun pureNumbersKeepTheirSurdBehaviour() {
        assertEquals("2√3 ≈ 3.46", answerOf("\\sqrt{12}"))
        assertEquals("2", answerOf("\\sqrt[3]{8}"))
        assertEquals("x = ±8√2 ≈ ±11.31", answerOf("2x^{2}+144=400"))
    }
    @Test fun nestedRadicalsAreUnsupportedWithAReason() = assertTrue(failedReason("\\sqrt{\\sqrt{x}+1}=2").contains("nested radicals"))
    @Test fun rootOfAFractionIsNumeric() {
        val r = solved("\\sqrt{\\frac{x+1}{x-1}}=2")
        assertEquals("x ≈ 1.6667", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Approximate") })
    }
    @Test fun threeRadicalsFallBackToTheNumericSearchOrFail() {
        // not an exact method; the numeric search may still find the root
        val r = solved("\\sqrt{x}+\\sqrt{x+1}+\\sqrt{x+2}=6")
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Approximate") })
    }
}
