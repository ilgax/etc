package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** Parse tree of the supported LaTeX subset. */
sealed interface Node {
    data class Number(val value: Rational) : Node
    data class Variable(val name: Char) : Node
    data class Sum(val terms: List<Node>) : Node
    data class Neg(val arg: Node) : Node
    data class Product(val factors: List<Node>) : Node
    data class Quotient(val num: Node, val den: Node) : Node
    data class Power(val base: Node, val exp: Node) : Node
    data class Root(val index: Int, val arg: Node) : Node
    /** π or Euler's e. */
    data class Const(val symbol: Char) : Node
    /** "ln" (base e, [base] null) or "log" with an explicit base. */
    data class Func(val name: String, val base: Node?, val arg: Node) : Node
}

/**
 * One line of input: expressions joined by `=` (a chain a=b=c has three parts) or by relations. [ops] holds one
 * entry per gap, each one of "=", "<", ">", "<=", ">=".
 */
class ParsedLine(val parts: List<Node>, val sources: List<String>, val ops: List<String> = List(parts.size - 1) { "=" }) {
    val isEquation get() = ops.isNotEmpty() && ops.all { it == "=" }
    val isInequality get() = ops.any { it != "=" }
}

private enum class T { NUM, ID, OP, CMD, BREAK, END }
private class Tok(val type: T, val text: String, val start: Int, val end: Int)

object LatexParser {
    private val relations = mapOf(
        "\\le" to "<=", "\\leq" to "<=", "\\leqslant" to "<=", "\\ge" to ">=", "\\geq" to ">=", "\\geqslant" to ">=",
        "\\lt" to "<", "\\gt" to ">",
    )
    private val trig = setOf("\\sin", "\\cos", "\\tan", "\\cot", "\\sec", "\\csc", "\\arcsin", "\\arccos", "\\arctan", "\\sinh", "\\cosh", "\\tanh")
    private val skipCommands = setOf("\\,", "\\;", "\\!", "\\:", "\\ ", "\\quad", "\\qquad", "\\left", "\\right", "\\displaystyle", "\\textstyle", "\\big", "\\Big")

    /** Splits [input] into lines (by `\\` or newline) and each line into `=`-separated parts. */
    fun parse(input: String, target: Char? = null): List<ParsedLine> {
        val toks = tokenize(input)
        val letters = toks.filter { it.type == T.ID }.map { it.text[0] }.toSet()
        // `e` is Euler's number, except when it is the only letter of an equation or the unknown asked for (target line)
        val hasRelation = toks.any { relationOf(it) != null }
        val eConst = 'e' in letters && (letters.size > 1 || !hasRelation) && target != 'e'
        val lines = ArrayList<ParsedLine>()
        var from = 0
        for (i in 0..toks.size) {
            if (i == toks.size || toks[i].type == T.BREAK) {
                val slice = toks.subList(from, i)
                if (slice.isNotEmpty()) lines += parseLine(input, slice, eConst)
                from = i + 1
            }
        }
        if (lines.isEmpty()) throw UnsupportedMath("nothing to solve")
        return lines
    }

    private fun relationOf(t: Tok): String? = when {
        t.type == T.OP && (t.text == "=" || t.text == "<" || t.text == ">") -> t.text
        t.type == T.CMD -> relations[t.text]
        else -> null
    }

