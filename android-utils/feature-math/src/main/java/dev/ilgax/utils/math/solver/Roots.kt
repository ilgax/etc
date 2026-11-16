package dev.ilgax.utils.math.solver

import java.math.MathContext

/**
 * A real solution with how much is known about it. [exact] is a surd; otherwise [tex] holds a closed form that is
 * not a surd (`\log_3 5`, `\sqrt[3]{2}`) or null for a bare decimal. [d] is always the numeric value.
 */
internal class Val private constructor(val exact: Surd?, val d: Double, val tex: String?, val mult: Int) {
    val isExact get() = exact != null
    val isClosed get() = exact == null && tex != null
    val isApprox get() = exact == null && tex == null

    fun withMult(m: Int) = Val(exact, d, tex, m)

    fun sameAs(o: Val): Boolean =
        if (exact != null && o.exact != null) exact == o.exact else Math.abs(d - o.d) <= 1e-9 * Math.max(1.0, Math.abs(d))

    /** LaTeX of the exact part (what comes after `x=`); approximations print as a rounded decimal. */
    fun texOrDecimal(): String = exact?.let { Printer.surd(it) } ?: tex ?: Printer.num(d, 4)

    override fun toString() = (exact?.toString() ?: tex ?: "≈$d") + (if (mult > 1) " (×$mult)" else "")

    companion object {
        fun of(s: Surd, mult: Int = 1) = Val(s, s.toDouble(), null, mult)
        fun of(q: Rational, mult: Int = 1) = of(Surd.of(q), mult)
        fun closed(tex: String, d: Double, mult: Int = 1) = Val(null, d, tex, mult)
        fun approx(d: Double, mult: Int = 1) = Val(null, d, null, mult)
        fun merge(all: List<Val>): List<Val> {
            val out = ArrayList<Val>()
            for (v in all.sortedBy { it.d }) {
                val i = out.indexOfFirst { it.sameAs(v) }
                if (i >= 0) out[i] = out[i].withMult(out[i].mult + v.mult) else out += v
            }
            return out
        }
    }
}

/** Univariate polynomial with rational coefficients, lowest degree first. Used for roots and gcd. */
internal class QPoly(coeffs: List<Rational>) {
    val c: List<Rational> = coeffs.dropLastWhile { it.isZero }
    val deg get() = c.size - 1
    val isZero get() = c.isEmpty()
    val lead get() = c.last()

    fun eval(x: Rational): Rational {
        var r = Rational.ZERO
        for (i in c.indices.reversed()) r = r * x + c[i]
        return r
    }

    fun derivative() = QPoly(c.drop(1).mapIndexed { i, q -> q * Rational.of((i + 1).toLong()) })
    operator fun unaryMinus() = QPoly(c.map { -it })
    fun scale(k: Rational) = QPoly(c.map { it * k })
    fun monic() = if (isZero) this else scale(lead.inverse())
    operator fun minus(o: QPoly) = QPoly((0 until maxOf(c.size, o.c.size)).map { (c.getOrNull(it) ?: Rational.ZERO) - (o.c.getOrNull(it) ?: Rational.ZERO) })

    fun times(o: QPoly): QPoly {
        if (isZero || o.isZero) return QPoly(emptyList())
        val r = MutableList(c.size + o.c.size - 1) { Rational.ZERO }
        for (i in c.indices) for (j in o.c.indices) r[i + j] = r[i + j] + c[i] * o.c[j]
        return QPoly(r)
    }

    /** Quotient and remainder of this ÷ d. */
    fun divRem(d: QPoly): Pair<QPoly, QPoly> {
        require(!d.isZero)
        var rem = c.toMutableList()
        val q = MutableList(maxOf(0, c.size - d.c.size + 1)) { Rational.ZERO }
        while (rem.size >= d.c.size && rem.isNotEmpty()) {
            val k = rem.last() / d.lead
            val shift = rem.size - d.c.size
            q[shift] = k
            for (i in d.c.indices) rem[shift + i] = rem[shift + i] - k * d.c[i]
            rem = rem.dropLastWhile { it.isZero }.toMutableList()
        }
        return QPoly(q) to QPoly(rem)
    }

    fun toPoly(v: Char): Poly {
        var p = Poly.ZERO
        for ((i, q) in c.withIndex()) if (!q.isZero) p += Poly.of(if (i == 0) Mono.ONE else Mono.of(v, i), Surd.of(q))
        return p
    }

