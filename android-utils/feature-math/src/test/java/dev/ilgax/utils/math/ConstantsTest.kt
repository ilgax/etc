package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** π and e are exact constants inside the coefficients. */
class ConstantsTest {
    @Test fun halfPi() {
        assertEquals("x = π/2 ≈ 1.571", answerOf("2x=\\pi"))
        assertEquals("x=\\frac{\\pi}{2}\\approx1.571", latexOf("2x=\\pi"))
    }
    @Test fun twoPiOverThree() = assertEquals("x=\\frac{2\\pi}{3}\\approx2.094", latexOf("3x=2\\pi"))
    @Test fun unicodePi() = assertEquals("x = π/2 ≈ 1.571", answerOf("2x=π"))
    @Test fun squareRootOfPiSquared() = assertEquals("x = ±2π ≈ ±6.283", answerOf("x^2=4\\pi^2"))
    @Test fun squareRootOfPi() = assertEquals("x = ±√π ≈ ±1.772", answerOf("x^2=\\pi"))
    @Test fun piInDenominator() = assertEquals("x = 3/π ≈ 0.955", answerOf("\\pi x=3"))
    @Test fun piCancels() = assertEquals("x = 2", answerOf("\\frac{\\pi}{2}x=\\pi"))
    @Test fun eToTheZero() = assertEquals("x = 0", answerOf("e^{x}=1"))
    @Test fun naturalLogGivesEPower() {
        assertEquals("x = e² ≈ 7.389", answerOf("\\ln x=2"))
        assertEquals("x=e^{2}\\approx7.389", latexOf("\\ln x=2"))
    }
    @Test fun expressionWithConstants() = assertEquals("3π ≈ 9.425", answerOf("2\\pi+\\pi"))
    @Test fun eAsConstantNextToAnotherLetter() = assertEquals("x=\\frac{5}{e}\\approx1.839", latexOf("ex=5"))
    @Test fun mathrmEIsAlwaysEuler() = assertEquals("x = ln 2 ≈ 0.693", answerOf("\\mathrm{e}^{x}=2"))

    // the rule for the letter e: a variable only when it is the only letter of an equation, or the requested unknown
    @Test fun lonelyEIsAVariable() = assertEquals("e = 2", answerOf("3e=6"))
    @Test fun targetEMakesItAVariable() = assertEquals("e = 2", answerOf("3e=6 \\\\ e="))
    @Test fun eWithOtherVariablesIsEuler() = assertEquals("x = e ≈ 2.718", answerOf("x=e"))
    @Test fun lonelyEExpressionIsEuler() = assertEquals("e² ≈ 7.389", answerOf("e^2"))
    @Test fun lnEIsOne() = assertEquals("1", answerOf("\\ln e"))

    @Test fun constantsKeepSurdsExact() {
        val r = solved("x^{2}-4\\pi=0")
        assertEquals("x = ±2√π ≈ ±3.545", r.answers[0].plain)
    }
    @Test fun checkStepsStayExact() {
        val r = solved("2x=\\pi")
        assertTrue(r.steps.any { it.caption.startsWith("Check") && it.latex == "\\pi=\\pi" })
    }
}
