package dev.ilgax.utils.math.solver

import java.math.BigInteger
import java.util.TreeMap

/** Prime exponent vector of a positive number: primes as decimal strings, plus "π" and "e". Exponents are rational. */
internal typealias PVec = Map<String, Rational>

/** Integer and prime helpers shared by the root finder, the logarithms and the exponential solver. */
internal object NumberTheory {
    private val LIMIT = BigInteger.valueOf(1_000_000)
    private val BOUND = BigInteger.TEN.pow(12)

    /** Prime factorisation of n > 0 by trial division; null when a leftover cofactor cannot be trusted. */
    fun factorize(n: BigInteger): Map<BigInteger, Int>? {
        require(n.signum() > 0)
        val out = TreeMap<BigInteger, Int>()
        var rest = n
        var p = BigInteger.TWO
        while (p <= LIMIT && p * p <= rest) {
            var e = 0
            while (rest.mod(p).signum() == 0) { rest = rest.divide(p); e++ }
            if (e > 0) out[p] = e
            p = if (p == BigInteger.TWO) BigInteger.valueOf(3) else p + BigInteger.TWO
        }
        if (rest > BigInteger.ONE) {
            if (rest < BOUND || rest.isProbablePrime(40)) out[rest] = (out[rest] ?: 0) + 1 else return null
        }
        return out
    }

    /** All positive divisors, or null when n is too big to factor or has more than [cap] divisors. */
    fun divisors(n: BigInteger, cap: Int = 4000): List<BigInteger>? {
        val f = factorize(n.abs().takeIf { it.signum() > 0 } ?: return null) ?: return null
        var ds = listOf(BigInteger.ONE)
        for ((p, e) in f) {
            val next = ArrayList<BigInteger>()
            var pw = BigInteger.ONE
            for (i in 0..e) { for (d in ds) next += d * pw; pw *= p }
            ds = next
            if (ds.size > cap) return null
        }
        return ds.sorted()
    }

    /** Exact k-th root of a non-negative integer, or null. */
    fun exactRoot(v: BigInteger, k: Int): BigInteger? {
        if (v.signum() == 0) return BigInteger.ZERO
        if (k == 1) return v
        var lo = BigInteger.ONE
        var hi = BigInteger.ONE.shiftLeft(v.bitLength() / k + 1)
        while (lo < hi) {
            val mid = (lo + hi).shiftRight(1)
            if (mid.pow(k) < v) lo = mid + BigInteger.ONE else hi = mid
        }
        return if (lo.pow(k) == v) lo else null
    }

    /** Exact k-th root of a non-negative rational, or null. */
    fun exactRoot(q: Rational, k: Int): Rational? {
        val n = exactRoot(q.num, k) ?: return null
        val d = exactRoot(q.den, k) ?: return null
        return Rational.of(n, d)
    }

    private fun addFactors(m: MutableMap<String, Rational>, n: BigInteger, sign: Int, half: Boolean): Boolean {
        if (n == BigInteger.ONE) return true
        val f = factorize(n) ?: return false
        for ((p, e) in f) {
            val key = p.toString()
            var q = Rational.of(e.toLong() * sign)
            if (half) q /= Rational.of(2)
            m[key] = (m[key] ?: Rational.ZERO) + q
        }
        return true
    }

    /** Exponent vector of a positive single-term surd (q·√k·π^a·e^b); null for sums, zero or negatives. */
    fun vecOf(s: Surd): PVec? {
        if (!s.isSingleTerm) return null
        val (key, q) = s.terms.entries.first()
        if (q.signum <= 0) return null
        val m = HashMap<String, Rational>()
        if (!addFactors(m, q.num, 1, false) || !addFactors(m, q.den, -1, false) || !addFactors(m, key.k, 1, true)) return null
        if (key.pi != 0) m["π"] = Rational.of(key.pi.toLong(), 2)
        if (key.e != 0) m["e"] = Rational.of(key.e.toLong(), 2)
        return m.filterValues { !it.isZero }
    }

    /** The surd with the given exponent vector, if every exponent is a multiple of 1/2. */
    fun fromVec(v: PVec): Surd? {
        var q = Rational.ONE
        var k = BigInteger.ONE
        var pi = 0
        var e = 0
        for ((key, r) in v) {
            val twice = r * Rational.of(2)
            if (!twice.isInteger) return null
            val t = twice.num.toInt()
            when (key) {
                "π" -> pi = t
                "e" -> e = t
                else -> {
                    val p = BigInteger(key)
                    val whole = Math.floorDiv(t, 2)
                    q *= Rational.of(p).pow(whole)
                    if (t % 2 != 0) k *= p
                }
            }
        }
        return Surd.root(k, q) * Surd.constant(pi, e)
    }

    /** q such that b = q·a (as exponent vectors), or null. */
    fun ratioOf(a: PVec, b: PVec): Rational? {
        if (a.isEmpty()) return null
        val first = a.entries.first()
        val q = (b[first.key] ?: Rational.ZERO) / first.value
        for (key in a.keys + b.keys) {
            if ((b[key] ?: Rational.ZERO) != q * (a[key] ?: Rational.ZERO)) return null
        }
        return q
    }

    /** log_base(arg) when it is a rational number (log_4 2 = 1/2, log 1000 = 3, ln e² = 2), else null. */
    fun exactLog(base: Surd, arg: Surd): Rational? {
        val vb = vecOf(base) ?: return null
        val va = vecOf(arg) ?: return null
        if (va.isEmpty()) return Rational.ZERO
        return ratioOf(vb, va)
    }

    /** b^r as an exact surd when it exists (integer powers, halves, perfect powers), else null. */
    fun exactPow(b: Surd, r: Rational): Surd? {
        if (r.isInteger && r.num.bitLength() < 12) return runCatching { b.pow(r.num.toInt()) }.getOrNull()
        val v = vecOf(b) ?: return null
        return fromVec(v.mapValues { it.value * r })
    }

    /** A common root β with each base = β^m (m rational), taken from the first base's vector; null if unrelated. */
    class CommonBase(val base: Surd, val exps: List<Rational>)

    fun commonBase(bases: List<Surd>): CommonBase? {
        val vecs = bases.map { vecOf(it) ?: return null }
        if (vecs.any { it.isEmpty() }) return null
        val v1 = vecs[0]
        // primitive vector: integer entries with gcd 1 and a positive first entry
        var num = BigInteger.ZERO
        var den = BigInteger.ONE
        for (r in v1.values) { num = num.gcd(r.num); den = den.multiply(r.den).divide(den.gcd(r.den)) }
        val g = Rational.of(num, den)
        val firstEntry = v1.entries.sortedBy { it.key }.first().value
        val sign = if (firstEntry.signum < 0) Rational.of(-1) else Rational.ONE
        val prim = v1.mapValues { it.value / (g * sign) }
        val beta = fromVec(prim) ?: return null
        val m1 = g * sign
        val exps = vecs.map { (ratioOf(v1, it) ?: return null) * m1 }
        return CommonBase(beta, exps)
    }
}
