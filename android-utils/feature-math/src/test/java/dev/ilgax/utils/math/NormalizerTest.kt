package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.recognition.LatexJoin
import dev.ilgax.utils.math.solver.MathSolver
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {
    private fun n(s: String, dot: DotMeaning = DotMeaning.Multiply) = LatexNormalizer.normalize(s, dot)
    private fun answer(s: String, dot: DotMeaning = DotMeaning.Multiply): String {
        val norm = n(s, dot)
        val r = MathSolver.solve(norm.latex, norm.target)
        assertTrue("$s -> ${norm.latex} -> $r", r is SolveResult.Solved)
        return (r as SolveResult.Solved).answers.joinToString("; ") { it.plain }
    }

    @Test fun stripsWrappersAndSizing() = assertEquals("\\frac{3}{4}+x=1", n("\\[ \\displaystyle \\left( \\frac { 3 } { 4 } \\right) + x = 1 \\]").latex.replace("(", "").replace(")", ""))
    @Test fun stripsSpacingCommands() = assertEquals("2x+3=7", n("2\\,x\\;+\\!3\\quad=\\ 7").latex)
    @Test fun dfrac() = assertEquals("\\frac{1}{2}", n("\\dfrac{1}{2}").latex)

    // \times is always the variable x
    @Test fun timesBetweenFactors() = assertEquals("3xh", n("3 \\times h").latex)
    @Test fun timesInsideFractionsMatchesSpec() =
        assertEquals("\\frac{3xh}{2}\\cdot\\frac{1}{4xh}", n("\\frac{3 \\times h}{2} \\cdot \\frac{1}{4 \\times h}").latex)
    @Test fun timesWithExponent() = assertEquals("2x^{2}+144=400", n("2\\times^{2}+144=400").latex)
    @Test fun timesEvenWithOtherLetters() = assertEquals("2xh=8", n("2 \\times h = 8").latex)
    @Test fun unicodeTimes() = assertEquals("2x+1=5", n("2×+1=5").latex)
    @Test fun timesAsMultiplyWhenSettingOff() = assertEquals("3\\cdot h", LatexNormalizer.normalize("3 \\times h", timesIsVariable = false).latex)

    @Test fun timesSolves() {
        assertEquals("x = ±8√2 ≈ ±11.31", answer("2\\times^{2}+144=400"))
        assertEquals("3/8 = 0.375", answer("\\frac{3 \\times h}{2} \\cdot \\frac{1}{4 \\times h}").replace("  ", " "))
    }

    // digit . digit
    @Test fun dotMultiplyDefault() = assertEquals("15\\cdot13+13x=16x", n("15.13+13x=16x").latex)
    @Test fun dotDecimal() = assertEquals("1.5+x=4", n("1.5+x=4", DotMeaning.Decimal).latex)
    @Test fun dotMultiplySolves() = assertEquals("x = 65", answer("15.13+13x=16x"))
    @Test fun dotDecimalSolves() = assertEquals("x = 5/2 = 2.5", answer("1.5+x=4", DotMeaning.Decimal))
    @Test fun dotNotBetweenDigitsUntouched() = assertEquals("x.y", n("x.y").latex)

    @Test fun divAndMiddleDot() = assertEquals("6/3\\cdot2", n("6 \\div 3 · 2").latex)
    @Test fun minusSign() = assertEquals("3-1", n("3−1").latex)

    // trailing target line
    @Test fun trailingTargetDropped() {
        val r = n("3s=48 \\\\ s=")
        assertEquals("3s=48", r.latex)
        assertEquals('s', r.target)
    }
    @Test fun trailingTargetX() {
        val r = n("2x^{2}+144=400\\\\x=")
        assertEquals("2x^{2}+144=400", r.latex)
        assertEquals('x', r.target)
    }
    @Test fun noTargetOnSingleLine() = assertNull(n("3s=48").target)
    @Test fun targetSolves() = assertEquals("s = 16", answer("3s=48 \\\\ s="))

    @Test fun keepsSpaceAfterCommand() = assertEquals("2\\cdot x", n("2 \\cdot x").latex)
    @Test fun trailingPunctuation() = assertEquals("17+x=26", n("17+x=26.").latex)
    @Test fun arrayWrapper() = assertEquals("3s=48\\\\2x=4", n("\\begin{array}{l}3s=48\\\\2x=4\\end{array}").latex)

    // model post-processing
    @Test fun joinSpacedTokens() {
        assertEquals("17+x=15+11", LatexJoin.join("1 7 + x = 1 5 + 1 1"))
        assertEquals("\\frac{3}{4}", LatexJoin.join("\\frac { 3 } { 4 }"))
        assertEquals("15\\cdot x", LatexJoin.join("1 5 \\cdot x"))
        assertEquals("\\frac{x^{2}\\sqrt{3}}{2}=32\\sqrt{3}", LatexJoin.join("\\frac { x ^ { 2 } \\sqrt { 3 } } { 2 } = 3 2 \\sqrt { 3 }"))
    }
    @Test fun stripWrappersAtEnds() = assertEquals("x=1", LatexJoin.stripWrappers("\\[ x=1 \\]", listOf("\\[", "\\]")))
}
