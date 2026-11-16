package dev.ilgax.utils.math.solver

import java.math.BigDecimal

/** One piece of a solution set: an interval, or a single point when both ends are the same closed value. */
internal class Seg(val lo: Val?, val loClosed: Boolean, val hi: Val?, val hiClosed: Boolean) {
    val isPoint get() = lo != null && hi != null && loClosed && hiClosed && lo.sameAs(hi)
    private val loD get() = lo?.d ?: Double.NEGATIVE_INFINITY
    private val hiD get() = hi?.d ?: Double.POSITIVE_INFINITY

    fun tex(): String {
        if (isPoint) return "\\{${lo!!.texOrDecimal()}\\}"
        val a = lo?.texOrDecimal() ?: "-\\infty"
        val b = hi?.texOrDecimal() ?: "\\infty"
        return (if (lo != null && loClosed) "[" else "(") + a + ", " + b + (if (hi != null && hiClosed) "]" else ")")
    }

    fun intersect(o: Seg): Seg? {
        // lower end: the larger one; equal ends are closed only if both are
        val lo1 = pickLo(o)
        val hi1 = pickHi(o)
        val l = lo1.first
        val h = hi1.first
        if (l != null && h != null) {
            if (l.d > h.d + 1e-12 * Math.max(1.0, Math.abs(l.d))) return null
            if (l.sameAs(h) && !(lo1.second && hi1.second)) return null
        }
        return Seg(l, lo1.second, h, hi1.second)
    }

    private fun pickLo(o: Seg): Pair<Val?, Boolean> = when {
        lo == null -> o.lo to o.loClosed
        o.lo == null -> lo to loClosed
        lo.sameAs(o.lo) -> lo to (loClosed && o.loClosed)
        lo.d > o.lo.d -> lo to loClosed
        else -> o.lo to o.loClosed
    }

    private fun pickHi(o: Seg): Pair<Val?, Boolean> = when {
        hi == null -> o.hi to o.hiClosed
        o.hi == null -> hi to hiClosed
        hi.sameAs(o.hi) -> hi to (hiClosed && o.hiClosed)
        hi.d < o.hi.d -> hi to hiClosed
        else -> o.hi to o.hiClosed
    }

    companion object {
        val ALL = Seg(null, false, null, false)
    }
}

/**
 * Linear and chained inequalities, and polynomial/rational ones through a sign chart. The solution set is a list of
 * [Seg]s printed as `x \in (-\infty, -2) \cup [3, \infty)`.
 */
internal class Inequalities(private val s: SolveSession) {
    private class Linear(val v: Char, val op: String, val bound: Val?, val verdict: Boolean?, val set: List<Seg>)

    fun solve(line: ParsedLine): SolveResult {
        if (line.ops.any { it == "=" }) throw UnsupportedMath("an equation mixed into an inequality")
        val ev = Evaluator(s.atoms)
        val vals = line.parts.map { ev.eval(it) }
        if (!s.atoms.isEmpty) throw UnsupportedMath("an inequality with roots, logarithms or powers of the unknown")
        if (line.ops.size == 1) return single(vals[0], line.ops[0], vals[1], line.sources[0], line.sources[1])
        return chain(line, vals)
    }

    // ---------------------------------------------------------------- helpers

    private fun opTex(op: String) = when (op) { "<=" -> "\\le"; ">=" -> "\\ge"; else -> op }
    private fun flip(op: String) = when (op) { "<" -> ">"; ">" -> "<"; "<=" -> ">="; else -> "<=" }
    private fun strict(op: String) = op == "<" || op == ">"

    private fun variableOf(l: RatFn, r: RatFn): Char? {
        val vars = (l.variables + r.variables).userVars
        if (vars.size > 1) throw SolveFailure("it still has more than one unknown (${vars.joinToString(", ")})")
        return vars.firstOrNull()
    }

    private fun holds(c: Surd, op: String): Boolean {
        val sg = c.signum()
        return when (op) { "<" -> sg < 0; ">" -> sg > 0; "<=" -> sg <= 0; else -> sg >= 0 }
    }

    // ---------------------------------------------------------------- one inequality