    private fun parseLine(input: String, slice: List<Tok>, eConst: Boolean): ParsedLine {
        val parts = ArrayList<List<Tok>>()
        val ops = ArrayList<String>()
        var depth = 0
        var cur = ArrayList<Tok>()
        for (t in slice) {
            if (t.type == T.OP) {
                if (t.text == "{" || t.text == "(" || t.text == "[") depth++
                if (t.text == "}" || t.text == ")" || t.text == "]") depth--
            }
            val rel = if (depth == 0) relationOf(t) else null
            if (rel != null) { parts += cur; ops += rel; cur = ArrayList(); continue }
            cur += t
        }
        parts += cur
        if (parts.any { it.isEmpty() }) throw UnsupportedMath(if (ops.all { it == "=" }) "an equation is missing one side" else "an inequality is missing one side")
        val nodes = parts.map { p -> Parser(p, eConst).parseAll() }
        val sources = parts.map { p -> input.substring(p.first().start, p.last().end).trim() }
        return ParsedLine(nodes, sources, ops)
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\n' -> { out += Tok(T.BREAK, "\n", i, i + 1); i++ }
                c.isWhitespace() || c == '~' -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    var j = i
                    var dot = false
                    while (j < s.length && (s[j].isDigit() || (s[j] == '.' && !dot && j + 1 < s.length && s[j + 1].isDigit()))) {
                        if (s[j] == '.') dot = true
                        j++
                    }
                    out += Tok(T.NUM, s.substring(i, j), i, j); i = j
                }
                c.isLetter() && c.code < 128 -> { out += Tok(T.ID, c.toString(), i, i + 1); i++ }
                c == '\\' -> {
                    if (i + 1 < s.length && s[i + 1] == '\\') { out += Tok(T.BREAK, "\\\\", i, i + 2); i += 2 }
                    else if (i + 1 < s.length && s[i + 1].isLetter()) {
                        var j = i + 1
                        while (j < s.length && s[j].isLetter()) j++
                        val name = s.substring(i, j)
                        if ((name == "\\mathrm" || name == "\\operatorname") && s.startsWith("{e}", j)) {
                            out += Tok(T.CMD, "\\eulere", i, j + 3); j += 3
                        } else if (name == "\\colon") out += Tok(T.OP, "/", i, j)
                        else if (name !in skipCommands) out += Tok(T.CMD, name, i, j)
                        i = j
                    } else if (i + 1 < s.length) {
                        val name = s.substring(i, i + 2)
                        if (name in skipCommands) i += 2
                        else if (s[i + 1] == '{' || s[i + 1] == '}') { out += Tok(T.OP, s[i + 1].toString(), i, i + 2); i += 2 }
                        else { out += Tok(T.CMD, name, i, i + 2); i += 2 }
                    } else i++
                }
                c in "+-*/^=(){}[]_<>" -> { out += Tok(T.OP, c.toString(), i, i + 1); i++ }
                c == ':' -> { out += Tok(T.OP, "/", i, i + 1); i++ }
                c == 'π' -> { out += Tok(T.CMD, "\\pi", i, i + 1); i++ }
                c == '≤' -> { out += Tok(T.CMD, "\\le", i, i + 1); i++ }
                c == '≥' -> { out += Tok(T.CMD, "\\ge", i, i + 1); i++ }
                c == '−' -> { out += Tok(T.OP, "-", i, i + 1); i++ }
                c == '·' || c == '⋅' -> { out += Tok(T.CMD, "\\cdot", i, i + 1); i++ }
                c == '×' -> { out += Tok(T.CMD, "\\times", i, i + 1); i++ }
                c == '÷' -> { out += Tok(T.OP, "/", i, i + 1); i++ }
                else -> throw UnsupportedMath("the symbol “$c”")
            }
        }
        return out
    }

    private class Parser(tokens: List<Tok>, private val eConst: Boolean) {
        private val toks = ArrayList(tokens)
        private var pos = 0

        private fun peek(): Tok? = toks.getOrNull(pos)
        private fun isOp(t: Tok?, op: String) = t != null && t.type == T.OP && t.text == op
        private fun isCmd(t: Tok?, vararg names: String) = t != null && t.type == T.CMD && t.text in names

        fun parseAll(): Node {
            val n = expr()
            peek()?.let { throw UnsupportedMath("unexpected “${it.text}”") }
            return n
        }

        private fun expr(): Node {
            val terms = ArrayList<Node>()
            var sign = 1
            val t0 = peek()
            if (isOp(t0, "-")) { sign = -1; pos++ } else if (isOp(t0, "+")) pos++
            terms += signed(sign, term())
            while (true) {
                val t = peek()
                if (isOp(t, "+")) { pos++; terms += term() }
                else if (isOp(t, "-")) { pos++; terms += Node.Neg(term()) }
                else break
            }
            return if (terms.size == 1) terms[0] else Node.Sum(terms)
        }

        private fun signed(sign: Int, n: Node) = if (sign < 0) Node.Neg(n) else n

        private fun term(): Node {
            var left = unary()
            while (true) {
                val t = peek() ?: break
                when {
                    isCmd(t, "\\cdot", "\\times", "\\ast") || isOp(t, "*") -> { pos++; left = mul(left, unary()) }
                    isOp(t, "/") || isCmd(t, "\\div") -> { pos++; left = Node.Quotient(left, unary()) }
                    startsAtom(t) -> left = mul(left, unary())
                    else -> break
                }
            }
            return left
        }

        private fun mul(a: Node, b: Node): Node = if (a is Node.Product) Node.Product(a.factors + b) else Node.Product(listOf(a, b))

        private fun startsAtom(t: Tok) = when (t.type) {
            T.NUM, T.ID -> true
            T.OP -> t.text == "(" || t.text == "{" || t.text == "["
            T.CMD -> t.text in setOf("\\frac", "\\dfrac", "\\tfrac", "\\sqrt", "\\pi", "\\eulere", "\\ln", "\\lg", "\\log", "\\exp")
            else -> false
        }

        private fun unary(): Node {
            val t = peek()
            if (isOp(t, "-")) { pos++; return Node.Neg(unary()) }
            if (isOp(t, "+")) { pos++; return unary() }
            return power()
        }

        private fun power(): Node {
            val base = atom()
            if (isOp(peek(), "^")) {
                pos++
                return Node.Power(base, exponent())
            }
            if (isOp(peek(), "_")) throw UnsupportedMath("subscripts")
            return base
        }

        private fun exponent(): Node {
            val t = peek() ?: throw UnsupportedMath("a missing exponent")
            if (isOp(t, "{")) return group()
            if (isOp(t, "-")) { pos++; return Node.Neg(exponent()) }
            if (t.type == T.NUM && t.text.length > 1 && !t.text.contains('.')) {
                // x^23 means x^2 followed by 3
                toks[pos] = Tok(T.NUM, t.text.substring(1), t.start + 1, t.end)
                return Node.Number(Rational.parse(t.text.substring(0, 1)))
            }
            return atom()
        }

        /** Argument of ln/log/exp: a parenthesised group, or the next power-level factor (`\\ln x^2` is ln(x²)). */
        private fun funcArg(): Node {
            val t = peek() ?: throw UnsupportedMath("a function without an argument")
            return if (isOp(t, "(") || isOp(t, "{") || isOp(t, "[")) atom() else power()
        }

        /** The base after `\\log_`: a braced group or one digit/letter/constant. */
        private fun subscript(): Node {
            val t = peek() ?: throw UnsupportedMath("a missing subscript")
            if (isOp(t, "{")) return group()
            if (t.type == T.NUM && t.text.length > 1 && !t.text.contains('.')) {
                toks[pos] = Tok(T.NUM, t.text.substring(1), t.start + 1, t.end)
                return Node.Number(Rational.parse(t.text.substring(0, 1)))
            }
            return atom()
        }

        private fun group(): Node {
            if (!isOp(peek(), "{")) throw UnsupportedMath("a missing group")
            pos++
            val n = expr()
            if (!isOp(peek(), "}")) throw UnsupportedMath("an unclosed brace")
            pos++
            return n
        }

        private fun atom(): Node {
            val t = peek() ?: throw UnsupportedMath("the expression ends too early")
            pos++
            return when {
                t.type == T.NUM -> Node.Number(Rational.parse(t.text))
                t.type == T.ID -> if (t.text[0] == 'e' && eConst) Node.Const('e') else Node.Variable(t.text[0])
                isCmd(t, "\\pi") -> Node.Const('π')
                isCmd(t, "\\eulere") -> Node.Const('e')
                isCmd(t, "\\ln") -> Node.Func("ln", null, funcArg())
                isCmd(t, "\\lg") -> Node.Func("log", Node.Number(Rational.of(10)), funcArg())
                isCmd(t, "\\log") -> {
                    val base = if (isOp(peek(), "_")) { pos++; subscript() } else Node.Number(Rational.of(10))
                    Node.Func("log", base, funcArg())
                }
                isCmd(t, "\\exp") -> Node.Power(Node.Const('e'), funcArg())
                isOp(t, "(") -> { val n = expr(); if (!isOp(peek(), ")")) throw UnsupportedMath("an unclosed parenthesis"); pos++; n }
                isOp(t, "{") -> { pos--; group() }
                isOp(t, "[") -> { val n = expr(); if (!isOp(peek(), "]")) throw UnsupportedMath("an unclosed bracket"); pos++; n }
                isCmd(t, "\\frac", "\\dfrac", "\\tfrac") -> { val a = group(); val b = group(); Node.Quotient(a, b) }
                isCmd(t, "\\sqrt") -> {
                    var index = 2
                    if (isOp(peek(), "[")) {
                        pos++
                        val n = expr()
                        if (!isOp(peek(), "]")) throw UnsupportedMath("a malformed root index")
                        pos++
                        val v = (n as? Node.Number)?.value
                        if (v == null || !v.isInteger || v.num < BigInteger.TWO || v.num > BigInteger.valueOf(12)) throw UnsupportedMath("this root index")
                        index = v.num.toInt()
                    }
                    Node.Root(index, if (isOp(peek(), "{")) group() else atom())
                }
                t.type == T.CMD && t.text in trig -> throw UnsupportedMath("trigonometric functions")
                t.type == T.CMD -> throw UnsupportedMath("the symbol ${t.text}")
                else -> throw UnsupportedMath("unexpected “${t.text}”")
            }
        }
    }
}

