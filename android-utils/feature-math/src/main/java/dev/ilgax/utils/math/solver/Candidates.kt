package dev.ilgax.utils.math.solver

/**
 * Candidate solutions are checked against the *original* equation: exactly when the values are surds, numerically
 * otherwise. Used by the absolute-value and trigonometric solvers (the same idea as the check in [AtomSolver]).
 */
internal object Candidates {
    sealed interface Check {
        class Ok(val latex: String) : Check
        class Bad(val latex: String?, val reason: String) : Check
    }

    private fun plain(t: String) = plainOf(t)

    fun at(lNode: Node, rNode: Node, v: Char, x: Val): Check {
        if (x.exact != null) {
            try {
                val a = NodeExact.eval(lNode, mapOf(v to x.exact))
                val b = NodeExact.eval(rNode, mapOf(v to x.exact))
                return if (a == b) Check.Ok("${Printer.surd(a)}=${Printer.surd(b)}")
                else Check.Bad("${Printer.surd(a)}\\ne${Printer.surd(b)}", "left side is ${plain(Printer.surd(a))}, right side is ${plain(Printer.surd(b))}: extraneous")
            } catch (u: Undefined) {
                return Check.Bad(null, u.why)
            } catch (_: NotExact) {
            } catch (_: UnsupportedMath) {
            }
        }
        return try {
            val a = NodeNum.eval(lNode, mapOf(v to x.d))
            val b = NodeNum.eval(rNode, mapOf(v to x.d))
            val tol = (if (x.isExact) 1e-9 else 1e-6) * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)))
            if (Math.abs(a - b) <= tol) Check.Ok("${Printer.num(a, 4)}\\approx${Printer.num(b, 4)}")
            else Check.Bad("${Printer.num(a, 4)}\\ne${Printer.num(b, 4)}", "left side is ${Printer.num(a, 4)}, right side is ${Printer.num(b, 4)}: extraneous")
        } catch (u: Undefined) {
            Check.Bad(null, u.why)
        }
    }

    /** Checks each distinct candidate, leaving a "Check" step per candidate; returns the ones that hold. */
    fun verify(s: SolveSession, lNode: Node, rNode: Node, v: Char, cands: List<Val>, shown: (Val) -> String = { it.texOrDecimal() }): List<Val> {
        val distinct = ArrayList<Val>()
        for (c in cands.sortedBy { it.d }) if (distinct.none { it.sameAs(c) }) distinct += c.withMult(1)
        val kept = ArrayList<Val>()
        for (x in distinct) {
            val label = plain(shown(x)).let { if (x.isApprox) "≈$it" else it }
            when (val r = at(lNode, rNode, v, x)) {
                is Check.Ok -> { s.step(r.latex, "Check $v = $label: ok"); kept += x }
                is Check.Bad -> {
                    s.step(r.latex ?: "$v=${shown(x)}", "Check $v = $label: rejected, ${r.reason}")
                    s.note("Rejected $v = $label: ${r.reason}")
                }
            }
        }
        return kept
    }
}
