package dev.ilgax.utils.math.solver

import java.math.BigInteger

/**
 * Real roots of a one-variable polynomial of any degree, with the working shown as steps.
 * Order: common power of the unknown, binomials (xⁿ = k), even polynomials (u = x²), the rational root theorem with
 * synthetic division until a quadratic is left (handed to the quadratic solver), and finally, for what is left,
 * Sturm-sequence bisection (exact counting, no root missed) labelled as approximate.
 */
internal class RootFinder(private val s: SolveSession) {

    fun solve(p: Poly, v: Char): Roots {
        val found = collect(p, v)
        if (p.degreeIn(v) >= 3) {
            if (found.isEmpty()) s.step("${Printer.poly(p)}\\ne0", "No real roots")
            else if (found.size > 1) s.step(found.joinToString(",\\ ") { solutionTex(v, it) }, "Real solutions")
        }
        return if (found.isEmpty()) Roots.None else Roots.Some(found)
    }

    /** Silent version for exclusions and sign charts. */
    fun silent(p: Poly, v: Char): List<Val> = s.quiet { collect(p, v) }

    /** Loud version without the closing step (the caller prints its own). */
    fun loud(p: Poly, v: Char): List<Val> = collect(p, v)

    private fun solutionTex(v: Char, r: Val): String {
        val core = if (r.isApprox) "$v\\approx${Printer.num(r.d, 4)}" else "$v=${r.texOrDecimal()}"
        return core + multTex(r.mult)
    }

    private fun collect(p: Poly, v: Char): List<Val> {
        val out = ArrayList<Val>()
        val m = p.monomialGcd().exp(v)
        var q = p
        if (m > 0) {
            q = p.divMono(Mono.of(v, m))
            val power = if (m == 1) "$v" else "$v^{$m}"
            s.step(if (q.isConstant) "$power=0" else "$power(${Printer.poly(q)})=0", "Factor out $power")
            out += Val.of(Surd.ZERO, m)
        }
        reduced(q, v, out)
        return Val.merge(out)
    }

    private fun coeff(q: Poly, v: Char, i: Int) = q.coeffOf(v, i).constant

    private fun reduced(q: Poly, v: Char, out: MutableList<Val>) {
        when (val deg = q.degreeIn(v)) {
            0 -> return
            1 -> out += Val.of(-coeff(q, v, 0) / coeff(q, v, 1))
            2 -> {
                val r = s.quadraticSteps(q, Poly.ZERO, q, coeff(q, v, 2), coeff(q, v, 1), coeff(q, v, 0), v)
                if (r is Roots.Some) out += if (r.values.size == 1) r.values.map { it.withMult(2) } else r.values
            }
            else -> big(q, v, deg, out)
        }
    }

    private fun big(q: Poly, v: Char, deg: Int, out: MutableList<Val>) {
        if (q.terms.size == 2 && q.constant != Surd.ZERO) return binomial(q, v, deg, out)
        if (deg >= 4 && (1..deg step 2).all { q.coeffOf(v, it).isZero }) return even(q, v, out)
        var rest = q
        val rational = QPoly.fromPoly(q, v)
        if (rational != null) rest = rationalRoots(rational, v, out)
        remainder(rest, v, out)
    }

    // ---------------------------------------------------------------- xⁿ = k

    private fun ordinal(n: Int) = when (n) { 3 -> "cube"; else -> "${n}th" }

