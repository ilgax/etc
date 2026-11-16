package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** One line of working: a LaTeX formula and a short caption. */
data class Step(val latex: String, val caption: String)

/** One line of the final answer, as LaTeX (for display) and plain text (for the clipboard). */
data class Answer(val latex: String, val plain: String)

sealed interface SolveResult {
    data class Solved(val answers: List<Answer>, val steps: List<Step>, val notes: List<String>) : SolveResult
    data class Failed(val reason: String) : SolveResult {
        val title get() = "Can't solve this yet"
    }
}

/** Entry point: solves already-normalised LaTeX. Never throws. */
object MathSolver {
    fun solve(latex: String, target: Char? = null, angles: AngleMode = AngleMode.Auto): SolveResult = try {
        SolveSession(target, angles).run(latex)
    } catch (e: UnsupportedMath) {
        SolveResult.Failed("it needs ${e.message}, which isn't supported")
    } catch (e: SolveFailure) {
        SolveResult.Failed(e.message ?: "unknown problem")
    } catch (e: ArithmeticException) {
        SolveResult.Failed("it divides by zero")
    } catch (e: StackOverflowError) {
        SolveResult.Failed("it is too deeply nested")
    } catch (e: RuntimeException) {
        SolveResult.Failed("unexpected problem: ${e.javaClass.simpleName}")
    }
}

internal class SolveFailure(message: String) : Exception(message)

private class Contradiction : Exception()

internal class Eq(val l: RatFn, val r: RatFn, val lSrc: String, val rSrc: String) {
    fun substitute(v: Char, x: Surd) = Eq(l.substitute(v, x), r.substitute(v, x), lSrc, rSrc)
    val diff: Poly get() = l.num * r.den - r.num * l.den
}

internal sealed interface Roots {
    data class Some(val values: List<Val>) : Roots
    data object All : Roots
    data object None : Roots
}

internal class SolveSession(private val target: Char?, private val angles: AngleMode = AngleMode.Auto) {
    val steps = ArrayList<Step>()
    val notes = ArrayList<String>()
    val atoms = AtomTable()

    fun step(latex: String, caption: String) { steps += Step(latex, caption) }
    fun note(t: String) { if (t !in notes) notes += t }

    /** Runs [block] without leaving steps or notes behind (used to look at roots silently). */
    fun <T> quiet(block: () -> T): T {
        val s = steps.size
        val n = notes.size
        try { return block() } finally {
            while (steps.size > s) steps.removeAt(steps.size - 1)
            while (notes.size > n) notes.removeAt(notes.size - 1)
        }
    }

    fun run(latex: String): SolveResult {
        Printer.atomTex.set { c -> atoms[c]?.tex }
        try { return runInner(latex) } finally { Printer.atomTex.remove() }
    }

