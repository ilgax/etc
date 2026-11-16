package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InequalityTest {
    @Test fun linearLess() = assertEquals("x < 2", answerOf("2x+3<7"))
    @Test fun linearNegativeDivisorFlips() {
        val r = solved("-3x\\ge 9")
        assertEquals("x ≤ -3", r.answers[0].plain)
        assertEquals("x\\le-3", r.answers[0].latex)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("flip the sign") })
    }
    @Test fun unicodeAndAsciiRelations() {
        assertEquals("x ≤ 3", answerOf("x≤3"))
        assertEquals("x ≥ 3", answerOf("x>=3"))
        assertEquals("x < 3", answerOf("x\\lt3"))
        assertEquals("x ≥ 2", answerOf("x\\geqslant2"))
        assertEquals("x ≥ 4", answerOf("x\\leqslant 2x-4"))
        assertEquals("x ≤ 4", answerOf("x\\geqslant 2x-4"))
    }
    @Test fun constantOnTheLeft() = assertEquals("x ≤ 3", answerOf("3\\ge x"))
    @Test fun unknownCancels() {
        assertEquals("x ∈ ℝ", answerOf("x+1>x"))
        assertEquals("x ∈ ∅", answerOf("x+1<x"))
    }
    @Test fun noUnknown() {
        assertEquals("False", answerOf("5<3"))
        assertEquals("True", answerOf("3\\le3"))
    }

    @Test fun quadraticOutside() {
        val r = solved("x^2-x-6>0")
        assertEquals("x ∈ (-∞, -2) ∪ (3, ∞)", r.answers[0].plain)
        assertEquals("x\\in (-\\infty, -2) \\cup (3, \\infty)", r.answers[0].latex)
        assertTrue(r.steps.any { it.caption.startsWith("Sign chart") && it.latex.contains("\\begin{array}") })
    }
    @Test fun quadraticInsideClosed() = assertEquals("x ∈ [-2, 2]", answerOf("x^2\\le 4"))
    @Test fun rationalWithExcludedDenominator() {
        val r = solved("\\frac{x-1}{x+2}\\ge 0")
        assertEquals("x ∈ (-∞, -2) ∪ [1, ∞)", r.answers[0].plain)
        assertTrue(r.steps.any { it.latex.contains("\\times") })
    }
    @Test fun chained() {
        val r = solved("1<2x+1\\le 7")
        assertEquals("x ∈ (0, 3]", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("flip") })
    }
    @Test fun chainedDescending() = assertEquals("x ∈ [1, 5)", answerOf("5>x\\ge1"))
    @Test fun chainedEmpty() = assertEquals("x ∈ ∅", answerOf("1<x<0"))
    @Test fun noRealZerosNegative() = assertEquals("x ∈ ∅", answerOf("x^2+1<0"))
    @Test fun noRealZerosPositive() = assertEquals("x ∈ ℝ", answerOf("x^2+1>0"))
    @Test fun singlePoint() = assertEquals("x = 0", answerOf("x^2\\le 0"))
    @Test fun touchingRootIsExcludedWhenStrict() = assertEquals("x ∈ (-∞, 0) ∪ (0, ∞)", answerOf("x^2>0"))
    @Test fun cubicWithRationalRoots() = assertEquals("x ∈ (-∞, -2) ∪ (1/2, 3)", answerOf("2x^3-3x^2-11x+6<0"))
    @Test fun doubleRootDoesNotChangeSign() = assertEquals("x ∈ [-2, ∞)", answerOf("(x-1)^2(x+2)\\ge 0"))
    @Test fun surdEndpoints() = assertEquals("x ∈ (-∞, 1 - √2] ∪ [1 + √2, ∞)", answerOf("x^2-2x-1\\ge0"))
    @Test fun irrationalCubicEndpointsAreDecimals() {
        val r = solved("x^3-x-1>0")
        assertEquals("x ∈ (1.3247, ∞)", r.answers[0].plain)
        assertTrue(r.notes.any { it.contains("approximate") })
    }
    @Test fun cancelledFactorStaysExcluded() {
        val r = solved("\\frac{x^2-1}{x-1}>0")
        assertEquals("x ∈ (-1, 1) ∪ (1, ∞)", r.answers[0].plain)
    }
    @Test fun reciprocal() = assertEquals("x ∈ (-∞, 0) ∪ (1/2, ∞)", answerOf("\\frac{1}{x}<2"))
    @Test fun sumOfFractions() = assertEquals("x ∈ (-1, 0) ∪ (1, ∞)", answerOf("\\frac{1}{x-1}+\\frac{1}{x+1}>0"))
    @Test fun denominatorMakesOpenEndsEvenWhenNonStrict() = assertEquals("x ∈ (-∞, -2) ∪ [-1, 0] ∪ [1, 2)", answerOf("\\frac{x^3-x}{x^2-4}\\le 0"))

    @Test fun inequalitiesWithRootsAreNotClaimed() {
        assertTrue(failedReason("\\sqrt{x}<3").contains("inequality"))
    }
    @Test fun changingDirectionChainIsRejected() = assertTrue(failedReason("1<x>0").contains("changes direction"))
    @Test fun twoUnknownsAreRejected() = assertTrue(failedReason("x<y").contains("more than one unknown"))
}
