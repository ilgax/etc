package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ten samples the solver was first built for: the answers must not change. Run through the whole pipeline. */
class RegressionSamplesTest {
    private fun plain(raw: String, dot: DotMeaning = DotMeaning.Multiply): List<String> {
        val r = SolvePipeline.run(raw, dot)
        assertTrue("$raw -> $r", r is SolveResult.Solved)
        return (r as SolveResult.Solved).answers.map { it.plain }
    }

    @Test fun s01() = assertEquals(listOf("s = 16"), plain("3s=48"))
    @Test fun s02() = assertEquals(listOf("x = ±8√2 ≈ ±11.31"), plain("2x^{2}+144=400"))
    @Test fun s02TimesAsVariable() = assertEquals(listOf("x = ±8√2 ≈ ±11.31"), plain("2\\times^{2}+144=400"))
    @Test fun s03() = assertEquals(listOf("x = 9"), plain("17+x=15+11"))
    @Test fun s04() = assertEquals(listOf("3/8 = 0.375"), plain("\\frac{3 \\times h}{2} \\cdot \\frac{1}{4 \\times h}").map { it.replace("  ", " ") })
    @Test fun s05() = assertEquals(listOf("x = 15"), plain("18+12+2x=60"))
    @Test fun s06() = assertEquals(listOf("a = 32"), plain("20+18+a=70"))
    @Test fun s07() = assertEquals(listOf("x = 65"), plain("15.13+13x=16x"))
    @Test fun s08() = assertEquals(listOf("x = 4"), plain("12x+3x+9x=96"))
    @Test fun s09() = assertEquals(listOf("x = ±8"), plain("\\frac{x^{2}\\sqrt{3}}{2}=32\\sqrt{3}"))
    @Test fun s10() = assertEquals(listOf("x = 65", "y = 20"), plain("\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y}"))
    @Test fun s10Target() = assertEquals("y = 20", plain("\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y} \\\\ y=")[0])
    @Test fun dotDecimalSetting() = assertEquals(listOf("x = 5/2 = 2.5"), plain("1.5+x=4", DotMeaning.Decimal))

    @Test fun quadraticsStillShowTheirSteps() {
        val r = solved("x^2-2x-1=0")
        assertEquals("x = 1 - √2 ≈ -0.41 or x = 1 + √2 ≈ 2.41", r.answers[0].plain.replace("  ", " "))
        assertTrue(r.captions().contains("Discriminant"))
    }
}
