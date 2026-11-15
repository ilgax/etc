package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** Monomial: variable -> positive exponent. The empty map is the constant 1. */
data class Mono(val powers: Map<Char, Int>) : Comparable<Mono> {
    val degree get() = powers.values.sum()
    val isConstant get() = powers.isEmpty()
    fun exp(v: Char) = powers[v] ?: 0

    operator fun times(o: Mono): Mono {
        val m = HashMap(powers)
        for ((v, e) in o.powers) m[v] = (m[v] ?: 0) + e
        return Mono(m)
    }

    /** this / o when o divides this, else null. */
    fun divOrNull(o: Mono): Mono? {
        val m = HashMap(powers)
        for ((v, e) in o.powers) {
            val left = (m[v] ?: 0) - e
            if (left < 0) return null
            if (left == 0) m.remove(v) else m[v] = left
        }
        return Mono(m)
    }

    fun gcd(o: Mono) = Mono(powers.mapNotNull { (v, e) -> minOf(e, o.exp(v)).takeIf { it > 0 }?.let { v to it } }.toMap())

    /** Higher degree first, then alphabetical: x^2 before x before 1. */
    override fun compareTo(other: Mono): Int {
        if (degree != other.degree) return other.degree.compareTo(degree)
        val a = powers.entries.sortedBy { it.key }
        val b = other.powers.entries.sortedBy { it.key }
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i].key != b[i].key) return a[i].key.compareTo(b[i].key)
            if (a[i].value != b[i].value) return b[i].value.compareTo(a[i].value)
        }
        return a.size.compareTo(b.size)
    }

    companion object {
        val ONE = Mono(emptyMap())
        fun of(v: Char, e: Int = 1) = Mono(mapOf(v to e))
    }
}

/** Multivariate polynomial with exact surd coefficients. */
class Poly private constructor(val terms: Map<Mono, Surd>) {
    val isZero get() = terms.isEmpty()
    val isConstant get() = terms.keys.all { it.isConstant }
    val constant: Surd get() = terms[Mono.ONE] ?: Surd.ZERO
    val variables: Set<Char> get() = terms.keys.flatMap { it.powers.keys }.toSortedSet()
    val isMonomial get() = terms.size == 1

    operator fun plus(o: Poly): Poly {
        val m = HashMap(terms)
        for ((k, c) in o.terms) m[k] = (m[k] ?: Surd.ZERO) + c
        return build(m)
    }

    operator fun unaryMinus() = Poly(terms.mapValues { -it.value })
    operator fun minus(o: Poly) = this + (-o)

    operator fun times(o: Poly): Poly {
        val m = HashMap<Mono, Surd>()
        for ((k1, c1) in terms) for ((k2, c2) in o.terms) {
            val k = k1 * k2
            m[k] = (m[k] ?: Surd.ZERO) + c1 * c2
        }
        return build(m)
    }

    fun times(s: Surd) = if (s.isZero) ZERO else build(terms.mapValues { it.value * s })
    fun divBy(s: Surd) = build(terms.mapValues { it.value / s })

    fun pow(n: Int): Poly {
        require(n >= 0)
        var r = ONE
        repeat(n) { r *= this }
        return r
    }

    fun degreeIn(v: Char) = terms.keys.maxOfOrNull { it.exp(v) } ?: 0

    /** Coefficient (a polynomial in the other variables) of v^e. */
    fun coeffOf(v: Char, e: Int): Poly = build(
        terms.filterKeys { it.exp(v) == e }.mapKeys { (k, _) -> Mono(k.powers - v) },
    )

    /** Replaces v by a constant. */
    fun substitute(v: Char, value: Surd): Poly {
        var r = ZERO
        for (e in 0..degreeIn(v)) {
            val c = coeffOf(v, e)
            if (!c.isZero) r += c.times(value.pow(e))
        }
        return r
    }

    /** Monomial common to all terms. */
    fun monomialGcd(): Mono = terms.keys.reduceOrNull { a, b -> a.gcd(b) } ?: Mono.ONE

    fun divMono(m: Mono): Poly = build(terms.mapKeys { (k, _) -> k.divOrNull(m)!! })

    /** The only variable, if the polynomial has exactly one. */
    fun soleVariable(): Char? = variables.singleOrNull()

    /** Exact value if constant. */
    fun asSurd(): Surd? = if (isConstant) constant else null

    override fun equals(other: Any?) = other is Poly && terms == other.terms
    override fun hashCode() = terms.hashCode()
    override fun toString() = Printer.poly(this)

