package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** The solver cannot handle this input; the message is shown to the user. */
class UnsupportedMath(message: String) : Exception(message)

/**
 * Exact real number Σ qᵢ·√kᵢ with distinct squarefree integers kᵢ ≥ 1 (k = 1 is the rational part).
 * Canonical form (no zero terms) makes equality structural.
 */
class Surd private constructor(val terms: Map<BigInteger, Rational>) {
    val isZero get() = terms.isEmpty()
    val isRational get() = terms.isEmpty() || (terms.size == 1 && terms.containsKey(BigInteger.ONE))
    val rational: Rational get() = terms[BigInteger.ONE] ?: Rational.ZERO
    val isSingleTerm get() = terms.size == 1

    operator fun plus(o: Surd): Surd {
        val m = HashMap(terms)
        for ((k, q) in o.terms) m[k] = (m[k] ?: Rational.ZERO) + q
        return build(m)
    }

    operator fun unaryMinus() = Surd(terms.mapValues { -it.value })
    operator fun minus(o: Surd) = this + (-o)

    operator fun times(o: Surd): Surd {
        val m = HashMap<BigInteger, Rational>()
        for ((k1, q1) in terms) for ((k2, q2) in o.terms) {
            val g = k1.gcd(k2)
            val k = k1.divide(g) * k2.divide(g)
            val q = q1 * q2 * Rational.of(g)
            m[k] = (m[k] ?: Rational.ZERO) + q
        }
        return build(m)
    }

    /** Division by a single-term surd (or any rational). */
    operator fun div(o: Surd): Surd {
        if (o.isZero) throw ArithmeticException("division by zero")
        if (!o.isSingleTerm) throw UnsupportedMath("dividing by a sum of square roots")
        val (k, q) = o.terms.entries.first()
        // 1/(q√k) = √k / (q·k)
        val inv = Surd(mapOf(k to (Rational.ONE / (q * Rational.of(k)))))
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
        for ((k, q) in terms) {
            val root = if (k == BigInteger.ONE) BigDecimal.ONE else BigDecimal(k).sqrt(mc)
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
            val (k, q) = terms.entries.first()
            // √(q√k) is a fourth root: only exact in the perfect-square-times-... cases we skip
            throw UnsupportedMath("the square root of ${q}√$k")
        }
        // (a + b√k)² candidates: try denesting a+b√k with rational parts only.
        denest()?.let { return it }
        throw UnsupportedMath("the square root of a sum of surds")
    }

    private fun denest(): Surd? {
        if (terms.size != 2 || !terms.containsKey(BigInteger.ONE)) return null
        val a = rational
        val (k, b) = terms.entries.first { it.key != BigInteger.ONE }
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
    override fun toString() = if (isZero) "0" else terms.entries.sortedBy { it.key }.joinToString(" + ") { (k, q) -> if (k == BigInteger.ONE) "$q" else "$q√$k" }

    companion object {
        val ZERO = Surd(emptyMap())
        val ONE = of(Rational.ONE)

        fun of(q: Rational): Surd = if (q.isZero) ZERO else Surd(mapOf(BigInteger.ONE to q))
        fun of(n: Long) = of(Rational.of(n))

        private fun build(m: Map<BigInteger, Rational>) = Surd(m.filterValues { !it.isZero })

        /** q·√k with k reduced to squarefree. */
        fun root(k: BigInteger, q: Rational = Rational.ONE): Surd {
            val (outer, inner) = squareFree(k)
            return build(mapOf(inner to q * Rational.of(outer)))
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