    private fun binomial(q: Poly, v: Char, n: Int, out: MutableList<Val>) {
        val k = -q.constant / coeff(q, v, n)
        s.step("$v^{$n}=${Printer.surd(k)}", "Isolate $v^{$n}")
        val kd = k.toDouble()
        val even = n % 2 == 0
        if (even && kd < 0) { s.step("$v^{$n}<0", "An even power is never negative: no real solution"); return }
        val mag = if (kd < 0) -k else k
        val root: Val = run {
            if (mag.isRational) NumberTheory.exactRoot(mag.rational, n)?.let { return@run Val.of(it) }
            val inner = if (mag.isRational && !mag.rational.isInteger) "\\frac{${mag.rational.num}}{${mag.rational.den}}" else Printer.surd(mag)
            Val.closed("\\sqrt[$n]{$inner}", Math.pow(Math.abs(kd), 1.0 / n))
        }
        val neg = negate(root)
        if (even) {
            s.step("$v=\\pm${root.texOrDecimal()}", "Take the ${ordinal(n)} root (±)")
            out += neg; out += root
        } else {
            val pick = if (kd < 0) neg else root
            s.step("$v=${pick.texOrDecimal()}", "Take the ${ordinal(n)} root")
            out += pick
        }
    }

    private fun negate(r: Val): Val = when {
        r.exact != null -> Val.of(-r.exact)
        r.tex != null -> Val.closed("-${r.tex}", -r.d)
        else -> Val.approx(-r.d)
    }

    // ---------------------------------------------------------------- even polynomials

    private fun pickVar(v: Char) = "ustwzr".first { it != v }

    private fun even(q: Poly, v: Char, out: MutableList<Val>) {
        val u = pickVar(v)
        var qu = Poly.ZERO
        for ((m, c) in q.terms) qu += Poly.of(if (m.exp(v) == 0) Mono.ONE else Mono.of(u, m.exp(v) / 2), c)
        s.step("${Printer.poly(qu)}=0", "Substitute $u = $v^{2}")
        val us = ArrayList<Val>()
        reduced(qu, u, us)
        val merged = Val.merge(us)
        if (merged.isEmpty()) return
        val shown = ArrayList<String>()
        var skipped = false
        for (r in merged) {
            if (r.d <= 0) { skipped = true; continue }
            val ex = r.exact?.let { runCatching { it.sqrt() }.getOrNull() }
            val x: Val = when {
                ex != null -> Val.of(ex, r.mult)
                r.isApprox -> Val.approx(Math.sqrt(r.d), r.mult)
                else -> Val.closed("\\sqrt{${r.texOrDecimal()}}", Math.sqrt(r.d), r.mult)
            }
            out += negate(x).withMult(r.mult)
            out += x
            shown += "$v=\\pm${x.texOrDecimal()}"
        }
        if (shown.isNotEmpty()) s.step(shown.joinToString(",\\ "), "Back-substitute $u = $v^{2}" + if (skipped) " (a negative $u gives no real $v)" else "")
        else s.step("$u<0", "$v^{2} = $u is never negative: no real solution")
    }

    // ---------------------------------------------------------------- rational root theorem

    private fun lcm(a: BigInteger, b: BigInteger) = a.multiply(b).divide(a.gcd(b))

