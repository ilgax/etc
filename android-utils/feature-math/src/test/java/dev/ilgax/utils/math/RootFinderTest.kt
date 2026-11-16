package dev.ilgax.utils.math

import dev.ilgax.utils.math.solver.NumberTheory
import dev.ilgax.utils.math.solver.QPoly
import dev.ilgax.utils.math.solver.Rational
import dev.ilgax.utils.math.solver.RealRoots
import dev.ilgax.utils.math.solver.Surd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class RootFinderTest {
    private fun q(vararg c: Long) = QPoly(c.map { Rational.of(it) })

    @Test fun sturmFindsEveryRealRoot() {
        val roots = RealRoots.realRoots(q(-1, -1, 0, 1)) // x^3 - x - 1
        assertEquals(1, roots.size)
        assertEquals(1.324717957, roots[0], 1e-9)
        val three = RealRoots.realRoots(q(1, -3, 0, 1)) // x^3 - 3x + 1
        assertEquals(listOf(-1.8793852, 0.3472964, 1.5320889), three.map { Math.round(it * 1e7) / 1e7 })
    }
    @Test fun closeRootsAreSeparated() {
        // (x - 1)(x - 1.000001) has two real roots 1e-6 apart
        val p = QPoly(listOf(Rational.of(1_000_001, 1_000_000), Rational.of(-2_000_001, 1_000_000), Rational.ONE))
        assertEquals(2, RealRoots.realRoots(p).size)
    }
    @Test fun noRealRoots() = assertTrue(RealRoots.realRoots(q(1, 0, 1)).isEmpty())
    @Test fun squareFreeDecompositionReportsMultiplicity() {
        val f = q(-1, 1).times(q(-1, 1)).times(q(2, 1)) // (x-1)^2 (x+2)
        val parts = RealRoots.squareFree(f).map { it.first.deg to it.second }.sortedBy { it.second }
        assertEquals(listOf(1 to 1, 1 to 2), parts)
        assertEquals(listOf(-2.0 to 1, 1.0 to 2), RealRoots.withMultiplicity(f).map { Math.round(it.first * 1e9) / 1e9 to it.second })
    }
    @Test fun primeVectorsGiveExactLogs() {
        fun s(n: Long) = Surd.of(n)
        assertEquals(Rational.of(3), NumberTheory.exactLog(s(2), s(8)))
        assertEquals(Rational.of(1, 2), NumberTheory.exactLog(s(4), s(2)))
        assertEquals(Rational.of(3, 2), NumberTheory.exactLog(s(9), s(27)))
        assertEquals(Rational.of(3), NumberTheory.exactLog(s(10), s(1000)))
        assertNull(NumberTheory.exactLog(s(2), s(5)))
        assertEquals(Rational.ONE, NumberTheory.exactLog(Surd.E_SURD, Surd.E_SURD))
        assertEquals(Rational.of(2), NumberTheory.exactLog(Surd.E_SURD, Surd.E_SURD * Surd.E_SURD))
        assertEquals(Rational.of(-1), NumberTheory.exactLog(s(2), Surd.of(Rational.of(1, 2))))
    }
    @Test fun commonBase() {
        val c = NumberTheory.commonBase(listOf(Surd.of(9), Surd.of(27)))!!
        assertEquals(Surd.of(3), c.base)
        assertEquals(listOf(Rational.of(2), Rational.of(3)), c.exps)
        assertNull(NumberTheory.commonBase(listOf(Surd.of(2), Surd.of(3))))
    }
    @Test fun factorizationIsBounded() {
        assertEquals(mapOf(BigInteger.TWO to 3, BigInteger.valueOf(3) to 1), NumberTheory.factorize(BigInteger.valueOf(24)))
        assertEquals(6, NumberTheory.divisors(BigInteger.valueOf(12))!!.size)
        assertNull(NumberTheory.divisors(BigInteger.valueOf(2).pow(40).multiply(BigInteger.valueOf(3).pow(25)).multiply(BigInteger.valueOf(5).pow(20)), 100))
    }
    @Test fun surdsCarryPiAndEExactly() {
        val pi = Surd.PI_SURD
        assertEquals(Surd.of(Rational.of(1, 2)) * pi, pi / Surd.of(2))
        assertEquals(pi * Surd.of(2), (pi * pi * Surd.of(4)).sqrt())
        assertEquals(3.14159265358979, pi.toDouble(), 1e-12)
        assertEquals(Math.E * Math.E, (Surd.E_SURD * Surd.E_SURD).toDouble(), 1e-12)
        assertEquals(Math.sqrt(Math.PI), pi.sqrt().toDouble(), 1e-12)
        assertTrue((pi - Surd.of(3)).signum() > 0)
    }
}
