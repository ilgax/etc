package dev.ilgax.utils.math.solver

import kotlin.math.floor

/**
 * Trigonometric equations in one unknown. The polynomial engine holds `sin(ax+b)` etc. as placeholder symbols
 * ([Atom.Trig]); this class reduces the equation to ONE function of ONE argument:
 *  - a polynomial in a single function (`2\sin^2 x-\sin x-1=0`: substitute u = sin x, solve, drop |u| > 1),
 *  - sin and cos together where one of them only appears squared (sin² = 1 − cos²),
 *  - `a\sin x = b\cos x` (divide by cos: tan x = b/a),
 * then solves `f(θ) = c` for each value c: a table lookup for the exact angles (multiples of 15°) or an inverse
 * function numerically, the general solution θ = θ₀ + P·k, and x = (θ − b)/a. The solutions in one turn
 * ([0°, 360°) or [0, 2π)) are listed and every one is checked against the original equation.
 * Anything else (several arguments, different functions, the unknown outside the functions) is reported as unsupported;
 * a numeric search is never used because a periodic equation has infinitely many roots.
 */
internal class TrigSolver(
    private val s: SolveSession,
    private val lNode: Node,
    private val rNode: Node,
    private val eq: Eq,
) {
    private val atoms = s.atoms
    private fun step(latex: String, caption: String) = s.step(latex, caption)

    private var v = 'x'
    private var deg = true

    private sealed interface Plan {
        data object All : Plan
        data object None : Plan
        class Some(val fn: String, val values: List<Val>, val head: Atom.Trig) : Plan
    }

    /** One value of θ = a·x + b and its solutions: x = x0 + period·k. */
    private class Fam(val x0: Ang)

    fun solve(): SolveResult {
        val p = eq.diff
        val all = atoms.list.filterIsInstance<Atom.Trig>()
        v = all.first().v
        deg = all.first().deg
        if (all.any { it.v != v }) throw UnsupportedMath("trigonometric functions of several unknowns")
        if (p.variables.userVars.isNotEmpty()) throw UnsupportedMath("the unknown both inside and outside a trigonometric function")
        val present = p.variables.atomSyms.mapNotNull { atoms[it] as? Atom.Trig }
        if (present.isEmpty()) {
            if (p.isZero) {
                step("0=0", "Both sides are always equal")
                return anyNumber()
            }
            step("${Printer.poly(p)}=0", "That is never true")
            return noSolution()
        }
        val head = present.first()
        if (present.any { it.a != head.a || it.b != head.b }) {
            throw UnsupportedMath("functions of different angles (like sin 2x together with cos x; double-angle identities are not applied)")
        }
        val plan = reduce(p, present)
        return when (plan) {
            is Plan.None -> noSolution()
            is Plan.All -> anyNumber()
            is Plan.Some -> finish(plan)
        }
    }

    private fun noSolution() = SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)
    private fun anyNumber() = SolveResult.Solved(listOf(Answer("$v\\text{ can be any number}", "$v can be any number")), s.steps, s.notes)

    // ------------------------------------------------------------------ reduce to one function

    private fun reduce(p: Poly, present: List<Atom.Trig>): Plan {
        val fns = present.map { it.fn }.distinct()
        if (fns.size == 1) return single(p, present[0])
        if (fns.toSet() == setOf("sin", "cos")) return sinCos(p, present.first { it.fn == "sin" }, present.first { it.fn == "cos" })
        throw UnsupportedMath("different trigonometric functions in one equation (${fns.joinToString(", ")}); only sin together with cos is reduced to one function")
    }

    private fun single(p: Poly, a: Atom.Trig): Plan {
        if (p.degreeIn(a.sym) == 1 && p.coeffOf(a.sym, 1).isConstant) {
            val c1 = p.coeffOf(a.sym, 1).constant
            val c0 = p.coeffOf(a.sym, 0).constant
            val value = -c0 / c1
            val plainForm = (lNode is Node.Trig && !rNode.hasVariable()) || (rNode is Node.Trig && !lNode.hasVariable())
            if (!plainForm) step("${a.tex}=${Printer.surd(value)}", "Isolate ${plainOf(a.tex)}")
            return Plan.Some(a.fn, listOf(Val.of(value)), a)
        }
        return polynomialIn(p, a)
    }

    private fun letter(): Char = "uwtz".first { it != v }

    private fun rename(p: Poly, from: Char, to: Char): Poly {
        var out = Poly.ZERO
        for (e in 0..p.degreeIn(from)) {
            val c = p.coeffOf(from, e)
            if (!c.isZero) out += c * (if (e == 0) Poly.ONE else Poly.of(Mono.of(to, e), Surd.ONE))
        }
        return out
    }

    /** Substitute u = f(θ), solve the polynomial in u. */
    private fun polynomialIn(p: Poly, a: Atom.Trig): Plan {
        val u = letter()
        val pu = rename(p, a.sym, u)
        step("${Printer.poly(pu)}=0", "Let $u = ${plainOf(a.tex)}")
        return when (val r = s.solveOne(Eq(RatFn(pu, Poly.ONE), RatFn(Poly.ZERO, Poly.ONE), Printer.poly(pu), "0"), u, check = false)) {
            is Roots.None -> Plan.None
            is Roots.All -> Plan.All
            is Roots.Some -> Plan.Some(a.fn, r.values.map { it.withMult(1) }, a)
        }
    }

    private fun sinCos(p: Poly, sin: Atom.Trig, cos: Atom.Trig): Plan {
        fun evenIn(a: Atom.Trig) = p.terms.keys.all { it.exp(a.sym) % 2 == 0 }
        fun eliminate(drop: Atom.Trig, keep: Atom.Trig): Plan {
            var out = Poly.ZERO
            val sub = Poly.ONE - Poly.of(Mono.of(keep.sym, 2), Surd.ONE)
            for ((m, c) in p.terms) {
                val e = m.exp(drop.sym)
                val base = Poly.of(Mono(m.powers - drop.sym), c)
                out += if (e == 0) base else base * sub.pow(e / 2)
            }
            val tail = drop.tex.removePrefix("\\" + drop.fn)
            step("\\${drop.fn}^{2}$tail=1-\\${keep.fn}^{2}$tail", "Use sin² + cos² = 1 to write ${drop.fn}² with ${keep.fn}")
            return polynomialIn(out, keep)
        }
        if (evenIn(cos)) return eliminate(cos, sin)
        if (evenIn(sin)) return eliminate(sin, cos)
        val homogeneous = p.terms.keys.all { it == Mono.of(sin.sym) || it == Mono.of(cos.sym) } && p.terms.size == 2
        if (homogeneous) {
            val value = -p.coeffOf(cos.sym, 1).constant / p.coeffOf(sin.sym, 1).constant
            val tan = Atom.Trig(sin.sym, "\\tan" + sin.tex.removePrefix("\\sin"), "tan", sin.v, sin.a, sin.b, sin.deg)
            step("${tan.tex}=${Printer.surd(value)}", "Divide both sides by ${plainOf(cos.tex)} (it cannot be 0 here, or sin would be 0 too)")
            return Plan.Some("tan", listOf(Val.of(value)), tan)
        }
        throw UnsupportedMath("a mix of sin and cos that doesn't reduce to one function (like sin x + cos x = 1 or sin x · cos x = 1/4)")
    }

    // ------------------------------------------------------------------ f(θ) = c

    private class Basic(val bases: List<Ang>, val exact: Boolean, val fn: String, val c: Val)

    private fun turn() = TrigExact.angle(360, deg)

    private fun periodOf(fn: String) = TrigExact.angle(if (fn == "tan" || fn == "cot") 180 else 360, deg)

    private fun outOfRange(fn: String, c: Val): Boolean {
        val abs = fn == "sin" || fn == "cos"
        val big = fn == "sec" || fn == "csc"
        if (!abs && !big) return false
        val exact = c.exact
        val dir = if (exact != null) (exact * exact - Surd.ONE).signum() else (c.d * c.d - 1).let { if (Math.abs(it) < 1e-12) 0 else if (it > 0) 1 else -1 }
        return if (abs) dir > 0 else dir < 0
    }

    private fun basic(fn: String, c: Val): Basic {
        val period = periodOf(fn)
        c.exact?.let { ce ->
            val degs = TrigExact.solveDeg(fn, ce)
            if (degs.isNotEmpty()) return Basic(degs.map { Ang.of(TrigExact.angle(it, deg)) }, true, fn, c)
        }
        val x = c.d
        fun clamp(t: Double) = t.coerceIn(-1.0, 1.0)
        val rad: List<Double> = when (fn) {
            "sin" -> Math.asin(clamp(x)).let { listOf(it, Math.PI - it) }
            "cos" -> Math.acos(clamp(x)).let { listOf(it, -it) }
            "tan" -> listOf(Math.atan(x))
            "cot" -> listOf(Math.atan2(1.0, x))
            "sec" -> Math.acos(clamp(1 / x)).let { listOf(it, -it) }
            else -> Math.asin(clamp(1 / x)).let { listOf(it, Math.PI - it) }
        }
        val pd = period.toDouble()
        val out = ArrayList<Ang>()
        for (r in rad) {
            var t = (if (deg) Math.toDegrees(r) else r) % pd
            if (t < 0) t += pd
            if (t > pd - 1e-9) t = 0.0
            if (out.none { Math.abs(it.d - t) < 1e-9 }) out += Ang.num(t)
        }
        return Basic(out.sortedBy { it.d }, false, fn, c)
    }

    private fun argTex(head: Atom.Trig): String {
        val ax = Printer.poly(Poly.of(Mono.of(v), head.a))
        if (head.b.isZero) return ax
        val bt = AngleFmt.exact(head.b.let { if (it.signum() < 0) -it else it }, deg)
        return ax + (if (head.b.signum() < 0) "-" else "+") + bt
    }

    private fun isPlainArg(head: Atom.Trig) = head.a == Surd.ONE && head.b.isZero

    // ------------------------------------------------------------------ solve and list

    private fun finish(plan: Plan.Some): SolveResult {
        val head = plan.head
        // values of f(θ) that the function can never take are dropped
        val values = ArrayList<Val>()
        for (c in plan.values.sortedBy { it.d }) {
            if (outOfRange(plan.fn, c)) {
                val why = if (plan.fn == "sec" || plan.fn == "csc") "stays outside (-1, 1)" else "always stays between -1 and 1"
                step("${head.tex}=${c.texOrDecimal()}", "Rejected: ${plainOf(head.tex)} $why")
                s.note("Rejected ${plainOf(head.tex)} = ${plainOf(c.texOrDecimal())}: ${plain(plan.fn)} $why")
            } else if (plan.fn == "sec" && c.d == 0.0 || plan.fn == "csc" && c.d == 0.0) {
                step("${head.tex}=0", "Rejected: ${plain(plan.fn)} is never 0")
            } else values += c
        }
        if (values.isEmpty()) return noSolution()

        val a = head.a
        val b = head.b
        val plainArg = isPlainArg(head)
        val period = periodOf(plan.fn)
        val px = try { period / (if (a.signum() < 0) -a else a) } catch (e: UnsupportedMath) { throw UnsupportedMath("an angle coefficient that is a sum of roots") }
        val fams = ArrayList<Fam>()
        for (c in values) {
            val bs = basic(plan.fn, c)
            val shownC = c.texOrDecimal()
            if (bs.exact) {
                val angles = bs.bases.joinToString("\\ \\text{or}\\ ") { "\\theta=${AngleFmt.of(it, deg)}" }
                step("\\${plan.fn}\\theta=$shownC\\Rightarrow $angles", "Base angle${if (bs.bases.size > 1) "s" else ""} in [0, ${plainOf(AngleFmt.exact(period, deg))}) where ${plain(plan.fn)} θ = ${plainOf(shownC)}")
            } else {
                val inv = when (plan.fn) { "sin", "csc" -> "arcsin"; "cos", "sec" -> "arccos"; else -> "arctan" }
                val arg = when (plan.fn) { "sec", "csc", "cot" -> "\\frac{1}{$shownC}"; else -> shownC }
                step("\\theta=\\$inv\\left($arg\\right)\\approx ${AngleFmt.of(bs.bases[0], deg)}", "Not a standard value: inverse function, numeric")
            }
            val pTex = AngleFmt.exact(period, deg)
            val general = bs.bases.joinToString(",\\; ") { "${argTex(head)} = ${AngleFmt.of(it, deg)} + $pTex${if (deg) "" else "\\,"}k" }
            if (!plainArg) step("$general,\\; k\\in\\mathbb{Z}", "General solution for the argument")
            for (th in bs.bases) {
                var x0 = if (th.s != null) Ang.of((th.s - b) / a) else Ang.num((th.d - b.toDouble()) / a.toDouble())
                val pxd = px.toDouble()
                val n = floor(x0.d / pxd + 1e-9)
                if (n != 0.0) {
                    x0 = if (x0.s != null) Ang.of(x0.s - px * Surd.of(n.toLong())) else Ang.num(x0.d - pxd * n)
                }
                if (x0.s == null && x0.d < 0 && x0.d > -1e-9) x0 = Ang.num(0.0)
                if (fams.none { Math.abs(it.x0.d - x0.d) < 1e-9 }) fams += Fam(x0)
            }
        }
        fams.sortBy { it.x0.d }

        fun famTex(f: Fam): String {
            val k = AngleFmt.exact(px, deg) + (if (deg) "k" else "\\,k")
            val x0Zero = f.x0.s?.isZero == true || (f.x0.s == null && Math.abs(f.x0.d) < 1e-9)
            return if (x0Zero) "$v = $k" else "$v = ${AngleFmt.of(f.x0, deg)} + $k"
        }
        val generalTex = fams.joinToString(",\\; ") { famTex(it) } + ",\\; k\\in\\mathbb{Z}"
        step(generalTex, "General solution (k is any integer)")

        // the solutions of one turn
        val full = turn()
        fun below(x: Ang): Boolean = if (x.s != null) (full - x.s).signum() > 0 else x.d < full.toDouble() - 1e-9
        val members = fams.map { f ->
            val list = ArrayList<Ang>()
            var k = 0
            while (list.size <= 200) {
                val x = if (f.x0.s != null) Ang.of(f.x0.s + px * Surd.of(k.toLong())) else Ang.num(f.x0.d + px.toDouble() * k)
                if (!below(x)) break
                list += x
                k++
            }
            list
        }
        // every solution is checked against the original equation (poles of tan/sec/csc included)
        val good = ArrayList<Fam>()
        val goodMembers = ArrayList<List<Ang>>()
        for ((i, f) in fams.withIndex()) {
            var ok = true
            val toCheck = if (members[i].any { Math.abs(it.d - f.x0.d) < 1e-9 }) members[i] else listOf(f.x0) + members[i]
            for (x in toCheck) {
                val cv = x.s?.let { Val.of(it) } ?: Val.approx(x.d)
                val label = plainOf(AngleFmt.of(x, deg))
                when (val r = Candidates.at(lNode, rNode, v, cv)) {
                    is Candidates.Check.Ok -> if (members[i].any { Math.abs(it.d - x.d) < 1e-9 }) step(r.latex, "Check $v = $label: ok")
                    is Candidates.Check.Bad -> {
                        ok = false
                        step(r.latex ?: "$v=${AngleFmt.of(x, deg)}", "Check $v = $label: rejected, ${r.reason}")
                        s.note("Rejected $v = $label: ${r.reason}")
                    }
                }
            }
            if (ok) { good += f; goodMembers += members[i] }
        }
        if (good.isEmpty()) return noSolution()
        if (good.any { it.x0.s == null }) s.note("Decimal angles are rounded to 4 places")
        s.note(if (deg) "Angles are in degrees" else "Angles are in radians")
        val periodList = goodMembers.flatten().sortedBy { it.d }.distinctBy { Math.round(it.d * 1e7) }
        val range = "[0, ${plainOf(AngleFmt.exact(full, deg))})"
        if (periodList.isEmpty() || periodList.size > 24) {
            s.note("The solutions repeat every ${plainOf(AngleFmt.exact(px, deg))}; the general solution is shown")
            val g = good.joinToString(",\\; ") { famTex(it) } + ",\\; k\\in\\mathbb{Z}"
            return SolveResult.Solved(listOf(Answer(g, plainOf(g))), s.steps, s.notes)
        }
        s.note("Solutions in $range; add ${plainOf(AngleFmt.exact(px, deg))}·k (k an integer) for all of them")
        step("$v\\in\\{${periodList.joinToString(", ") { AngleFmt.of(it, deg) }}\\}", "Solutions in $range")
        val tex = "$v \\in \\{${periodList.joinToString(", ") { AngleFmt.of(it, deg) }}\\}"
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }

    private fun plain(fn: String) = fn
}

