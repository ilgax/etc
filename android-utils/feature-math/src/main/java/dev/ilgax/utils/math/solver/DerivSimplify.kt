package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** One factor base^exp of a [Term]; [base] is a canonical node (variable, sum, trig call, log, |.|, a^u ...), never a number. */
internal data class Fac(val base: Node, val exp: Rational)

/** coef · Π facs; the facs have distinct bases, non-zero exponents and are in print order. */
internal class Term(val coef: Surd, val facs: List<Fac>)

/** A sum of [Term]s in canonical order (like terms collected, zero terms gone). */
internal class Ex(val terms: List<Term>) {
    val isZero get() = terms.isEmpty()
}

/** Builders that keep the *unsimplified* derivative readable: drop 0 and 1, fold numbers, never reorder otherwise. */
internal object NB {
    val ZERO = Node.Number(Rational.ZERO)
    val ONE = Node.Number(Rational.ONE)

    fun num(n: Long) = Node.Number(Rational.of(n))
    fun isZero(n: Node) = n is Node.Number && n.value.isZero
    fun isOne(n: Node) = n is Node.Number && n.value == Rational.ONE

    fun neg(n: Node): Node = when (n) {
        is Node.Number -> Node.Number(-n.value)
        is Node.Neg -> n.arg
        else -> Node.Neg(n)
    }

    fun add(list: List<Node>): Node {
        val l = list.filter { !isZero(it) }
        return when (l.size) { 0 -> ZERO; 1 -> l[0]; else -> Node.Sum(l) }
    }

    fun mul(a: Node, b: Node): Node {
        if (isZero(a) || isZero(b)) return ZERO
        if (a is Node.Neg) return neg(mul(a.arg, b))
        if (b is Node.Neg) return neg(mul(a, b.arg))
        if (a is Node.Number && a.value.signum < 0) return neg(mul(Node.Number(-a.value), b))
        if (b is Node.Number && b.value.signum < 0) return neg(mul(a, Node.Number(-b.value)))
        if (isOne(a)) return b
        if (isOne(b)) return a
        if (a is Node.Number && b is Node.Number) return Node.Number(a.value * b.value)
        if (a is Node.Number && b is Node.Product && b.factors.first() is Node.Number) {
            return Node.Product(listOf(Node.Number(a.value * (b.factors.first() as Node.Number).value)) + b.factors.drop(1))
        }
        if (b is Node.Number) return mul(b, a)
        return Node.Product(listOf(a, b))
    }

    fun mulAll(list: List<Node>): Node = list.fold(ONE as Node) { acc, f -> mul(acc, f) }

    fun quot(a: Node, b: Node): Node = when {
        isZero(a) -> ZERO
        isOne(b) -> a
        a is Node.Neg -> neg(quot(a.arg, b))
        else -> Node.Quotient(a, b)
    }

    fun pow(b: Node, e: Node): Node = when {
        e is Node.Number && e.value == Rational.ONE -> b
        e is Node.Number && e.value.isZero -> ONE
        else -> Node.Power(b, e)
    }

    fun minusOne(k: Node): Node = if (k is Node.Number) Node.Number(k.value - Rational.ONE) else Node.Sum(listOf(k, Node.Number(Rational.of(-1))))
}

/** LaTeX for the derivative work: readable products, fractions, roots and degree-marked trigonometry. */
internal object DTex {
    private fun simpleArg(a: Node) = a is Node.Variable || a is Node.Const || (a is Node.Number && a.value.isInteger && a.value.signum >= 0)

    fun tex(n: Node): String = when (n) {
        is Node.Number -> Printer.rational(n.value)
        is Node.Variable -> n.name.toString()
        is Node.Const -> if (n.symbol == 'π') "\\pi" else "e"
        is Node.Neg -> "-" + (if (n.arg is Node.Sum) "(${tex(n.arg)})" else tex(n.arg))
        is Node.Sum -> sum(n)
        is Node.Product -> product(n)
        is Node.Quotient -> "\\frac{${tex(n.num)}}{${tex(n.den)}}"
        is Node.Power -> power(n, false)
        is Node.Root -> if (n.index == 2) "\\sqrt{${tex(n.arg)}}" else "\\sqrt[${n.index}]{${tex(n.arg)}}"
        is Node.Abs -> "\\left|${tex(n.arg)}\\right|"
        is Node.Trig -> trig(n, null, false)
        is Node.Func -> func(n, false)
    }

