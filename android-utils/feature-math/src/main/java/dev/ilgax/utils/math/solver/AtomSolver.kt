package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger

/**
 * The "isolate and invert" layer. The polynomial engine holds the equation with placeholder symbols for each root,
 * power-of-the-unknown or logarithm (see [Atom]); this class isolates the symbol with ordinary algebra, applies the
 * inverse operation (square, take a log, raise to a power) and recurses into the polynomial solver. Every candidate is
 * then checked against the *original* equation, exactly when the values are surds and numerically otherwise, and
 * rejected candidates are shown in "Check" steps.
 */
internal class AtomSolver(
    private val s: SolveSession,
    private val ev: Evaluator,
    private val lNode: Node,
    private val rNode: Node,
    private val eq: Eq,
) {
    private val atoms = s.atoms
    private fun step(latex: String, caption: String) = s.step(latex, caption)

    fun solve(): SolveResult {
        val stepMark = s.steps.size
        val noteMark = s.notes.size
        try {
            return exact()
        } catch (e: NeedsNumeric) {
            while (s.steps.size > stepMark) s.steps.removeAt(s.steps.size - 1)
            while (s.notes.size > noteMark) s.notes.removeAt(s.notes.size - 1)
            return NumericSearch(s, lNode, rNode, e.message ?: "this mix of terms").solve()
        }
    }

    // ------------------------------------------------------------------ dispatch

    private fun atomVars(a: Atom): Set<Char> = when (a) {
        is Atom.Rad -> a.arg.variables.userVars
        is Atom.Pow -> a.exp.variables.userVars
        is Atom.Log -> a.arg.variables.userVars
        is Atom.Trig -> setOf(a.v)
    }

    private fun exact(): SolveResult {
        val p = eq.diff
        val syms = p.variables.atomSyms.mapNotNull { atoms[it] }
        var vars = p.variables.userVars
        for (a in syms) vars = vars + atomVars(a)
        if (vars.isEmpty()) throw UnsupportedMath("a statement about logarithms or roots of plain numbers that aren't exact")
        if (vars.size > 1) throw UnsupportedMath("several unknowns together with roots, logarithms or powers with an unknown exponent")
        val v = vars.first()
        if (syms.any { it.isConstant } && !syms.all { it is Atom.Log }) throw NeedsNumeric("a logarithm or power of a plain number that isn't exact, next to the unknown")
        if (syms.isEmpty()) throw UnsupportedMath("an equation these methods do not reach")
        return when {
            syms.all { it is Atom.Rad } -> radicals(p, syms.map { it as Atom.Rad }, v)
            syms.all { it is Atom.Pow } -> exponentials(p, syms.map { it as Atom.Pow }, v)
            syms.all { it is Atom.Log } -> logs(p, syms.map { it as Atom.Log }, v)
            else -> throw NeedsNumeric("a mix of roots, exponentials and logarithms in one equation")
        }
    }

    // ------------------------------------------------------------------ verification

    private fun plain(t: String) = plainOf(t)
    private fun sym(a: Atom) = RatFn(Poly.variable(a.sym), Poly.ONE)
    private fun ratConst(sv: Surd) = RatFn(Poly.const(sv), Poly.ONE)
    private fun one(p: Poly) = RatFn(p, Poly.ONE)

    private sealed interface Check {
        class Ok(val latex: String) : Check
        class Bad(val latex: String?, val reason: String) : Check
    }

    private fun checkAt(x: Val, v: Char): Check {
        if (x.exact != null) {
            try {
                val a = NodeExact.eval(lNode, mapOf(v to x.exact))
                val b = NodeExact.eval(rNode, mapOf(v to x.exact))
                return if (a == b) Check.Ok("${Printer.surd(a)}=${Printer.surd(b)}")
                else Check.Bad("${Printer.surd(a)}\\ne${Printer.surd(b)}", "left side is ${plain(Printer.surd(a))}, right side is ${plain(Printer.surd(b))}: extraneous")
            } catch (u: Undefined) {
                return Check.Bad(null, u.why)
            } catch (_: NotExact) {
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

    private fun verify(cands: List<Val>, v: Char): List<Val> {
        val distinct = ArrayList<Val>()
        for (c in cands.sortedBy { it.d }) if (distinct.none { it.sameAs(c) }) distinct += c.withMult(1)
        val kept = ArrayList<Val>()
        for (x in distinct) {
            val shown = plain(x.texOrDecimal()).let { if (x.isApprox) "≈$it" else it }
            when (val r = checkAt(x, v)) {
                is Check.Ok -> { step(r.latex, "Check $v = $shown: ok"); kept += x }
                is Check.Bad -> {
                    step(r.latex ?: "$v=${x.texOrDecimal()}", "Check $v = $shown: rejected, ${r.reason}")
                    s.note("Rejected $v = $shown: ${r.reason}")
                }
            }
        }
        return kept
    }

    private fun finish(v: Char, cands: List<Val>): SolveResult {
        if (cands.isEmpty()) return noSolution()
        val kept = verify(cands, v)
        if (kept.isEmpty()) return noSolution()
        if (kept.any { it.isApprox }) s.note("Decimal answers are approximate")
        return SolveResult.Solved(listOf(s.rootsAnswer(v, kept.sortedBy { it.d })), s.steps, s.notes)
    }

    private fun noSolution() = SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)

    private fun roots(r: Roots): List<Val> = when (r) {
        is Roots.Some -> r.values
        is Roots.None -> emptyList()
        is Roots.All -> throw UnsupportedMath("an equation that holds on a whole interval")
    }

    private fun solvePoly(l: RatFn, r: RatFn, v: Char) =
        roots(s.solveOne(Eq(l, r, Printer.ratFn(l), Printer.ratFn(r)), v, check = false))

    private fun strip(t: String) = t.replace(" ", "").replace("{", "").replace("}", "")

    // ------------------------------------------------------------------ roots

    private fun reduceMod(p: Poly, a: Atom.Rad): Poly {
        var out = Poly.ZERO
        for ((m, c) in p.terms) {
            val e = m.exp(a.sym)
            val rest = Mono(m.powers - a.sym)
            val q = e / a.index
            val rem = e % a.index
            val base = Poly.of(if (rem == 0) rest else rest * Mono.of(a.sym, rem), c)
            out += if (q == 0) base else base * a.arg.pow(q)
        }
        return out
    }

    private fun ordinalPower(n: Int) = when (n) { 2 -> "Square"; 3 -> "Cube"; else -> "Raise to the power $n" }

    private fun radicals(p0: Poly, rads: List<Atom.Rad>, v: Char): SolveResult {
        var p = p0
        for (a in rads) p = reduceMod(p, a)
        val left = rads.filter { it.sym in p.variables }
        if (rads.any { it.index % 2 == 0 }) {
            val conds = rads.filter { it.index % 2 == 0 && !it.arg.isConstant }.map { "${Printer.poly(it.arg)}\\ge0" }
            if (conds.isNotEmpty()) step(conds.joinToString(",\\ "), "Domain: what is under an even root must be ≥ 0")
        }
        val src = "${Printer.ratFn(eq.l)}=${Printer.ratFn(eq.r)}"
        when (left.size) {
            0 -> return finish(v, solvePoly(one(p), RatFn(Poly.ZERO, Poly.ONE), v))
            1 -> {
                val r = left[0]
                if (p.degreeIn(r.sym) != 1) throw NeedsNumeric("the same root appearing in several powers")
                val c1 = p.coeffOf(r.sym, 1)
                val c0 = p.coeffOf(r.sym, 0)
                if (c1.variables.atomSyms.isNotEmpty() || c0.variables.atomSyms.isNotEmpty()) throw NeedsNumeric("roots combined with other powers or logs")
                val sRatio = RatFn(-c0, c1).simplified().value
                val isoText = "${r.tex}=${Printer.ratFn(sRatio)}"
                if (strip(isoText) != strip(src) && strip("${Printer.ratFn(sRatio)}=${r.tex}") != strip(src)) step(isoText, "Isolate the root")
                if (r.index % 2 == 0) {
                    val value = if (sRatio.variables.isEmpty()) sRatio.num.constant / sRatio.den.constant else null
                    if (value != null && value.signum() < 0) {
                        step("${r.tex}=${Printer.surd(value)}", "A square root is never negative: no solution")
                        return noSolution()
                    }
                    if (value == null) step("${Printer.ratFn(sRatio)}\\ge0", "The other side must be ≥ 0 too")
                }
                val rhs = sRatio.pow(r.index)
                step("${Printer.poly(r.arg)}=${Printer.ratFn(rhs.simplified().value)}", "${ordinalPower(r.index)} both sides")
                return finish(v, solvePoly(one(r.arg), rhs, v))
            }
            2 -> return twoRadicals(p, left[0], left[1], v)
            else -> throw NeedsNumeric("three or more roots in one equation")
        }
    }

    private fun twoRadicals(p: Poly, r1: Atom.Rad, r2: Atom.Rad, v: Char): SolveResult {
        if (r1.index != r2.index) throw NeedsNumeric("roots of different orders in one equation")
        val cross = p.coeffOf(r1.sym, 1).coeffOf(r2.sym, 1)
        if (!cross.isZero || p.degreeIn(r1.sym) > 1 || p.degreeIn(r2.sym) > 1) throw NeedsNumeric("a product of two different roots")
        val c1 = p.coeffOf(r1.sym, 1)
        val c2 = p.coeffOf(r2.sym, 1)
        val c0 = p.coeffOf(r1.sym, 0).coeffOf(r2.sym, 0)
        if (listOf(c1, c2, c0).any { it.variables.atomSyms.isNotEmpty() }) throw NeedsNumeric("roots combined with other powers or logs")
        val n = r1.index
        if (c0.isZero) {
            // c1 r1 = -c2 r2: raise both sides to the n-th power
            step("${Printer.poly(c1).wrapIfSum()}${r1.tex}=${Printer.poly(-c2).wrapIfSum()}${r2.tex}".replace("1\\sqrt", "\\sqrt"), "Isolate one root")
            val l = RatFn(r1.arg * c1.pow(n), Poly.ONE)
            val r = RatFn(r2.arg * (-c2).pow(n), Poly.ONE)
            step("${Printer.ratFn(l)}=${Printer.ratFn(r)}", "${ordinalPower(n)} both sides")
            return finish(v, solvePoly(l, r, v))
        }
        if (n != 2) throw NeedsNumeric("two higher roots with other terms")
        val u = RatFn(-c2, c1)
        val w = RatFn(-c0, c1)
        val e1 = u * sym(r2) + w
        step("${r1.tex}=${Printer.ratFn(e1.simplified().value)}", "Isolate one root")
        // (u r2 + w)^2 with r2^2 = A2
        val a2 = one(r2.arg)
        val uw2 = u * w * ratConst(Surd.of(2))
        val squared = u * u * a2 + uw2 * sym(r2) + w * w
        step("${Printer.poly(r1.arg)}=${Printer.ratFn(squared.simplified().value)}", "Square both sides (first squaring)")
        val t = uw2
        val rest = one(r1.arg) - u * u * a2 - w * w
        if (t.num.isZero) return finish(v, solvePoly(rest, RatFn(Poly.ZERO, Poly.ONE), v))
        val s2 = (rest / t).simplified().value
        step("${r2.tex}=${Printer.ratFn(s2)}", "Isolate the remaining root")
        val value = if (s2.variables.isEmpty()) s2.num.constant / s2.den.constant else null
        if (value != null && value.signum() < 0) {
            step("${r2.tex}=${Printer.surd(value)}", "A square root is never negative: no solution")
            return noSolution()
        }
        val rhs = s2.pow(2)
        step("${Printer.poly(r2.arg)}=${Printer.ratFn(rhs.simplified().value)}", "Square both sides (second squaring)")
        return finish(v, solvePoly(a2, rhs, v))
    }

    private fun String.wrapIfSum() = if (contains('+') || drop(1).contains('-')) "($this)" else this

    // ------------------------------------------------------------------ exponentials

    private fun expPoly(a: Atom.Pow): Poly {
        if (!a.exp.den.isConstant) throw NeedsNumeric("an exponent that is a fraction of the unknown")
        return a.exp.num.divBy(a.exp.den.constant)
    }

    private fun baseTex(b: Surd): String {
        val t = Printer.surd(b)
        return if (b.isRational && b.rational.isInteger || b == Surd.E_SURD || b == Surd.PI_SURD) t else "($t)"
    }

    private fun powTex(b: Surd, e: String) = "${baseTex(b)}^{$e}"

    /** `\log_b K`, `\ln K`, or `\log K` for base 10. */
    private fun logTex(b: Surd, k: Surd): String {
        val kt = Printer.surd(k).let { if (k.isSingleTerm && (k.isRational && k.rational.isInteger || k == Surd.E_SURD || k == Surd.PI_SURD)) it else "($it)" }
        return when {
            b == Surd.E_SURD -> "\\ln $kt"
            b == Surd.of(10) -> "\\log $kt"
            else -> "\\log_{${Printer.surd(b)}} $kt"
        }
    }

    private fun logValue(b: Surd, k: Surd) = Math.log(k.toDouble()) / Math.log(b.toDouble())

    /** (a, c) with f = a·v + c, constants only. */
    private fun linearForm(f: Poly, v: Char): Pair<Surd, Surd>? {
        if (f.degreeIn(v) != 1 || f.variables.any { it != v }) return null
        return f.coeffOf(v, 1).constant to f.coeffOf(v, 0).constant
    }

    private fun closedLinear(ltex: String, ld: Double, a: Surd, c: Surd): Val {
        val num = when {
            c.isZero -> ltex
            c.signum() < 0 -> "$ltex+${Printer.surd(-c)}"
            else -> "$ltex-${Printer.surd(c)}"
        }
        val tex = if (a == Surd.ONE) num else "\\frac{$num}{${Printer.surd(a)}}"
        return Val.closed(tex, (ld - c.toDouble()) / a.toDouble())
    }

    /** Solves b^f = K for the unknown, showing the steps; K must be positive. */
    private fun solveExpEquals(b: Surd, f: Poly, k: Surd, v: Char): List<Val> {
        if (k.signum() <= 0) throw IllegalStateException()
        val common = if (k == Surd.ONE) NumberTheory.CommonBase(b, listOf(Rational.ONE, Rational.ZERO))
        else NumberTheory.commonBase(listOf(b, k))
        if (common != null) {
            val mb = common.exps[0]
            val mk = common.exps[1]
            val g = f.times(Surd.of(mb))
            step("${powTex(common.base, Printer.poly(g))}=${powTex(common.base, Printer.rational(mk))}", "Same base ${plain(Printer.surd(common.base))} on both sides")
            return solvePoly(one(g), ratConst(Surd.of(mk)), v)
        }
        val lt = logTex(b, k)
        step("${Printer.poly(f)}=$lt", "Take the logarithm to base ${plain(Printer.surd(b))} of both sides")
        val ld = logValue(b, k)
        val lin = linearForm(f, v)
        if (lin != null) return listOf(closedLinear(lt, ld, lin.first, lin.second))
        // a nonlinear exponent: solve f(x) = log_b K numerically
        val target = Rational.parse(BigDecimal(ld).toPlainString())
        val rts = solvePoly(one(f), ratConst(Surd.of(target)), v)
        return rts.map { Val.approx(it.d) }
    }

    private fun exponentials(p: Poly, pows: List<Atom.Pow>, v: Char): SolveResult {
        for (m in p.terms.keys) if (m.exp(v) != 0) throw NeedsNumeric("the unknown multiplied by a power of the unknown")
        pows.forEach { expPoly(it) }
        val monos = p.terms
        val syms = pows.map { it.sym }
        val onlyLinear = monos.keys.all { m -> m.powers.keys.all { it in syms } && m.powers.values.all { it == 1 } && m.powers.size <= 1 }

        // one exponential: alpha*E + gamma = 0
        if (pows.size == 1 && onlyLinear) {
            val a = pows[0]
            val alpha = p.coeffOf(a.sym, 1).constant
            val gamma = p.coeffOf(a.sym, 0).constant
            val k = -gamma / alpha
            val iso = "${a.tex}=${Printer.surd(k)}"
            if (strip(iso) != strip("${Printer.ratFn(eq.l)}=${Printer.ratFn(eq.r)}")) step(iso, "Isolate the power")
            if (k.signum() <= 0) {
                step("${a.tex}>0", "A power with a positive base is always positive: no solution")
                return noSolution()
            }
            return finish(v, solveExpEquals(a.base, expPoly(a), k, v))
        }
        // two exponentials: alpha*E1 + beta*E2 = 0
        if (pows.size == 2 && onlyLinear && p.constant.isZero) {
            val (a1, a2) = pows
            val rho = -p.coeffOf(a2.sym, 1).constant / p.coeffOf(a1.sym, 1).constant
            val isoText = "${a1.tex}=${if (rho == Surd.ONE) "" else Printer.surd(rho) + "\\cdot "}${a2.tex}"
            if (strip(isoText) != strip("${Printer.ratFn(eq.l)}=${Printer.ratFn(eq.r)}")) step(isoText, "Isolate the powers")
            if (rho.signum() <= 0) {
                step("${a1.tex}>0", "Powers with a positive base are positive: no solution")
                return noSolution()
            }
            return finish(v, twoPowers(a1, a2, rho, v))
        }
        return substitution(p, pows, v)
    }

    private fun twoPowers(a1: Atom.Pow, a2: Atom.Pow, rho: Surd, v: Char): List<Val> {
        val f1 = expPoly(a1)
        val f2 = expPoly(a2)
        val common = NumberTheory.commonBase(listOf(a1.base, a2.base))
        val rhoExp = if (rho == Surd.ONE) Rational.ZERO else common?.let { NumberTheory.exactLog(it.base, rho) }
        if (common != null && rhoExp != null) {
            val g1 = f1.times(Surd.of(common.exps[0]))
            val g2 = f2.times(Surd.of(common.exps[1])) + Poly.const(Surd.of(rhoExp))
            val b = common.base
            step("${powTex(b, Printer.poly(g1))}=${powTex(b, Printer.poly(g2))}", "Same base ${plain(Printer.surd(b))} on both sides")
            return solvePoly(one(g1), one(g2), v)
        }
        // unrelated bases: take ln of both sides and solve the linear result
        val l1 = linearForm(f1, v) ?: throw NeedsNumeric("exponentials with different bases and a non-linear exponent")
        val l2 = linearForm(f2, v) ?: throw NeedsNumeric("exponentials with different bases and a non-linear exponent")
        if (listOf(l1.first, l1.second, l2.first, l2.second).any { !it.isRational }) throw NeedsNumeric("exponentials with irrational exponents")
        val (aa1, cc1) = l1.first.rational to l1.second.rational
        val (aa2, cc2) = l2.first.rational to l2.second.rational
        fun term(f: Poly, b: Surd) = (if (f.terms.size > 1) "(${Printer.poly(f)})" else Printer.poly(f)) + "\\ln ${baseTex(b)}"
        step("${term(f1, a1.base)}=${if (rho == Surd.ONE) "" else "\\ln ${baseTex(rho)}+"}${term(f2, a2.base)}", "Take ln of both sides")
        val lnb1 = Math.log(a1.base.toDouble())
        val lnb2 = Math.log(a2.base.toDouble())
        val lnrho = if (rho == Surd.ONE) 0.0 else Math.log(rho.toDouble())
        val dd = aa1.toDouble() * lnb1 - aa2.toDouble() * lnb2
        if (Math.abs(dd) < 1e-12) throw NeedsNumeric("exponentials that cancel each other")
        val nd = lnrho + cc2.toDouble() * lnb2 - cc1.toDouble() * lnb1
        val den = lnSum(listOf(aa1 to a1.base, -aa2 to a2.base))
        val num = lnSum(listOf(Rational.ONE to rho, cc2 to a2.base, -cc1 to a1.base).filter { it.second != Surd.ONE })
        val numTex = num.ifEmpty { "0" }
        step("$v=\\frac{$numTex}{$den}", "Solve the linear equation")
        return listOf(Val.closed("\\frac{$numTex}{$den}", nd / dd))
    }

    /** Σ rᵢ·ln(bᵢ) as LaTeX, merging equal arguments. */
    private fun lnSum(parts: List<Pair<Rational, Surd>>): String {
        val merged = LinkedHashMap<Surd, Rational>()
        for ((r, b) in parts) merged[b] = (merged[b] ?: Rational.ZERO) + r
        val sb = StringBuilder()
        for ((b, r) in merged) {
            if (r.isZero) continue
            val mag = r.abs()
            val coef = if (mag == Rational.ONE) "" else Printer.rational(mag)
            val t = coef + "\\ln " + baseTex(b)
            if (sb.isEmpty()) sb.append(if (r.signum < 0) "-" else "").append(t) else sb.append(if (r.signum < 0) "-" else "+").append(t)
        }
        return sb.toString()
    }

    /** A polynomial in u = β^(a0·x): rewrite every power, solve for u, reject u ≤ 0, then back-substitute. */
    private fun substitution(p: Poly, pows: List<Atom.Pow>, v: Char): SolveResult {
        val common = NumberTheory.commonBase(pows.map { it.base }) ?: throw NeedsNumeric("exponentials with unrelated bases")
        val beta = common.base
        data class Info(val atom: Atom.Pow, val k: Int, val gamma: Surd)
        val lins = pows.mapIndexed { i, a ->
            val g = expPoly(a).times(Surd.of(common.exps[i]))
            linearForm(g, v)?.takeIf { it.first.isRational && it.second.isRational && !it.first.isZero } ?: throw NeedsNumeric("a power whose exponent is not linear in the unknown")
        }
        var num = BigInteger.ZERO
        var den = BigInteger.ONE
        for (l in lins) { val a = l.first.rational.abs(); num = num.gcd(a.num); den = den.multiply(a.den).divide(den.gcd(a.den)) }
        val a0 = Rational.of(num, den)
        val infos = pows.mapIndexed { i, a ->
            val ratio = lins[i].first.rational / a0
            val c = lins[i].second.rational
            val gamma = NumberTheory.exactPow(beta, c) ?: throw NeedsNumeric("a power with a shift that is not a whole number of steps")
            Info(a, ratio.num.toInt(), gamma)
        }
        // P(E) -> U(u)
        val exps = LinkedHashMap<Int, Surd>()
        for ((m, c) in p.terms) {
            var e = 0
            var coef = c
            for (info in infos) {
                val n = m.exp(info.atom.sym)
                e += n * info.k
                coef *= info.gamma.pow(n)
            }
            exps[e] = (exps[e] ?: Surd.ZERO) + coef
        }
        val shift = -(exps.keys.minOrNull() ?: 0).coerceAtMost(0)
        val u = if (v == 'u') 't' else 'u'
        var up = Poly.ZERO
        for ((e, c) in exps) up += Poly.of(if (e + shift == 0) Mono.ONE else Mono.of(u, e + shift), c)
        val uTex = powTex(beta, Printer.poly(Poly.of(Mono.of(v), Surd.of(a0))))
        if (up.isConstant) {
            step("${Printer.poly(up)}=0", "Substitute u = ${plain(uTex)}")
            return if (up.isZero) throw UnsupportedMath("an equation that holds for every x") else noSolution()
        }
        step("${Printer.poly(up)}=0", "Substitute u = ${plain(uTex)}")
        val uRoots = roots(s.solveOne(Eq(one(up), RatFn(Poly.ZERO, Poly.ONE), Printer.poly(up), "0"), u, check = false))
        val cands = ArrayList<Val>()
        for (r in uRoots) {
            if (r.d <= 0) {
                step("$u=${r.texOrDecimal()}", "$u = ${plain(r.texOrDecimal())} ≤ 0: ${plain(powTex(beta, "…"))} is never ≤ 0, rejected")
                continue
            }
            val f = Poly.of(Mono.of(v), Surd.of(a0))
            val k = r.exact
            if (k != null) {
                step("${uTex}=${Printer.surd(k)}", "Back-substitute")
                cands += solveExpEquals(beta, f, k, v)
            } else {
                val xv = Math.log(r.d) / Math.log(beta.toDouble()) / a0.toDouble()
                step("$uTex\\approx${Printer.num(r.d, 4)}", "Back-substitute")
                cands += Val.approx(xv)
            }
        }
        return finish(v, cands)
    }

    // ------------------------------------------------------------------ logarithms

    private fun logs(p: Poly, lg: List<Atom.Log>, v: Char): SolveResult {
        for (m in p.terms.keys) if (m.exp(v) != 0) throw NeedsNumeric("the unknown multiplied by a logarithm")
        val conds = lg.filter { !it.isConstant }.map { "${Printer.ratFn(it.arg.simplified().value)}>0" }.distinct()
        if (conds.isNotEmpty()) step(conds.joinToString(",\\ "), "Domain: what is inside a logarithm must be > 0")
        if (lg.size == 1) return singleLog(p, lg[0], v)
        if (p.terms.keys.any { m -> m.powers.size > 1 || m.powers.values.any { it != 1 } }) throw NeedsNumeric("products or powers of different logarithms")
        val common = NumberTheory.commonBase(lg.map { it.base }) ?: throw NeedsNumeric("logarithms with unrelated bases")
        val beta = common.base
        val gamma = p.constant
        // sum alpha_i log_{b_i}(A_i) = -gamma, converted to base beta
        val alphas = lg.mapIndexed { i, a ->
            val al = p.coeffOf(a.sym, 1).constant
            if (!al.isRational) throw NeedsNumeric("a logarithm with an irrational coefficient")
            al.rational / common.exps[i]
        }
        val distinctBases = lg.map { it.base }.distinct().size > 1
        if (distinctBases) {
            step(lg.indices.joinToString(",\\ ") { i -> "${logOf(lg[i].base, Printer.ratFn(lg[i].arg.simplified().value))}=${Printer.rational(Rational.ONE / common.exps[i])}\\log_{${Printer.surd(beta)}}(${Printer.ratFn(lg[i].arg.simplified().value)})" }, "Change of base to ${plain(Printer.surd(beta))}")
        }
        var l = BigInteger.ONE
        for (a in alphas) l = l.multiply(a.den).divide(l.gcd(a.den))
        val ms = alphas.map { (it * Rational.of(l)).num.toInt() }
        val c = -gamma * Surd.of(Rational.of(l))
        val argsTex = lg.map { Printer.ratFn(it.arg.simplified().value) }
        fun termTex(i: Int, withPower: Boolean): String {
            val m = Math.abs(ms[i])
            val simple = argsTex[i].all { it.isLetterOrDigit() }
            val inner = if (withPower && m != 1) (if (simple) argsTex[i] else "(${argsTex[i]})") + "^{$m}" else argsTex[i]
            val t = logOf(beta, inner)
            return if (withPower || m == 1) t else "$m$t"
        }
        fun signed(i: Int, withPower: Boolean) = (if (ms[i] < 0) "-" else if (i == 0) "" else "+") + termTex(i, withPower)
        val rhs = "=${Printer.surd(c)}"
        // moving terms and clearing fractions only deserves a line when something changed
        if (distinctBases || ms.any { it != 1 } || lg.size == 1) step(lg.indices.joinToString("") { signed(it, false) } + rhs, "Collect the logarithms")
        if (ms.any { Math.abs(it) != 1 }) step(lg.indices.joinToString("") { signed(it, true) } + rhs, "Power rule: n·log a = log aⁿ")
        var nTop = RatFn(Poly.ONE, Poly.ONE)
        var nBot = RatFn(Poly.ONE, Poly.ONE)
        for ((i, a) in lg.withIndex()) {
            if (ms[i] > 0) nTop = nTop * a.arg.pow(ms[i]) else if (ms[i] < 0) nBot = nBot * a.arg.pow(-ms[i])
        }
        val topTex = Printer.ratFn(nTop.simplified().value)
        val botTex = Printer.ratFn(nBot.simplified().value)
        val ratio = if (nBot.num == Poly.ONE && nBot.den == Poly.ONE) topTex else "\\frac{$topTex}{$botTex}"
        step(logOf(beta, ratio) + rhs, if (ms.any { it < 0 }) "Quotient rule: log a − log b = log (a/b)" else "Product rule: log a + log b = log (ab)")
        return finish(v, powerForm(beta, nTop, nBot, c, v))
    }

    private fun logOf(b: Surd, arg: String) = when {
        b == Surd.E_SURD -> "\\ln($arg)"
        else -> "\\log_{${Printer.surd(b)}}($arg)"
    }

    /** log_β(top/bottom) = c  →  top = β^c · bottom, solved. */
    private fun powerForm(beta: Surd, top: RatFn, bot: RatFn, c: Surd, v: Char): List<Val> {
        val approx: Boolean
        val k: Surd
        if (c.isZero) {
            step("${Printer.ratFn(top.simplified().value)}=${Printer.ratFn(bot.simplified().value)}", "Equal logarithms: equal arguments")
            approx = false
            k = Surd.ONE
        } else {
            val exact = if (c.isRational) NumberTheory.exactPow(beta, c.rational) else null
            approx = exact == null
            k = exact ?: Surd.of(Rational.parse(BigDecimal(Math.pow(beta.toDouble(), c.toDouble())).toPlainString()))
            val t = if (bot.num == Poly.ONE && bot.den == Poly.ONE) Printer.ratFn(top.simplified().value) else "\\frac{${Printer.ratFn(top.simplified().value)}}{${Printer.ratFn(bot.simplified().value)}}"
            step("$t=${powTex(beta, Printer.surd(c))}", "Rewrite the logarithm as a power")
        }
        val rts = solvePoly(top, bot * ratConst(k), v)
        return if (approx) rts.map { Val.approx(it.d) } else rts
    }

    /** One logarithm atom L: a polynomial in L with constant coefficients. */
    private fun singleLog(p: Poly, a: Atom.Log, v: Char): SolveResult {
        val deg = p.degreeIn(a.sym)
        val cs = (0..deg).map { p.coeffOf(a.sym, it).asSurd() ?: throw NeedsNumeric("a logarithm mixed with the unknown") }
        val argTex = Printer.ratFn(a.arg.simplified().value)
        if (deg == 1) {
            val c = -cs[0] / cs[1]
            val iso = "${a.tex}=${Printer.surd(c)}"
            if (strip(iso) != strip("${Printer.ratFn(eq.l)}=${Printer.ratFn(eq.r)}")) step(iso, "Isolate the logarithm")
            return finish(v, powerForm(a.base, a.arg, RatFn(Poly.ONE, Poly.ONE), c, v))
        }
        val u = if (v == 'u') 't' else 'u'
        var up = Poly.ZERO
        for ((i, c) in cs.withIndex()) if (!c.isZero) up += Poly.of(if (i == 0) Mono.ONE else Mono.of(u, i), c)
        step("${Printer.poly(up)}=0", "Substitute $u = ${plain(logOf(a.base, argTex))}")
        val uRoots = roots(s.solveOne(Eq(one(up), RatFn(Poly.ZERO, Poly.ONE), Printer.poly(up), "0"), u, check = false))
        val cands = ArrayList<Val>()
        for (r in uRoots) {
            step("${logOf(a.base, argTex)}=${r.texOrDecimal()}", "Back-substitute")
            val rs = r.exact
            if (rs != null) cands += powerForm(a.base, a.arg, RatFn(Poly.ONE, Poly.ONE), rs, v)
            else {
                val k = Rational.parse(BigDecimal(Math.pow(a.base.toDouble(), r.d)).toPlainString())
                cands += solvePoly(one(a.arg.num), ratConst(Surd.of(k)) * RatFn(a.arg.den, Poly.ONE), v).map { Val.approx(it.d) }
            }
        }
        return finish(v, cands)
    }
}

/**
 * Last resort for one unknown: scan [−1000, 1000] for sign changes of left − right, bisect each in doubles and keep
 * those that really are zeros (not poles). Results are decimals only. A double root that only touches zero is missed.
 */
internal class NumericSearch(
    private val s: SolveSession,
    private val lNode: Node,
    private val rNode: Node,
    private val reason: String,
) {
    fun solve(): SolveResult {
        val vars = lNode.variableNames() + rNode.variableNames()
        if (vars.size != 1) throw UnsupportedMath(reason)
        val v = vars.first()
        fun f(x: Double): Double = try {
            val a = NodeNum.eval(lNode, mapOf(v to x))
            val b = NodeNum.eval(rNode, mapOf(v to x))
            a - b
        } catch (_: Undefined) { Double.NaN }
        fun scale(x: Double): Double = try {
            1 + Math.abs(NodeNum.eval(lNode, mapOf(v to x))) + Math.abs(NodeNum.eval(rNode, mapOf(v to x)))
        } catch (_: Undefined) { 1.0 }

        val grid = ArrayList<Double>()
        var x = -1000.0
        while (x < -50.0) { grid += x; x += 0.5 }
        x = -50.0
        while (x <= 50.0) { grid += Math.round(x * 100) / 100.0; x += 0.01 }
        x = 50.5
        while (x <= 1000.0) { grid += x; x += 0.5 }
        val found = ArrayList<Double>()
        var prevX = grid[0]
        var prevF = f(prevX)
        for (i in 1 until grid.size) {
            val cx = grid[i]
            val cf = f(cx)
            if (!prevF.isNaN() && !cf.isNaN()) {
                if (cf == 0.0) found += cx
                else if (prevF * cf < 0) {
                    var lo = prevX
                    var hi = cx
                    var flo = prevF
                    repeat(200) {
                        val mid = (lo + hi) / 2
                        val fm = f(mid)
                        if (fm.isNaN()) return@repeat
                        if (flo * fm <= 0) hi = mid else { lo = mid; flo = fm }
                    }
                    val root = (lo + hi) / 2
                    val fr = f(root)
                    if (!fr.isNaN() && Math.abs(fr) <= 1e-7 * scale(root)) found += root
                }
            }
            prevX = cx
            prevF = cf
        }
        val roots = found.sorted().fold(ArrayList<Double>()) { acc, d -> if (acc.isEmpty() || Math.abs(acc.last() - d) > 1e-7) acc += d; acc }
        if (roots.isEmpty()) throw UnsupportedMath("$reason (a numeric search between -1000 and 1000 found no solution either)")
        val vals = roots.map { Val.approx(it) }
        s.step(vals.joinToString(",\\ ") { "$v\\approx${Printer.num(it.d, 4)}" }, "No exact method for this mix; approximate (numeric search)")
        s.note("Approximate: found by scanning −1000…1000 for sign changes; a root that only touches zero would be missed")
        return SolveResult.Solved(listOf(s.rootsAnswer(v, vals)), s.steps, s.notes)
    }
}
