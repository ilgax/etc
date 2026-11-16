package dev.ilgax.utils.math.solver

/**
 * An expression without an unknown that still contains logarithms that are not exact numbers (log_2 5 + log_2 3).
 * Same-base logs are merged with the power, product and quotient rules; whatever stays is printed symbolically
 * next to a decimal.
 */
internal class LogExpression(private val s: SolveSession, private val ev: Evaluator) {
    private fun baseTex(b: Surd) = if (b == Surd.E_SURD) "e" else Printer.surd(b)
    private fun logTex(b: Surd, arg: String): String {
        val a = if (arg.all { it.isDigit() }) " $arg" else "($arg)"
        return if (b == Surd.E_SURD) "\\ln$a" else "\\log_{${baseTex(b)}}$a"
    }

    fun simplify(raw: RatFn): SolveResult {
        if (!raw.den.isConstant) throw UnsupportedMath("dividing by a logarithm")
        val p = raw.num.divBy(raw.den.constant)
        if (p.variables.userVars.isNotEmpty()) throw UnsupportedMath("an expression that mixes the unknown with roots, logarithms or powers")
        var constant = p.constant
        val byBase = LinkedHashMap<Surd, MutableList<Pair<Atom.Log, Surd>>>()
        for ((m, c) in p.terms) {
            if (m.isConstant) continue
            val only = m.powers.entries.singleOrNull()
            val atom = only?.takeIf { it.value == 1 }?.let { s.atoms[it.key] } as? Atom.Log
                ?: throw UnsupportedMath("products or powers of logarithms")
            byBase.getOrPut(atom.base) { ArrayList() } += atom to c
        }
        val parts = ArrayList<Pair<String, Double>>()
        for ((b, items) in byBase) {
            val args = items.map { ev.constantOf(it.first.arg)!! }
            if (items.size == 1) {
                val (atom, c) = items[0]
                parts += termText(c, logTex(b, Printer.surd(args[0]))) to c.toDouble() * Math.log(args[0].toDouble()) / Math.log(b.toDouble())
                continue
            }
            if (items.any { !it.second.isRational || !it.second.rational.isInteger }) throw UnsupportedMath("combining logarithms with fractional coefficients")
            val cs = items.map { it.second.rational.num.toInt() }
            var total = Surd.ONE
            for ((i, a) in args.withIndex()) total *= a.pow(cs[i])
            val line = items.indices.joinToString("") { i ->
                (if (cs[i] < 0) "-" else if (i == 0) "" else "+") + logTex(b, Printer.surd(args[i])).let { t -> if (Math.abs(cs[i]) == 1) t else "${Math.abs(cs[i])}$t" }
            }
            s.step(line, "Original logarithms with base ${plainOf(baseTex(b))}")
            if (cs.any { Math.abs(it) != 1 }) {
                s.step(items.indices.joinToString("") { i ->
                    (if (cs[i] < 0) "-" else if (i == 0) "" else "+") + logTex(b, "${Printer.surd(args[i])}^{${Math.abs(cs[i])}}")
                }, "Power rule: n·log a = log aⁿ")
            }
            val numer = items.indices.filter { cs[it] > 0 }.joinToString("\\cdot ") { i -> if (cs[i] == 1) Printer.surd(args[i]) else "${Printer.surd(args[i])}^{${cs[i]}}" }
            val denom = items.indices.filter { cs[it] < 0 }.joinToString("\\cdot ") { i -> if (cs[i] == -1) Printer.surd(args[i]) else "${Printer.surd(args[i])}^{${-cs[i]}}" }
            val arg = if (denom.isEmpty()) numer else "\\frac{${numer.ifEmpty { "1" }}}{$denom}"
            s.step(logTex(b, arg), if (denom.isEmpty()) "Product rule: log a + log b = log (ab)" else "Quotient rule: log a − log b = log (a/b)")
            val exactV = NumberTheory.exactLog(b, total)
            if (exactV != null) {
                s.step(Printer.rational(exactV), "Evaluate: ${plainOf(Printer.surd(b))}^${plainOf(Printer.rational(exactV))} = ${plainOf(Printer.surd(total))}")
                constant += Surd.of(exactV)
            } else {
                s.step(logTex(b, Printer.surd(total)), "Simplify the argument")
                parts += logTex(b, Printer.surd(total)) to Math.log(total.toDouble()) / Math.log(b.toDouble())
            }
        }
        val sb = StringBuilder()
        if (!constant.isZero) sb.append(Printer.surd(constant))
        for ((t, _) in parts) {
            if (sb.isNotEmpty() && !t.startsWith("-")) sb.append("+")
            sb.append(t)
        }
        if (parts.isEmpty()) {
            return SolveResult.Solved(listOf(s.valueAnswer(null, Val.of(constant))), s.steps, s.notes)
        }
        val d = constant.toDouble() + parts.sumOf { it.second }
        val tex = sb.toString() + "\\approx" + Printer.num(d, 3)
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }

    private fun termText(c: Surd, log: String): String = when {
        c == Surd.ONE -> log
        c == -Surd.ONE -> "-$log"
        c.isSingleTerm -> Printer.surd(c) + log
        else -> "(${Printer.surd(c)})$log"
    }
}
