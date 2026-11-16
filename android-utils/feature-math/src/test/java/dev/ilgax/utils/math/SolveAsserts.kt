package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.AngleMode
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertTrue

/** Shared helpers: run raw recognised text through the normaliser and the solver, as the app does. */
internal fun solved(raw: String, dot: DotMeaning = DotMeaning.Multiply, angles: AngleMode = AngleMode.Auto): SolveResult.Solved {
    val r = SolvePipeline.run(raw, dot, angles = angles)
    assertTrue("expected a solution for $raw but got $r", r is SolveResult.Solved)
    return r as SolveResult.Solved
}

/** The answer lines as the clipboard text, joined with " ; ". */
internal fun answerOf(raw: String, dot: DotMeaning = DotMeaning.Multiply, angles: AngleMode = AngleMode.Auto): String =
    solved(raw, dot, angles).answers.joinToString(" ; ") { it.plain }

internal fun latexOf(raw: String): String = solved(raw).answers.joinToString(" ; ") { it.latex }

internal fun failedReason(raw: String, angles: AngleMode = AngleMode.Auto): String {
    val r = SolvePipeline.run(raw, DotMeaning.Multiply, angles = angles)
    assertTrue("expected a failure for $raw but got $r", r is SolveResult.Failed)
    return (r as SolveResult.Failed).reason
}

internal fun SolveResult.Solved.captions() = steps.map { it.caption }
