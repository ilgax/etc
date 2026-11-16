package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** The expression has no value at this point (division by zero, root or log outside its domain). */
internal class Undefined(val why: String) : Exception(why, null, false, false)

/** The value exists but is not a [Surd] (an irrational log, a fractional power ...). */
internal class NotExact : Exception(null, null, false, false)

internal fun Node.hasVariable(): Boolean = when (this) {
    is Node.Variable -> true
    is Node.Number, is Node.Const -> false
    is Node.Neg -> arg.hasVariable()
    is Node.Sum -> terms.any { it.hasVariable() }
    is Node.Product -> factors.any { it.hasVariable() }
    is Node.Quotient -> num.hasVariable() || den.hasVariable()
    is Node.Power -> base.hasVariable() || exp.hasVariable()
    is Node.Root -> arg.hasVariable()
    is Node.Func -> arg.hasVariable() || base?.hasVariable() == true
}

internal fun Node.variableNames(): Set<Char> = when (this) {
    is Node.Variable -> setOf(name)
    is Node.Number, is Node.Const -> emptySet()
    is Node.Neg -> arg.variableNames()
    is Node.Sum -> terms.flatMapTo(sortedSetOf()) { it.variableNames() }
    is Node.Product -> factors.flatMapTo(sortedSetOf()) { it.variableNames() }
    is Node.Quotient -> num.variableNames() + den.variableNames()
    is Node.Power -> base.variableNames() + exp.variableNames()
    is Node.Root -> arg.variableNames()
    is Node.Func -> arg.variableNames() + (base?.variableNames() ?: emptySet())
}

internal fun Node.hasFunc(): Boolean = when (this) {
    is Node.Func -> true
    is Node.Variable, is Node.Number, is Node.Const -> false
    is Node.Neg -> arg.hasFunc()
    is Node.Sum -> terms.any { it.hasFunc() }
    is Node.Product -> factors.any { it.hasFunc() }
    is Node.Quotient -> num.hasFunc() || den.hasFunc()
    is Node.Power -> base.hasFunc() || exp.hasFunc()
    is Node.Root -> arg.hasFunc()
}

internal fun Node.mapFuncs(f: (Node.Func) -> Node): Node = when (this) {
    is Node.Func -> f(this)
    is Node.Variable, is Node.Number, is Node.Const -> this
    is Node.Neg -> Node.Neg(arg.mapFuncs(f))
    is Node.Sum -> Node.Sum(terms.map { it.mapFuncs(f) })
    is Node.Product -> Node.Product(factors.map { it.mapFuncs(f) })
    is Node.Quotient -> Node.Quotient(num.mapFuncs(f), den.mapFuncs(f))
    is Node.Power -> Node.Power(base.mapFuncs(f), exp.mapFuncs(f))
    is Node.Root -> Node.Root(index, arg.mapFuncs(f))
}

/** Node → LaTeX, for atoms and for echoing the original equation in steps. */
internal object NodeTex {
    fun tex(n: Node): String = when (n) {
        is Node.Number -> Printer.rational(n.value)
        is Node.Variable -> n.name.toString()
        is Node.Const -> if (n.symbol == 'π') "\\pi" else "e"
        is Node.Neg -> "-" + (if (n.arg is Node.Sum) "(${tex(n.arg)})" else tex(n.arg))
        is Node.Sum -> n.terms.withIndex().joinToString("") { (i, t) ->
            when {
                t is Node.Neg && t.arg !is Node.Sum -> "-" + tex(t.arg)
                t is Node.Neg -> "-(" + tex(t.arg) + ")"
                i == 0 -> tex(t)
                else -> "+" + tex(t)
            }
        }
        is Node.Product -> {
            val sb = StringBuilder()
            for ((i, f) in n.factors.withIndex()) {
                val text = if (f is Node.Sum || f is Node.Neg) "(${tex(f)})" else tex(f)
                if (i > 0) {
                    val prev = n.factors[i - 1]
                    val needsDot = (prev is Node.Number && (f is Node.Number || text[0].isDigit())) || prev is Node.Quotient || f is Node.Quotient
                    if (needsDot) sb.append("\\cdot ")
                }
                sb.append(text)
            }
            sb.toString()
        }
        is Node.Quotient -> "\\frac{${tex(n.num)}}{${tex(n.den)}}"
        is Node.Power -> {
            val b = n.base
            val bt = if (b is Node.Number || b is Node.Variable || b is Node.Const) tex(b) else "(${tex(b)})"
            "$bt^{${tex(n.exp)}}"
        }
        is Node.Root -> if (n.index == 2) "\\sqrt{${tex(n.arg)}}" else "\\sqrt[${n.index}]{${tex(n.arg)}}"
        is Node.Func -> {
            val a = n.arg
            val arg = if (a is Node.Variable || a is Node.Number || a is Node.Const) " " + tex(a) else "(${tex(a)})"
            if (n.name == "ln") "\\ln$arg" else {
                val b = tex(n.base!!)
                "\\log_" + (if (b.length > 1) "{$b}" else b) + arg
            }
        }
    }
}