    private fun single(l: RatFn, op: String, r: RatFn, ls: String, rs: String): SolveResult {
        val d = (l - r)
        val v = variableOf(l, r)
        if (v == null) {
            val c = d.simplified().value
            val value = c.num.constant / c.den.constant
            step("${Printer.ratFn(l.simplified().value)}${opTex(op)}${Printer.ratFn(r.simplified().value)}", "No unknown: check the statement")
            val ok = holds(value, op)
            return SolveResult.Solved(listOf(if (ok) Answer("\\text{True}", "True") else Answer("\\text{False}", "False")), s.steps, s.notes)
        }
        val lin = linear(l, op, r, v, ls, rs, "")
        if (lin != null) return resultOf(v, lin.set, lin)
        val set = general(l, op, r, v)
        return resultOf(v, set, null)
    }

    private fun step(latex: String, caption: String) = s.step(latex, caption)

    /** The solution set of `l op r` in [v] (linear, or a sign chart), with its steps; used for each case of an absolute-value problem. */
    internal fun setOf(l: RatFn, op: String, r: RatFn, v: Char, ls: String, rs: String): List<Seg> =
        linear(l, op, r, v, ls, rs, "")?.set ?: general(l, op, r, v)

    internal fun answerOf(v: Char, set: List<Seg>): SolveResult = resultOf(v, set, null)

    /** Linear in v with constant denominators; null otherwise. Shows its steps. */
    private fun linear(l: RatFn, op: String, r: RatFn, v: Char, ls: String, rs: String, prefix: String): Linear? {
        val c = (l - r).simplified()
        val f = c.value
        if (!f.den.isConstant || !c.factor.isConstant) return null
        val deg = f.num.degreeIn(v)
        if (deg > 1) return null
        val a = f.num.coeffOf(v, 1).constant
        val b = f.num.coeffOf(v, 0).constant
        if (deg == 0 || a.isZero) {
            val ok = holds(b, op)
            step("${Printer.surd(b)}${opTex(op)}0", prefix + (if (ok) "The unknown cancels: always true" else "The unknown cancels: never true"))
            return Linear(v, op, null, ok, if (ok) listOf(Seg.ALL) else emptyList())
        }
        val lSimple = l.simplified().value
        val rSimple = r.simplified().value
        val isolated = (lSimple.den.isConstant && rSimple.den.isConstant) &&
            ((lSimple.num.isMonomial && lSimple.num.terms.keys.first() == Mono.of(v) && rSimple.num.isConstant) ||
                (rSimple.num.isMonomial && rSimple.num.terms.keys.first() == Mono.of(v) && lSimple.num.isConstant))
        var curOp = op
        var coef = a
        var rhs = -b
        if (isolated) {
            // keep the user's orientation: kx op c, or c op kx
            if (rSimple.num.isMonomial && rSimple.num.terms.keys.first() == Mono.of(v) && lSimple.num.isConstant) {
                // c op kx  ->  kx flip(op) c
                curOp = flip(op)
                val k = rSimple.num.constant.let { _ -> rSimple.num.terms.values.first() }
                coef = k
                rhs = lSimple.num.constant
            } else {
                coef = lSimple.num.terms.values.first()
                rhs = rSimple.num.constant
            }
        } else {
            // (l - r) op 0 with a x + b: a x op -b
            step("${vTerm(a, v)}${opTex(op)}${Printer.surd(rhs)}", prefix + "Move terms")
        }
        val bound = rhs / coef
        var finalOp = curOp
        if (coef != Surd.ONE) {
            val neg = coef.signum() < 0
            if (neg) finalOp = flip(curOp)
            step("$v${opTex(finalOp)}${Printer.surd(bound)}", prefix + "Divide both sides by ${Printer.surd(coef)}" + if (neg) " (negative: flip the sign)" else "")
        }
        val bv = Val.of(bound)
        val set = when (finalOp) {
            "<" -> listOf(Seg(null, false, bv, false))
            "<=" -> listOf(Seg(null, false, bv, true))
            ">" -> listOf(Seg(bv, false, null, false))
            else -> listOf(Seg(bv, true, null, false))
        }
        return Linear(v, finalOp, bv, null, set)
    }

