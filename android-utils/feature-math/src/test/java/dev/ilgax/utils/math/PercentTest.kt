package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.normalize.PercentUse
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `%20` (Turkish: the sign first) and `20\%` both mean the exact fraction 20/100. */
class PercentTest {
    private fun n(s: String, dot: DotMeaning = DotMeaning.Multiply) = LatexNormalizer.normalize(s, dot).latex

    @Test fun prefixAndSuffixBecomeFractions() {
        assertEquals("(20/100)x=15", n("%20x=15"))
        assertEquals("(20/100)x=15", n("20\\%x=15"))
        assertEquals("(20/100)x=15", n("20%x=15"))
        assertEquals("(20/100)x=15", n("\\%20x=15"))
        assertEquals("(20/100)x=15", n("\\% 20 x=15"))
        assertEquals("(20/100)x=15", n("20 \\, \\% x=15"))
        assertEquals("(20/100)x=15", n("20\\ \\%x=15"))
    }

    @Test fun decimalPercentFollowsTheDotSetting() {
        assertEquals("(12.5/100)\\cdot8", n("%12.5\\cdot8", DotMeaning.Decimal))
        // under "multiply" the dot is a product, so only the integer part belongs to the percent
        assertEquals("(12/100)\\cdot5\\cdot8", n("%12.5\\cdot8"))
    }

    @Test fun spec() {
        assertEquals("x = 75", answerOf("%20x=15"))
        assertEquals("x = 30", answerOf("x=%15\\cdot 200"))
        assertEquals("20", answerOf("25\\%\\cdot 80"))
        assertEquals("x = 50", answerOf("x+%10x=55"))
        assertEquals("1/2 = 0.5", answerOf("%50"))
    }

    @Test fun percentOfByImplicitMultiplication() {
        for (raw in listOf("%20 \\cdot x = 15", "20\\% x = 15", "20\\%\\cdot x=15", "\\%20x=15", "20%x=15", "x\\cdot%20=15", "15=%20x"))
            assertEquals(raw, "x = 75", answerOf(raw))
    }

    @Test fun showsTheWriteAsStep() {
        val prefix = solved("%20x=15")
        val step = prefix.steps.first()
        assertEquals("Write %20 as 20/100", step.caption)
        assertEquals("\\%20=\\frac{20}{100}", step.latex)
        assertEquals("Write 25% as 25/100", solved("25\\%\\cdot 80").steps.first().caption)
        assertEquals("25\\%=\\frac{25}{100}", solved("25\\%\\cdot 80").steps.first().latex)
        // no percent, no extra step
        assertTrue(solved("2x=4").steps.none { it.caption.startsWith("Write") })
    }

    @Test fun stepSurvivesTheCardEditingNormalisedText() {
        val r = SolvePipeline.run("(20/100)x=15", DotMeaning.Multiply, percentHints = listOf(PercentUse("20", true)))
        assertEquals("Write %20 as 20/100", (r as SolveResult.Solved).steps.first().caption)
        // a hint that is no longer in the text is not shown
        val gone = SolvePipeline.run("x=75", DotMeaning.Multiply, percentHints = listOf(PercentUse("20", true)))
        assertTrue((gone as SolveResult.Solved).steps.none { it.caption.startsWith("Write") })
    }

    @Test fun twoPercentagesAndMixedWithDecimals() {
        assertEquals("x = 20", answerOf("x=%50\\cdot%80\\cdot 50"))
        assertEquals("x = 5", answerOf("%10x+%40x=2.5", DotMeaning.Decimal))
    }

    @Test fun aLoneSignIsReportedNotIgnored() {
        assertTrue(failedReason("x=%").isNotBlank())
    }
}