    private fun sum(n: Node.Sum): String {
        val sb = StringBuilder()
        for ((i, t) in n.terms.withIndex()) {
            val neg = t is Node.Neg || (t is Node.Number && t.value.signum < 0)
            val body = when {
                t is Node.Neg -> t.arg
                t is Node.Number && t.value.signum < 0 -> Node.Number(-t.value)
                else -> t
            }
            val text = if (body is Node.Sum) "(${tex(body)})" else tex(body)
            when {
                i == 0 -> sb.append(if (neg) "-" else "").append(text)
                neg -> sb.append("-").append(text)
                text.startsWith("-") -> sb.append(text)
                else -> sb.append("+").append(text)
            }
        }
        return sb.toString()
    }

    private fun flat(n: Node.Product): List<Node> = n.factors.flatMap { if (it is Node.Product) flat(it) else listOf(it) }

    private fun factorTex(f: Node, paren: Boolean): String = when {
        f is Node.Sum || f is Node.Neg -> "(${tex(f)})"
        f is Node.Number && f.value.signum < 0 -> "(${tex(f)})"
        f is Node.Trig -> trig(f, null, paren)
        f is Node.Func -> func(f, paren)
        f is Node.Power -> power(f, paren)
        else -> tex(f)
    }

    /** The parser reads `\\sin x\\sqrt{2}` as sin(x√2): after a function, only a command that is not an argument starter may follow bare. */
    private fun readsAsArgument(next: String) =
        next[0].isLetterOrDigit() || next[0] == '(' || next.startsWith("\\frac") || next.startsWith("\\sqrt") || next.startsWith("\\pi")

    private fun product(n: Node.Product): String {
        val fs = flat(n)
        val plain = fs.map { factorTex(it, false) }
        val sb = StringBuilder()
        for ((i, f) in fs.withIndex()) {
            val needsParen = i < fs.size - 1 && (f is Node.Trig || f is Node.Func || f is Node.Power) && readsAsArgument(plain[i + 1])
            val text = if (needsParen) factorTex(f, true) else plain[i]
            if (i > 0) {
                val prev = fs[i - 1]
                val dot = text[0].isDigit() || f is Node.Number || (f is Node.Quotient && (prev is Node.Quotient || prev is Node.Number))
                if (dot) sb.append("\\cdot ")
                // \pi followed by a letter would run together as one command name
                else if (text[0].isLetter() && Regex("""\\[A-Za-z]+$""").containsMatchIn(sb)) sb.append(' ')
            }
            sb.append(text)
        }
        return sb.toString()
    }

    private fun power(n: Node.Power, paren: Boolean): String {
        val b = n.base
        val e = n.exp
        if (b is Node.Trig && !b.name.startsWith("arc") && e is Node.Number && e.value.isInteger && e.value.signum > 0) return trig(b, e, paren)
        val bt = if (b is Node.Variable || b is Node.Const || (b is Node.Number && b.value.isInteger && b.value.signum >= 0)) tex(b) else "(${tex(b)})"
        return "$bt^{${tex(e)}}"
    }

    private fun trig(n: Node.Trig, power: Node?, paren: Boolean): String {
        val a = n.arg
        val arg = if (n.deg && !n.name.startsWith("arc")) when {
            a is Node.Variable || (a is Node.Number && a.value.isInteger && a.value.signum >= 0) ->
                if (paren) "(${tex(a)}^{\\circ})" else " ${tex(a)}^{\\circ}"
            else -> "((${tex(a)})^{\\circ})"
        } else if (simpleArg(a) && !paren) " ${tex(a)}" else "(${tex(a)})"
        return "\\" + n.name + (power?.let { "^{${tex(it)}}" } ?: "") + arg
    }

    private fun func(n: Node.Func, paren: Boolean): String {
        val a = n.arg
        val arg = if (simpleArg(a) && !paren) " ${tex(a)}" else "(${tex(a)})"
        if (n.name == "ln") return "\\ln$arg"
        val b = tex(n.base!!)
        return "\\log_" + (if (b.length > 1) "{$b}" else b) + arg
    }
}

/**
 * Canonical algebra for the derivative results: every expression becomes a sum of terms `coef · Π base^exp`
 * (exact [Surd] coefficients, rational exponents), which collects like terms, merges like powers and cancels
 * what cancels. [v] is the variable of differentiation (it sorts first, so polynomials print in descending powers).
 * Rational functions with a non-trivial denominator are then combined over one denominator through [Poly]/[RatFn].
 */
