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
}

/** One line of input: expressions joined by `=` (a chain a=b=c has three parts). */
class ParsedLine(val parts: List<Node>, val sources: List<String>)

private enum class T { NUM, ID, OP, CMD, BREAK, END }
private class Tok(val type: T, val text: String, val start: Int, val end: Int)

object LatexParser {
    private val skipCommands = setOf("\\,", "\\;", "\\!", "\\:", "\\ ", "\\quad", "\\qquad", "\\left", "\\right", "\\displaystyle", "\\textstyle", "\\big", "\\Big")

    /** Splits [input] into lines (by `\\` or newline) and each line into `=`-separated parts. */
    fun parse(input: String): List<ParsedLine> {
        val toks = tokenize(input)
        val lines = ArrayList<ParsedLine>()
        var from = 0
        for (i in 0..toks.size) {
            if (i == toks.size || toks[i].type == T.BREAK) {
                val slice = toks.subList(from, i)
                if (slice.isNotEmpty()) lines += parseLine(input, slice)
                from = i + 1
            }
        }
        if (lines.isEmpty()) throw UnsupportedMath("nothing to solve")
        return lines
    }

    private fun parseLine(input: String, slice: List<Tok>): ParsedLine {
        val parts = ArrayList<List<Tok>>()
        var depth = 0
        var cur = ArrayList<Tok>()
        for (t in slice) {
            if (t.type == T.OP) {
                if (t.text == "{" || t.text == "(" || t.text == "[") depth++
                if (t.text == "}" || t.text == ")" || t.text == "]") depth--
                if (t.text == "=" && depth == 0) { parts += cur; cur = ArrayList(); continue }
            }
            cur += t
        }
        parts += cur
        if (parts.any { it.isEmpty() }) throw UnsupportedMath("an equation is missing one side")
        val nodes = parts.map { p -> Parser(p).parseAll() }
        val sources = parts.map { p -> input.substring(p.first().start, p.last().end).trim() }
        return ParsedLine(nodes, sources)
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
                        if (name !in skipCommands) out += Tok(T.CMD, name, i, j)
                        i = j
                    } else if (i + 1 < s.length) {
                        val name = s.substring(i, i + 2)
                        if (name in skipCommands) i += 2
                        else if (s[i + 1] == '{' || s[i + 1] == '}') { out += Tok(T.OP, s[i + 1].toString(), i, i + 2); i += 2 }
                        else { out += Tok(T.CMD, name, i, i + 2); i += 2 }
                    } else i++
                }
                c in "+-*/^=(){}[]" -> { out += Tok(T.OP, c.toString(), i, i + 1); i++ }
                c == '−' -> { out += Tok(T.OP, "-", i, i + 1); i++ }
                c == '·' || c == '⋅' -> { out += Tok(T.CMD, "\\cdot", i, i + 1); i++ }
                c == '×' -> { out += Tok(T.CMD, "\\times", i, i + 1); i++ }
                c == '÷' -> { out += Tok(T.OP, "/", i, i + 1); i++ }
                else -> throw UnsupportedMath("the symbol “$c”")
            }
        }
        return out
    }

    private class Parser(tokens: List<Tok>) {
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
            T.CMD -> t.text in setOf("\\frac", "\\dfrac", "\\tfrac", "\\sqrt")
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
                t.type == T.ID -> Node.Variable(t.text[0])
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
                t.type == T.CMD -> throw UnsupportedMath("the symbol ${t.text}")
                else -> throw UnsupportedMath("unexpected “${t.text}”")
            }
        }
    }
}

/** Evaluates a [Node] to an exact rational function. */
object Evaluator {
    fun eval(n: Node): RatFn = when (n) {
        is Node.Number -> const(Surd.of(n.value))
        is Node.Variable -> RatFn(Poly.variable(n.name), Poly.ONE)
        is Node.Neg -> -eval(n.arg)
        is Node.Sum -> n.terms.map { eval(it) }.reduce { a, b -> a + b }
        is Node.Product -> n.factors.map { eval(it) }.reduce { a, b -> a * b }
        is Node.Quotient -> {
            val d = eval(n.den)
            if (d.num.isZero) throw UnsupportedMath("division by zero")
            if (d.num.isConstant && !d.num.constant.isSingleTerm) throw UnsupportedMath("dividing by a sum of square roots")
            eval(n.num) / d
        }
        is Node.Power -> power(eval(n.base), n.exp)
        is Node.Root -> root(n.index, eval(n.arg))
    }

    private fun const(s: Surd) = RatFn(Poly.const(s), Poly.ONE)

    private fun power(base: RatFn, exp: Node): RatFn {
        val e = eval(exp)
        val ev = (if (e.num.isConstant && e.den.isConstant) e.num.constant / e.den.constant else null)
        val r = ev?.takeIf { it.isRational }?.rational ?: throw UnsupportedMath("a variable exponent")
        if (!r.isInteger) throw UnsupportedMath("a fractional exponent (use \\sqrt)")
        val n = r.num.toInt()
        if (kotlin.math.abs(n) > 64) throw UnsupportedMath("a very large exponent")
        return base.pow(n)
    }

    private fun root(index: Int, a: RatFn): RatFn {
        if (!a.num.isConstant || !a.den.isConstant) throw UnsupportedMath("a square root of an expression with variables")
        val value = a.num.constant / a.den.constant
        if (value.signum() < 0) throw UnsupportedMath("the root of a negative number")
        if (index == 2) return const(value.sqrt())
        if (!value.isRational) throw UnsupportedMath("a higher root of a surd")
        val q = value.rational
        val n = exactRoot(q.num, index)
        val d = exactRoot(q.den, index)
        if (n == null || d == null) throw UnsupportedMath("a root that is not exact")
        return const(Surd.of(Rational.of(n, d)))
    }

    private fun exactRoot(v: BigInteger, k: Int): BigInteger? {
        if (v.signum() == 0) return BigInteger.ZERO
        var r = BigInteger.valueOf(Math.round(Math.pow(v.toDouble(), 1.0 / k)))
        for (c in listOf(r - BigInteger.ONE, r, r + BigInteger.ONE)) if (c.signum() >= 0 && c.pow(k) == v) return c
        return null
    }
}
