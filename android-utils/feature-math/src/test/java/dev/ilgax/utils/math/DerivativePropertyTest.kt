package dev.ilgax.utils.math

import dev.ilgax.utils.math.solver.Alg
import dev.ilgax.utils.math.solver.DTex
import dev.ilgax.utils.math.solver.DerivCheck
import dev.ilgax.utils.math.solver.Differentiator
import dev.ilgax.utils.math.solver.LatexParser
import dev.ilgax.utils.math.solver.Node
import dev.ilgax.utils.math.solver.NodeNum
import dev.ilgax.utils.math.solver.AngleMode
import dev.ilgax.utils.math.solver.Rational
import dev.ilgax.utils.math.solver.any
import dev.ilgax.utils.math.solver.Undefined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * ~200 random expression trees built from the supported blocks (powers, roots, e^u, a^u, ln, log_b, the six trigonometric and
 * three inverse functions, |u|, sums, products, quotients) are differentiated, simplified and printed; the result (and the raw
 * rule output, and the printed text parsed back) is compared with a central finite difference at random points of the domain.
 */
class DerivativePropertyTest {
    private val x = Node.Variable('x')
    private fun n(k: Long) = Node.Number(Rational.of(k))
    private fun q(a: Long, b: Long) = Node.Number(Rational.of(a, b))

    private fun leaf(r: Random): Node = when (r.nextInt(5)) {
        0 -> n((r.nextInt(7) - 3).toLong().let { if (it == 0L) 2 else it })
        1 -> q((r.nextInt(5) + 1).toLong(), (r.nextInt(3) + 2).toLong())
        else -> x
    }

    /** u²+1: positive, so logs and roots of it are always defined. */
    private fun positive(u: Node): Node = Node.Sum(listOf(Node.Power(u, n(2)), n(1)))

    private fun gen(r: Random, depth: Int): Node {
        if (depth == 0) return leaf(r)
        val d = depth - 1
        return when (r.nextInt(20)) {
            0, 1 -> Node.Sum(listOf(gen(r, d), gen(r, d)))
            2 -> Node.Sum(listOf(gen(r, d), Node.Neg(gen(r, d))))
            3, 4 -> Node.Product(listOf(gen(r, d), gen(r, d)))
            5 -> Node.Quotient(gen(r, d), positive(gen(r, d)))
            6 -> Node.Quotient(gen(r, d), Node.Sum(listOf(x, n((r.nextInt(5) + 1).toLong()))))
            7 -> Node.Power(gen(r, d), n((r.nextInt(4) + 2).toLong()))
            8 -> Node.Power(gen(r, d), n(-(r.nextInt(2) + 1).toLong())).let { if (r.nextBoolean()) it else Node.Power(positive(gen(r, d)), q(1, 2)) }
            9 -> Node.Root(if (r.nextBoolean()) 2 else 3, positive(gen(r, d)))
            10 -> Node.Power(if (r.nextBoolean()) Node.Const('e') else n((r.nextInt(3) + 2).toLong()), gen(r, d))
            11 -> Node.Func("ln", null, positive(gen(r, d)))
            12 -> Node.Func("log", n((r.nextInt(3) + 2).toLong()), positive(gen(r, d)))
            13, 14 -> Node.Trig(listOf("sin", "cos", "tan", "cot", "sec", "csc")[r.nextInt(6)], gen(r, d), false)
            15 -> Node.Trig(listOf("arcsin", "arccos")[r.nextInt(2)], Node.Trig("sin", gen(r, d), false), false)
            16 -> Node.Trig("arctan", gen(r, d), false)
            17 -> Node.Abs(gen(r, d))
            18 -> Node.Neg(gen(r, d))
            else -> Node.Power(Node.Trig(listOf("sin", "cos", "tan")[r.nextInt(3)], gen(r, d), false), n((r.nextInt(2) + 2).toLong()))
        }
    }

    private fun valueCheck(a: Node, b: Node, r: Random): Boolean {
        var ok = 0
        var tries = 0
        while (ok < 3 && tries < 200) {
            tries++
            val xv = (r.nextDouble() - 0.4) * 6
            try {
                val va = NodeNum.eval(a, mapOf('x' to xv))
                val vb = NodeNum.eval(b, mapOf('x' to xv))
                if (va.isNaN() || vb.isNaN() || Math.abs(va) > 1e6) continue
                if (Math.abs(va - vb) > 1e-7 * (1 + Math.abs(va))) return false
                ok++
            } catch (_: Undefined) {
            }
        }
        return true
    }

    @Test fun twoHundredRandomTrees() {
        val want = System.getenv("DERIV_TREES")?.toIntOrNull() ?: 200
        val r = Random(System.getenv("DERIV_SEED")?.toLongOrNull() ?: 20260416)
        var checked = 0
        var skipped = 0
        val failures = ArrayList<String>()
        var i = 0
        while (checked < want && i < want * 10) {
            i++
            val f = gen(r, 1 + r.nextInt(3))
            val dz = Differentiator('x')
            val raw = dz.d(f)
            val rawRes = DerivCheck.run(f, raw, 'x', seed = i.toLong())
            if (rawRes.checked < 2 && rawRes.mismatch == null) { skipped++; continue }
            checked++
            if (rawRes.mismatch != null) { failures += "RAW ${DTex.tex(f)} -> ${DTex.tex(raw)}: ${rawRes.mismatch}"; continue }
            val simp = try { Alg('x').simplify(raw) } catch (e: Exception) { failures += "SIMPLIFY threw ${e} on ${DTex.tex(f)} -> ${DTex.tex(raw)}"; continue }
            val res = DerivCheck.run(f, simp, 'x', seed = i.toLong())
            if (res.mismatch != null) { failures += "SIMP ${DTex.tex(f)} -> ${DTex.tex(simp)} (raw ${DTex.tex(raw)}): ${res.mismatch}"; continue }
            val shown = DTex.tex(simp)
            // nested |…|…| is ambiguous for the parser (bars are read by context), not a maths problem
            val nestedAbs = simp.any { it is Node.Abs && it.arg.any { a -> a is Node.Abs } }
            val parsed = try { LatexParser.parse(shown, null, AngleMode.Radians)[0].parts[0] } catch (e: Exception) { if (!nestedAbs) failures += "PARSE $shown: $e"; continue }
            val res2 = DerivCheck.run(f, parsed, 'x', seed = i.toLong())
            if (res2.mismatch != null) failures += "REPARSE ${DTex.tex(f)} -> $shown: ${res2.mismatch}"
            // the simplifier alone must also preserve the value of the function
            val fs = try { Alg('x').simplify(f) } catch (e: Exception) { null }
            if (fs != null && !valueCheck(f, fs, r)) failures += "VALUE ${DTex.tex(f)} != ${DTex.tex(fs)}"
        }
        println("checked=$checked skipped=$skipped failures=${failures.size}")
        failures.take(40).forEach { println(it) }
        assertTrue("trees checked: $checked", checked >= want)
        assertEquals("mismatches:\n" + failures.take(15).joinToString("\n"), 0, failures.size)
    }
}