    private fun rationalRoots(qp: QPoly, v: Char, out: MutableList<Val>): Poly {
        var l = BigInteger.ONE
        for (c in qp.c) l = lcm(l, c.den)
        var ints = qp.c.map { (it * Rational.of(l)).num }
        val g = ints.fold(BigInteger.ZERO) { a, b -> a.gcd(b) }
        ints = ints.map { it.divide(g) }
        val a0 = ints.first().abs()
        val an = ints.last().abs()
        val ps = if (a0.signum() == 0) null else NumberTheory.divisors(a0)
        val qs = NumberTheory.divisors(an)
        if (ps == null || qs == null || ps.size * qs.size > 3000) {
            s.step("a_0=$a0,\\ a_n=$an", "Rational root theorem skipped: the coefficients are too large")
            return qp.toPoly(v)
        }
        val cands = java.util.TreeSet<Rational>()
        for (p in ps) for (d in qs) cands += Rational.of(p, d)
        val shown = cands.take(14).joinToString(",\\ ") { "\\pm" + Printer.rational(it) } + if (cands.size > 14) ",\\ \\ldots" else ""

        var cur = qp
        val roots = ArrayList<Rational>()
        for (c in cands) for (sign in intArrayOf(1, -1)) {
            val r = if (sign > 0) c else -c
            while (cur.deg > 2 && cur.eval(r).isZero) {
                cur = cur.divRem(QPoly(listOf(-r, Rational.ONE))).first
                roots += r
            }
        }
        if (roots.isEmpty()) {
            s.step("$shown\\ \\Rightarrow\\ \\text{none works}", "Try rational roots ±p/q: no rational root")
            return qp.toPoly(v)
        }
        val sorted = roots.sorted()
        s.step("$shown\\ \\Rightarrow\\ " + sorted.distinct().joinToString(",\\ ") { "$v=${Printer.rational(it)}" }, "Try rational roots ±p/q")
        for (r in sorted) out += Val.of(r)

        // factorisation with primitive integer linear factors
        val grouped = sorted.groupingBy { it }.eachCount()
        var product = ""
        var leadProduct = Rational.ONE
        for ((r, k) in grouped) {
            val factor = Poly.of(Mono.of(v), Surd.of(Rational.of(r.den))) - Poly.const(Surd.of(Rational.of(r.num)))
            product += "(${Printer.poly(factor)})" + if (k > 1) "^{$k}" else ""
            leadProduct *= Rational.of(r.den).pow(k)
        }
        var restL = BigInteger.ONE
        for (c in cur.c) restL = lcm(restL, c.den)
        var restInts = cur.c.map { (it * Rational.of(restL)).num }
        val rg = restInts.fold(BigInteger.ZERO) { a, b -> a.gcd(b) }
        restInts = restInts.map { it.divide(rg) }
        val prim = QPoly(restInts.map { Rational.of(it) })
        val k = qp.lead / (leadProduct * prim.lead)
        val restPoly = prim.toPoly(v)
        if (cur.deg > 0) product += "(${Printer.poly(restPoly)})"
        val coef = if (k == Rational.ONE) "" else if (k == Rational.of(-1)) "-" else Printer.rational(k) + ""
        s.step("$coef$product=0", "Factor by synthetic division")
        return restPoly
    }

    // ---------------------------------------------------------------- what is left

    private fun remainder(rest: Poly, v: Char, out: MutableList<Val>) {
        val deg = rest.degreeIn(v)
        if (deg <= 2) { reduced(rest, v, out); return }
        val exactPoly = QPoly.fromPoly(rest, v)
        val qp = exactPoly ?: QPoly.approximate(rest, v)
        val parts = RealRoots.squareFree(qp)
        val numeric = ArrayList<Val>()
        if (parts.any { it.second > 1 } && exactPoly != null) {
            s.step(parts.joinToString("") { (f, m) -> "(${Printer.poly(f.toPoly(v))})" + if (m > 1) "^{${m}}" else "" } + "=0", "Repeated factors")
        }
        for ((f, mult) in parts) {
            if (f.deg <= 2 && exactPoly != null) {
                val tmp = ArrayList<Val>()
                reduced(f.toPoly(v), v, tmp)
                out += tmp.map { it.withMult(mult) }
            } else {
                for (r in RealRoots.realRoots(f)) numeric += Val.approx(r, mult)
            }
        }
        if (numeric.isNotEmpty()) {
            s.step(numeric.sortedBy { it.d }.joinToString(",\\ ") { solutionTex(v, it) }, "No exact roots; approximate")
            out += numeric
        } else if (parts.any { it.first.deg > 2 || exactPoly == null }) {
            s.step("${Printer.poly(rest)}\\ne0", "No exact roots, and no real ones left")
        }
    }

    companion object {
        fun multTex(m: Int) = when {
            m <= 1 -> ""
            m == 2 -> "\\ (\\text{double})"
            m == 3 -> "\\ (\\text{triple})"
            else -> "\\ (\\text{multiplicity }$m)"
        }
    }
}
