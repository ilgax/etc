package dev.ilgax.utils.math.solver

import java.math.BigDecimal

/**
 * Equations and inequalities with absolute values of the unknown.
 *
 * The common shapes get the textbook treatment: `|f| = c` (none / f = 0 / f = ±c), `|f| = |g|` (f = ±g), `|f| = g`
 * (f = g or f = -g, then check g ≥ 0) and `|f| ⋚ c` (a double inequality, or two outside pieces). Everything else is
 * split into cases by the sign of each absolute value's argument: the zeros of the arguments cut the line into
 * intervals, on each interval every |a| is replaced by a or -a, the plain equation or inequality is solved there and
 * only what lies in the interval is kept. Nested bars are handled by splitting on the innermost ones first.
 */
internal class AbsSolver(private val s: SolveSession) {
    private val ev = Evaluator(s.atoms)
    private fun step(latex: String, caption: String) = s.step(latex, caption)

    private fun unknown(line: ParsedLine): Char {
        val names = line.parts.flatMapTo(sortedSetOf<Char>()) { it.variableNames() }
        if (names.size > 1) throw SolveFailure("it still has more than one unknown (${names.joinToString(", ")})")
        return names.first()
    }

    private fun rat(n: Node): RatFn {
        val r = try { ev.eval(n) } catch (e: NeedsNumeric) { throw UnsupportedMath(e.message ?: "this expression next to an absolute value") }
        if (!s.atoms.isEmpty) throw UnsupportedMath("an absolute value together with roots, logarithms, powers of the unknown or trigonometric functions")
        return r
    }

    private fun rollback(steps: Int, notes: Int) {
        while (s.steps.size > steps) s.steps.removeAt(s.steps.size - 1)
        while (s.notes.size > notes) s.notes.removeAt(s.notes.size - 1)
    }