internal class Alg(private val v: Char) {
    /** Factors that were cancelled in a rational function: the result is valid where they are not 0. */
    val cancelled = ArrayList<Poly>()

    private val zero = Ex(emptyList())
    private val one = const(Surd.ONE)
    private val minusOne = Rational.of(-1)
    private val oddFns = setOf("sin", "tan", "cot", "csc", "arcsin", "arctan")
    private val trigOrder = listOf("sec", "csc", "sin", "cos", "tan", "cot", "arcsin", "arccos", "arctan")

    fun const(s: Surd) = if (s.isZero) zero else Ex(listOf(Term(s, emptyList())))
    private fun atom(base: Node) = Ex(listOf(Term(Surd.ONE, listOf(Fac(base, Rational.ONE)))))

    // ------------------------------------------------------------------ Node -> Ex

    fun of(n: Node): Ex = when (n) {
        is Node.Number -> const(Surd.of(n.value))
        is Node.Const -> const(if (n.symbol == 'π') Surd.PI_SURD else Surd.E_SURD)
        is Node.Variable -> atom(n)
        is Node.Neg -> neg(of(n.arg))
        is Node.Sum -> n.terms.fold(zero) { a, t -> add(a, of(t)) }
        is Node.Product -> n.factors.fold(one) { a, f -> mul(a, of(f)) }
        is Node.Quotient -> mul(of(n.num), powNode(n.den, minusOne))
        is Node.Power -> power(n)
        is Node.Root -> powNode(n.arg, Rational.of(1, n.index.toLong()))
        is Node.Abs -> abs(of(n.arg))
        is Node.Trig -> trig(n)
        is Node.Func -> func(n)
    }

    private fun power(n: Node.Power): Ex {
        val e = of(n.exp)
        constRational(e)?.let { return powNode(n.base, it) }
        val b = of(n.base)
        val cb = constSurd(b)
        val expNode = toNode(e, true)
        if (cb != null && cb.signum() > 0 && cb != Surd.ONE) {
            val baseNode: Node = if (cb == Surd.E_SURD) Node.Const('e') else surdNode(cb)
            return buildTerm(Surd.ONE, linkedMapOf(Node.Power(baseNode, expNode) as Node to Rational.ONE)).let { Ex(listOf(it)) }
        }
        if (cb == Surd.ONE) return one
        return atom(Node.Power(toNode(b, true), expNode))
    }

    private fun mergeOk(k: Rational, r: Rational) = r.isInteger || !k.isInteger || k.num.testBit(0)

    private fun powNode(n: Node, r: Rational): Ex {
        if (r.isZero) return one
        if (r == Rational.ONE) return of(n)
        when (n) {
            is Node.Power -> constRational(of(n.exp))?.let { k -> if (mergeOk(k, r)) return powNode(n.base, k * r) }
            is Node.Product -> if (r.isInteger) return n.factors.fold(one) { a, f -> mul(a, powNode(f, r)) }
            is Node.Neg -> if (r.isInteger) {
                val p = powNode(n.arg, r)
                return if (r.num.testBit(0)) neg(p) else p
            }
            is Node.Quotient -> if (r.isInteger) return mul(powNode(n.num, r), powNode(n.den, -r))
            is Node.Root -> return powNode(n.arg, r / Rational.of(n.index.toLong()))
            else -> {}
        }
        return pow(of(n), r)
    }

    fun pow(e: Ex, r: Rational): Ex {
        if (r.isZero) return one
        if (r == Rational.ONE) return e
        if (e.isZero) {
            if (r.signum < 0) throw UnsupportedMath("division by zero")
            return zero
        }
        if (e.terms.size == 1) return Ex(listOf(powTerm(e.terms[0], r)))
        if (r.isInteger && r.signum > 0 && expandable(e, r.num.toInt())) {
            var acc = e
            repeat(r.num.toInt() - 1) { acc = mul(acc, e) }
            return acc
        }
        return powMulti(e, r)
    }

    private fun expandable(e: Ex, n: Int): Boolean {
        if (n > 8) return false
        var maxDeg = 1
        for (t in e.terms) {
            var deg = 0
            for (f in t.facs) {
                if (f.base !is Node.Variable || !f.exp.isInteger || f.exp.signum < 0) return false
                deg += f.exp.num.toInt()
            }
            maxDeg = maxOf(maxDeg, deg)
        }
        return n * maxDeg <= 8
    }