    private fun vTerm(coef: Surd, v: Char) = Printer.poly(Poly.of(Mono.of(v), coef))

    // ---------------------------------------------------------------- sign chart

    private class Critical(val at: Val, var numZero: Boolean, var denZero: Boolean)

    private fun general(l: RatFn, op: String, r: RatFn, v: Char): List<Seg> {
        val c = (l - r).simplified()
        val f = c.value
        val rZero = r.num.isZero
        val moved = Printer.ratFnNice(f)
        if (!rZero || strip(moved) != strip(Printer.ratFnNice(l.simplified().value))) step("$moved${opTex(op)}0", "Move everything to one side")
        val num = f.num
        val den = f.den
        if (num.isZero) {
            val all = !strict(op)
            step("0${opTex(op)}0", if (all) "Always true" else "Never true")
            return if (all) listOf(Seg.ALL) else emptyList()
        }
        val finder = RootFinder(s)
        val crit = ArrayList<Critical>()
        fun add(roots: List<Val>, isNum: Boolean) {
            for (z in roots) {
                val ex = crit.firstOrNull { it.at.sameAs(z) }
                if (ex != null) { if (isNum) ex.numZero = true else ex.denZero = true }
                else crit += Critical(z, isNum, !isNum)
            }
        }
        fun zeros(p: Poly): List<Val> = if (p.isConstant) emptyList() else if (p.degreeIn(v) >= 3) finder.loud(p, v) else s.quiet { finder.loud(p, v) }
        val numRoots = zeros(num)
        add(numRoots, true)
        if (numRoots.isNotEmpty()) step(numRoots.joinToString(",\\ ") { "$v=${it.texOrDecimal()}" }, if (den.isConstant) "Zeros" else "Zeros of the numerator")
        else if (!num.isConstant) step("${Printer.poly(num)}\\ne0", if (den.isConstant) "No real zeros" else "The numerator has no real zeros")
        if (!den.isConstant) {
            val dRoots = zeros(den)
            add(dRoots, false)
            if (dRoots.isNotEmpty()) step(dRoots.joinToString(",\\ ") { "$v=${it.texOrDecimal()}" }, "Zeros of the denominator (never allowed)")
        }
        if (!c.factor.isConstant && v in c.factor.variables) {
            val cancelled = zeros(c.factor)
            add(cancelled, false)
            if (cancelled.isNotEmpty()) s.note("Excluded: " + cancelled.joinToString(", ") { "$v ≠ ${plainOf(it.texOrDecimal())}" })
        }
        crit.sortBy { it.at.d }

        // sign on each open interval, from an exact test point
        val signs = IntArray(crit.size + 1)
        for (i in signs.indices) {
            val t = testPoint(crit, i)
            val sn = sign(num, v, t) * sign(den, v, t)
            signs[i] = if (sn == 0) 1 else sn
        }
        chartStep(v, crit, signs)

        val wantPositive = op == ">" || op == ">="
        val inclPoint = !strict(op)
        // pieces: I0 P1 I1 ... Pn In
        class Piece(val included: Boolean, val point: Critical?)
        val pieces = ArrayList<Piece>()
        for (i in signs.indices) {
            pieces += Piece((signs[i] > 0) == wantPositive, null)
            if (i < crit.size) pieces += Piece(inclPoint && crit[i].numZero && !crit[i].denZero, crit[i])
        }
        val out = ArrayList<Seg>()
        var i = 0
        while (i < pieces.size) {
            if (!pieces[i].included) { i++; continue }
            var j = i
            while (j + 1 < pieces.size && pieces[j + 1].included) j++
            // piece index p: even = interval number p/2, odd = critical point (p-1)/2
            fun leftEnd(p: Int): Pair<Val?, Boolean> = if (p % 2 == 1) crit[(p - 1) / 2].at to true
                else if (p == 0) null to false else crit[p / 2 - 1].at to false
            fun rightEnd(p: Int): Pair<Val?, Boolean> = if (p % 2 == 1) crit[(p - 1) / 2].at to true
                else if (p / 2 >= crit.size) null to false else crit[p / 2].at to false
            val (lo, loC) = leftEnd(i)
            val (hi, hiC) = rightEnd(j)
            out += Seg(lo, loC, hi, hiC)
            i = j + 1
        }
        return out
    }

