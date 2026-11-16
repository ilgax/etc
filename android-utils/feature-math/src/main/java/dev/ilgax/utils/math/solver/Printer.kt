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

    private val superscripts = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    /** π^(half/2) or e^(half/2) as numerator or denominator pieces. */
    private fun constPieces(half: Int, sym: String, plainSym: String, plain: Boolean, num: MutableList<String>, den: MutableList<String>) {
        if (half == 0) return
        val target = if (half > 0) num else den
        val n = Math.abs(half)
        val whole = n / 2
        if (whole >= 1) {
            target += if (plain) plainSym + (if (whole > 1) whole.toString().map { superscripts[it - '0'] }.joinToString("") else "")
            else sym + (if (whole > 1) "^{$whole}" else "")
        }
        if (n % 2 == 1) target += if (plain) "√$plainSym" else "\\sqrt{$sym}"
    }

    /** One term q·√k·π^a·e^b, no leading sign handling beyond the minus. */
    private fun term(key: SKey, q: Rational, plain: Boolean): String {
        if (key == SKey.ONE) return rational(q, plain)
        val neg = q.signum < 0
        val a = q.num.abs()
        val nums = ArrayList<String>()
        val dens = ArrayList<String>()
        if (key.k != BigInteger.ONE) nums += sqrtText(key.k, plain)
        constPieces(key.pi, "\\pi", "π", plain, nums, dens)
        constPieces(key.e, "e", "e", plain, nums, dens)
        val numText = (if (a == BigInteger.ONE && nums.isNotEmpty()) "" else a.toString()) + nums.joinToString("")
        val denText = (if (q.den == BigInteger.ONE) "" else q.den.toString()) + dens.joinToString("")
        val core = when {
            denText.isEmpty() -> numText
            plain -> "${numText.ifEmpty { "1" }}/${if (q.den != BigInteger.ONE && dens.isNotEmpty() || dens.size > 1) "($denText)" else denText}"
            else -> "\\frac{${numText.ifEmpty { "1" }}}{$denText}"
        }
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

    /** Resolves atom placeholder symbols to their LaTeX while a solve is running (per thread). */
    val atomTex = ThreadLocal<((Char) -> String?)?>()

    private fun varText(v: Char, e: Int): String {
        val t = if (AtomTable.isAtomSym(v)) atomTex.get()?.invoke(v) else null
        if (t == null) return if (e == 1) "$v" else "$v^{$e}"
        return if (e == 1) t else "($t)^{$e}"
    }

    private fun mono(m: Mono): String {
        val sb = StringBuilder()
        for ((v, e) in m.powers.entries.sortedBy { it.key }) {
            val piece = varText(v, e)
            if (sb.isNotEmpty() && piece[0].isDigit()) sb.append("\\cdot ")
            sb.append(piece)
        }
        return sb.toString()
    }

    /** A real number for display: [dp] decimals, trailing zeros dropped. */
    fun num(d: Double, dp: Int = 4): String {
        if (d.isNaN() || d.isInfinite()) return "?"
        val t = BigDecimal(d).setScale(dp, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (t == "-0") "0" else t
    }

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
                mag.isSingleTerm || mag.isRational -> surd(mag) + dotIfDigit(mono(m))
                else -> "(" + surd(mag) + ")" + mono(m)
            }
            if (i == 0) sb.append(if (neg) "-" else "").append(text) else sb.append(if (neg) "-" else "+").append(text)
        }
        return sb.toString()
    }

    private fun dotIfDigit(m: String) = if (m.isNotEmpty() && m[0].isDigit()) "\\cdot $m" else m

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
        val scale = if (s.hasConstants) 3 else 2
        val rounded = bd.setScale(scale, RoundingMode.HALF_UP).let { if (it.signum() == 0 && bd.signum() != 0) bd.round(java.math.MathContext(3)) else it }
        val exact = s.isRational && runCatching { BigDecimal(s.rational.num).divide(BigDecimal(s.rational.den)) }.getOrNull()?.let { it.scale() <= 4 } == true
        if (exact) {
            val v = BigDecimal(s.rational.num).divide(BigDecimal(s.rational.den)).stripTrailingZeros()
            return Decimal(v.toPlainString(), true)
        }
        return Decimal(rounded.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }, false)
    }

    data class Decimal(val text: String, val exact: Boolean)
}
