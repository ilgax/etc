package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.LatexNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsoluteValueTest {
    private fun n(s: String) = LatexNormalizer.normalize(s).latex

    // ---- parsing and the normaliser ----
    @Test fun everySpellingOfTheBar() {
        for (raw in listOf("|x-1|=2", "\\left|x-1\\right|=2", "\\lvert x-1\\rvert=2", "\\vert x-1\\vert=2", "\\mid x-1\\mid=2", "\\left\\lvert x-1\\right\\rvert=2"))
            assertEquals(raw, "x = -1 or x = 3", answerOf(raw))
        assertEquals("|x-1|=2", n("\\left|x-1\\right|=2"))
        assertEquals("|x-1|=2", n("\\mid x-1\\mid=2"))
        assertEquals("|x-1|=2", n("\\vert x-1\\vert=2"))
    }

    @Test fun doubleBarsAreAbsoluteValuesWithANote() {
        val r = solved("\\lVert x-1\\rVert=2")
        assertEquals("x = -1 or x = 3", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.contains("Double bars") })
        assertEquals("|x-1|=2", n("\\lVert x-1\\rVert=2"))
    }

    @Test fun nestedBars() {
        assertEquals("x = ±3", answerOf("||x|-1|=2"))
        assertEquals("x = ±3", answerOf("\\left|\\left|x\\right|-1\\right|=2"))
        assertEquals("x = ±1", answerOf("||x|+1|=2"))
    }

    @Test fun adjacentBarsMultiply() {
        assertEquals("x = -1 or x = 2", answerOf("|x||x-1|=2"))
        assertEquals("6", answerOf("|-3||2|"))
        assertEquals("6", answerOf("2|-3|"))
        assertEquals("x = ±2", answerOf("|x||x|=4"))
        assertEquals("12", answerOf("|-3||2||-2|"))
    }

    @Test fun barsInsideOtherStructures() {
        assertEquals("x = -2 or x = 4", answerOf("2|x-1|=6"))
        assertEquals("x = ±2", answerOf("|x|^2=4"))
        assertEquals("x = ±3", answerOf("\\frac{|x|}{3}=1"))
        assertEquals("x = -2 or x = 3", answerOf("\\frac{|2x-1|}{5}=1"))
    }

    // ---- numbers ----
    @Test fun numeric() {
        assertEquals("6", answerOf("|-3|+|2-5|"))
        // the sign of surds and of π is decided exactly
        assertEquals("-1 + √2 ≈ 0.41", answerOf("|1-\\sqrt2|"))
        assertEquals("-3 + π ≈ 0.142", answerOf("|3-\\pi|"))
        assertEquals("2√3 - 2√2 ≈ 0.64", answerOf("|\\sqrt2-\\sqrt3|+|\\sqrt3-\\sqrt2|"))
        assertEquals("2", answerOf("|-2|"))
    }

    // ---- equations ----
    @Test fun equalsConstant() {
        assertEquals("x = -2 or x = 3", answerOf("|2x-1|=5"))
        assertEquals("x = -2 or x = 3", answerOf("5=|2x-1|"))
        assertEquals("No solution", answerOf("|x+3|=-1"))
        assertEquals("x = -3", answerOf("|x+3|=0"))
        assertEquals("x = ±1 or x = ±√7 ≈ ±2.65", answerOf("|x^2-4|=3"))
    }

    @Test fun equalsAbsolute() {
        assertEquals("x = -3 or x = 1/3 ≈ 0.33", answerOf("|x-2|=|2x+1|"))
    }

    @Test fun equalsExpressionWithTheUnknownRejectsTheNegative() {
        val r = solved("|x-1|=2x")
        assertEquals("x = 1/3 ≈ 0.33", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Check x = -1: rejected") })
        assertTrue(r.steps.any { it.latex == "2x\\ge 0" })
    }

    @Test fun identityGivesAnInterval() {
        assertEquals("x ∈ [0, ∞)", answerOf("|x|=x"))
        assertEquals("x ∈ (-∞, 0]", answerOf("|x|=-x"))
        assertEquals("x ∈ ℝ", answerOf("|x-1|=|x-1|"))
    }

    @Test fun twoBarsSplitIntoCases() {
        val r = solved("|x-1|+|x+2|=5")
        assertEquals("x = -3 or x = 2", r.answers[0].plain)
        assertEquals(3, r.captions().count { it.startsWith("Case") })
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Case x ≤ -2: |x - 1| = -(x - 1), |x + 2| = -(x + 2)") })
        assertTrue(r.captions().any { it.contains("does not fit") || it.contains("fits") })
    }

    @Test fun casesOnPolynomialArguments() {
        assertEquals("x = -1 or x = 2", answerOf("|x||x-1|=2"))
        assertEquals("x = 0 or x = ±4", answerOf("x^2=4|x|"))
        assertEquals("x = ±3", answerOf("|x^2-4|=5"))
    }

    @Test fun unsupportedCombinations() {
        assertTrue(failedReason("|\\sqrt{x}-1|=2").contains("absolute value"))
        assertTrue(failedReason("|\\ln x|=1").contains("absolute value"))
        assertTrue(failedReason("|x||y|=2").contains("unknown"))
        assertEquals("x = ±1/2 = ±0.5", answerOf("|\\frac{1}{x}|=2"))
        assertTrue(failedReason("|\\frac{1}{x}|+|x|=3").contains("fraction"))
        assertTrue(failedReason("|\\sin x|=\\frac12").contains("absolute values together with trigonometric"))
    }

    // ---- inequalities ----
    @Test fun lessThanConstant() {
        val r = solved("|2x-3|<5")
        assertEquals("x ∈ (-1, 4)", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("means -c < f < c") })
        assertEquals("x ∈ [-2, 2]", answerOf("|x^2-1|\\le3"))
        assertEquals("x ∈ [1, 5]", answerOf("|x-3|\\le2"))
    }

    @Test fun greaterThanConstant() {
        assertEquals("x ∈ (-∞, -3] ∪ [1, ∞)", answerOf("|x+1|\\ge 2"))
        assertEquals("x ∈ (-∞, -1) ∪ (3, ∞)", answerOf("|x-1|>2"))
    }

    @Test fun constantsThatAreNotPositive() {
        assertEquals("x ∈ ∅", answerOf("|x|<-1"))
        assertEquals("x ∈ ∅", answerOf("|x|<0"))
        assertEquals("x = 3", answerOf("|x-3|\\le0"))
        assertEquals("x ∈ ℝ", answerOf("|x|>-1"))
        assertEquals("x ∈ ℝ", answerOf("|x|\\ge0"))
        assertEquals("x ∈ (-∞, 0) ∪ (0, ∞)", answerOf("|x|>0"))
        assertEquals("x ∈ ℝ", answerOf("|x^2-4|\\ge0"))
        assertEquals("x ∈ ∅", answerOf("|x-3|<0"))
    }

    @Test fun constantOnTheLeft() {
        assertEquals("x ∈ (-1, 4)", answerOf("5>|2x-3|"))
    }

    @Test fun generalCaseSplitting() {
        assertEquals("x ∈ (1/3, ∞)", answerOf("|x-1|<2x"))
        assertEquals("x ∈ (1, ∞)", answerOf("|x|-|x-2|>0"))
    }

    @Test fun orderedAnswersAreNotApproximateByAccident() {
        val r = solved("|x^2-2|<1")
        assertEquals("x ∈ (-√3, -1) ∪ (1, √3)", r.answers[0].plain)
    }
}
