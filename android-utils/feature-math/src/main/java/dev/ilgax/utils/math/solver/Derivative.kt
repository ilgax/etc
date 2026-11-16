package dev.ilgax.utils.math.solver

import java.util.Random

/**
 * Symbolic differentiation over the parser's [Node] tree. The tree is reused rather than a new representation
 * because it already is what the parser, [NodeNum] (numeric check), [NodeExact] (exact evaluation at a point)
 * and the printers speak, and it keeps the rule applications visible: the result is *not* simplified here,
 * it is the textbook rule output (`2x\sin x+x^2\cos x`, `\cos(3x^2)\cdot 6x`), which is shown as an
 * intermediate step; [Alg] then turns it into the canonical form.
 */
internal class Differentiator(private val v: Char) {
    /** Names of the rules used, in order of first use. */
    val rules = LinkedHashSet<String>()
    /** Arguments u of every |u| that was differentiated (not differentiable where u = 0). */
    val absArgs = ArrayList<Node>()
    var degreesUsed = false

    private fun varies(n: Node) = v in n.variableNames()
    private fun isV(n: Node) = n is Node.Variable && n.name == v

    fun d(n: Node): Node {
        if (!varies(n)) return NB.ZERO
        return when (n) {
            is Node.Variable -> NB.ONE
            is Node.Neg -> NB.neg(d(n.arg))
            is Node.Sum -> {
                if (n.terms.count { varies(it) } > 1) rules += SUM
                NB.add(n.terms.map { d(it) })
            }
            is Node.Product -> product(n)
            is Node.Quotient -> quotient(n)
            is Node.Power -> power(n)
            is Node.Root -> {
                rules += POWER
                if (!isV(n.arg)) rules += CHAIN
                powerRule(n.arg, Node.Number(Rational.of(1, n.index.toLong())))
            }
            is Node.Func -> func(n)
            is Node.Abs -> {
                rules += ABS
                absArgs += n.arg
                NB.mulAll(listOf(Node.Quotient(n.arg, n), d(n.arg)))
            }
            is Node.Trig -> trig(n)
            is Node.Number, is Node.Const -> NB.ZERO
        }
    }

    private fun product(n: Node.Product): Node {
        val varying = n.factors.indices.filter { varies(n.factors[it]) }
        if (varying.size == 1) {
            if (n.factors.size > 1) rules += CONSTANT_MULTIPLE
            val i = varying[0]
            if (isV(n.factors[i])) rules += POWER
            return NB.mulAll(n.factors.mapIndexed { j, f -> if (j == i) d(f) else f })
        }
        rules += PRODUCT
        val terms = varying.map { i -> NB.mulAll(n.factors.mapIndexed { j, f -> if (j == i) d(f) else f }) }
        return NB.add(terms)
    }

    private fun quotient(n: Node.Quotient): Node {
        val a = n.num
        val b = n.den
        if (!varies(b)) {
            rules += CONSTANT_MULTIPLE
            return NB.quot(d(a), b)
        }
        rules += QUOTIENT
        val sq = Node.Power(b, NB.num(2))
        if (!varies(a)) return NB.neg(NB.quot(NB.mul(a, d(b)), sq))
        return NB.quot(NB.add(listOf(NB.mul(d(a), b), NB.neg(NB.mul(a, d(b))))), sq)
    }

    /** k·base^(k-1)·base' */
    private fun powerRule(base: Node, k: Node): Node = NB.mulAll(listOf(k, NB.pow(base, NB.minusOne(k)), d(base)))

    private fun power(n: Node.Power): Node {
        val bv = varies(n.base)
        val ev = varies(n.exp)
        if (!ev) {
            rules += POWER
            if (!isV(n.base)) rules += CHAIN
            return powerRule(n.base, n.exp)
        }
        if (!bv) {
            val isE = n.base is Node.Const && n.base.symbol == 'e'
            rules += if (isE) EXP_E else EXP_A
            if (!isV(n.exp)) rules += CHAIN
            val de = d(n.exp)
            return if (isE) NB.mul(n, de) else NB.mulAll(listOf(n, Node.Func("ln", null, n.base), de))
        }
        rules += LOG_DIFF
        val inner = NB.add(listOf(NB.mul(d(n.exp), Node.Func("ln", null, n.base)), NB.quot(NB.mul(n.exp, d(n.base)), n.base)))
        return NB.mul(n, inner)
    }

    private fun func(n: Node.Func): Node {
        val u = n.arg
        if (!isV(u)) rules += CHAIN
        if (n.name == "ln") {
            rules += LN
            return NB.quot(d(u), u)
        }
        val b = n.base!!
        if (varies(b)) {
            rules += LOG_BASE
            return d(Node.Quotient(Node.Func("ln", null, u), Node.Func("ln", null, b)))
        }
        rules += LOGB
        return NB.quot(d(u), NB.mul(u, Node.Func("ln", null, b)))
    }