    private fun tex(n: Node) = NodeTex.tex(n)
    private fun noSolution() = SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)

    // ------------------------------------------------------------------ equations

    fun equation(line: ParsedLine): SolveResult {
        if (line.parts.size != 2) throw UnsupportedMath("a chain of equations with absolute values")
        val v = unknown(line)
        val l = line.parts[0]
        val r = line.parts[1]
        val steps = s.steps.size
        val notes = s.notes.size
        simpleEquation(l, r, v)?.let { return it }
        rollback(steps, notes)
        return generalEquation(l, r, v)
    }

    /** |f| = c, |f| = |g| and |f| = g with f and g free of other bars; null when the equation is not of that shape. */
    private fun simpleEquation(l: Node, r: Node, v: Char): SolveResult? {
        val (a, other) = when {
            l is Node.Abs -> l to r
            r is Node.Abs -> r to l
            else -> return null
        }
        if (a.arg.hasAbs()) return null
        val otherAbs = other as? Node.Abs
        if (otherAbs == null && other.hasAbs()) return null
        if (otherAbs != null && otherAbs.arg.hasAbs()) return null
        val f = a.arg
        val g = otherAbs?.arg ?: other
        val fR = rat(f)
        val gR = rat(g)
        val fTex = tex(f)
        val gTex = tex(g)
        val absTex = tex(a)
        val roots = ArrayList<Val>()
        var bail = false
        fun solveEq(x: RatFn, y: RatFn, xs: String, ys: String) {
            when (val res = s.solveOne(Eq(x, y, xs, ys), v, check = false)) {
                is Roots.Some -> roots += res.values
                is Roots.None -> {}
                is Roots.All -> bail = true
            }
        }
        val zero = RatFn(Poly.ZERO, Poly.ONE)
        if (otherAbs == null && !g.hasVariable()) {
            val c = ev.constantOf(gR)!!
            val cTex = Printer.surd(c)
            if (c.signum() < 0) {
                step("$absTex=$cTex", "An absolute value is never negative: no solution")
                return noSolution()
            }
            if (c.isZero) {
                step("$fTex=0", "|f| = 0 only when f = 0")
                solveEq(fR, zero, fTex, "0")
            } else {
                step("$fTex=$cTex\\ \\text{or}\\ $fTex=-$cTex", "|f| = c means f = c or f = -c")
                step("$fTex=$cTex", "First case: f = c")
                solveEq(fR, gR, fTex, cTex)
                step("$fTex=-$cTex", "Second case: f = -c")
                solveEq(fR, -gR, fTex, "-$cTex")
            }
        } else {
            val negTex = "-" + (if (g is Node.Sum) "($gTex)" else gTex)
            step("$fTex=$gTex\\ \\text{or}\\ $fTex=$negTex", if (otherAbs != null) "|f| = |g| means f = g or f = -g" else "|f| = g means f = g or f = -g")
            if (otherAbs == null) step("$gTex\\ge 0", "An absolute value is never negative, so g must be ≥ 0 (checked at the end)")
            step("$fTex=$gTex", "First case: f = g")
            solveEq(fR, gR, fTex, gTex)
            step("$fTex=$negTex", "Second case: f = -g")
            solveEq(fR, -gR, fTex, negTex)
        }
        if (bail) return null
        val kept = Candidates.verify(s, l, r, v, roots)
        if (kept.isEmpty()) return noSolution()
        return SolveResult.Solved(listOf(s.rootsAnswer(v, kept.sortedBy { it.d })), s.steps, s.notes)
    }

    private fun generalEquation(l: Node, r: Node, v: Char): SolveResult {
        val segs = walk(l, r, v, Seg.ALL, true) { nl, nr, dom ->
            val lv = rat(nl)
            val rv = rat(nr)
            when (val res = s.solveOne(Eq(lv, rv, tex(nl), tex(nr)), v, check = false)) {
                is Roots.None -> emptyList()
                is Roots.All -> { step("0=0", "True for every x in this case"); listOf(dom) }
                is Roots.Some -> res.values.map { it.withMult(1) }.mapNotNull { x ->
                    val ok = inside(x, dom)
                    step("$v=${x.texOrDecimal()}", "${plainOf("$v=${x.texOrDecimal()}")} ${if (ok) "fits" else "does not fit"} ${plainOf(intervalTex(v, dom))}: ${if (ok) "keep" else "discard"}")
                    if (ok) Seg(x, true, x, true) else null
                }
            }
        }
        val set = union(segs)
        if (set.isEmpty()) return noSolution()
        if (set.all { it.isPoint }) {
            val kept = Candidates.verify(s, l, r, v, set.map { it.lo!! })
            if (kept.isEmpty()) return noSolution()
            return SolveResult.Solved(listOf(s.rootsAnswer(v, kept.sortedBy { it.d })), s.steps, s.notes)
        }
        return Inequalities(s).answerOf(v, set)
    }

    // ------------------------------------------------------------------ inequalities

    fun inequality(line: ParsedLine): SolveResult {
        if (line.ops.size != 1) throw UnsupportedMath("a chained inequality with absolute values")
        val v = unknown(line)
        val op = line.ops[0]
        val l = line.parts[0]
        val r = line.parts[1]
        val steps = s.steps.size
        val notes = s.notes.size
        simpleInequality(l, op, r, v)?.let { return it }
        rollback(steps, notes)
        val ineq = Inequalities(s)
        val segs = walk(l, r, v, Seg.ALL, true) { nl, nr, dom ->
            val set = ineq.setOf(rat(nl), op, rat(nr), v, tex(nl), tex(nr))
            intersect(set, listOf(dom))
        }
        return ineq.answerOf(v, union(segs))
    }

    private fun opTex(op: String) = when (op) { "<=" -> "\\le"; ">=" -> "\\ge"; else -> op }
    private fun flip(op: String) = when (op) { "<" -> ">"; ">" -> "<"; "<=" -> ">="; else -> "<=" }

    /** |f| ⋚ c with a plain number c and f free of other bars. */
    private fun simpleInequality(l: Node, op: String, r: Node, v: Char): SolveResult? {
        val (a, c, o) = when {
            l is Node.Abs && !r.hasVariable() && !r.hasAbs() -> Triple(l, r, op)
            r is Node.Abs && !l.hasVariable() && !l.hasAbs() -> Triple(r, l, flip(op))
            else -> return null
        }
        if (a.arg.hasAbs()) return null
        val f = a.arg
        val fR = rat(f)
        val cR = rat(c)
        val cv = ev.constantOf(cR)!!
        val fTex = tex(f)
        val cTex = Printer.surd(cv)
        val ineq = Inequalities(s)
        val strict = o == "<" || o == ">"
        step("${tex(a)}${opTex(o)}$cTex", "Absolute value against the number $cTex")
        val set: List<Seg>
        if (o == "<" || o == "<=") {
            if (cv.signum() < 0 || (cv.isZero && o == "<")) {
                step("${tex(a)}${opTex(o)}$cTex", "An absolute value is never negative: no solution")
                set = emptyList()
            } else if (cv.isZero) {
                step("$fTex=0", "|f| ≤ 0 only when f = 0")
                set = intersect(ineq.setOf(fR, "<=", cR, v, fTex, "0"), ineq.setOf(fR, ">=", cR, v, fTex, "0"))
            } else {
                step("-$cTex${opTex(o)}$fTex${opTex(o)}$cTex", "|f| ${plainOf(opTex(o))} c means -c ${plainOf(opTex(o))} f ${plainOf(opTex(o))} c")
                step("$fTex${opTex(flip(o))}-$cTex", "Left part: f ${plainOf(opTex(flip(o)))} -c")
                val lo = ineq.setOf(fR, flip(o), -cR, v, fTex, "-$cTex")
                step("$fTex${opTex(o)}$cTex", "Right part: f ${plainOf(opTex(o))} c")
                val hi = ineq.setOf(fR, o, cR, v, fTex, cTex)
                set = intersect(lo, hi)
                step(set.joinToString("\\cup ") { it.tex() }.ifEmpty { "\\varnothing" }, "Both parts must hold: intersect")
            }
        } else {
            if (cv.signum() < 0 || (cv.isZero && !strict)) {
                step("${tex(a)}${opTex(o)}$cTex", "An absolute value is never negative: always true")
                set = listOf(Seg.ALL)
            } else {
                val lowOp = if (strict) "<" else "<="
                step("$fTex${opTex(lowOp)}-$cTex\\ \\text{or}\\ $fTex${opTex(o)}$cTex", "|f| ${plainOf(opTex(o))} c means f ${plainOf(opTex(lowOp))} -c or f ${plainOf(opTex(o))} c")
                step("$fTex${opTex(lowOp)}-$cTex", "Left part: f ${plainOf(opTex(lowOp))} -c")
                val lo = ineq.setOf(fR, lowOp, -cR, v, fTex, "-$cTex")
                step("$fTex${opTex(o)}$cTex", "Right part: f ${plainOf(opTex(o))} c")
                val hi = ineq.setOf(fR, o, cR, v, fTex, cTex)
                set = union(lo + hi)
                step(set.joinToString("\\cup ") { it.tex() }.ifEmpty { "\\varnothing" }, "Either part: join")
            }
        }
        return ineq.answerOf(v, set)
    }

    // ------------------------------------------------------------------ case splitting

    private fun argPoly(arg: Node, v: Char): Poly {
        val c = rat(arg).simplified()
        if (!c.value.den.isConstant || !c.factor.isConstant) throw UnsupportedMath("an absolute value of a fraction with an unknown")
        if (c.value.num.variables.any { it != v }) throw UnsupportedMath("several unknowns inside an absolute value")
        return c.value.num
    }

    private fun innermost(n: Node): List<Node.Abs> =
        if (n is Node.Abs && n.arg.hasVariable() && n.arg.children().none { it.hasAbsVar() } && !n.arg.hasAbsVar()) listOf(n)
        else n.children().flatMap { innermost(it) }

    private fun eps(x: Double) = 1e-9 * Math.max(1.0, Math.abs(x))
    private fun strictlyInside(z: Val, d: Seg) =
        (d.lo == null || z.d > d.lo.d + eps(z.d)) && (d.hi == null || z.d < d.hi.d - eps(z.d))
    private fun inside(z: Val, d: Seg) =
        (d.lo == null || z.d >= d.lo.d - eps(z.d)) && (d.hi == null || z.d <= d.hi.d + eps(z.d))

    private fun intervalTex(v: Char, i: Seg): String = when {
        i.lo == null && i.hi == null -> "$v\\in\\mathbb{R}"
        i.lo == null -> "$v${if (i.hiClosed) "\\le " else "<"}${i.hi!!.texOrDecimal()}"
        i.hi == null -> "$v${if (i.loClosed) "\\ge " else ">"}${i.lo.texOrDecimal()}"
        else -> "${i.lo.texOrDecimal()}${if (i.loClosed) "\\le " else "<"}$v${if (i.hiClosed) "\\le " else "<"}${i.hi.texOrDecimal()}"
    }

    private fun testPoint(i: Seg): Rational {
        val x = when {
            i.lo == null && i.hi == null -> 0.0
            i.lo == null -> i.hi!!.d - 1.0
            i.hi == null -> i.lo.d + 1.0
            else -> (i.lo.d + i.hi.d) / 2.0
        }
        return Rational.parse(BigDecimal(x).toPlainString())
    }

    /**
     * Splits [dom] at the zeros of the innermost bars' arguments, rewrites the bars on each piece and recurses; when no
     * bars are left, [leaf] solves the plain problem on that piece and returns its solution set.
     */
    private fun walk(l: Node, r: Node, v: Char, dom: Seg, announce: Boolean, leaf: (Node, Node, Seg) -> List<Seg>): List<Seg> {
        val inner = (innermost(l) + innermost(r)).distinct()
        if (inner.isEmpty()) return leaf(l, r, dom)
        val polys = inner.map { argPoly(it.arg, v) }
        val zeros = polys.flatMap { p -> s.rootsOrEmpty(p, v).filter { strictlyInside(it, dom) } }
        val cuts = Val.merge(zeros).map { it.withMult(1) }.sortedBy { it.d }
        if (announce) {
            val bars = inner.joinToString(", ") { plainOf(tex(it)) }
            step(
                if (cuts.isEmpty()) "\\text{no sign change}" else cuts.joinToString(",\\ ") { "$v=${it.texOrDecimal()}" },
                if (cuts.isEmpty()) "What is inside $bars never changes sign" else "Where what is inside $bars changes sign: split the line here",
            )
        }
        val out = ArrayList<Seg>()
        for (i in 0..cuts.size) {
            val piece = Seg(
                if (i == 0) dom.lo else cuts[i - 1], if (i == 0) dom.loClosed else true,
                if (i == cuts.size) dom.hi else cuts[i], if (i == cuts.size) dom.hiClosed else true,
            )
            val t = testPoint(piece)
            val repl = HashMap<Node, Node>()
            for ((a, p) in inner.zip(polys)) repl[a] = if (p.substitute(v, Surd.of(t)).constant.signum() >= 0) a.arg else Node.Neg(a.arg)
            val nl = l.rewrite { repl[it] }
            val nr = r.rewrite { repl[it] }
            val how = inner.joinToString(", ") { "${plainOf(tex(it))} = ${plainOf(tex(repl[it]!!))}" }
            step("${tex(nl)}=${tex(nr)}", "Case ${plainOf(intervalTex(v, piece))}: $how")
            out += walk(nl, nr, v, piece, false, leaf)
        }
        return out
    }

    private fun intersect(a: List<Seg>, b: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (x in a) for (y in b) x.intersect(y)?.let { out += it }
        return out.sortedBy { it.lo?.d ?: Double.NEGATIVE_INFINITY }
    }

    /** Sorted, with overlapping and touching pieces merged (points inside an interval disappear). */
    private fun union(all: List<Seg>): List<Seg> {
        val sorted = all.sortedWith(compareBy({ it.lo?.d ?: Double.NEGATIVE_INFINITY }, { if (it.loClosed) 0 else 1 }))
        val out = ArrayList<Seg>()
        for (n in sorted) {
            val cur = out.lastOrNull()
            if (cur == null) { out += n; continue }
            val touches = when {
                cur.hi == null || n.lo == null -> true
                n.lo.d < cur.hi.d - eps(cur.hi.d) -> true
                n.lo.sameAs(cur.hi) -> cur.hiClosed || n.loClosed
                else -> false
            }
            if (!touches) { out += n; continue }
            val hi: Val?
            val hiClosed: Boolean
            when {
                cur.hi == null || n.hi == null -> { hi = null; hiClosed = false }
                cur.hi.sameAs(n.hi) -> { hi = cur.hi; hiClosed = cur.hiClosed || n.hiClosed }
                cur.hi.d > n.hi.d -> { hi = cur.hi; hiClosed = cur.hiClosed }
                else -> { hi = n.hi; hiClosed = n.hiClosed }
            }
            out[out.size - 1] = Seg(cur.lo, cur.loClosed, hi, hiClosed)
        }
        return out
    }
}