/** Evaluates a [Node] to an exact rational function; roots, exponentials and logs of the unknown become atoms. */
internal class Evaluator(val atoms: AtomTable = AtomTable()) {
    fun eval(n: Node): RatFn = when (n) {
        is Node.Number -> const(Surd.of(n.value))
        is Node.Variable -> RatFn(Poly.variable(n.name), Poly.ONE)
        is Node.Const -> const(if (n.symbol == 'π') Surd.PI_SURD else Surd.E_SURD)
        is Node.Neg -> -eval(n.arg)
        is Node.Sum -> n.terms.map { eval(it) }.reduce { a, b -> a + b }
        is Node.Product -> n.factors.map { eval(it) }.reduce { a, b -> a * b }
        is Node.Quotient -> {
            val d = eval(n.den)
            if (d.num.isZero) throw UnsupportedMath("division by zero")
            if (d.num.isConstant && !d.num.constant.isSingleTerm) throw UnsupportedMath("dividing by a sum of square roots")
            eval(n.num) / d
        }
        is Node.Power -> power(n)
        is Node.Root -> root(n)
        is Node.Func -> func(n)
    }

    private fun const(s: Surd) = RatFn(Poly.const(s), Poly.ONE)
    private fun sym(a: Atom) = RatFn(Poly.variable(a.sym), Poly.ONE)

