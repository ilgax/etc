package dev.ilgax.utils.math.solver

import java.math.BigInteger

/**
 * Unit of the angles in an equation. Auto: degrees if a degree sign appears or π does not, radians if π appears
 * (and no degree sign does).
 */
enum class AngleMode { Auto, Degrees, Radians }

/** Exact values of the six trigonometric functions at the multiples of 15°, and their inverses. */
internal object TrigExact {
    val forward = listOf("sin", "cos", "tan", "cot", "sec", "csc")

    private fun q(n: Long, d: Long = 1) = Surd.of(Rational.of(n, d))
    private fun sq(k: Long, n: Long = 1, d: Long = 1) = Surd.root(BigInteger.valueOf(k), Rational.of(n, d))

    private val s6 = sq(6)
    private val s2 = sq(2)
    private val s3 = sq(3)

    /** sin, tan and sec on the first quadrant, by degrees; a missing entry is undefined. */
    private val sinQ = mapOf(
        0 to Surd.ZERO, 15 to (s6 - s2) * q(1, 4), 30 to q(1, 2), 45 to sq(2, 1, 2), 60 to sq(3, 1, 2), 75 to (s6 + s2) * q(1, 4), 90 to q(1),
    )
    private val tanQ = mapOf(
        0 to Surd.ZERO, 15 to q(2) - s3, 30 to sq(3, 1, 3), 45 to q(1), 60 to s3, 75 to q(2) + s3,
    )
    private val secQ = mapOf(
        0 to q(1), 15 to s6 - s2, 30 to sq(3, 2, 3), 45 to s2, 60 to q(2), 75 to s6 + s2,
    )

    /** The exact value of [fn] at [deg] degrees (a multiple of 15), or null where it is undefined. */
    fun value(fn: String, deg: Int): Surd? {
        val d = ((deg % 360) + 360) % 360
        val (r, ss, cs) = when {
            d <= 90 -> Triple(d, 1, 1)
            d <= 180 -> Triple(180 - d, 1, -1)
            d <= 270 -> Triple(d - 180, -1, -1)
            else -> Triple(360 - d, -1, 1)
        }
        val sign = when (fn) { "sin", "csc" -> ss; "cos", "sec" -> cs; else -> ss * cs }
        val v = when (fn) {
            "sin" -> sinQ[r]
            "cos" -> sinQ[90 - r]
            "tan" -> tanQ[r]
            "cot" -> tanQ[90 - r]
            "sec" -> secQ[r]
            "csc" -> secQ[90 - r]
            else -> null
        } ?: return null
        return if (sign < 0) -v else v
    }

    /** [angle] (in degrees, or radians when [deg] is false) as a whole number of degrees that is a multiple of 15, else null. */
    fun degreesOf(angle: Surd, deg: Boolean): Int? {
        val inDeg = if (deg) angle else if (angle.isZero) angle else (angle / Surd.PI_SURD) * Surd.of(180)
        if (!inDeg.isRational) return null
        val r = inDeg.rational
        if (!r.isInteger || r.num.abs() > BigInteger.valueOf(100000)) return null
        val n = r.num.toInt()
        return if (n % 15 == 0) n else null
    }

    /** All degrees in [0, 360) at which [fn] equals [c]; for tan and cot only [0, 180) (their period). */
    fun solveDeg(fn: String, c: Surd): List<Int> {
        val limit = if (fn == "tan" || fn == "cot") 180 else 360
        return (0 until limit step 15).filter { value(fn, it) == c }
    }

    /** arcsin, arccos or arctan of an exact value as degrees, when that is a multiple of 15. */
    fun inverseDeg(fn: String, c: Surd): Int? {
        val range = when (fn) { "arcsin" -> -90..90; "arccos" -> 0..180; else -> -90..90 }
        val base = fn.removePrefix("arc")
        return (range step 15).firstOrNull { value(base, it) == c }
    }

    /** A whole number of degrees as an angle in the unit of the equation (kπ/12 for radians). */
    fun angle(deg: Int, inDegrees: Boolean): Surd =
        if (inDegrees) Surd.of(deg.toLong()) else Surd.of(Rational.of(deg.toLong(), 180)) * Surd.PI_SURD

    /** Numeric value of [fn] at [radians]; throws [Undefined] where the function has a pole. */
    fun numeric(fn: String, radians: Double, shown: () -> String): Double {
        val s = Math.sin(radians)
        val c = Math.cos(radians)
        fun pole(x: Double, name: String) { if (Math.abs(x) < 1e-10) throw Undefined("$name ${shown()} is undefined") }
        return when (fn) {
            "sin" -> s
            "cos" -> c
            "tan" -> { pole(c, "tan"); s / c }
            "cot" -> { pole(s, "cot"); c / s }
            "sec" -> { pole(c, "sec"); 1 / c }
            "csc" -> { pole(s, "csc"); 1 / s }
            else -> throw IllegalArgumentException(fn)
        }
    }
}

/** Angles as LaTeX: degrees as `30^{\circ}`, radians as `\frac{\pi}{6}`; numeric values to 4 decimals. */
internal object AngleFmt {
    fun exact(s: Surd, deg: Boolean): String {
        if (!deg) return Printer.surd(s)
        if (!s.isRational) return "(" + Printer.surd(s) + ")^{\\circ}"
        val r = s.rational
        if (r.isInteger) return r.num.toString() + "^{\\circ}"
        val dec = try { java.math.BigDecimal(r.num).divide(java.math.BigDecimal(r.den)) } catch (_: ArithmeticException) { null }
        return if (dec != null && dec.scale() <= 4) dec.stripTrailingZeros().toPlainString() + "^{\\circ}" else Printer.rational(r) + "^{\\circ}"
    }

    fun numeric(d: Double, deg: Boolean): String = Printer.num(d, 4) + if (deg) "^{\\circ}" else ""

    fun of(a: Ang, deg: Boolean): String = a.s?.let { exact(it, deg) } ?: numeric(a.d, deg)
}

/** An angle that is either exact (a surd, in the equation's unit) or only known numerically. */
internal class Ang(val s: Surd?, val d: Double) {
    companion object {
        fun of(s: Surd) = Ang(s, s.toDouble())
        fun num(d: Double) = Ang(null, d)
    }
}