/** Expressions without an unknown: `\sin 30° + \cos 60°`, `\tan 90°`. Exact when the angles are multiples of 15°, else numeric. */
internal class TrigExpression(private val s: SolveSession) {
    fun solve(line: ParsedLine): SolveResult {
        val node = line.parts[0]
        if (node.hasVariable()) throw UnsupportedMath("a trigonometric expression with an unknown but no equation")
        val deg = run { var d = true; node.any { if (it is Node.Trig) { d = it.deg; true } else false }; d }
        val trigs = ArrayList<Node.Trig>()
        node.any { if (it is Node.Trig && it !in trigs) trigs += it; false }
        val angleResult = node is Node.Trig && node.name.startsWith("arc")
        val src = NodeTex.tex(node)
        try {
            val exactLines = ArrayList<String>()
            for (t in trigs) {
                try { exactLines += "${NodeTex.tex(t)}&=${Printer.surd(NodeExact.eval(t, emptyMap())).let { x -> if (t.name.startsWith("arc")) AngleFmt.exact(NodeExact.eval(t, emptyMap()), t.deg) else x }}" } catch (_: NotExact) {}
            }
            if (exactLines.isNotEmpty() && trigs.size > 0) {
                s.step("\\begin{aligned}${exactLines.joinToString("\\\\")}\\end{aligned}", "Exact values of the trigonometric terms")
            }
            val value = NodeExact.eval(node, emptyMap())
            val shown = if (angleResult) AngleFmt.exact(value, deg) else Printer.surd(value)
            if (node !is Node.Trig) s.step("$src=$shown", "Combine")
            val answer = if (angleResult) Answer(shown, plainOf(shown)) else s.valueAnswer(null, Val.of(value))
            return SolveResult.Solved(listOf(answer), s.steps, s.notes)
        } catch (u: Undefined) {
            s.step(src, u.why)
            return SolveResult.Solved(listOf(Answer("\\text{undefined}", "undefined")), s.steps, s.notes)
        } catch (_: NotExact) {
        } catch (_: UnsupportedMath) {
        }
        // numeric
        val d = try { NodeNum.eval(node, emptyMap()) } catch (u: Undefined) {
            s.step(src, u.why)
            return SolveResult.Solved(listOf(Answer("\\text{undefined}", "undefined")), s.steps, s.notes)
        }
        snap(d)?.let { q ->
            s.step("$src=${Printer.rational(q)}", "Equal to an exact value (a trigonometric identity)")
            return SolveResult.Solved(listOf(s.valueAnswer(null, Val.of(q))), s.steps, s.notes)
        }
        s.note("Not an exact angle: the value is numeric")
        val shown = if (angleResult) AngleFmt.numeric(d, deg) else Printer.num(d, 4)
        s.step("$src\\approx $shown", "Numeric value to 4 decimals")
        val tex = "\\approx $shown"
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }

    /** A small fraction that the double equals to 1e-11: how sin²θ + cos²θ = 1 shows up for angles that are not standard. */
    private fun snap(d: Double): Rational? {
        for (q in 1..60) {
            val p = Math.round(d * q)
            if (Math.abs(d * q - p) < 1e-10 * q) return Rational.of(p, q.toLong())
        }
        return null
    }
}
