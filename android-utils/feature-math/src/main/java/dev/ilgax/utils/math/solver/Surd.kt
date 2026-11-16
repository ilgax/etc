package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** The solver cannot handle this input; the message is shown to the user. */
open class UnsupportedMath(message: String) : Exception(message)

/** Unsupported in exact form, but a numeric search for the roots of one unknown may still work. */
class NeedsNumeric(message: String) : UnsupportedMath(message)

/**
 * One basis element √k·π^(pi/2)·e^(e/2) of a [Surd]: k is squarefree, the constant exponents are counted in
 * halves so that √π and √e stay exact. (k=1, 0, 0) is the rational part.
 */
data class SKey(val k: BigInteger, val pi: Int = 0, val e: Int = 0) : Comparable<SKey> {
    val hasConstants get() = pi != 0 || e != 0
    override fun compareTo(other: SKey): Int {
        val c = k.compareTo(other.k)
        if (c != 0) return c
        if (pi != other.pi) return pi.compareTo(other.pi)
        return e.compareTo(other.e)
    }

    companion object { val ONE = SKey(BigInteger.ONE) }
}

/**
 * Exact real number Σ qᵢ·√kᵢ·π^aᵢ·e^bᵢ with distinct squarefree integers kᵢ ≥ 1 (k = 1, no constants is the
 * rational part). Canonical form (no zero terms) makes equality structural.
 */
class Surd private constructor(val terms: Map<SKey, Rational>) {
    val isZero get() = terms.isEmpty()
    val isRational get() = terms.isEmpty() || (terms.size == 1 && terms.containsKey(SKey.ONE))
    val rational: Rational get() = terms[SKey.ONE] ?: Rational.ZERO
    val isSingleTerm get() = terms.size == 1
    /** True when π or e appears. */
    val hasConstants get() = terms.keys.any { it.hasConstants }

    operator fun plus(o: Surd): Surd {
        val m = HashMap(terms)
        for ((k, q) in o.terms) m[k] = (m[k] ?: Rational.ZERO) + q
        return build(m)
    }

    operator fun unaryMinus() = Surd(terms.mapValues { -it.value })
    operator fun minus(o: Surd) = this + (-o)

    operator fun times(o: Surd): Surd {
        val m = HashMap<SKey, Rational>()
        for ((a, q1) in terms) for ((b, q2) in o.terms) {
            val g = a.k.gcd(b.k)
            val key = SKey(a.k.divide(g) * b.k.divide(g), a.pi + b.pi, a.e + b.e)
            val q = q1 * q2 * Rational.of(g)
            m[key] = (m[key] ?: Rational.ZERO) + q
        }
        return build(m)
    }

    /** Division by a single-term surd (or any rational). */
    operator fun div(o: Surd): Surd {
        if (o.isZero) throw ArithmeticException("division by zero")
        if (!o.isSingleTerm) throw UnsupportedMath("dividing by a sum of square roots")
        val (key, q) = o.terms.entries.first()
        // 1/(q√k·π^a·e^b) = √k·π^-a·e^-b / (q·k)
        val inv = Surd(mapOf(SKey(key.k, -key.pi, -key.e) to (Rational.ONE / (q * Rational.of(key.k)))))
        return this * inv
    }

    fun pow(n: Int): Surd = when {
        n == 0 -> ONE
        n < 0 -> ONE / pow(-n)
        else -> { var r = ONE; repeat(n) { r *= this }; r }
    }

    fun signum(): Int = if (isZero) 0 else toBigDecimal(MathContext(40)).signum()

    fun toBigDecimal(mc: MathContext = MathContext(40)): BigDecimal {
        var sum = BigDecimal.ZERO
        for ((key, q) in terms) {
            var root = if (key.k == BigInteger.ONE) BigDecimal.ONE else BigDecimal(key.k).sqrt(mc)
            if (key.pi != 0) root = root.multiply(halfPower(PI, key.pi, mc), mc)
            if (key.e != 0) root = root.multiply(halfPower(E, key.e, mc), mc)
            sum += BigDecimal(q.num).multiply(root, mc).divide(BigDecimal(q.den), mc)
        }
        return sum
    }

    fun toDouble() = toBigDecimal().toDouble()

    /** Principal square root. Exact for a single term; otherwise only when the sum is rational. */
    fun sqrt(): Surd {
        if (signum() < 0) throw ArithmeticException("negative square root")
        if (isZero) return ZERO
        if (isRational) return sqrtRational(rational)
        if (isSingleTerm) {
            val (key, q) = terms.entries.first()
            // √(q·π^a·e^b) is exact when the exponents are whole; √(q√k) would be a fourth root
            if (key.k == BigInteger.ONE && key.pi % 2 == 0 && key.e % 2 == 0 && q.signum > 0) {
                return sqrtRational(q) * Surd(mapOf(SKey(BigInteger.ONE, key.pi / 2, key.e / 2) to Rational.ONE))
            }
            throw UnsupportedMath("the square root of ${q}√${key.k}")
        }
        // (a + b√k)² candidates: try denesting a+b√k with rational parts only.
        denest()?.let { return it }
        throw UnsupportedMath("the square root of a sum of surds")
    }