    private fun strip(t: String) = t.replace(" ", "").replace("{", "").replace("}", "")

    private fun sign(p: Poly, v: Char, t: Rational): Int = p.substitute(v, Surd.of(t)).constant.signum()

    private fun testPoint(crit: List<Critical>, i: Int): Rational {
        val x: Double = when {
            crit.isEmpty() -> 0.0
            i == 0 -> crit.first().at.d - 1.0
            i == crit.size -> crit.last().at.d + 1.0
            else -> (crit[i - 1].at.d + crit[i].at.d) / 2.0
        }
        return Rational.parse(BigDecimal(x).toPlainString())
    }

    private fun chartStep(v: Char, crit: List<Critical>, signs: IntArray) {
        val sb = StringBuilder()
        val cols = signs.size + crit.size
        sb.append("\\begin{array}{c|").append("c".repeat(cols)).append("}")
        val header = ArrayList<String>()
        val row = ArrayList<String>()
        fun sg(x: Int) = if (x > 0) "+" else "-"
        for (i in signs.indices) {
            val a = if (i == 0) "-\\infty" else crit[i - 1].at.texOrDecimal()
            val b = if (i == crit.size) "\\infty" else crit[i].at.texOrDecimal()
            header += "($a, $b)"
            row += sg(signs[i])
            if (i < crit.size) { header += crit[i].at.texOrDecimal(); row += if (crit[i].denZero) "\\times" else "0" }
        }
        sb.append(v).append(" & ").append(header.joinToString(" & ")).append("\\\\ \\hline f(")
        sb.append(v).append(") & ").append(row.joinToString(" & ")).append("\\end{array}")
        step(sb.toString(), "Sign chart of f (× = not allowed)")
    }

    // ---------------------------------------------------------------- chains

    private fun chain(line: ParsedLine, vals: List<RatFn>): SolveResult {
        val ops = line.ops
        val up = ops.all { it == "<" || it == "<=" }
        val down = ops.all { it == ">" || it == ">=" }
        if (!up && !down) throw UnsupportedMath("a chained inequality that changes direction")
        val all = vals.fold(emptySet<Char>()) { a, f -> a + f.variables.userVars }
        if (all.size != 1) throw if (all.isEmpty()) UnsupportedMath("a chained inequality without an unknown") else SolveFailure("it still has more than one unknown (${all.joinToString(", ")})")
        val v = all.first()
        var set: List<Seg> = listOf(Seg.ALL)
        for (i in ops.indices) {
            step("${line.sources[i]}${opTex(ops[i])}${line.sources[i + 1]}", "Part ${i + 1} of the chain")
            val lin = linear(vals[i], ops[i], vals[i + 1], v, line.sources[i], line.sources[i + 1], "")
                ?: throw UnsupportedMath("a chained inequality that isn't linear")
            set = intersect(set, lin.set)
        }
        step(set.joinToString("\\cup ") { it.tex() }.ifEmpty { "\\varnothing" }, "Both parts must hold: intersect")
        return resultOf(v, set, null)
    }

    private fun intersect(a: List<Seg>, b: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (x in a) for (y in b) x.intersect(y)?.let { out += it }
        return out.sortedBy { it.lo?.d ?: Double.NEGATIVE_INFINITY }
    }

    // ---------------------------------------------------------------- answers

    private fun resultOf(v: Char, set: List<Seg>, lin: Linear?): SolveResult {
        val tex: String = when {
            set.isEmpty() -> "$v\\in\\varnothing"
            set.size == 1 && set[0].lo == null && set[0].hi == null -> "$v\\in\\mathbb{R}"
            lin != null && lin.bound != null -> "$v${opTex(lin.op)}${lin.bound.texOrDecimal()}"
            set.size == 1 && set[0].isPoint -> "$v=${set[0].lo!!.texOrDecimal()}"
            else -> "$v\\in " + set.joinToString(" \\cup ") { it.tex() }
        }
        if (set.any { (it.lo?.isApprox == true) || (it.hi?.isApprox == true) }) s.note("Some endpoints are approximate (no exact roots)")
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }
}