    private fun runInner(latex: String): SolveResult {
        if (DerivativeSolver.applies(latex)) DerivativeSolver(this, angles).solve(latex)?.let { return it }
        val lines = LatexParser.parse(latex, target, angles)
        val nodes = lines.flatMap { it.parts }
        val trigVar = nodes.any { it.hasTrigVar() }
        val absVar = nodes.any { it.hasAbsVar() }
        if (trigVar && absVar) throw UnsupportedMath("absolute values together with trigonometric functions")
        if (lines.any { it.isInequality }) {
            if (lines.size > 1) throw UnsupportedMath("several inequalities at once")
            if (trigVar) throw UnsupportedMath("trigonometric functions inside an inequality")
            if (absVar) return AbsSolver(this).inequality(lines[0])
            return Inequalities(this).solve(lines[0])
        }
        if (absVar) {
            if (lines.size > 1) throw UnsupportedMath("several equations with absolute values")
            return AbsSolver(this).equation(lines[0])
        }
        val eqLines = lines.filter { it.parts.size > 1 }
        if (eqLines.isEmpty()) {
            if (lines.size > 1) throw UnsupportedMath("several separate expressions")
            if (lines[0].parts[0].hasTrig()) return TrigExpression(this).solve(lines[0])
            return simplifyExpression(lines[0])
        }
        if (eqLines.size != lines.size) throw SolveFailure("one line has no “=”")
        val ev = Evaluator(atoms)
        val eqs = ArrayList<Eq>()
        for (line in eqLines) {
            val values = try { line.parts.map { ev.eval(it) } } catch (e: NeedsNumeric) {
                if (eqLines.size != 1 || line.parts.size != 2) throw e
                if (line.parts.any { it.hasTrigVar() }) throw UnsupportedMath(e.message ?: "this trigonometric equation")
                return NumericSearch(this, line.parts[0], line.parts[1], e.message ?: "this equation").solve()
            }
            for (i in 0 until values.size - 1) eqs += Eq(values[i], values[i + 1], line.sources[i], line.sources[i + 1])
        }
        if (atoms.list.any { it is Atom.Trig }) {
            if (eqs.size > 1) throw UnsupportedMath("a system with trigonometric functions")
            if (!atoms.list.all { it is Atom.Trig }) throw UnsupportedMath("trigonometric functions together with roots, logarithms or powers with an unknown exponent")
            return TrigSolver(this, eqLines[0].parts[0], eqLines[0].parts[1], eqs[0]).solve()
        }
        if (!atoms.isEmpty) {
            if (eqs.size > 1) throw UnsupportedMath("a system that contains roots, logarithms or powers with an unknown exponent")
            return AtomSolver(this, ev, eqLines[0].parts[0], eqLines[0].parts[1], eqs[0]).solve()
        }
        return try { solveEquations(eqs) } catch (c: Contradiction) {
            step("", "The equations contradict each other")
            SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), steps, notes)
        }
    }

    // ------------------------------------------------------------------ expression

    private fun simplifyExpression(line: ParsedLine): SolveResult {
        val node = line.parts[0]
        val ev = Evaluator(atoms)
        val raw = ev.eval(node)
        if (!atoms.isEmpty) return LogExpression(this, ev).simplify(raw)
        val hasLogs = node.hasFunc()
        val c = raw.simplified()
        val rawText = Printer.ratFn(raw)
        val finalText = Printer.ratFn(c.value)
        if (hasLogs) {
            val shown = NodeTex.tex(node.mapFuncs { f ->
                val r = ev.constantOf(ev.eval(f))!!.rational
                if (r.signum < 0) Node.Neg(Node.Number(-r)) else Node.Number(r)
            })
            step(shown, "Evaluate each logarithm")
            if (strip(shown) != strip(finalText)) step(finalText, "Simplify")
        } else if (strip(rawText) != strip(line.sources[0])) step(rawText, "Multiply out")
        if (!c.factor.isConstant) {
            step(finalText, "Cancel ${Printer.poly(c.factor)}")
            excludeFactor(c.factor)
        } else if (!hasLogs && strip(rawText) != strip(finalText)) {
            step(finalText, "Simplify")
        }
        val value = c.value.takeIf { it.num.isConstant && it.den.isConstant }?.let { it.num.constant }
        val answer = if (value != null) valueAnswer(null, Val.of(value)) else Answer(finalText, plainOf(finalText))
        return SolveResult.Solved(listOf(answer), steps, notes)
    }

    private fun excludeFactor(f: Poly) {
        if (f.isMonomial) {
            val vars = f.terms.keys.first().powers.keys.sorted()
            if (vars.isNotEmpty()) note("Valid for " + vars.joinToString(", ") { "$it ≠ 0" })
        } else note("Valid for ${Printer.poly(f)} ≠ 0")
    }

    private fun strip(s: String) = s.replace(" ", "").replace("\\cdot", "").replace("{", "").replace("}", "")

    // ------------------------------------------------------------------ equations

    private fun solveEquations(all: List<Eq>): SolveResult {
        if (all.all { it.diff.isZero }) {
            val vars = all.flatMap { it.l.variables + it.r.variables }.toSortedSet()
            if (vars.isNotEmpty()) {
                step("${all[0].lSrc}=${all[0].rSrc}", "Both sides are always equal")
                return SolveResult.Solved(vars.map { Answer("$it\\text{ can be any number}", "$it can be any number") }, steps, notes)
            }
        }
        val known = LinkedHashMap<Char, Val>()
        val multi = LinkedHashMap<Char, List<Val>>()
        var pending: List<Eq> = all
        var identityVars = emptyList<Char>()
        var anyUnknown = false
        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { it.diff.variables.size == 1 }
            if (next != null) {
                anyUnknown = true
                val v = next.diff.variables.first()
                if (all.size > 1) step("${next.lSrc}=${next.rSrc}", "Solve this pair")
                val roots = solveOne(next, v)
                pending = pending - next
                when (roots) {
                    is Roots.None -> return SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), steps, notes)
                    is Roots.All -> identityVars = identityVars + v
                    is Roots.Some -> {
                        if (roots.values.size > 1) {
                            if (pending.isNotEmpty()) throw UnsupportedMath("a quadratic mixed with other equations")
                            multi[v] = roots.values
                        } else {
                            known[v] = roots.values[0]
                            if (pending.isNotEmpty()) {
                                val exact = roots.values[0].exact ?: throw UnsupportedMath("a system whose solution is only approximate")
                                pending = pending.map { it.substitute(v, exact) }
                                val shown = pending.firstOrNull()
                                if (shown != null) step(
                                    "${Printer.ratFn(shown.l.simplified().value)}=${Printer.ratFn(shown.r.simplified().value)}",
                                    "Substitute ${v} = ${plainOf(Printer.surd(exact))}",
                                )
                            }
                        }
                    }
                }
                continue
            }
            // equations with no unknown left: verify them
            val done = pending.filter { it.diff.variables.isEmpty() }
            if (done.isNotEmpty()) {
                for (e in done) if (!e.diff.isZero) throw Contradiction()
                pending = pending - done.toSet()
                continue
            }
            // several unknowns: linear system
            known += solveLinearSystem(pending).mapValues { Val.of(it.value) }
            pending = emptyList()
        }
        if (!anyUnknown && known.isEmpty()) {
            val ok = all.all { it.diff.isZero }
            return SolveResult.Solved(listOf(if (ok) Answer("\\text{True}", "True") else Answer("\\text{False}", "False")), steps, notes)
        }
        val answers = ArrayList<Answer>()
        val order = (known.keys + multi.keys).sortedWith(compareBy({ it != target }, { it }))
        for (v in order) {
            val roots = multi[v]
            answers += if (roots != null) rootsAnswer(v, roots) else valueAnswer(v, known[v]!!)
        }
        for (v in identityVars) answers += Answer("$v\\text{ can be any number}", "$v can be any number")
        return SolveResult.Solved(answers, steps, notes)
    }

    // ------------------------------------------------------------------ one unknown

    /** [check] off: the caller verifies candidates against the original problem itself. */
    internal fun solveOne(eq: Eq, v: Char, check: Boolean = true): Roots {
        val lc = eq.l.simplified()
        val rc = eq.r.simplified()
        val ls = lc.value
        val rs = rc.value
        val excluded = ArrayList<Val>()
        val simplifiedText = "${Printer.ratFnNice(ls)}=${Printer.ratFnNice(rs)}"
        if (strip(simplifiedText).length < strip("${eq.lSrc}=${eq.rSrc}").length) step(simplifiedText, "Simplify each side")

        // values that were cancelled away or sit in a denominator are forbidden
        for (f in listOf(lc.factor, rc.factor, ls.den, rs.den)) if (!f.isConstant && v in f.variables) excluded += rootsOrEmpty(f, v)

        val a: Poly
        val b: Poly
        val hasFractions = !ls.den.isConstant || !rs.den.isConstant
        if (hasFractions && ls.den == rs.den) {
            a = ls.num
            b = rs.num
            step("${Printer.poly(a)}=${Printer.poly(b)}", "Multiply both sides by ${Printer.poly(ls.den).let { if (ls.den.terms.size > 1) "($it)" else it }}")
            if (excluded.isNotEmpty()) note("Excluded: " + excluded.distinctBy { it.d }.joinToString(", ") { "$v ≠ ${plainOf(it.texOrDecimal())}" })
        } else if (hasFractions) {
            a = ls.num * rs.den
            b = rs.num * ls.den
            step("${Printer.product(ls.num, rs.den)}=${Printer.product(rs.num, ls.den)}", "Cross-multiply")
            val expanded = "${Printer.poly(a)}=${Printer.poly(b)}"
            if (strip(expanded) != strip("${Printer.product(ls.num, rs.den)}=${Printer.product(rs.num, ls.den)}")) step(expanded, "Expand")
            if (excluded.isNotEmpty()) note("Excluded: " + excluded.distinctBy { it.d }.joinToString(", ") { "$v ≠ ${plainOf(it.texOrDecimal())}" })
        } else {
            val lcm = denominatorLcm(ls.num, rs.num)
            if (lcm != BigInteger.ONE) {
                val k = Surd.of(Rational.of(lcm))
                a = ls.num.times(k)
                b = rs.num.times(k)
                step("${Printer.poly(a)}=${Printer.poly(b)}", "Multiply both sides by $lcm")
            } else {
                a = ls.num
                b = rs.num
            }
        }
        val p = a - b
        val roots = solvePolynomial(p, a, b, v)
        if (roots !is Roots.Some) return roots
        val kept = roots.values.filter { r -> excluded.none { it.sameAs(r) } }
        if (kept.size < roots.values.size) {
            val dropped = roots.values.filter { it !in kept }
            note("Rejected $v = ${dropped.joinToString(", ") { plainOf(it.texOrDecimal()) }}: it makes a denominator 0")
            step("$v=${dropped.joinToString(",") { it.texOrDecimal() }}", "Rejected: denominator would be 0")
        }
        if (kept.isEmpty()) return Roots.None
        if (check) check(ls, rs, v, kept)
        return Roots.Some(kept)
    }

    /** Least common multiple of the denominators of all rational coefficients (1 if any coefficient is a surd). */
    private fun denominatorLcm(vararg ps: Poly): BigInteger {
        var l = BigInteger.ONE
        for (p in ps) for (c in p.terms.values) {
            if (!c.isRational) return BigInteger.ONE
            val d = c.rational.den
            l = l.multiply(d).divide(l.gcd(d))
        }
        return l
    }

    internal fun rootsOrEmpty(f: Poly, v: Char): List<Val> = try {
        polyRoots(f, v) ?: emptyList()
    } catch (e: UnsupportedMath) {
        emptyList()
    }

    /** Real roots of a polynomial in [v], silently (null: it has more than one unknown). */
    internal fun polyRoots(f: Poly, v: Char): List<Val>? {
        val deg = f.degreeIn(v)
        if (f.variables.any { it != v }) return null
        val c = (0..deg).map { f.coeffOf(v, it).constant }
        return when (deg) {
            0 -> emptyList()
            1 -> listOf(Val.of(-c[0] / c[1]))
            2 -> quadratic(c[2], c[1], c[0])?.map { Val.of(it) }
            else -> RootFinder(this).silent(f, v)
        }
    }

    private fun quadratic(a: Surd, b: Surd, c: Surd): List<Surd>? {
        val d = b * b - Surd.of(4) * a * c
        if (d.signum() < 0) return null
        val two = Surd.of(2) * a
        if (d.isZero) return listOf(-b / two)
        val s = d.sqrt()
        return listOf((-b - s) / two, (-b + s) / two).sortedBy { it.toDouble() }
    }

    private fun solvePolynomial(p: Poly, a: Poly, b: Poly, v: Char): Roots {
        val deg = p.degreeIn(v)
        if (p.variables.any { it != v }) throw SolveFailure("it still has more than one unknown")
        val c = (0..deg).map { p.coeffOf(v, it).constant }
        return when {
            p.isZero -> { step("0=0", "Both sides are always equal"); Roots.All }
            deg == 0 -> { step("${Printer.surd(c[0])}=0", "That is never true"); Roots.None }
            deg == 1 -> linear(a, b, c[1], c[0], v)
            deg == 2 -> quadraticSteps(a, b, p, c[2], c[1], c[0], v)
            else -> RootFinder(this).solve(p, v)
        }
    }

    private fun isSingleMono(p: Poly, m: Mono) = p.isMonomial && p.terms.keys.first() == m
    private fun vTerm(coef: Surd, v: Char, e: Int = 1) = Printer.poly(Poly.of(if (e == 0) Mono.ONE else Mono.of(v, e), coef))

    private fun linear(a: Poly, b: Poly, k: Surd, c0: Surd, v: Char): Roots {
        val already = (isSingleMono(a, Mono.of(v)) && b.isConstant) || (isSingleMono(b, Mono.of(v)) && a.isConstant)
        val rhs = -c0
        if (!already) step("${vTerm(k, v)}=${Printer.surd(rhs)}", "Combine and move terms")
        val x = rhs / k
        val rightAlone = isSingleMono(b, Mono.of(v)) && a.isConstant && b.terms.values.first() == Surd.ONE
        if (k != Surd.ONE && !(already && rightAlone)) step("$v=${Printer.surd(x)}", "Divide both sides by ${Printer.surd(k)}")
        else if (!already) { /* already isolated */ }
        return Roots.Some(listOf(Val.of(x)))
    }

    internal fun quadraticSteps(a: Poly, b: Poly, p: Poly, qa: Surd, qb: Surd, qc: Surd, v: Char): Roots {
        if (qb.isZero) {
            val already = (isSingleMono(a, Mono.of(v, 2)) && b.isConstant) || (isSingleMono(b, Mono.of(v, 2)) && a.isConstant)
            if (!already) step("${vTerm(qa, v, 2)}=${Printer.surd(-qc)}", "Move terms")
            val k = -qc / qa
            if (qa != Surd.ONE) step("$v^{2}=${Printer.surd(k)}", "Divide both sides by ${Printer.surd(qa)}")
            if (k.signum() < 0) { step("$v^{2}<0", "A square is never negative: no real solution"); return Roots.None }
            if (k.isZero) return Roots.Some(listOf(Val.of(Surd.ZERO)))
            val root = k.sqrt()
            val plain = "\\sqrt{${Printer.surd(k)}}"
            step("$v=\\pm$plain", "Take the square root (±)")
            if (Printer.surd(root) != plain) step("$v=\\pm ${Printer.surd(root)}", "Simplify")
            return Roots.Some(listOf(Val.of(-root), Val.of(root)))
        }
        step("${Printer.poly(p)}=0", "Move everything to one side")
        if (qc.isZero) {
            step("$v(${Printer.poly(Poly.of(Mono.of(v), qa) + Poly.const(qb))})=0", "Factor out $v")
            return Roots.Some(listOf(Surd.ZERO, -qb / qa).sortedBy { it.toDouble() }.map { Val.of(it) })
        }
        val d = qb * qb - Surd.of(4) * qa * qc
        step("D=b^{2}-4ac=${Printer.surd(d)}", "Discriminant")
        if (d.signum() < 0) { step("D<0", "Negative: no real solution"); return Roots.None }
        val two = Surd.of(2) * qa
        if (d.isZero) {
            val r = -qb / two
            step("$v=${Printer.surd(r)}", "D = 0: one solution")
            return Roots.Some(listOf(Val.of(r)))
        }
        val s = d.sqrt()
        val rational = d.isRational && s.isRational
        step("$v=\\frac{${Printer.surd(-qb)}\\pm\\sqrt{${Printer.surd(d)}}}{${Printer.surd(two)}}", if (rational) "Quadratic formula (D is a perfect square)" else "Quadratic formula")
        val roots = listOf((-qb - s) / two, (-qb + s) / two).sortedBy { it.toDouble() }
        step(roots.joinToString("\\ \\text{or}\\ ") { "$v=${Printer.surd(it)}" }, "Solutions")
        return Roots.Some(roots.map { Val.of(it) })
    }

    private fun check(l: RatFn, r: RatFn, v: Char, vals: List<Val>) {
        val roots = vals.mapNotNull { it.exact }
        val shown = if (roots.size == 2 && roots[0] == -roots[1]) listOf(roots[1]) else roots
        for (x in shown) {
            val lv = l.substitute(v, x).simplified().value
            val rv = r.substitute(v, x).simplified().value
            step("${Printer.ratFn(lv)}=${Printer.ratFn(rv)}", "Check $v = ${plainOf(Printer.surd(x))}")
        }
    }

    // ------------------------------------------------------------------ linear systems

    private fun solveLinearSystem(eqs: List<Eq>): Map<Char, Surd> {
        val polys = eqs.map { it.diff }
        val vars = polys.flatMap { it.variables }.toSortedSet().toList()
        if (polys.size < vars.size) throw SolveFailure("there are ${vars.size} unknowns (${vars.joinToString(", ")}) but only ${polys.size} equation${if (polys.size == 1) "" else "s"}")
        for (p in polys) if (p.terms.keys.any { it.degree > 1 }) throw UnsupportedMath("a system that isn't linear")
        val rows = polys.map { p -> (vars.map { p.coeffOf(it, 1).constant } + (-p.constant)).toMutableList() }.toMutableList()
        fun line(row: List<Surd>): String {
            var left = Poly.ZERO
            vars.forEachIndexed { i, v -> left += Poly.of(Mono.of(v), row[i]) }
            return "${Printer.poly(left)}=${Printer.surd(row.last())}"
        }
        fun block(rs: List<List<Surd>>) = "\\begin{aligned}" + rs.joinToString("\\\\") { line(it) } + "\\end{aligned}"
        step(block(rows), "Write in standard form")
        var r = 0
        for (col in vars.indices) {
            val pivot = (r until rows.size).firstOrNull { !rows[it][col].isZero } ?: throw SolveFailure("the equations don't pin down ${vars[col]}")
            rows[r] = rows[pivot].also { rows[pivot] = rows[r] }
            for (i in r + 1 until rows.size) {
                if (rows[i][col].isZero) continue
                val f = rows[i][col] / rows[r][col]
                for (j in rows[i].indices) rows[i][j] = rows[i][j] - f * rows[r][j]
            }
            r++
        }
        for (i in r until rows.size) if (!rows[i].last().isZero) throw Contradiction()
        val tri = rows.take(vars.size)
        step(block(tri), "Eliminate unknowns")
        val sol = HashMap<Char, Surd>()
        for (i in vars.indices.reversed()) {
            var rhs = tri[i].last()
            for (j in i + 1 until vars.size) rhs -= tri[i][j] * sol[vars[j]]!!
            sol[vars[i]] = rhs / tri[i][i]
        }
        step("\\begin{aligned}" + vars.joinToString("\\\\") { "$it=${Printer.surd(sol[it]!!)}" } + "\\end{aligned}", "Back-substitute")
        return vars.associateWith { sol[it]!! }
    }

    // ------------------------------------------------------------------ answers

    internal fun valueAnswer(v: Char?, x: Val): Answer {
        val lhs = if (v != null) "$v=" else ""
        val tex = when {
            x.exact != null -> {
                val d = Printer.decimal(x.exact)
                lhs + Printer.surd(x.exact) + (d?.let { (if (it.exact) "=" else "\\approx") + it.text } ?: "")
            }
            x.tex != null -> lhs + x.tex + "\\approx" + Printer.num(x.d, 3)
            else -> (if (v != null) "$v" else "") + "\\approx" + Printer.num(x.d, 4)
        } + RootFinder.multTex(x.mult)
        return Answer(tex, plainOf(tex))
    }

    private fun pmTex(v: Char, pos: Val): String = when {
        pos.exact != null -> {
            val d = Printer.decimal(pos.exact)
            "$v=\\pm ${Printer.surd(pos.exact)}" + (d?.let { (if (it.exact) "=" else "\\approx") + "\\pm" + it.text } ?: "")
        }
        pos.tex != null -> "$v=\\pm ${pos.tex}\\approx\\pm${Printer.num(pos.d, 3)}"
        else -> "$v\\approx\\pm${Printer.num(pos.d, 4)}"
    } + RootFinder.multTex(pos.mult)

    internal fun rootsAnswer(v: Char, roots: List<Val>): Answer {
        // pair x and -x into ±x
        val used = BooleanArray(roots.size)
        val groups = ArrayList<Pair<Val, Boolean>>()
        for (i in roots.indices) {
            if (used[i]) continue
            val r = roots[i]
            val j = roots.indices.firstOrNull { it != i && !used[it] && roots[it].d > 0 && r.d < 0 && Math.abs(roots[it].d + r.d) <= 1e-12 * Math.max(1.0, Math.abs(r.d)) && roots[it].mult == r.mult && roots[it].isExact == r.isExact }
            if (j != null) { used[i] = true; used[j] = true; groups += roots[j] to true } else { used[i] = true; groups += r to false }
        }
        val anyPair = groups.any { it.second }
        val ordered = if (anyPair) groups.sortedBy { Math.abs(it.first.d) } else groups
        val tex = ordered.joinToString("\\ \\text{or}\\ ") { (r, pm) -> if (pm) pmTex(v, r) else valueAnswer(v, r).latex }
        return Answer(tex, plainOf(tex))
    }
}