    /** The exact value of an expression without unknowns, else null. */
    fun constantOf(f: RatFn): Surd? = if (f.variables.isEmpty()) f.num.constant / f.den.constant else null

    private fun power(n: Node.Power): RatFn {
        val base = eval(n.base)
        val e = eval(n.exp)
        if (e.variables.isEmpty()) {
            val ev = e.num.constant / e.den.constant
            val r = ev.takeIf { it.isRational }?.rational ?: throw UnsupportedMath("an irrational exponent")
            if (!r.isInteger) throw UnsupportedMath("a fractional exponent (use \\sqrt)")
            val k = r.num.toInt()
            if (kotlin.math.abs(k) > 64) throw UnsupportedMath("a very large exponent")
            return base.pow(k)
        }
        if (base.variables.isNotEmpty()) throw UnsupportedMath("a variable in both the base and the exponent (like x^x)")
        if (e.variables.atomSyms.isNotEmpty()) throw UnsupportedMath("an exponent that contains a root, a logarithm or another power of the unknown")
        val b = constantOf(base)!!
        if (b.signum() <= 0) throw UnsupportedMath("a power with a base that is not positive and an unknown exponent")
        if (b == Surd.ONE) return const(Surd.ONE)
        return sym(atoms.pow(b, e, NodeTex.tex(n)))
    }

    private fun root(n: Node.Root): RatFn {
        val a = eval(n.arg)
        if (a.variables.isEmpty()) {
            val value = constantOf(a)!!
            if (value.signum() < 0) {
                if (n.index % 2 == 0) throw UnsupportedMath("the root of a negative number")
                return -constRoot(n.index, -value)
            }
            return constRoot(n.index, value)
        }
        if (a.variables.atomSyms.isNotEmpty()) {
            val inner = a.variables.atomSyms.mapNotNull { atoms[it] }
            throw UnsupportedMath(if (inner.any { it is Atom.Rad }) "a root inside a root (nested radicals)" else "a root of a logarithm or of a power of the unknown")
        }
        if (!a.den.isConstant) throw NeedsNumeric("a root of a fraction with an unknown")
        return sym(atoms.rad(n.index, a.num.divBy(a.den.constant), NodeTex.tex(n)))
    }

    private fun constRoot(index: Int, value: Surd): RatFn {
        if (index == 2) return const(value.sqrt())
        if (!value.isRational) throw UnsupportedMath("a higher root of a surd")
        val q = NumberTheory.exactRoot(value.rational, index) ?: throw UnsupportedMath("a root that is not exact")
        return const(Surd.of(q))
    }

    private fun func(n: Node.Func): RatFn {
        val a = eval(n.arg)
        val base = if (n.name == "ln") Surd.E_SURD else {
            val b = eval(n.base!!)
            constantOf(b) ?: throw UnsupportedMath("a logarithm with an unknown base")
        }
        if (base.signum() <= 0 || base == Surd.ONE) throw UnsupportedMath("a logarithm whose base is not positive or is 1")
        if (a.variables.isEmpty()) {
            val value = constantOf(a)!!
            if (value.signum() <= 0) throw UnsupportedMath("the logarithm of a number that is not positive")
            NumberTheory.exactLog(base, value)?.let { return const(Surd.of(it)) }
        } else if (a.variables.atomSyms.isNotEmpty()) {
            throw UnsupportedMath("a logarithm of a root, of a power of the unknown or of another logarithm")
        }
        return sym(atoms.log(base, a, NodeTex.tex(n)))
    }
}
