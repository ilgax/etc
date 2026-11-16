package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.solver.AngleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrigonometryTest {
    private fun n(s: String) = LatexNormalizer.normalize(s).latex
    private val rad = AngleMode.Radians
    private val dec = DotMeaning.Decimal

    // ---- normaliser ----
    @Test fun spellingsOfFunctionsAndDegrees() {
        assertEquals("\\sin30°=\\frac{1}{2}", n("\\sin 30^{\\circ} = \\frac{1}{2}"))
        assertEquals("\\sin30°", n("\\sin 30^\\circ"))
        assertEquals("\\sin30°", n("\\sin 30°"))
        assertEquals("\\sin30°", n("\\sin 30\\degree"))
        assertEquals("\\sin30°", n("\\operatorname{sin} 30°"))
        assertEquals("\\sin30°", n("sin 30°"))
        assertEquals("\\cos x=0", n("cos x = 0"))
        assertEquals("\\arcsin x=0", n("\\operatorname{arcsin} x = 0"))
        assertEquals("\\sin^{2}x", n("\\mathrm{sin}^{2} x"))
        // the existing rules are untouched
        assertEquals("2xh=8", n("2 \\times h = 8"))
    }

    // ---- values ----
    @Test fun exactValues() {
        assertEquals("1/2 = 0.5", answerOf("\\sin 30°"))
        assertEquals("√2/2 ≈ 0.71", answerOf("\\cos 45°"))
        assertEquals("√3 ≈ 1.73", answerOf("\\tan 60°"))
        assertEquals("(√6 - √2)/4 ≈ 0.26", answerOf("\\sin 15°"))
        assertEquals("(√6 - √2)/4 ≈ 0.26", answerOf("\\cos 75°"))
        assertEquals("2 - √3 ≈ 0.27", answerOf("\\tan 15°"))
        assertEquals("√6 - √2 ≈ 1.04", answerOf("\\sec 15°"))
        assertEquals("-1/2 = -0.5", answerOf("\\cos 120°"))
        assertEquals("-√3/2 ≈ -0.87", answerOf("\\sin 240°"))
        assertEquals("-1", answerOf("\\sin(-90°)"))
        assertEquals("1", answerOf("\\cos 360°"))
        assertEquals("2", answerOf("\\csc 30°"))
    }

    @Test fun everyMultipleOf15IsExact() {
        for (d in 0 until 360 step 15) {
            for (fn in listOf("sin", "cos")) {
                val shown = answerOf("\\$fn $d°")
                val v = if (fn == "sin") Math.sin(Math.toRadians(d.toDouble())) else Math.cos(Math.toRadians(d.toDouble()))
                val approx = shown.substringAfter("≈ ", "").ifEmpty { shown.substringAfter("= ", shown) }.toDouble()
                assertEquals("$fn $d", v, approx, 0.01)
            }
        }
    }

    @Test fun undefinedValues() {
        assertEquals("undefined", answerOf("\\tan 90°"))
        assertEquals("undefined", answerOf("\\tan 270°"))
        assertEquals("undefined", answerOf("\\cot 0°"))
        assertEquals("undefined", answerOf("\\sec 90°"))
        assertEquals("undefined", answerOf("\\csc 180°"))
        assertEquals("undefined", answerOf("\\tan 90° + 1"))
        assertEquals("undefined", answerOf("\\tan\\frac{\\pi}{2}"))
    }

    @Test fun expressions() {
        assertEquals("1", answerOf("\\sin^2 45°+\\cos^2 45°"))
        assertEquals("1", answerOf("\\sin 30° + \\cos 60°"))
        assertEquals("1/2 = 0.5", answerOf("\\sin^2 45°"))
        assertEquals("1/2 = 0.5", answerOf("\\sin^{2} 45°"))
        assertEquals("√3 ≈ 1.73", answerOf("2\\sin 60°"))
        assertEquals("1", answerOf("\\tan 45°\\cdot\\cot 45°"))
    }

    @Test fun nonStandardAnglesAreNumeric() {
        assertEquals("≈ 0.342", answerOf("\\sin 20°"))
        // simple identities hold even where the angle is not standard
        assertEquals("1", answerOf("\\sin^2 20°+\\cos^2 20°"))
        assertEquals("1", answerOf("\\tan 20°\\cdot\\cot 20°"))
    }

    @Test fun radiansAndInverseFunctions() {
        assertEquals("1/2 = 0.5", answerOf("\\sin\\frac{\\pi}{6}"))
        assertEquals("√2/2 ≈ 0.71", answerOf("\\cos\\frac{\\pi}{4}"))
        assertEquals("30°", answerOf("\\arcsin\\frac12"))
        assertEquals("30°", answerOf("\\sin^{-1}\\frac12"))
        assertEquals("60°", answerOf("\\arccos\\frac12"))
        assertEquals("45°", answerOf("\\arctan 1"))
        assertEquals("≈ 72.5424°", answerOf("\\arccos 0.3", dec))
        assertEquals("π/6", answerOf("\\arcsin\\frac12", angles = rad))
        assertEquals("1/2 = 0.5", answerOf("\\sin 30°", angles = rad))
    }

    // ---- equations ----
    @Test fun sinHasTwoSolutions() {
        val r = solved("\\sin x=\\frac12")
        assertEquals("x ∈ {30°, 150°}", r.answers[0].plain)
        assertEquals("x \\in \\{30^{\\circ}, 150^{\\circ}\\}", r.answers[0].latex)
        // the general solution is a step
        val general = r.steps.first { it.caption.startsWith("General solution") }
        assertEquals("x = 30^{\\circ} + 360^{\\circ}k,\\; x = 150^{\\circ} + 360^{\\circ}k,\\; k\\in\\mathbb{Z}", general.latex)
    }

    @Test fun cosTanAndFriends() {
        assertEquals("x ∈ {30°, 330°}", answerOf("2\\cos x=\\sqrt3"))
        assertEquals("x ∈ {45°, 225°}", answerOf("\\tan x=1"))
        assertEquals("x ∈ {45°, 225°}", answerOf("\\cot x=1"))
        assertEquals("x ∈ {60°, 300°}", answerOf("\\sec x=2"))
        assertEquals("x ∈ {30°, 150°}", answerOf("\\csc x=2"))
        assertEquals("x ∈ {0°, 180°}", answerOf("\\sin x=0"))
        assertEquals("x ∈ {180°}", answerOf("\\cos x=-1"))
        assertEquals("x ∈ {60°, 240°}", answerOf("\\tan x=\\sqrt3"))
    }

    @Test fun outOfRangeHasNoSolution() {
        assertEquals("No solution", answerOf("\\sin x=2"))
        assertEquals("No solution", answerOf("\\cos x=-1.5", dec))
        assertEquals("No solution", answerOf("\\sec x=\\frac12"))
        assertEquals("No solution", answerOf("2\\sin^2 x=-1"))
    }

    @Test fun linearArguments() {
        assertEquals("x ∈ {30°, 60°, 210°, 240°}", answerOf("\\sin(2x)=\\frac{\\sqrt3}{2}"))
        assertEquals("x ∈ {30°, 60°, 210°, 240°}", answerOf("\\sin 2x=\\frac{\\sqrt3}{2}"))
        assertEquals("x ∈ {0°, 60°, 180°, 240°}", answerOf("\\sin(2x+30°)=\\frac12"))
        assertEquals("x ∈ {45°, 225°}", answerOf("\\sin(x-45°)=0"))
        assertEquals("x ∈ {60°, 300°}", answerOf("\\sin\\frac{x}{2}=\\frac12"))
        assertEquals("x ∈ {80°, 100°, 200°, 220°, 320°, 340°}", answerOf("\\sin 3x=-\\frac{\\sqrt3}{2}"))
        val r = solved("\\sin(2x)=\\frac{\\sqrt3}{2}")
        assertTrue(r.steps.any { it.latex.startsWith("2x = 60^{\\circ} + 360^{\\circ}k") })
        assertTrue(r.steps.any { it.latex.startsWith("x = 30^{\\circ} + 180^{\\circ}k,\\; x = 60^{\\circ} + 180^{\\circ}k") })
    }

    @Test fun argumentIsTheNextTermOnly() {
        // \sin 2x + 1 = 2 is sin(2x) + 1 = 2, not sin(2x + 1)
        assertEquals("x ∈ {45°, 225°}", answerOf("\\sin 2x+1=2"))
        // two functions side by side are two arguments
        assertEquals("x ∈ {45°, 225°}", answerOf("\\sin x=\\cos x"))
        assertEquals("x ∈ {15°, 75°, 195°, 255°}", answerOf("\\sin 2x=\\frac12"))
        assertEquals("x ∈ {30°, 150°}", answerOf("2\\sin x=1"))
    }

    @Test fun substitutionForPolynomialsInOneFunction() {
        val r = solved("2\\sin^2 x-\\sin x-1=0")
        assertEquals("x ∈ {90°, 210°, 330°}", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it == "Let u = sin x" })
        assertEquals("x ∈ {30°, 150°, 210°, 330°}", answerOf("\\sin^2 x=\\frac14"))
        // |u| > 1 is dropped
        assertEquals("x ∈ {0°}", answerOf("\\cos^2 x-3\\cos x+2=0"))
        assertEquals("x ∈ {60°, 120°, 240°, 300°}", answerOf("4\\cos^2 x=1"))
    }

    @Test fun pythagoreanConversion() {
        val r = solved("2\\cos^2 x+3\\sin x-3=0")
        assertEquals("x ∈ {30°, 90°, 150°}", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Use sin² + cos² = 1") })
        // sin² = 1 − cos² also works the other way round
        assertEquals("x ∈ {60°, 90°, 270°, 300°}", answerOf("2\\sin^2 x+\\cos x-2=0"))
        assertEquals("x can be any number", answerOf("\\sin^2 x+\\cos^2 x=1"))
    }

    @Test fun sinEqualsCos() {
        assertEquals("x ∈ {45°, 225°}", answerOf("\\sin x=\\cos x"))
        assertEquals("x ∈ {60°, 240°}", answerOf("\\sin x=\\sqrt3\\cos x"))
        assertEquals("x ∈ {120°, 300°}", answerOf("\\sin x=-\\sqrt3\\cos x"))
        val r = solved("\\sin x=\\cos x")
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Divide both sides by cos x") })
    }

    // ---- units ----
    @Test fun autoIsDegreesUnlessPiAppears() {
        assertEquals("x ∈ {30°, 150°}", answerOf("\\sin x=\\frac12"))
        assertEquals("x ∈ {30°, 150°}", answerOf("\\sin x=\\frac12", angles = AngleMode.Degrees))
        // π anywhere (and no °) means radians
        assertEquals("x ∈ {π/6, 5π/6}", answerOf("\\pi\\sin x=\\frac{\\pi}{2}"))
        assertEquals("x ∈ {π/6, 5π/6}", answerOf("\\sin x=\\frac12", angles = rad))
        // a degree sign wins over π
        assertEquals("x ∈ {60°, 300°}", answerOf("\\pi\\sin(x+90°)=\\frac{\\pi}{2}"))
    }

    @Test fun radiansUnit() {
        val r = solved("\\sin x=\\frac12", angles = rad)
        assertEquals("x \\in \\{\\frac{\\pi}{6}, \\frac{5\\pi}{6}\\}", r.answers[0].latex)
        assertEquals("x = \\frac{\\pi}{6} + 2\\pi\\,k,\\; x = \\frac{5\\pi}{6} + 2\\pi\\,k,\\; k\\in\\mathbb{Z}", r.steps.first { it.caption.startsWith("General") }.latex)
        assertEquals("x ∈ {π/6, 5π/6, 7π/6, 11π/6}", answerOf("\\cos 2x=\\frac12", angles = rad))
        assertEquals("x ∈ {π/4, 5π/4}", answerOf("\\tan x=1", angles = rad))
        assertEquals("x ∈ {π/2, 3π/2}", answerOf("\\cos x=0", angles = rad))
        assertEquals("x ∈ {π/6, 5π/6}", answerOf("\\sin x=\\frac12", angles = rad))
    }

    @Test fun numericAngles() {
        assertEquals("x ∈ {72.5424°, 287.4576°}", answerOf("\\cos x=0.3", dec))
        assertEquals("x ∈ {1.2661, 5.0171}", answerOf("\\cos x=0.3", dec, rad))
        assertEquals("x ∈ {17.4576°, 162.5424°}", answerOf("\\sin x=0.3", dec))
        assertEquals("x ∈ {26.5651°, 206.5651°}", answerOf("\\tan x=0.5", dec))
        val r = solved("\\cos x=0.3", dec)
        assertEquals("x \\in \\{72.5424^{\\circ}, 287.4576^{\\circ}\\}", r.answers[0].latex)
    }

    // ---- honesty ----
    @Test fun unsupportedHasAReason() {
        assertTrue(failedReason("\\sin 2x=\\cos x").contains("different angles"))
        assertTrue(failedReason("\\sin x+\\cos x=1").contains("doesn't reduce"))
        assertTrue(failedReason("\\sin x=x").contains("inside and outside"))
        assertTrue(failedReason("\\tan x+\\cot x=2").contains("different trigonometric functions"))
        assertTrue(failedReason("\\sin x>\\frac12").contains("inequality"))
        assertTrue(failedReason("\\sin x\\cos x=\\frac14").contains("doesn't reduce"))
        assertTrue(failedReason("\\sin(x^2)=\\frac12").contains("linear"))
        assertTrue(failedReason("\\sin x=\\ln x").contains("together with"))
        assertTrue(failedReason("\\sinh x=1").contains("hyperbolic"))
        assertTrue(failedReason("\\sin x").contains("unknown"))
    }

    @Test fun everySolutionIsCheckedAgainstTheOriginalEquation() {
        val r = solved("\\sin(2x)=\\frac{\\sqrt3}{2}")
        assertEquals(4, r.captions().count { it.startsWith("Check x = ") && it.endsWith(": ok") })
        // a pole of tan is never an answer
        assertEquals("x ∈ {45°, 225°}", answerOf("\\tan x=1"))
        assertEquals("x ∈ {90°, 270°}", answerOf("\\cos x=0"))
    }

    @Test fun anAnswerLineIsShort() {
        assertTrue(solved("\\sin x=\\frac12").answers[0].latex.length < 50)
        assertEquals(1, solved("2\\sin^2 x-\\sin x-1=0").answers.size)
    }
}