    private fun powMulti(e: Ex, r: Rational): Ex {
        val (g, scaled) = content(e)
        val gr: Surd? = if (g == Surd.ONE) Surd.ONE else powCoef(g, r)
        val use = if (gr != null) scaled else e
        val base = toNode(use, true)
        return Ex(listOf(Term(gr ?: Surd.ONE, listOf(Fac(base, r)))))
    }

    /** Positive rational content of a sum: 2x+2 = 2(x+1). */
    private fun content(e: Ex): Pair<Surd, Ex> {
        if (e.terms.any { !it.coef.isRational }) return Surd.ONE to e
        var num = BigInteger.ZERO
        var den = BigInteger.ONE
        for (t in e.terms) {
            val q = t.coef.rational
            num = num.gcd(q.num)
            den = den.multiply(q.den).divide(den.gcd(q.den))
        }
        if (num.signum() == 0) return Surd.ONE to e
        val g = Rational.of(num, den)
        if (g == Rational.ONE) return Surd.ONE to e
        val gs = Surd.of(g)
        return gs to Ex(e.terms.map { Term(it.coef / gs, it.facs) })
    }

    /** c^r as an exact surd, or null when it is not one. */
    private fun powCoef(c: Surd, r: Rational): Surd? {
        if (r.isInteger) {
            if (r.num.abs() > BigInteger.valueOf(64)) throw UnsupportedMath("a very large exponent")
            if (c.isZero && r.signum < 0) throw UnsupportedMath("division by zero")
            if (!c.isSingleTerm && r.signum < 0) return null
            return c.pow(r.num.toInt())
        }
        if (c.signum() < 0) {
            if (!r.den.testBit(0)) throw UnsupportedMath("an even root of a negative number")
            val p = NumberTheory.exactPow(-c, r) ?: return null
            return if (r.num.testBit(0)) -p else p
        }
        return NumberTheory.exactPow(c, r)
    }

    private fun powTerm(t: Term, r: Rational): Term {
        if (r.isInteger) {
            val m = LinkedHashMap<Node, Rational>()
            for (f in t.facs) {
                if (isExpAtom(f.base)) { val s = scaleExp(f.base, r * f.exp); m[s] = (m[s] ?: Rational.ZERO) + Rational.ONE }
                else m[f.base] = f.exp * r
            }
            val c = powCoef(t.coef, r)
            if (c != null) return buildTerm(c, m)
            m[surdNode(t.coef)] = r
            return buildTerm(Surd.ONE, m)
        }
        val ok = t.facs.size <= 1 && t.facs.all { isExpAtom(it.base) || mergeOk(it.exp, r) }
        if (!ok) return Term(Surd.ONE, listOf(Fac(termNode(t, false), r)))
        val m = LinkedHashMap<Node, Rational>()
        for (f in t.facs) {
            if (isExpAtom(f.base)) m[scaleExp(f.base, r * f.exp)] = Rational.ONE else m[f.base] = f.exp * r
        }
        val c = powCoef(t.coef, r)
        if (c != null) return buildTerm(c, m)
        // e^(2/3), 2^(1/3): stays a root, the coefficient becomes a base
        if (t.coef.signum() < 0) throw UnsupportedMath("a root of a negative number")
        m[surdNode(t.coef)] = r
        return buildTerm(Surd.ONE, m)
    }

    private fun scaleExp(base: Node, k: Rational): Node {
        base as Node.Power
        val u = mul(of(base.exp), const(Surd.of(k)))
        return Node.Power(base.base, toNode(u, true))
    }

    private fun trig(n: Node.Trig): Ex {
        var arg = of(n.arg)
        if (isConstantEx(arg)) {
            try { return const(NodeExact.eval(Node.Trig(n.name, toNode(arg, true), n.deg), emptyMap())) }
            catch (_: NotExact) {} catch (_: UnsupportedMath) {} catch (u: Undefined) { throw UnsupportedMath("a trigonometric value that does not exist") }
        }
        var sign = 1
        if (arg.terms.size == 1 && arg.terms[0].coef.signum() < 0 && n.name != "arccos") {
            arg = neg(arg)
            if (n.name in oddFns) sign = -1
        }
        val a = atom(Node.Trig(n.name, toNode(arg, true), n.deg))
        return if (sign < 0) neg(a) else a
    }

