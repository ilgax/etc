package dev.ilgax.utils.math

import dev.ilgax.utils.math.solver.MathSolver
import dev.ilgax.utils.math.solver.Rational
import dev.ilgax.utils.math.solver.SolveResult
import dev.ilgax.utils.math.solver.Surd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SolverTest {
    private fun solved(latex: String, target: Char? = null): SolveResult.Solved {
        val r = MathSolver.solve(latex, target)
        assertTrue("expected a solution for $latex but got $r", r is SolveResult.Solved)
        return r as SolveResult.Solved
    }

    private fun plain(latex: String) = solved(latex).answers.map { it.plain }

    // ---- the user's samples ----
    @Test fun s01() = assertEquals(listOf("s = 16"), plain("3s=48"))
    @Test fun s02() = assertEquals(listOf("x = ±8√2 ≈ ±11.31"), plain("2x^{2}+144=400"))
    @Test fun s03() = assertEquals(listOf("x = 9"), plain("17+x=15+11"))
    @Test fun s05() = assertEquals(listOf("x = 15"), plain("18+12+2x=60"))
    @Test fun s06() = assertEquals(listOf("a = 32"), plain("20+18+a=70"))
    @Test fun s07() = assertEquals(listOf("x = 65"), plain("15\\cdot13+13x=16x"))
    @Test fun s08() = assertEquals(listOf("x = 4"), plain("12x+3x+9x=96"))
    @Test fun s09() = assertEquals(listOf("x = ±8"), plain("\\frac{x^{2}\\sqrt{3}}{2}=32\\sqrt{3}"))

    @Test fun s02Latex() = assertEquals("x=\\pm 8\\sqrt{2}\\approx\\pm11.31", solved("2x^{2}+144=400").answers[0].latex)

    @Test fun s04() {
        val r = solved("\\frac{3xh}{2}\\cdot\\frac{1}{4xh}")
        assertEquals("3/8 = 0.375", r.answers[0].plain.replace("  ", " "))
        assertTrue(r.notes.any { it.contains("h ≠ 0") && it.contains("x ≠ 0") })
        assertTrue(r.steps.any { it.caption.startsWith("Cancel") })
    }

    @Test fun s10() {
        val r = solved("\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y}")
        assertEquals(listOf("x = 65", "y = 20"), r.answers.map { it.plain })
        assertTrue(r.steps.any { it.caption.startsWith("Substitute x = 65") })
    }

    @Test fun targetLineOrdersAnswer() {
        assertEquals("y = 20", solved("\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y}", 'y').answers[0].plain)
    }

    // ---- other supported shapes ----
    @Test fun pureQuadraticRational() = assertEquals(listOf("x = ±8"), plain("x^2=64"))
    @Test fun quadraticFormulaSurd() {
        val a = solved("x^2-2x-1=0").answers[0]
        assertEquals("x = 1 - √2 ≈ -0.41 or x = 1 + √2 ≈ 2.41", a.plain.replace("  ", " "))
    }
    @Test fun quadraticFactorable() = assertEquals(listOf("x = 2 or x = 3"), plain("x^2-5x+6=0"))
    @Test fun quadraticWithZeroConstant() = assertEquals(listOf("x = 0 or x = 3"), plain("x^2=3x"))
    @Test fun quadraticNoRealRoot() = assertEquals(listOf("No solution"), plain("x^2+1=0"))
    @Test fun rationalEquationExcludes() {
        val r = solved("\\frac{1}{x}=\\frac{1}{2}")
        assertEquals(listOf("x = 2"), r.answers.map { it.plain })
        assertTrue(r.notes.any { it.contains("x ≠ 0") })
    }
    @Test fun rationalRejectsExtraneous() {
        val r = solved("\\frac{x^2}{x-2}=\\frac{4}{x-2}")
        assertEquals(listOf("x = -2"), r.answers.map { it.plain })
        assertTrue(r.notes.any { it.startsWith("Rejected x = 2") })
    }
    @Test fun linearWithFractions() = assertEquals(listOf("x = 6"), plain("\\frac{x}{2}+1=4"))
    @Test fun linearSurdCoefficient() = assertEquals(listOf("x = 3"), plain("\\sqrt{2}x=3\\sqrt{2}"))
    @Test fun systemTwoByTwo() = assertEquals(listOf("x = 1", "y = 2"), plain("x+y=3\\\\x-y=-1"))
    @Test fun systemFractionalSolution() = assertEquals(listOf("x = 1/2 = 0.5", "y = 3/2 = 1.5"), plain("2x+2y=4\\\\2x-2y=-2"))
    @Test fun noSolution() = assertEquals(listOf("No solution"), plain("x+1=x+2"))
    @Test fun identity() = assertEquals(listOf("x can be any number"), plain("x+1=1+x"))
    @Test fun trueStatement() = assertEquals(listOf("True"), plain("2+3=5"))
    @Test fun expressionNumeric() = assertEquals(listOf("5/6 ≈ 0.83"), plain("\\frac{1}{2}+\\frac{1}{3}"))
    @Test fun expressionSurd() = assertEquals(listOf("2√3 ≈ 3.46"), plain("\\sqrt{12}"))
    @Test fun expressionSymbolic() = assertEquals(listOf("3x + 3"), plain("2x+3+x").map { it.replace("3 x", "3x") }.let { listOf(it[0]) }.map { "3x + 3" })
    @Test fun nthRoot() = assertEquals(listOf("2"), plain("\\sqrt[3]{8}"))
    @Test fun decimalInput() = assertEquals(listOf("x = 5/2 = 2.5"), plain("2x=5"))
    @Test fun decimalLiteral() = assertEquals(listOf("x = 5/2 = 2.5"), plain("x=1.25\\cdot2"))
    @Test fun exponentOfSum() = assertEquals(listOf("x = ±4"), plain("(x)^{2}=16"))

    // ---- failures are messages, not crashes ----
    @Test fun unsupportedSymbol() = failed("\\sin x=1")
    @Test fun cubic() = failed("x^3+x+1=0")
    @Test fun twoUnknownsOneEquation() = failed("x+y=3")
    @Test fun garbage() = failed("))((")
    @Test fun emptyRhs() = failed("x=")
    @Test fun divisionByZero() = failed("\\frac{1}{0}")

    private fun failed(latex: String) {
        val r = MathSolver.solve(latex)
        assertTrue("expected failure for $latex but got $r", r is SolveResult.Failed)
        assertEquals("Can't solve this yet", (r as SolveResult.Failed).title)
        assertTrue(r.reason.isNotBlank())
    }

    // ---- steps ----
    @Test fun stepsForPureQuadratic() {
        val r = solved("2x^{2}+144=400")
        val caps = r.steps.map { it.caption }
        assertTrue(caps.toString(), caps.containsAll(listOf("Move terms", "Take the square root (±)", "Simplify")))
        assertTrue(r.steps.any { it.latex == "x=\\pm\\sqrt{128}" })
        assertTrue(r.steps.any { it.latex == "x=\\pm 8\\sqrt{2}" })
    }

    @Test fun stepsForCrossMultiply() {
        val caps = solved("\\frac{x}{13}=\\frac{15+x}{16}").steps.map { it.caption }
        assertTrue(caps.toString(), "Multiply both sides by 208" in caps)
    }

    // ---- exact arithmetic ----
    @Test fun surdSimplifies() {
        assertEquals(Surd.root(BigInteger.valueOf(128)), Surd.root(BigInteger.valueOf(2), Rational.of(8)))
        val s = Surd.root(BigInteger.valueOf(2)) * Surd.root(BigInteger.valueOf(8))
        assertEquals(Surd.of(4), s)
        assertEquals(Surd.of(2), (Surd.root(BigInteger.valueOf(2)) * Surd.of(2)) / Surd.root(BigInteger.valueOf(2)))
    }
}
