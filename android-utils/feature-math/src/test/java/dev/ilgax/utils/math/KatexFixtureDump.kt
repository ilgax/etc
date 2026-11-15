package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Test
import java.io.File

/**
 * Writes the exact JSON the card's WebView would receive to build/katex-fixtures/, so the screenshot tests
 * can show a real KaTeX rendering (Robolectric cannot draw a WebView). See scripts/render-katex-fixtures.sh.
 */
class KatexFixtureDump {
    private fun content(latex: String, preview: Boolean): KatexContent {
        val r = SolvePipeline.run(latex, DotMeaning.Multiply) as SolveResult.Solved
        return KatexContent(if (preview) latex else null, r.answers.map { it.latex }, r.notes, r.steps)
    }

    @Test fun dump() {
        val dir = File("build/katex-fixtures").apply { mkdirs() }
        val light = Triple("#181C20", "#41484D", "#2B6486")
        val dark = Triple("#E0E3E7", "#C1C7CE", "#98CDF5")
        for ((name, c) in listOf(
            "result" to content("2x^{2}+144=400", true),
            "try" to content("\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y}", false),
        )) {
            for ((theme, col) in listOf("light" to light, "dark" to dark)) {
                File(dir, "$name-$theme.json").writeText(c.toJson(col.first, col.second, col.third))
            }
        }
    }
}