    private fun func(n: Node.Func): Ex {
        val arg = of(n.arg)
        val base = n.base?.let { of(it) }
        if (isConstantEx(arg) && (base == null || isConstantEx(base))) {
            try { return const(NodeExact.eval(Node.Func(n.name, base?.let { toNode(it, true) }, toNode(arg, true)), emptyMap())) }
            catch (_: NotExact) {} catch (_: UnsupportedMath) {} catch (u: Undefined) { throw UnsupportedMath("the logarithm of a number that is not positive") }
        }
        return atom(Node.Func(n.name, base?.let { toNode(it, true) }, toNode(arg, true)))
    }

    private fun abs(e: Ex): Ex {
        if (e.isZero) return zero
        if (isConstantEx(e)) {
            try { return const(NodeExact.eval(Node.Abs(toNode(e, true)), emptyMap())) } catch (_: NotExact) {} catch (_: UnsupportedMath) {} catch (_: Undefined) {}
        }
        if (e.terms.size == 1) {
            val t = e.terms[0]
            val m = LinkedHashMap<Node, Rational>()
            for (f in t.facs) {
                val b = f.base
                val positive = b is Node.Abs || b is Node.Number || isExpAtom(b) || !f.exp.isInteger || !f.exp.num.testBit(0)
                val key = if (positive) b else Node.Abs(b)
                m[key] = (m[key] ?: Rational.ZERO) + f.exp
            }
            return Ex(listOf(buildTerm(if (t.coef.signum() < 0) -t.coef else t.coef, m)))
        }
        val (g, scaled) = content(e)
        return Ex(listOf(Term(g, listOf(Fac(Node.Abs(toNode(scaled, true)), Rational.ONE)))))
    }

    // ------------------------------------------------------------------ algebra

    fun neg(e: Ex) = Ex(e.terms.map { Term(-it.coef, it.facs) })

    fun add(a: Ex, b: Ex): Ex = normalize(a.terms + b.terms)

    fun mul(a: Ex, b: Ex): Ex {
        if (a.isZero || b.isZero) return zero
        val out = ArrayList<Term>()
        for (x in a.terms) for (y in b.terms) out += mulTerm(x, y)
        return normalize(out)
    }

    private fun mulTerm(a: Term, b: Term): Term {
        val m = LinkedHashMap<Node, Rational>()
        for (f in a.facs) m[f.base] = f.exp
        for (f in b.facs) m[f.base] = (m[f.base] ?: Rational.ZERO) + f.exp
        return buildTerm(a.coef * b.coef, m)
    }

    /** Merges the exponential atoms (e^u·e^w = e^(u+w)), drops zero exponents, sorts the factors. */
    private fun buildTerm(coef: Surd, m: Map<Node, Rational>): Term {
        var c = coef
        val out = ArrayList<Fac>()
        val exps = LinkedHashMap<Node, Ex>()
        for ((b, k) in m) {
            if (k.isZero) continue
            if (isExpAtom(b)) {
                b as Node.Power
                val prev = exps[b.base] ?: zero
                exps[b.base] = add(prev, mul(of(b.exp), const(Surd.of(k))))
            } else if (b is Node.Abs && k.isInteger && !k.num.testBit(0)) {
                // |u|^2 = u^2: the bars go, and the power then merges with the other powers of u
                val i = out.indexOfFirst { it.base == b.arg }
                if (i >= 0) out[i] = Fac(b.arg, out[i].exp + k) else out += Fac(b.arg, k)
            } else out += Fac(b, k)
        }
        out.removeAll { it.exp.isZero }
        for ((base, u) in exps) {
            if (u.isZero) continue
            val node = Node.Power(base, toNode(u, true))
            if (isConstantEx(u)) {
                try { c = c * NodeExact.eval(node, emptyMap()); continue } catch (_: NotExact) {} catch (_: UnsupportedMath) {} catch (_: Undefined) {}
            }
            out += Fac(node, Rational.ONE)
        }
        if (c.isZero) return Term(Surd.ZERO, emptyList())
        return Term(c, out.sortedWith(Comparator { a, b -> facCmp(a, b) }))
    }

    private fun normalize(ts: List<Term>): Ex {
        val m = LinkedHashMap<List<Fac>, Surd>()
        for (t in ts) m[t.facs] = (m[t.facs] ?: Surd.ZERO) + t.coef
        var list = m.entries.filter { !it.value.isZero }.map { Term(it.value, it.key) }
        list = trigIdentity(list)
        return Ex(list.sortedWith(Comparator { a, b -> termCmp(a, b) }))
    }

