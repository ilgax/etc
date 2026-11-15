package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Renders exact values as LaTeX (for KaTeX and re-parsing) or as plain text with unicode (for the clipboard). */
object Printer {
    fun rational(q: Rational, plain: Boolean = false): String = when {
        q.isInteger -> q.num.toString()
        plain -> "${q.num}/${q.den}"
        q.signum < 0 -> "-\\frac{${q.num.abs()}}{${q.den}}"
        else -> "\\frac{${q.num}}{${q.den}}"
    }

    private fun sqrtText(k: BigInteger, plain: Boolean) = if (plain) "√$k" else "\\sqrt{$k}"

    /** One term q√k, no leading sign handling beyond the minus. */
    private fun term(k: BigInteger, q: Rational, plain: Boolean): String {
        if (k == BigInteger.ONE) return rational(q, plain)
        val neg = q.signum < 0
        val a = q.num.abs()
        val body = (if (a == BigInteger.ONE) "" else a.toString()) + sqrtText(k, plain)
        val core = if (q.isInteger) body else if (plain) "$body/${q.den}" else "\\frac{$body}{${q.den}}"
        return (if (neg) "-" else "") + core
    }

    fun surd(s: Surd, plain: Boolean = false): String {
        if (s.isZero) return "0"
        if (s.isSingleTerm) return s.terms.entries.first().let { term(it.key, it.value, plain) }
        // common denominator: (a + b√k)/L
        var l = BigInteger.ONE
        for (q in s.terms.values) l = l.multiply(q.den).divide(l.gcd(q.den))
        val ordered = s.terms.entries.sortedBy { it.key }
        val sb = StringBuilder()
        for ((i, e) in ordered.withIndex()) {
            val c = e.value * Rational.of(l)
            val t = term(e.key, c, plain)
            if (i == 0) sb.append(t) else if (t.startsWith("-")) sb.append("-").append(t.substring(1)) else sb.append("+").append(t)
        }
        if (l == BigInteger.ONE) return sb.toString()
        return if (plain) "($sb)/$l" else "\\frac{$sb}{$l}"
    }

    private fun mono(m: Mono): String = m.powers.entries.sortedBy { it.key }.joinToString("") { (v, e) -> if (e == 1) "$v" else "$v^{$e}" }

    fun poly(p: Poly): String {
        if (p.isZero) return "0"
        val sb = StringBuilder()
        for ((i, e) in p.terms.entries.sortedBy { it.key }.withIndex()) {
            val m = e.key
            val c = e.value
            val neg = isNegative(c)
            val mag = if (neg) -c else c
            val text = when {
                m.isConstant -> surd(mag)
                mag == Surd.ONE -> mono(m)
                mag.isSingleTerm || mag.isRational -> surd(mag) + mono(m)
                else -> "(" + surd(mag) + ")" + mono(m)
            }
            if (i == 0) sb.append(if (neg) "-" else "").append(text) else sb.append(if (neg) "-" else "+").append(text)
        }
        return sb.toString()
    }

    /** Sign of a coefficient for printing: the sign of its first printed term. */
    private fun isNegative(c: Surd): Boolean = c.terms.entries.minByOrNull { it.key }!!.value.signum < 0

    private fun wrap(p: Poly) = if (p.terms.size > 1) "(${poly(p)})" else poly(p)

    fun ratFn(f: RatFn): String {
        if (f.den == Poly.ONE) return poly(f.num)
        if (f.num.isMonomial && isNegative(f.num.terms.values.first())) return "-\\frac{${poly(-f.num)}}{${poly(f.den)}}"
        return "\\frac{${poly(f.num)}}{${poly(f.den)}}"
    }

    /** Like [ratFn] but keeps rational coefficients as one fraction: (x+15)/16 instead of x/16 + 15/16. */
    fun ratFnNice(f: RatFn): String {
        if (f.den != Poly.ONE || f.num.terms.size < 2 && f.num.terms.values.all { it.isRational && it.rational.isInteger }) return ratFn(f)
        if (!f.num.terms.values.all { it.isRational }) return ratFn(f)
        var l = BigInteger.ONE
        for (c in f.num.terms.values) l = l.multiply(c.rational.den).divide(l.gcd(c.rational.den))
        if (l == BigInteger.ONE) return ratFn(f)
        val scaled = f.num.times(Surd.of(Rational.of(l)))
        return if (scaled.isMonomial && isNegative(scaled.terms.values.first())) "-\\frac{${poly(-scaled)}}{$l}" else "\\frac{${poly(scaled)}}{$l}"
    }

    fun product(a: Poly, b: Poly) = if (a == Poly.ONE) wrap(b) else if (b == Poly.ONE) wrap(a) else wrap(a) + "\\cdot " + wrap(b)

    /** "= 11.31" when finite and short, "≈ 11.31" otherwise; empty for integers. */
    fun decimal(s: Surd): Decimal? {
        if (s.isRational && s.rational.isInteger) return null
        val bd = s.toBigDecimal()
        val rounded = bd.setScale(2, RoundingMode.HALF_UP).let { if (it.signum() == 0 && bd.signum() != 0) bd.round(java.math.MathContext(3)) else it }
        val exact = s.isRational && runCatching { BigDecimal(s.rational.num).divide(BigDecimal(s.rational.den)) }.getOrNull()?.let { it.scale() <= 4 } == true
        if (exact) {
            val v = BigDecimal(s.rational.num).divide(BigDecimal(s.rational.den)).stripTrailingZeros()
            return Decimal(v.toPlainString(), true)
        }
        return Decimal(rounded.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }, false)
    }

    data class Decimal(val text: String, val exact: Boolean)
}
