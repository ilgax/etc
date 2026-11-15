package dev.ilgax.utils.math.normalize

import dev.ilgax.utils.math.solver.MathSolver
import dev.ilgax.utils.math.solver.SolveResult

/** Normalise then solve, as the card and the "Try it" field do on every edit. Never throws. */
object SolvePipeline {
    fun run(text: String, dot: DotMeaning, targetOverride: Char? = null): SolveResult {
        if (text.isBlank()) return SolveResult.Failed("there is nothing to solve")
        val n = LatexNormalizer.normalize(text, dot)
        if (n.latex.isBlank()) return SolveResult.Failed("there is nothing to solve")
        return MathSolver.solve(n.latex, targetOverride ?: n.target)
    }
}