    /** sin²u + cos²u = 1, only for two terms that are otherwise identical. */
    private fun trigIdentity(ts: List<Term>): List<Term> {
        var list = ts
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) for (j in list.indices) {
                if (i == j) continue
                val a = list[i]
                val b = list[j]
                if (a.coef != b.coef) continue
                val sin = a.facs.firstOrNull { it.base is Node.Trig && (it.base as Node.Trig).name == "sin" && it.exp == Rational.of(2) } ?: continue
                val sb = sin.base as Node.Trig
                val cosFac = Fac(Node.Trig("cos", sb.arg, sb.deg), Rational.of(2))
                if (cosFac !in b.facs) continue
                if (a.facs - sin != b.facs - cosFac) continue
                val merged = Term(a.coef, a.facs - sin)
                list = list.filterIndexed { k, _ -> k != i && k != j } + merged
                changed = true
                break@outer
            }
        }
        return list
    }

    // ------------------------------------------------------------------ ordering

    fun isExpAtom(b: Node) = b is Node.Power && (b.base is Node.Const || b.base is Node.Number) && b.exp.variableNames().isNotEmpty()

    private fun rank(b: Node): Int = when {
        b is Node.Variable -> if (b.name == v) 1 else 0
        b is Node.Number -> 2
        b is Node.Sum -> 3
        isExpAtom(b) -> 4
        b is Node.Trig -> 5
        b is Node.Abs -> 6
        b is Node.Func -> 7
        else -> 8
    }

    private fun facCmp(a: Fac, b: Fac): Int {
        val ra = rank(a.base)
        val rb = rank(b.base)
        if (ra != rb) return ra.compareTo(rb)
        val x = a.base
        val y = b.base
        if (x is Node.Trig && y is Node.Trig && x.name != y.name) return trigOrder.indexOf(x.name).compareTo(trigOrder.indexOf(y.name))
        return DTex.tex(x).compareTo(DTex.tex(y))
    }

    private fun degree(t: Term): Rational = t.facs.firstOrNull { it.base == Node.Variable(v) }?.exp ?: Rational.ZERO

    private fun termCmp(a: Term, b: Term): Int {
        val da = degree(a)
        val db = degree(b)
        if (da != db) return db.compareTo(da)
        return (a.facs.isEmpty()).compareTo(b.facs.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun constRational(e: Ex): Rational? {
        if (e.isZero) return Rational.ZERO
        val t = e.terms.singleOrNull() ?: return null
        return if (t.facs.isEmpty() && t.coef.isRational) t.coef.rational else null
    }

    private fun constSurd(e: Ex): Surd? {
        if (e.isZero) return Surd.ZERO
        val t = e.terms.singleOrNull() ?: return null
        return if (t.facs.isEmpty()) t.coef else null
    }

    private fun isConstantEx(e: Ex) = e.terms.all { t -> t.facs.all { it.base.variableNames().isEmpty() } }

    // ------------------------------------------------------------------ Ex -> Node

    private fun leadingNegative(ts: List<Term>) = ts.first().coef.signum() < 0 && ts.any { it.coef.signum() > 0 }

    /** [inner]: inside a bracket, a sum that starts with a minus starts with a positive term instead (1-x², not -x²+1). */
    fun toNode(e: Ex, inner: Boolean): Node {
        if (e.isZero) return NB.ZERO
        val ts = if (inner && leadingNegative(e.terms)) e.terms.filter { it.coef.signum() > 0 } + e.terms.filter { it.coef.signum() <= 0 } else e.terms
        val nodes = ts.map { termNode(it, false) }
        return if (nodes.size == 1) nodes[0] else Node.Sum(nodes)
    }

    private fun surdNode(s: Surd): Node {
        if (s.isZero) return NB.ZERO
        if (!s.isSingleTerm) return Node.Sum(s.terms.entries.sortedBy { it.key }.map { (k, q) -> singleSurd(k, q) })
        val num = ArrayList<Node>()
        val den = ArrayList<Node>()
        val (k, q) = s.terms.entries.first()
        singleParts(k, q.abs(), num, den)
        val node = fraction(num, den)
        return if (q.signum < 0) NB.neg(node) else node
    }

    private fun singleSurd(k: SKey, q: Rational): Node {
        val num = ArrayList<Node>()
        val den = ArrayList<Node>()
        singleParts(k, q.abs(), num, den)
        val node = fraction(num, den)
        return if (q.signum < 0) NB.neg(node) else node
    }

    private fun piece(half: Int, c: Node, num: MutableList<Node>, den: MutableList<Node>) {
        if (half == 0) return
        val target = if (half > 0) num else den
        val n = Math.abs(half)
        val whole = n / 2
        if (whole >= 1) target += if (whole == 1) c else Node.Power(c, NB.num(whole.toLong()))
        if (n % 2 == 1) target += Node.Root(2, c)
    }

    private fun singleParts(k: SKey, q: Rational, num: MutableList<Node>, den: MutableList<Node>) {
        if (q.num != BigInteger.ONE || k == SKey.ONE) num += Node.Number(Rational.of(q.num))
        if (q.den != BigInteger.ONE) den += Node.Number(Rational.of(q.den))
        if (k.k != BigInteger.ONE) num += Node.Root(2, Node.Number(Rational.of(k.k)))
        piece(k.pi, Node.Const('π'), num, den)
        piece(k.e, Node.Const('e'), num, den)
    }

    private fun prod(l: List<Node>): Node = when (l.size) { 0 -> NB.ONE; 1 -> l[0]; else -> Node.Product(l) }

    private fun fraction(num: List<Node>, den: List<Node>): Node {
        val n = prod(if (num.size > 1) num.filter { !NB.isOne(it) }.ifEmpty { listOf(NB.ONE) } else num)
        return if (den.isEmpty()) n else Node.Quotient(n, prod(den))
    }

    private fun facNodes(base: Node, e: Rational): List<Node> {
        if (e.isInteger) return listOf(powOf(base, e))
        if (e.den > BigInteger.valueOf(12)) return listOf(powOf(base, e))
        val q = e.den
        val p = e.num
        val w = p.divide(q)
        val rem = p.mod(q)
        val out = ArrayList<Node>()
        if (w.signum() > 0) out += powOf(base, Rational.of(w))
        val inner = if (rem == BigInteger.ONE) base else Node.Power(base, Node.Number(Rational.of(rem)))
        out += Node.Root(q.toInt(), inner)
        return out
    }

    private fun powOf(base: Node, k: Rational): Node = if (k == Rational.ONE) base else Node.Power(base, Node.Number(k))

    fun termNode(t: Term, signInside: Boolean): Node {
        val neg = t.coef.signum() < 0
        val mag = if (neg) -t.coef else t.coef
        val num = ArrayList<Node>()
        val den = ArrayList<Node>()
        val facNum = ArrayList<Node>()
        val facDen = ArrayList<Node>()
        for (f in t.facs) if (f.exp.signum > 0) facNum += facNodes(f.base, f.exp) else facDen += facNodes(f.base, -f.exp)
        // π/180·cos x stays a fraction in front; 3/2·√x is one fraction
        if (mag.isSingleTerm) {
            val (k, q) = mag.terms.entries.first()
            singleParts(k, q, num, den)
            val surdPart = k.k != BigInteger.ONE || k.hasConstants
            if (surdPart && den.isNotEmpty() && facDen.isEmpty() && facNum.isNotEmpty()) {
                val node = Node.Product(listOf(fraction(num, den)) + facNum)
                return if (neg) NB.neg(node) else node
            }
        } else num += surdNode(mag)
        num += facNum
        den += facDen
        val n = prod(if (num.size > 1) num.filter { !NB.isOne(it) }.ifEmpty { listOf(NB.ONE) } else num)
        if (den.isEmpty()) return if (neg) NB.neg(n) else n
        val d = prod(den)
        return if (neg && signInside) Node.Quotient(NB.neg(n), d) else if (neg) Node.Neg(Node.Quotient(n, d)) else Node.Quotient(n, d)
    }

    /** The finished, printable form of [e]. */
    fun finalNode(e: Ex, inner: Boolean = false): Node {
        if (e.isZero) return NB.ZERO
        if (e.terms.size == 1) {
            val t = e.terms[0]
            return termNode(t, t.facs.any { it.exp.signum < 0 && it.base is Node.Sum })
        }
        // (x-2)/|x-2|: a denominator shared by every term comes out of the sum
        val commonDen = e.terms[0].facs.filter { it.exp.signum < 0 && e.terms.all { t -> it in t.facs } }
        if (commonDen.isNotEmpty()) {
            val inner = normalize(e.terms.map { t -> Term(t.coef, t.facs.filter { it !in commonDen }) })
            return Node.Quotient(finalNode(inner, true), prod(commonDen.flatMap { facNodes(it.base, -it.exp) }))
        }
        // (t²+2t)e^t: a factor e^u present in every term comes out of the sum
        val common = e.terms[0].facs.filter { (isExpAtom(it.base) || it.base is Node.Power) && it.exp == Rational.ONE && e.terms.all { t -> it in t.facs } }
        if (common.isNotEmpty()) {
            var inner = normalize(e.terms.map { t -> Term(t.coef, t.facs.filter { it !in common }) })
            val allNeg = inner.terms.all { it.coef.signum() < 0 }
            if (allNeg) inner = neg(inner)
            val node = Node.Product(listOf(toNode(inner, true)) + common.map { it.base })
            return if (allNeg) Node.Neg(node) else node
        }
        return toNode(e, inner)
    }

    // ------------------------------------------------------------------ rational functions

    private fun toRatFn(e: Ex): RatFn? {
        var acc = RatFn(Poly.ZERO, Poly.ONE)
        for (t in e.terms) {
            var r = RatFn(Poly.const(t.coef), Poly.ONE)
            for (f in t.facs) {
                if (!f.exp.isInteger || f.exp.num.abs() > BigInteger.valueOf(40)) return null
                val b = when (val base = f.base) {
                    is Node.Variable -> if (base.name == v) RatFn(Poly.variable(v), Poly.ONE) else return null
                    is Node.Sum -> toRatFn(of(base)) ?: return null
                    else -> return null
                }
                r *= b.pow(f.exp.num.toInt())
            }
            acc += r
        }
        return acc
    }

    private fun polyEx(p: Poly): Ex = normalize(p.terms.map { (m, c) ->
        buildTerm(c, m.powers.entries.associate { (ch, k) -> Node.Variable(ch) as Node to Rational.of(k.toLong()) })
    })

    private fun polyNode(p: Poly, inner: Boolean = false): Node = finalNode(polyEx(p), inner)

    /**
     * A rational function with a real denominator as one fraction with factored denominator
     * (`-2/(x-1)²`), cancelling common factors; null when the term-by-term form is already best.
     */
    fun rationalNode(e: Ex): Node? {
        if (e.isZero) return null
        if (e.terms.none { t -> t.facs.any { it.base is Node.Sum && it.exp.signum < 0 } }) return null
        val rf = toRatFn(e) ?: return null
        val c = rf.simplified()
        val f = c.value
        if (e.terms.size == 1 && c.factor.isConstant) return null
        if (f.num.isZero) return NB.ZERO
        if (f.num.degreeIn(v) > 12 || f.den.degreeIn(v) > 12) return null
        if (!c.factor.isConstant && v in c.factor.variables) cancelled += c.factor
        if (f.den.isConstant) return polyNode(f.num)
        // candidate factors: the bases that stood in a denominator
        var den = f.den
        val denFactors = ArrayList<Node>()
        val seen = LinkedHashSet<Node>()
        for (t in e.terms) for (fac in t.facs) if (fac.exp.signum < 0 && (fac.base is Node.Sum || fac.base == Node.Variable(v))) seen += fac.base
        for (b in seen) {
            val bp = (if (b is Node.Variable) RatFn(Poly.variable(v), Poly.ONE) else toRatFn(of(b)))?.takeIf { it.den.isConstant }?.num ?: continue
            var m = 0
            while (true) {
                val q = Poly.exactDivide(den, bp, v) ?: break
                if (q.isZero) break
                den = q
                m++
                if (m > 40) break
            }
            if (m > 0) denFactors += powOf(b, Rational.of(m.toLong()))
        }
        var num = f.num
        if (den.isConstant) {
            num = num.divBy(den.constant)
        } else denFactors += finalNode(polyEx(den))
        val numNode = polyNode(num, true)
        val denNode = if (denFactors.size == 1) denFactors[0] else Node.Product(denFactors)
        return Node.Quotient(numNode, denNode)
    }

    /** The shown form of [n]: algebra, then the single-fraction form for rational functions. */
    fun simplify(n: Node): Node {
        val e = of(n)
        val fraction = try { rationalNode(e) } catch (_: UnsupportedMath) { null } catch (_: ArithmeticException) { null }
        return fraction ?: finalNode(e)
    }

    /** Is [n] (after simplification) a rational function of [v] with exact coefficients, and no other unknown? */
    fun asRatFn(n: Node): RatFn? = try { toRatFn(of(n)) } catch (_: UnsupportedMath) { null }
}