/** Converts the small LaTeX subset the solver prints into plain text for the clipboard. */
fun plainOf(latex: String): String {
    var s = latex.replace("\\pi", "π").replace("^{\\circ}", "°").replace("\\left|", "|").replace("\\right|", "|")
        .replace("\\{", "\u0001").replace("\\}", "\u0002").replace("\\mathbb{Z}", "ℤ").replace("\\;", " ").replace("\\quad", " ")
    val frac = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")
    val sqrtN = Regex("""\\sqrt\[(\d+)\]\{([^{}]*)\}""")
    val sqrt = Regex("""\\sqrt\{([^{}]*)\}""")
    val pow = Regex("""\^\{(\d+)\}""")
    // innermost first, so nested roots and fractions inside roots work
    do {
        val before = s
        s = frac.replace(s) { m ->
            fun wrap(t: String) = if (t.all { it.isLetterOrDigit() || it == '.' || it == '√' || it == 'π' || it in "⁰¹²³⁴⁵⁶⁷⁸⁹" }) t else "($t)"
            // 1/(2√x), not 1/2√x
            val den = m.groupValues[2].let { d -> if (Regex("""\d[A-Za-zπ√]""").containsMatchIn(d)) "($d)" else wrap(d) }
            val q = wrap(m.groupValues[1]) + "/" + den
            // (π/180)cos x, not π/180cos x
            if (Regex("""^(?:[A-Za-z0-9(]|\\(?:sin|cos|tan|cot|sec|csc|arc|ln|log|pi|sqrt|frac))""").containsMatchIn(s.substring(m.range.last + 1))) "($q)" else q
        }
        s = sqrtN.replace(s) {
            val n = it.groupValues[1]
            (if (n == "3") "∛" else n.map { c -> "⁰¹²³⁴⁵⁶⁷⁸⁹"[c - '0'] }.joinToString("") + "√") + it.groupValues[2]
        }
        s = sqrt.replace(s) {
            val inner = it.groupValues[1]
            "√" + if (inner.all { c -> c.isLetterOrDigit() || c == '.' || c == 'π' }) inner else "($inner)"
        }
        s = pow.replace(s) { it.groupValues[1].map { c -> "⁰¹²³⁴⁵⁶⁷⁸⁹"[c - '0'] }.joinToString("") }
    } while (s != before)
    // 2^{x+1} is 2^(x+1), not 2^x+1; a single character needs no brackets
    s = Regex("""\^\{([^{}]+)\}""").replace(s) { if (it.groupValues[1].length == 1) "^" + it.groupValues[1] else "^(" + it.groupValues[1] + ")" }
    s = Regex("""\\log_\{([^{}]*)\}""").replace(s) { "log_" + it.groupValues[1] }
    s = s.replace("\\approx", "≈").replace("\\pm", "±").replace("\\cdot", "·")
        .replace("\\ \\text{or}\\ ", " or ").replace("\\text{", "").replace("\\ ", " ").replace("\\,", " ")
        .replace("\\pi", "π").replace("\\infty", "∞").replace("\\cup", "∪").replace("\\in", "∈").replace("\\cap", "∩")
        .replace("\\varnothing", "∅").replace("\\emptyset", "∅").replace("\\mathbb{R}", "ℝ")
        .replace("\\ne", "≠").replace("\\Rightarrow", "⇒").replace("\\times", "×")
    s = Regex("""\\le(?![a-z])""").replace(s, "≤")
    s = Regex("""\\ge(?![a-z])""").replace(s, "≥")
    // a function name right after a letter or a bracket is a product: x·ln 3, not xln 3
    s = Regex("""\\(arcsin|arccos|arctan|sin|cos|tan|cot|sec|csc|ln|log)(?![a-z])""").replace(s) {
        val prev = s.getOrNull(it.range.first - 1)
        (if (prev != null && (prev.isLetter() || prev == ')' || prev == '}')) "·" else "") + it.groupValues[1]
    }
    s = s.replace("± ", "±")
    s = Regex("(?<=[\\w√π)∞°])([+-])(?=[\\w√π(∞|])").replace(s) { " ${it.value} " }
    s = s.replace("{", "").replace("}", "").replace("=", " = ").replace("≈", " ≈ ").replace("\u0001", "{").replace("\u0002", "}")
    s = s.replace("≤", " ≤ ").replace("≥", " ≥ ").replace("∈", " ∈ ").replace("<", " < ").replace(">", " > ")
    s = s.replace("∪", " ∪ ").replace(",", ", ").replace("≠", " ≠ ")
    return s.replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
}