    private fun denest(): Surd? {
        if (terms.size != 2 || !terms.containsKey(SKey.ONE) || hasConstants) return null
        val a = rational
        val (key, b) = terms.entries.first { it.key != SKey.ONE }
        val k = key.k
        // √(a + b√k) = √x + √y  with x + y = a, 4xy = b²k
        val disc = a * a - b * b * Rational.of(k)
        if (disc.signum < 0) return null
        val root = sqrtRational(disc)
        if (!root.isRational) return null
        val r = root.rational
        val x = (a + r) / Rational.of(2)
        val y = (a - r) / Rational.of(2)
        val sx = sqrtRational(x)
        val sy = sqrtRational(y)
        if (!sx.isRational && !sx.isSingleTerm) return null
        val res = if (b.signum > 0) sx + sy else sx - sy
        return if ((res * res - this).isZero) res else null
    }

    override fun equals(other: Any?) = other is Surd && terms == other.terms
    override fun hashCode() = terms.hashCode()
    override fun toString() = if (isZero) "0" else terms.entries.sortedBy { it.key }.joinToString(" + ") { (key, q) ->
        if (key == SKey.ONE) "$q" else "$q√${key.k}" + (if (key.pi != 0) "π^${key.pi}/2" else "") + (if (key.e != 0) "e^${key.e}/2" else "")
    }

    companion object {
        val ZERO = Surd(emptyMap())
        val ONE = of(Rational.ONE)

        fun of(q: Rational): Surd = if (q.isZero) ZERO else Surd(mapOf(SKey.ONE to q))
        fun of(n: Long) = of(Rational.of(n))

        private fun build(m: Map<SKey, Rational>) = Surd(m.filterValues { !it.isZero })

        val PI = BigDecimal("3.14159265358979323846264338327950288419716939937510582097494459")
        val E = BigDecimal("2.71828182845904523536028747135266249775724709369995957496696763")
        /** π^(pi/2)·e^(e/2) with exponents in halves. */
        fun constant(pi: Int, e: Int): Surd = Surd(mapOf(SKey(BigInteger.ONE, pi, e) to Rational.ONE))
        val PI_SURD: Surd = Surd(mapOf(SKey(BigInteger.ONE, 2, 0) to Rational.ONE))
        val E_SURD: Surd = Surd(mapOf(SKey(BigInteger.ONE, 0, 2) to Rational.ONE))

        /** c^(n/2). */
        private fun halfPower(c: BigDecimal, n: Int, mc: MathContext): BigDecimal {
            val whole = Math.abs(n) / 2
            var r = c.pow(whole, mc)
            if (Math.abs(n) % 2 == 1) r = r.multiply(c.sqrt(mc), mc)
            return if (n < 0) BigDecimal.ONE.divide(r, mc) else r
        }

        /** q·√k with k reduced to squarefree. */
        fun root(k: BigInteger, q: Rational = Rational.ONE): Surd {
            val (outer, inner) = squareFree(k)
            return build(mapOf(SKey(inner) to q * Rational.of(outer)))
        }

        fun sqrtRational(r: Rational): Surd {
            require(r.signum >= 0)
            // √(a/b) = √(ab) / b
            return root(r.num * r.den, Rational.of(BigInteger.ONE, r.den))
        }

        /** n = outer² · inner with inner squarefree (trial division, bounded). */
        fun squareFree(n: BigInteger): Pair<BigInteger, BigInteger> {
            var rest = n
            var outer = BigInteger.ONE
            var inn = BigInteger.ONE
            var p = BigInteger.TWO
            val limit = BigInteger.valueOf(1_000_000)
            while (p <= limit && p * p <= rest) {
                var e = 0
                while (rest.mod(p).signum() == 0) { rest = rest.divide(p); e++ }
                repeat(e / 2) { outer *= p }
                if (e % 2 == 1) inn *= p
                p = if (p == BigInteger.TWO) BigInteger.valueOf(3) else p + BigInteger.TWO
            }
            // leftover is a prime (or an unfactored cofactor); a perfect square cofactor is still pulled out
            val s = rest.sqrt()
            if (s * s == rest && rest != BigInteger.ONE) { outer *= s } else inn *= rest
            return outer to inn
        }
    }
}
