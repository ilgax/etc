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
            // inequality with its sign chart, and a log equation with a rejected root
            "inequality" to content("\\frac{x-1}{x+2}\\ge 0", true),
            "log" to content("\\log x+\\log(x-3)=1", true),
            // 0.5.0: a trigonometric equation (period solutions + general solution) and an absolute-value equation with cases
            "trig" to content("\\sin(2x)=\\frac{\\sqrt3}{2}", true),
            "abs" to content("|x-1|+|x+2|=5", true),
            // 0.6.0: a derivative with its rule steps, and a function alone: f', critical points, sign chart of f', extrema
            "derivative" to content("\\frac{d}{dx}\\left(x^{2}\\sin x\\right)", true),
            "critical" to content("f(x)=x^{3}-3x\\\\f'(x)", false),
        )) {
            for ((theme, col) in listOf("light" to light, "dark" to dark)) {
                File(dir, "$name-$theme.json").writeText(c.toJson(col.first, col.second, col.third))
            }
        }
    }
}