/** Double-precision evaluation of a [Node] with the unknowns bound; throws [Undefined] outside the domain. */
internal object NodeNum {
    private fun fmt(d: Double) = Printer.num(d)
    private fun plain(n: Node) = plainOf(NodeTex.tex(n))

    fun eval(n: Node, env: Map<Char, Double>): Double = when (n) {
        is Node.Number -> n.value.toDouble()
        is Node.Variable -> env[n.name] ?: throw Undefined("${n.name} has no value")
        is Node.Const -> if (n.symbol == 'π') Math.PI else Math.E
        is Node.Neg -> -eval(n.arg, env)
        is Node.Sum -> n.terms.sumOf { eval(it, env) }
        is Node.Product -> n.factors.fold(1.0) { a, f -> a * eval(f, env) }
        is Node.Quotient -> {
            val d = eval(n.den, env)
            if (Math.abs(d) < 1e-300) throw Undefined("a denominator is 0")
            eval(n.num, env) / d
        }
        is Node.Power -> {
            val b = eval(n.base, env)
            val e = eval(n.exp, env)
            if (b < 0 && Math.abs(e - Math.rint(e)) > 1e-12) throw Undefined("a negative base ${fmt(b)} with exponent ${fmt(e)}")
            if (b == 0.0 && e < 0) throw Undefined("0 to a negative power")
            Math.pow(b, e).also { if (it.isNaN() || it.isInfinite()) throw Undefined("the power is too large") }
        }
        is Node.Root -> {
            val a = eval(n.arg, env)
            if (n.index % 2 == 1) Math.signum(a) * Math.pow(Math.abs(a), 1.0 / n.index)
            else if (a < 0) throw Undefined("√(${plain(n.arg)}) needs ${plain(n.arg)} ≥ 0, but it is ${fmt(a)}")
            else Math.pow(a, 1.0 / n.index)
        }
        is Node.Func -> {
            val a = eval(n.arg, env)
            if (a <= 0) throw Undefined("log(${plain(n.arg)}) needs ${plain(n.arg)} > 0, but it is ${fmt(a)}")
            if (n.name == "ln") Math.log(a) else {
                val b = eval(n.base!!, env)
                Math.log(a) / Math.log(b)
            }
        }
    }
}

/** Exact evaluation: [Undefined] outside the domain, [NotExact] when the result is not a [Surd]. */
internal object NodeExact {
    private fun plain(n: Node) = plainOf(NodeTex.tex(n))
    private fun shown(s: Surd) = plainOf(Printer.surd(s))

    fun eval(n: Node, env: Map<Char, Surd>): Surd = when (n) {
        is Node.Number -> Surd.of(n.value)
        is Node.Variable -> env[n.name] ?: throw NotExact()
        is Node.Const -> if (n.symbol == 'π') Surd.PI_SURD else Surd.E_SURD
        is Node.Neg -> -eval(n.arg, env)
        is Node.Sum -> n.terms.map { eval(it, env) }.reduce { a, b -> a + b }
        is Node.Product -> n.factors.map { eval(it, env) }.reduce { a, b -> a * b }
        is Node.Quotient -> {
            val d = eval(n.den, env)
            if (d.isZero) throw Undefined("a denominator is 0")
            if (!d.isSingleTerm) throw NotExact()
            eval(n.num, env) / d
        }
        is Node.Power -> power(eval(n.base, env), eval(n.exp, env))
        is Node.Root -> root(n, eval(n.arg, env))
        is Node.Func -> {
            val a = eval(n.arg, env)
            if (a.signum() <= 0) throw Undefined("log(${plain(n.arg)}) needs ${plain(n.arg)} > 0, but it is ${shown(a)}")
            val base = if (n.name == "ln") Surd.E_SURD else eval(n.base!!, env)
            Surd.of(NumberTheory.exactLog(base, a) ?: throw NotExact())
        }
    }

    private fun power(b: Surd, e: Surd): Surd {
        if (!e.isRational) throw NotExact()
        val r = e.rational
        if (b.isZero) {
            if (r.signum < 0) throw Undefined("0 to a negative power")
            return if (r.isZero) Surd.ONE else Surd.ZERO
        }
        if (r.isInteger) {
            if (r.num.abs() > BigInteger.valueOf(200)) throw NotExact()
            return b.pow(r.num.toInt())
        }
        if (b.signum() < 0) throw Undefined("a negative base ${shown(b)} with exponent ${r}")
        return NumberTheory.exactPow(b, r) ?: throw NotExact()
    }

    private fun root(n: Node.Root, a: Surd): Surd {
        val neg = a.signum() < 0
        if (neg && n.index % 2 == 0) throw Undefined("√(${plain(n.arg)}) needs ${plain(n.arg)} ≥ 0, but it is ${shown(a)}")
        val m = if (neg) -a else a
        val r = if (n.index == 2) runCatching { m.sqrt() }.getOrNull() ?: throw NotExact()
        else if (m.isRational) Surd.of(NumberTheory.exactRoot(m.rational, n.index) ?: throw NotExact())
        else throw NotExact()
        return if (neg) -r else r
    }
}
