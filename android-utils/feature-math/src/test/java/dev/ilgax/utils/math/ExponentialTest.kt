package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExponentialTest {
    @Test fun sameBaseAfterRewriting() {
        val r = solved("2^{x+1}=8")
        assertEquals("x = 2", r.answers[0].plain)
        assertEquals("2^{x+1}=2^{3}", r.steps.first { it.caption.startsWith("Same base") }.latex)
    }
    @Test fun powerOfThree() {
        val r = solved("9^{x}=27")
        assertEquals("x = 3/2 = 1.5", r.answers[0].plain)
        assertEquals("3^{2x}=3^{3}", r.steps.first { it.caption.startsWith("Same base") }.latex)
    }
    @Test fun differentBasesTakeLogs() {
        assertEquals("x = log_3 5 ≈ 1.465", answerOf("3^x=5"))
        assertEquals("x=\\log_{3} 5\\approx1.465", latexOf("3^x=5"))
    }
    @Test fun substitution() {
        val r = solved("4^x-3\\cdot2^x+2=0")
        assertEquals("x = 0 or x = 1", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Substitute u = 2") })
    }
    @Test fun substitutionRejectsNonPositive() {
        val r = solved("4^x-2^x-2=0")
        assertEquals("x = 1", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("u = -1") && it.contains("rejected") })
    }
    @Test fun eToTheTwoX() {
        assertEquals("x = (ln 5)/2 ≈ 0.805", answerOf("e^{2x}=5"))
        assertEquals("x=\\frac{\\ln 5}{2}\\approx0.805", latexOf("e^{2x}=5"))
    }
    @Test fun bothSidesPowers() = assertEquals("x = -1", answerOf("5^{x-1}=25^{x}"))
    @Test fun logWithAShift() = assertEquals("x=\\log_{2} 10-1\\approx2.322", latexOf("2^{x+1}=10"))
    @Test fun unrelatedBasesBothSides() = assertEquals("x = (ln 3)/(ln 2 - ln 3) ≈ -2.71", answerOf("2^{x}=3^{x+1}"))
    @Test fun expFunction() {
        assertEquals("x = ln 3 ≈ 1.099", answerOf("\\exp(x)=3"))
        assertEquals("x = ln 2 ≈ 0.693", answerOf("e^{x}=2"))
    }
    @Test fun powerIsNeverNegative() = assertEquals("No solution", answerOf("2^x=-4"))
    @Test fun quadraticExponent() = assertEquals("x = ±√3 ≈ ±1.73", answerOf("2^{x^2}=8"))
    @Test fun rationalBase() = assertEquals("x = -3", answerOf("(\\frac{1}{2})^{x}=8"))
    @Test fun xToTheXIsUnsupported() = assertTrue(failedReason("x^x=27").contains("x^x"))
    @Test fun transcendentalMixIsNumericAndLabelled() {
        val r = solved("x+2^x=5")
        assertEquals("x ≈ 1.7156", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Approximate") })
        assertTrue(r.captions().toString(), r.captions().any { it.contains("approximate") })
    }
    @Test fun twoCrossingsAreBothFound() = assertEquals("x ≈ 0.1586 or x ≈ 3.1462", answerOf("\\ln x=x-2"))
    @Test fun numericSearchSaysSoWhenNothingIsFound() = assertTrue(failedReason("2^x+x^2=-1").contains("numeric search"))
}