    companion object {
        val ZERO = Poly(emptyMap())
        val ONE = const(Surd.ONE)

        private fun build(m: Map<Mono, Surd>) = Poly(m.filterValues { !it.isZero })
        fun const(s: Surd) = if (s.isZero) ZERO else Poly(mapOf(Mono.ONE to s))
        fun const(n: Long) = const(Surd.of(n))
        fun variable(v: Char) = Poly(mapOf(Mono.of(v) to Surd.ONE))
        fun of(m: Mono, c: Surd) = build(mapOf(m to c))

        /** Univariate rational polynomial helpers used for gcd/division (coefficients must be rational). */
        fun exactDivide(n: Poly, d: Poly, v: Char): Poly? {
            if (d.isZero || !n.isRationalIn() || !d.isRationalIn()) return null
            var rem = n
            var quo = ZERO
            val dd = d.degreeIn(v)
            val lead = d.coeffOf(v, dd)
            if (!lead.isConstant) return null
            while (!rem.isZero) {
                val rd = rem.degreeIn(v)
                if (rd < dd) return null
                val rl = rem.coeffOf(v, rd)
                if (!rl.isConstant) return null
                val q = of(if (rd - dd == 0) Mono.ONE else Mono.of(v, rd - dd), rl.constant / lead.constant)
                quo += q
                rem -= q * d
                if (rem.degreeIn(v) >= rd && !rem.isZero) return null
            }
            return quo
        }

        fun univariateGcd(a: Poly, b: Poly, v: Char): Poly {
            var x = a
            var y = b
            while (!y.isZero) {
                val r = remainder(x, y, v) ?: return ONE
                x = y
                y = r
            }
            return x
        }

        private fun remainder(n: Poly, d: Poly, v: Char): Poly? {
            var rem = n
            val dd = d.degreeIn(v)
            val lead = d.coeffOf(v, dd)
            if (!lead.isConstant) return null
            while (!rem.isZero && rem.degreeIn(v) >= dd) {
                val rd = rem.degreeIn(v)
                val rl = rem.coeffOf(v, rd)
                if (!rl.isConstant) return null
                val q = of(if (rd - dd == 0) Mono.ONE else Mono.of(v, rd - dd), rl.constant / lead.constant)
                rem -= q * d
            }
            return rem
        }
    }

    private fun isRationalIn() = terms.values.all { it.isRational } && variables.size <= 1
}

/** Rational function num/den with den ≠ 0. Not auto-simplified; see [simplified]. */
class RatFn(val num: Poly, val den: Poly) {
    init { require(!den.isZero) }

    val variables: Set<Char> get() = num.variables + den.variables
    val isPoly get() = den.isConstant

    operator fun plus(o: RatFn) = if (den == o.den) RatFn(num + o.num, den) else RatFn(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: RatFn) = this + (-o)
    operator fun unaryMinus() = RatFn(-num, den)
    operator fun times(o: RatFn) = RatFn(num * o.num, den * o.den)
    operator fun div(o: RatFn): RatFn {
        if (o.num.isZero) throw UnsupportedMath("division by zero")
        return RatFn(num * o.den, den * o.num)
    }

    fun pow(n: Int): RatFn = when {
        n >= 0 -> RatFn(num.pow(n), den.pow(n))
        else -> {
            if (num.isZero) throw UnsupportedMath("division by zero")
            RatFn(den.pow(-n), num.pow(-n))
        }
    }

    fun substitute(v: Char, value: Surd) = RatFn(num.substitute(v, value), den.substitute(v, value))

    /** Result of cancelling: the simplified function and the factor that was removed (1 if none). */
    class Cancelled(val value: RatFn, val factor: Poly)

    fun simplified(): Cancelled {
        if (num.isZero) return Cancelled(RatFn(Poly.ZERO, Poly.ONE), den)
        var n = num
        var d = den
        var factor = Poly.ONE
        // common monomial
        val g = n.monomialGcd().gcd(d.monomialGcd())
        if (!g.isConstant) {
            n = n.divMono(g); d = d.divMono(g); factor *= Poly.of(g, Surd.ONE)
        }
        // common univariate rational factor
        val vars = n.variables + d.variables
        if (vars.size == 1 && !d.isConstant && !n.isConstant) {
            val v = vars.first()
            val gp = Poly.univariateGcd(n, d, v)
            if (!gp.isConstant) {
                val nn = Poly.exactDivide(n, gp, v)
                val dd = Poly.exactDivide(d, gp, v)
                if (nn != null && dd != null) { n = nn; d = dd; factor *= gp }
            }
        } else if (vars.size == 1 && d.isConstant.not() && n.isConstant) {
            // constant over polynomial: nothing to cancel
        }
        // constant denominators move into the numerator
        if (d.isConstant) {
            val c = d.constant
            return Cancelled(RatFn(n.divBy(c), Poly.ONE), factor)
        }
        // normalise the denominator's leading coefficient to be positive-rational where possible
        val lead = d.terms.entries.minByOrNull { it.key }!!.value
        if (lead.isRational && lead.rational.signum != 0 && lead != Surd.ONE) {
            n = n.divBy(lead); d = d.divBy(lead)
        }
        return Cancelled(RatFn(n, d), factor)
    }

    override fun toString() = Printer.ratFn(this)
}