    private fun trig(n: Node.Trig): Node {
        val u = n.arg
        val inverse = n.name.startsWith("arc")
        fun t(name: String) = Node.Trig(name, u, n.deg)
        val outer: Node = when (n.name) {
            "sin" -> t("cos")
            "cos" -> NB.neg(t("sin"))
            "tan" -> Node.Power(t("sec"), NB.num(2))
            "cot" -> NB.neg(Node.Power(t("csc"), NB.num(2)))
            "sec" -> NB.mul(t("sec"), t("tan"))
            "csc" -> NB.neg(NB.mul(t("csc"), t("cot")))
            "arcsin" -> NB.quot(NB.ONE, Node.Root(2, Node.Sum(listOf(NB.ONE, NB.neg(Node.Power(u, NB.num(2)))))))
            "arccos" -> NB.neg(NB.quot(NB.ONE, Node.Root(2, Node.Sum(listOf(NB.ONE, NB.neg(Node.Power(u, NB.num(2))))))))
            else -> NB.quot(NB.ONE, Node.Sum(listOf(NB.ONE, Node.Power(u, NB.num(2)))))
        }
        rules += "Derivative of ${n.name}"
        if (!isV(u)) rules += CHAIN
        val conv: Node? = if (n.deg) {
            degreesUsed = true
            if (inverse) Node.Quotient(NB.num(180), Node.Const('π')) else Node.Quotient(Node.Const('π'), NB.num(180))
        } else null
        return NB.mulAll(listOfNotNull(conv, outer, d(u)))
    }

    companion object {
        const val SUM = "Sum rule"
        const val POWER = "Power rule"
        const val CHAIN = "Chain rule"
        const val CONSTANT_MULTIPLE = "Constant multiple rule"
        const val PRODUCT = "Product rule: (uv)' = u'v + uv'"
        const val QUOTIENT = "Quotient rule: (u/v)' = (u'v − uv')/v²"
        const val EXP_E = "Exponential rule: (e^u)' = e^u · u'"
        const val EXP_A = "Exponential rule: (a^u)' = a^u · ln a · u'"
        const val LN = "Logarithm rule: (ln u)' = u'/u"
        const val LOGB = "Logarithm rule: (log_b u)' = u'/(u ln b)"
        const val LOG_BASE = "Change of base"
        const val LOG_DIFF = "Logarithmic differentiation: (u^w)' = u^w (w' ln u + w u'/u)"
        const val ABS = "Absolute value: (|u|)' = u/|u| · u'"
    }
}

/**
 * Cross-check of a derivative against a central finite difference at random points of the domain. A mismatch
 * means a bug; the solver then reports "unsupported" instead of showing a wrong answer.
 */
internal object DerivCheck {
    class Result(val checked: Int, val mismatch: String?)

    private val ranges = listOf(-3.0 to 3.0, 0.05 to 4.0, -1.5 to 1.5, 1.0 to 12.0, -12.0 to -1.0, 0.0 to 30.0, -10.0 to 10.0, 0.01 to 1.0)

    fun run(f: Node, g: Node, v: Char, seed: Long = 12345L, want: Int = 5, tries: Int = 400): Result {
        val rnd = Random(seed xor f.hashCode().toLong())
        val others = (f.variableNames() + g.variableNames()) - v
        var ok = 0
        var attempt = 0
        while (ok < want && attempt < tries) {
            attempt++
            val (lo, hi) = ranges[attempt % ranges.size]
            val x = lo + (hi - lo) * rnd.nextDouble()
            val env = HashMap<Char, Double>()
            for (c in others) env[c] = 0.6 + 1.8 * rnd.nextDouble()
            val h = 1e-3
            try {
                fun at(t: Double): Double { env[v] = t; return NodeNum.eval(f, env) }
                // five-point central difference (error ~h⁴), and again with a tenth of the step to spot kinks, poles and aliasing
                fun diff(step: Double) = (-at(x + 2 * step) + 8 * at(x + step) - 8 * at(x - step) + at(x - 2 * step)) / (12 * step)
                val f0 = at(x)
                val fd = diff(h)
                val fd2 = diff(h / 10)
                env[v] = x
                val sym = NodeNum.eval(g, env)
                if (listOf(f0, fd, fd2, sym).any { it.isNaN() || it.isInfinite() } || Math.abs(f0) > 1e7 || Math.abs(sym) > 1e5 || Math.abs(fd) > 1e5) continue
                // a kink or a pole next to the point: the difference quotient itself is not stable there
                if (Math.abs(fd - fd2) > 2e-6 * (1 + Math.abs(fd2))) continue
                // the function turns many times inside the step (arcsin(sin x^5) far out): a difference quotient says nothing there
                if (h * Math.abs(sym) > 0.5 * (1 + Math.abs(f0))) continue
                val tol = 1e-5 * (1 + Math.max(Math.abs(fd2), Math.abs(sym)))
                if (Math.abs(fd2 - sym) > tol) return Result(ok, "at $v = ${Printer.num(x, 4)} the difference quotient is ${Printer.num(fd2, 6)} but the formula gives ${Printer.num(sym, 6)}")
                ok++
            } catch (_: Undefined) {
            } catch (_: ArithmeticException) {
            }
        }
        return Result(ok, null)
    }
}
