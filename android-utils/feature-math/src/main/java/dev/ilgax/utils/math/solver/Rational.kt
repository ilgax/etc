package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** Exact fraction, always reduced with a positive denominator. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {
    val isZero get() = num.signum() == 0
    val isInteger get() = den == BigInteger.ONE
    val signum get() = num.signum()

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.isZero) throw ArithmeticException("division by zero")
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)
    fun abs() = if (signum < 0) -this else this
    fun inverse() = ONE / this

    fun pow(n: Int): Rational = when {
        n >= 0 -> Rational(num.pow(n), den.pow(n))
        else -> inverse().pow(-n)
    }

    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = num.hashCode() * 31 + den.hashCode()
    override fun toString() = if (isInteger) num.toString() else "$num/$den"

    fun toDouble(): Double = java.math.BigDecimal(num).divide(java.math.BigDecimal(den), java.math.MathContext.DECIMAL64).toDouble()

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            if (d.signum() == 0) throw ArithmeticException("division by zero")
            if (n.signum() == 0) return ZERO
            val g = n.gcd(d)
            val s = if (d.signum() < 0) -1 else 1
            return Rational(n.divide(g).multiply(BigInteger.valueOf(s.toLong())), d.divide(g).abs())
        }

        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        /** "12", "-3.5", "0.25". */
        fun parse(s: String): Rational {
            val neg = s.startsWith("-")
            val t = s.removePrefix("-")
            val dot = t.indexOf('.')
            val r = if (dot < 0) of(BigInteger(t)) else {
                val frac = t.substring(dot + 1)
                of(BigInteger(t.substring(0, dot) + frac), BigInteger.TEN.pow(frac.length))
            }
            return if (neg) -r else r
        }
    }
}
