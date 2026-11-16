package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HigherDegreeTest {
    @Test fun threeRationalRoots() = assertEquals("x = 1 or x = 2 or x = 3", answerOf("x^3-6x^2+11x-6=0"))
    @Test fun biquadratic() = assertEquals("x = ±1 or x = ±2", answerOf("x^4-5x^2+4=0"))
    @Test fun commonPowerOfX() = assertEquals("x = 0 or x = ±2", answerOf("x^3=4x"))
    @Test fun leadingCoefficientRationalRoots() = assertEquals("x = -2 or x = 1/2 = 0.5 or x = 3", answerOf("2x^3-3x^2-11x+6=0"))
    @Test fun cubeRootIsExact() {
        assertEquals("x = ∛2 ≈ 1.26", answerOf("x^3-2=0"))
        assertEquals("x=\\sqrt[3]{2}\\approx1.26", latexOf("x^3-2=0"))
    }
    @Test fun irreducibleCubicIsApproximate() {
        val r = solved("x^3-x-1=0")
        assertEquals("x ≈ 1.3247", r.answers[0].plain)
        assertTrue(r.captions().toString(), "No exact roots; approximate" in r.captions())
    }
    @Test fun doubleRoot() = assertEquals("x = -2 or x = 1 (double)", answerOf("(x-1)^2(x+2)=0"))
    @Test fun doubleRootAmongSimpleOnes() = assertEquals("x = -1 (double) or x = 0 or x = 1", answerOf("x^4+x^3-x^2-x=0"))
    @Test fun repeatedBiquadratic() = assertEquals("x = ±√2 ≈ ±1.41 (double)", answerOf("(x^2-2)^2=0"))
    @Test fun fourthRootIsExactWhenPerfect() = assertEquals("x = ±2", answerOf("x^{4}-16=0"))
    @Test fun fourthRootOfFive() = assertEquals("x = ±⁴√5 ≈ ±1.495", answerOf("x^4=5"))
    @Test fun nestedRadicalBiquadraticIsClosedForm() = assertEquals("x = ±√(2 + √5) ≈ ±2.058", answerOf("x^4-4x^2-1=0"))
    @Test fun substitutionInSixthDegree() = assertEquals("x = ±1", answerOf("x^{8}+x^{4}-2=0"))
    @Test fun oneRationalRootThenNothing() = assertEquals("x = 1 or x = 2", answerOf("x^{6}-9x^{3}+8=0"))
    @Test fun noRealRoots() = assertEquals("No solution", answerOf("x^4+x^2+1=0"))
    @Test fun twoDistinctIrrationalRoots() {
        assertEquals("x ≈ -1.7672 or x ≈ -0.7203 or x ≈ 1.6929", answerOf("x^7-3x^5+x^2-1=0"))
    }
    @Test fun twentiethDegree() = assertEquals("x ≈ -0.8939 or x ≈ 1.0362", answerOf("x^{20}-x-1=0"))
    @Test fun coefficientsThatAreSurds() = assertEquals("x ≈ 0.5736", answerOf("x^3+\\sqrt{2}x-1=0"))

    @Test fun stepsShowCandidatesFactorsAndFactors() {
        val caps = solved("x^3-6x^2+11x-6=0").captions()
        assertTrue(caps.toString(), "Try rational roots ±p/q" in caps)
        assertTrue(caps.toString(), "Factor by synthetic division" in caps)
        assertTrue(caps.toString(), "Solutions" in caps)
        val s = solved("x^3-6x^2+11x-6=0").steps
        assertEquals("(x-1)(x^{2}-5x+6)=0", s.first { it.caption == "Factor by synthetic division" }.latex)
    }
    @Test fun biquadraticShowsTheSubstitution() {
        val s = solved("x^4-5x^2+4=0").steps
        assertEquals("u^{2}-5u+4=0", s.first { it.caption.startsWith("Substitute u") }.latex)
    }
    @Test fun smallRootsAreNotMissed() = assertEquals("x ≈ -0.6823", answerOf("x^3+x+1=0"))
}