    companion object {
        /** The rational polynomial behind [p] (variable [v]); null when a coefficient is not rational. */
        fun fromPoly(p: Poly, v: Char): QPoly? {
            val deg = p.degreeIn(v)
            val cs = ArrayList<Rational>()
            for (i in 0..deg) {
                val s = p.coeffOf(v, i).asSurd() ?: return null
                if (!s.isRational) return null
                cs += s.rational
            }
            return QPoly(cs)
        }

        /** Same, with surd/π coefficients rounded to 30 digits (the result is then only approximate). */
        fun approximate(p: Poly, v: Char): QPoly {
            val mc = MathContext(34)
            val cs = (0..p.degreeIn(v)).map { i ->
                val s = p.coeffOf(v, i).asSurd() ?: throw UnsupportedMath("a polynomial with several unknowns")
                if (s.isRational) s.rational else Rational.parse(s.toBigDecimal(mc).setScale(30, java.math.RoundingMode.HALF_EVEN).toPlainString())
            }
            return QPoly(cs)
        }

        fun gcd(a: QPoly, b: QPoly): QPoly {
            var x = a
            var y = b
            while (!y.isZero) { val r = x.divRem(y).second; x = y; y = r }
            return x.monic()
        }
    }
}

/** Real roots of a rational polynomial by Sturm sequences and bisection: no root is missed or invented. */
internal object RealRoots {
    /** Squarefree factors with their multiplicities (Yun's algorithm). */
    fun squareFree(f: QPoly): List<Pair<QPoly, Int>> {
        if (f.deg <= 0) return emptyList()
        val a0 = QPoly.gcd(f, f.derivative())
        var b = f.divRem(a0).first
        val c = f.derivative().divRem(a0).first
        var d = c - b.derivative()
        val out = ArrayList<Pair<QPoly, Int>>()
        var i = 1
        while (b.deg > 0) {
            val a = QPoly.gcd(b, d)
            if (a.deg > 0) out += a to i
            b = b.divRem(a).first
            val cc = d.divRem(a).first
            d = cc - b.derivative()
            i++
            if (i > 200) break
        }
        return out
    }

    private fun chain(p: QPoly): List<QPoly> {
        val ch = arrayListOf(p, p.derivative())
        while (ch.last().deg > 0) {
            val r = -ch[ch.size - 2].divRem(ch.last()).second
            if (r.isZero) break
            ch += r
        }
        return ch
    }

    private fun variations(ch: List<QPoly>, x: Rational): Int {
        var last = 0
        var n = 0
        for (q in ch) {
            val s = q.eval(x).signum
            if (s == 0) continue
            if (last != 0 && s != last) n++
            last = s
        }
        return n
    }

    /** Real roots (as doubles, ascending) of a squarefree polynomial. */
    fun realRoots(p: QPoly): List<Double> {
        if (p.deg < 1) return emptyList()
        val ch = chain(p)
        var bound = Rational.ZERO
        for (i in 0 until p.deg) bound = maxOf(bound, (p.c[i] / p.lead).abs())
        bound += Rational.ONE
        val out = ArrayList<Double>()
        fun isolate(a: Rational, b: Rational, count: Int, depth: Int) {
            if (count <= 0) return
            if (count == 1 || depth > 200) { out += refine(p, ch, a, b); return }
            val m = (a + b) / Rational.of(2)
            val left = variations(ch, a) - variations(ch, m)
            isolate(a, m, left, depth + 1)
            isolate(m, b, count - left, depth + 1)
        }
        val lo = -bound
        isolate(lo, bound, variations(ch, lo) - variations(ch, bound), 0)
        return out.sorted()
    }

    /** One root in (a, b]: bisection with exact signs and Sturm counts, then converted to a double. */
    private fun refine(p: QPoly, ch: List<QPoly>, a0: Rational, b0: Rational): Double {
        var a = a0
        var b = b0
        if (p.eval(b).isZero) return b.toDouble()
        repeat(110) {
            val m = (a + b) / Rational.of(2)
            if (p.eval(m).isZero) return m.toDouble()
            if (variations(ch, a) - variations(ch, m) >= 1) b = m else a = m
        }
        return ((a + b) / Rational.of(2)).toDouble()
    }

    /** Distinct real roots with multiplicities of any rational polynomial. */
    fun withMultiplicity(f: QPoly): List<Pair<Double, Int>> =
        squareFree(f).flatMap { (s, m) -> realRoots(s).map { it to m } }.sortedBy { it.first }
}
