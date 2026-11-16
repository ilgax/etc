package dev.ilgax.utils.math.normalize

import dev.ilgax.utils.math.solver.AngleMode
import dev.ilgax.utils.math.solver.MathSolver
import dev.ilgax.utils.math.solver.SolveResult
import dev.ilgax.utils.math.solver.Step

/** Normalise then solve, as the card and the "Try it" field do on every edit. Never throws. */
object SolvePipeline {
    /**
     * [percentHints]: percentages that an earlier normalisation turned into `(n/100)` (the card edits the already
     * normalised text), so the "Write %20 as 20/100" step can still be shown.
     */
    fun run(
        text: String,
        dot: DotMeaning,
        targetOverride: Char? = null,
        angles: AngleMode = AngleMode.Auto,
        percentHints: List<PercentUse> = emptyList(),
    ): SolveResult {
        if (text.isBlank()) return SolveResult.Failed("there is nothing to solve")
        val n = LatexNormalizer.normalize(text, dot)
        if (n.latex.isBlank()) return SolveResult.Failed("there is nothing to solve")
        val r = MathSolver.solve(n.latex, targetOverride ?: n.target, angles)
        if (r !is SolveResult.Solved) return r
        val uses = (n.percents + percentHints.filter { "(${it.number}/100)" in n.latex }).distinct()
        val extra = uses.map { Step("${it.written}=\\frac{${it.number}}{100}", "Write ${it.plainWritten} as ${it.number}/100") }
        return r.copy(steps = extra + r.steps, notes = (n.notes + r.notes).distinct())
    }
}
