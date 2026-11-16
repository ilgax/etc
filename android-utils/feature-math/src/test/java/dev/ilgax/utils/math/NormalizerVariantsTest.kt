package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The spellings the recognition model may emit for relations and functions. */
class NormalizerVariantsTest {
    private fun n(s: String, dot: DotMeaning = DotMeaning.Multiply) = LatexNormalizer.normalize(s, dot).latex

    @Test fun lessOrEqualVariants() {
        for (v in listOf("\\le", "\\leq", "\\leqslant", "≤", "<=")) assertEquals(v, "x\\le3", n("x $v 3"))
    }
    @Test fun greaterOrEqualVariants() {
        for (v in listOf("\\ge", "\\geq", "\\geqslant", "≥", ">=")) assertEquals(v, "x\\ge3", n("x $v 3"))
    }
    @Test fun strictVariants() {
        assertEquals("x<3", n("x \\lt 3"))
        assertEquals("x>3", n("x \\gt 3"))
    }
    @Test fun functionVariants() {
        assertEquals("\\ln x=2", n("\\operatorname{ln} x = 2"))
        assertEquals("\\ln x=2", n("\\mathrm{ln}\\,x=2"))
        assertEquals("\\log_{2}x=3", n("\\operatorname{log}_{2} x = 3"))
        assertEquals("\\log_{2}x=3", n("\\text{log}_{2}x=3"))
        assertEquals("\\log x=3", n("log x = 3"))
        assertEquals("\\ln x=3", n("ln x = 3"))
        assertEquals("\\exp(x)=3", n("\\exp(x)=3"))
        assertEquals("\\exp(x)=3", n("exp(x)=3"))
        assertEquals("\\lg x=3", n("\\lg x=3"))
    }
    @Test fun eulerVariants() {
        assertEquals("\\mathrm{e}^{x}=2", n("\\mathrm{e}^{x}=2"))
        assertEquals("\\mathrm{e}^{x}=2", n("\\operatorname{e}^{x}=2"))
        assertEquals("\\pi x=3", n("\\pi x = 3"))
    }
    @Test fun trigDegreePercentAndBarVariants() {
        assertEquals("\\sin30°=\\frac{1}{2}", n("\\operatorname{sin} 30^{\\circ} = \\frac{1}{2}"))
        assertEquals("\\cos60°", n("cos 60^\\circ"))
        assertEquals("\\tan45°", n("\\tan 45\\degree"))
        assertEquals("(20/100)x=15", n("\\%20 x = 15"))
        assertEquals("(20/100)x=15", n("20 \\% x = 15"))
        assertEquals("(20/100)x=15", n("%20x=15"))
        assertEquals("|x-1|=2", n("\\vert x-1\\vert = 2"))
        assertEquals("|x-1|=2", n("\\mid x-1 \\mid = 2"))
        assertEquals("|x-1|=2", n("\\left\\lvert x-1\\right\\rvert = 2"))
    }
    @Test fun existingRulesAreUntouched() {
        // \times is always the variable x; digit-dot-digit follows the setting
        assertEquals("2xh=8", n("2 \\times h = 8"))
        assertEquals("15\\cdot13+13x=16x", n("15.13+13x=16x"))
        assertEquals("1.5+x=4", n("1.5+x=4", DotMeaning.Decimal))
        assertEquals("6/3\\cdot2", n("6 \\div 3 · 2"))
    }
    @Test fun solvesVariants() {
        assertEquals("x = 8", answerOf("\\operatorname{log}_{2} x = 3"))
        assertEquals("x = 100", answerOf("\\lg x = 2"))
        assertEquals("x ≤ 3", answerOf("x \\leq 3"))
        assertEquals("x ≥ 3", answerOf("x \\geqslant 3"))
    }
}
