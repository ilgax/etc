package dev.ilgax.utils.math.solver

import java.math.BigDecimal

/**
 * Derivative problems: the notation (`\frac{d}{dx}`, `f'(x)`, `y''`, `f^{(n)}`, `(…)'`, a `f(x)=…` definition), the
 * rule-by-rule steps, evaluation at a point and the three applications (critical points with a sign chart,
 * tangent line, `f'(x)=0`). Differentiation is [Differentiator], the readable result is [Alg], and every derivative
 * is cross-checked against a finite difference ([DerivCheck]) before it is shown.
 *
 * Angles: calculus is in radians. With the setting on Auto a derivative problem is read in radians unless a `°`
 * is written; Degrees forces degrees (every derivative of a trigonometric function then carries a factor π/180),
 * Radians forces radians.
 */
internal class DerivativeSolver(private val s: SolveSession, private val angles: AngleMode) {
    companion object {
        /** Names that mean "a function" when they are written `f(x)=…` with no derivative mark anywhere. */
        private const val FN_NAMES = "fghFGH"
        private val defFn = Regex("""^([A-Za-z])\(([A-Za-z])\)=(.+)$""")
        private val defPlain = Regex("""^([A-Za-z])=(.+)$""")
        private val pointSub = Regex("""^([A-Za-z])_\{?0\}?=(.+)$""")
        private val leibniz = Regex("""^\\frac\{d(?:\^\{?(\d+)\}?)?([A-Za-z])?\}\{d([A-Za-z])(?:\^\{?(\d+)\}?)?\}(.*)$""")
        private val leibnizAnywhere = Regex("""\\frac\{d(?:\^\{?\d+\}?)?[A-Za-z]?\}\{d[A-Za-z](?:\^\{?\d+\}?)?\}""")
        private val orderN = Regex("""[A-Za-z]\^\{\(\d+\)\}""")

        /** Cheap test run on every input: does this look like a derivative problem at all? */
        fun applies(latex: String): Boolean {
            if (latex.contains('\'') || latex.contains("\\partial") || latex.contains("\\lim") || latex.contains("\\int")) return true
            if (leibnizAnywhere.containsMatchIn(latex) || orderN.containsMatchIn(latex)) return true
            return latex.split("\\\\").any { l ->
                defFn.matchEntire(l.trim())?.let { it.groupValues[1][0] in FN_NAMES && it.groupValues[1] != it.groupValues[2] } == true
            }
        }
    }

    private enum class Kind { Operator, Leibniz, Prime }
    private sealed interface L
    private class Req(val order: Int, val name: Char?, val dv: Char?, val body: String?, val arg: String?, val rhs: String?, val kind: Kind) : L
    private class Def(val name: Char, val arg: Char?, val body: String) : L
    private class Point(val v: Char, val value: String) : L
    private class Other(val src: String) : L

    private class Problem(
        val f: Node, val v: Char, val order: Int, val kind: Kind, val name: Char?, val showArg: Boolean,
    )

    private var pm = AngleMode.Radians
    private var deg = false
    private lateinit var alg: Alg

    private fun step(latex: String, caption: String) = s.step(latex, caption)

    // ------------------------------------------------------------------ notation

    private fun closing(t: String, open: Int): Int {
        var d = 0
        for (i in open until t.length) {
            if (t[i] == '(') d++
            if (t[i] == ')') { d--; if (d == 0) return i }
        }
        return -1
    }

    /** Index of the first `=` outside brackets, or -1. */
    private fun topEquals(t: String): Int {
        var d = 0
        for (i in t.indices) when (t[i]) {
            '(', '{', '[' -> d++
            ')', '}', ']' -> d--
            '=' -> if (d == 0) return i
        }
        return -1
    }

    /** The pieces of [t] between commas and semicolons outside brackets. */
    private fun splitTop(t: String): List<String> {
        val out = ArrayList<String>()
        var d = 0
        var from = 0
        for (i in t.indices) when (t[i]) {
            '(', '{', '[' -> d++
            ')', '}', ']' -> d--
            ',', ';' -> if (d == 0) { out += t.substring(from, i); from = i + 1 }
        }
        out += t.substring(from)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unwrap(t: String): String {
        if (t.startsWith("(") && closing(t, 0) == t.length - 1) return t.substring(1, t.length - 1)
        return t
    }

    private fun splitRhs(rest: String): Pair<String, String?> {
        val i = topEquals(rest)
        if (i < 0) return rest to null
        val rhs = rest.substring(i + 1)
        return rest.substring(0, i) to rhs.ifEmpty { null }
    }

    private fun classify(line: String): L {
        leibniz.matchEntire(line)?.let { m ->
            val top = m.groupValues[1].toIntOrNull()
            val bottom = m.groupValues[4].toIntOrNull()
            if (top != null && bottom != null && top != bottom) throw UnsupportedMath("a derivative whose orders don't match")
            val order = top ?: bottom ?: 1
            if (order > 8) throw UnsupportedMath("a derivative of order above 8")
            val name = m.groupValues[2].firstOrNull()
            val dv = m.groupValues[3][0]
            val (lhs, rhs) = splitRhs(m.groupValues[5])
            return if (name != null) Req(order, name, dv, null, lhs.ifEmpty { null }?.let { unwrap(it) }, rhs, Kind.Leibniz)
            else {
                if (lhs.isEmpty()) throw SolveFailure("there is nothing to differentiate")
                Req(order, null, dv, unwrap(lhs), null, rhs, Kind.Operator)
            }
        }
        if (line.startsWith("(")) {
            val c = closing(line, 0)
            if (c > 0 && c + 1 < line.length && line[c + 1] == '\'') {
                var j = c + 1
                while (j < line.length && line[j] == '\'') j++
                val (restL, rhs) = splitRhs(line.substring(j))
                if (restL.isEmpty()) return Req(j - c - 1, null, null, line.substring(1, c), null, rhs, Kind.Operator)
            }
        }
        // f', f'', f'(x), f'(2), f^{(n)}(x), with an optional `=` part
        val head = Regex("""^([A-Za-z])(?:('+)|\^\{\((\d+)\)\})""").find(line)
        if (head != null) {
            val order = if (head.groupValues[2].isNotEmpty()) head.groupValues[2].length else head.groupValues[3].toInt()
            if (order > 8) throw UnsupportedMath("a derivative of order above 8")
            var rest = line.substring(head.range.last + 1)
            var arg: String? = null
            if (rest.startsWith("(")) {
                val c = closing(rest, 0)
                if (c > 0) { arg = rest.substring(1, c); rest = rest.substring(c + 1) }
            }
            val (restL, rhs) = splitRhs(rest)
            if (restL.isEmpty()) return Req(order, head.groupValues[1][0], null, null, arg, rhs, Kind.Prime)
        }
        pointSub.matchEntire(line)?.let { return Point(it.groupValues[1][0], it.groupValues[2]) }
        defFn.matchEntire(line)?.let { if (it.groupValues[1] != it.groupValues[2]) return Def(it.groupValues[1][0], it.groupValues[2][0], it.groupValues[3]) }
        defPlain.matchEntire(line)?.let {
            val body = it.groupValues[2]
            val hasUnknown = body.replace(Regex("""\\[A-Za-z]+"""), "").any { c -> c.isLetter() && c != 'e' }
            return if (hasUnknown) Def(it.groupValues[1][0], null, body) else Point(it.groupValues[1][0], body)
        }
        return Other(line)
    }

    private fun parseExpr(src: String): Node {
        if (src.contains('\'') || leibnizAnywhere.containsMatchIn(src)) throw UnsupportedMath("a derivative inside a derivative")
        val lines = LatexParser.parse(src, null, pm)
        if (lines.size != 1 || lines[0].parts.size != 1) throw UnsupportedMath("an equation or several expressions where one function is expected")
        return lines[0].parts[0]
    }

    // ------------------------------------------------------------------ entry

    /** Null when the input turns out not to be a derivative problem after all (a plain equation such as `f(x)=4`). */
    fun solve(latex: String): SolveResult? {
        if (latex.contains("\\partial")) throw UnsupportedMath("partial derivatives")
        if (latex.contains("\\lim")) throw UnsupportedMath("limits (so no derivative from the limit definition)")
        if (latex.contains("\\int")) throw UnsupportedMath("integrals")
        val hasDegreeSign = latex.contains('°')
        deg = angles == AngleMode.Degrees || (angles == AngleMode.Auto && hasDegreeSign)
        pm = if (deg) AngleMode.Degrees else AngleMode.Radians

        val items = latex.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            // `y=x^2+3x,\ y'=` on one line is two statements
            val parts = splitTop(line)
            if (parts.size > 1 && parts.any { classify(it) is Req }) parts.map { classify(it) } else listOf(classify(line))
        }
        val reqs = items.filterIsInstance<Req>()
        val defs = LinkedHashMap<Char, Def>()
        for (d in items.filterIsInstance<Def>()) {
            if (d.name in defs) {
                if (reqs.isEmpty()) return null
                throw UnsupportedMath("two definitions of ${d.name}")
            }
            defs[d.name] = d
        }
        val points = items.filterIsInstance<Point>()
        val others = items.filterIsInstance<Other>()
        if (reqs.size > 1) throw UnsupportedMath("several derivatives at once")
        var req = reqs.singleOrNull()
        val fnDefs = defs.values.filter { it.arg != null }
        if (req == null) {
            if (others.isNotEmpty() || fnDefs.size != 1 || defs.size != 1 || points.size > 1) return null
            val d = fnDefs[0]
            if (d.name !in FN_NAMES) return null
            if (points.isEmpty() && !d.body.any { it == d.arg }) return null
            req = Req(1, d.name, null, null, null, null, Kind.Prime)
        }
        if (others.isNotEmpty()) throw UnsupportedMath(if (others.any { it.src.contains('=') }) "implicit or parametric differentiation" else "this combination of lines")
        return try { run(req, defs, points) } catch (u: Undefined) { throw SolveFailure(u.why) } catch (_: NotExact) { throw UnsupportedMath("an exact value here") }
    }

    private fun resolve(req: Req, defs: Map<Char, Def>): Problem {
        val body = req.body
        if (body != null) {
            // \frac{d}{dx} f(x): the name of a defined function
            Regex("""^([A-Za-z])\(([A-Za-z])\)$""").matchEntire(body)?.let { m ->
                val d = defs[m.groupValues[1][0]]
                if (d != null) return Problem(parseExpr(d.body), d.arg ?: m.groupValues[2][0], req.order, req.kind, d.name, true)
            }
            val f = parseExpr(body)
            val v = req.dv ?: f.variableNames().let { vs -> if ('x' in vs) 'x' else vs.singleOrNull() } ?: throw UnsupportedMath("a derivative without a clear variable")
            return Problem(f, v, req.order, req.kind, null, false)
        }
        val name = req.name!!
        val d = defs[name]
        if (d == null) {
            // possibly implicit: x²+y²=1 with dy/dx
            throw SolveFailure("$name isn't defined: write it on another line, like $name(x)=x^2")
        }
        val f = parseExpr(d.body)
        val v: Char = when {
            d.arg != null -> d.arg
            req.dv != null -> req.dv
            else -> {
                val vs = f.variableNames() - name
                if ('x' in vs) 'x' else vs.singleOrNull() ?: throw UnsupportedMath("a derivative with respect to which variable? (write dy/dx)")
            }
        }
        if (name in f.variableNames()) throw UnsupportedMath("implicit differentiation")
        if (v !in f.variableNames()) {
            if (f.variableNames().isNotEmpty() && req.kind == Kind.Leibniz) throw UnsupportedMath("implicit or parametric differentiation")
        }
        return Problem(f, v, req.order, req.kind, name, d.arg != null)
    }

    // ------------------------------------------------------------------ labels

    private fun primes(k: Int) = if (k <= 3) "'".repeat(k) else "^{($k)}"

    private fun lhs(p: Problem, k: Int, arg: String? = null): String = when (p.kind) {
        Kind.Prime -> p.name!!.toString() + primes(k) + (arg?.let { "($it)" } ?: if (p.showArg) "(${p.v})" else "")
        Kind.Leibniz -> if (k == 1) "\\frac{d${p.name}}{d${p.v}}" else "\\frac{d^{$k}${p.name}}{d${p.v}^{$k}}"
        Kind.Operator -> if (k == 1) "\\frac{d}{d${p.v}}" else "\\frac{d^{$k}}{d${p.v}^{$k}}"
    }

    /** What a derivative of order [k] is called in a step: f''(x), dy/dx, or d²/dx²(f) for the bare operator. */
    private fun label(p: Problem, k: Int): String =
        if (p.kind == Kind.Operator) "${lhs(p, k)}\\left(${DTex.tex(p.f)}\\right)" else lhs(p, k)

    private fun ordinal(k: Int) = when (k) { 1 -> "First"; 2 -> "Second"; 3 -> "Third"; 4 -> "Fourth"; else -> "${k}th" }

    // ------------------------------------------------------------------ main flow

    private fun run(req: Req, defs: Map<Char, Def>, points: List<Point>): SolveResult {
        val p = resolve(req, defs)
        alg = Alg(p.v)
        val hasTrig = p.f.hasTrig()
        if (hasTrig && angles == AngleMode.Auto && !deg) s.note("Angles are in radians (derivatives use radians unless you write a °)")
        if (deg && p.f.hasTrig()) s.note("Angles are in degrees, so every derivative of a trigonometric function carries a factor π/180")

        if (p.name != null && p.kind != Kind.Operator && p.showArg) step("${p.name}(${p.v})=${DTex.tex(p.f)}", "The function")
        else if (p.name != null && p.kind != Kind.Operator) step("${p.name}=${DTex.tex(p.f)}", "The function")

        val tangentPt = points.singleOrNull()
        if (points.size > 1) throw UnsupportedMath("several points")
        if (tangentPt != null && (p.name == null || !p.showArg || tangentPt.v != p.v)) throw UnsupportedMath("a tangent line without a function f(${p.v}) and a point ${p.v}=a")

        val levels = differentiate(p)
        val last = levels.last()
        for (f in alg.cancelled.distinct()) {
            val roots = s.rootsOrEmpty(f, p.v)
            s.note("Valid for " + if (roots.isNotEmpty()) roots.joinToString(", ") { "${p.v} ≠ ${plainOf(it.texOrDecimal())}" } else "${plainOf(Printer.poly(f))} ≠ 0")
        }

        if (tangentPt != null) return tangent(p, levels, tangentPt)
        val rhs = req.rhs
        if (rhs != null) return equation(p, last, rhs)

        val argSrc = req.arg
        if (argSrc != null && argSrc != p.v.toString() && p.kind == Kind.Prime) return atPoint(p, levels, argSrc)

        val answerLhs = if (p.kind == Kind.Operator) "" else lhs(p, p.order) + "="
        val shown = DTex.tex(last)
        val answer = Answer(answerLhs + shown, plainOf(answerLhs + shown))
        if (p.kind != Kind.Operator && p.name != null && p.showArg && p.order == 1) critical(p, last)
        return SolveResult.Solved(listOf(answer), s.steps, s.notes)
    }

    // ------------------------------------------------------------------ differentiate

    private class Computed(val raw: Node, val simp: Node, val rules: List<String>, val dz: Differentiator)

    /** Differentiates once, simplifies, and cross-checks the rule output and the simplified form against finite differences. */
    private fun compute(f: Node, v: Char): Computed {
        val dz = Differentiator(v)
        val raw = dz.d(f)
        val a = Alg(v)
        val simp = try { a.simplify(raw) } catch (e: UnsupportedMath) { raw } catch (e: ArithmeticException) { raw } catch (e: IllegalStateException) { raw } catch (e: ClassCastException) { raw }
        alg.cancelled += a.cancelled
        val rawCheck = DerivCheck.run(f, raw, v)
        if (rawCheck.mismatch != null) throw UnsupportedMath("a derivative the solver could not verify numerically (${rawCheck.mismatch})")
        var chosen = simp
        if (simp !== raw) {
            var bad = DerivCheck.run(f, simp, v).mismatch
            if (bad == null) {
                val reparsed = try { parseExpr(DTex.tex(simp)) } catch (e: UnsupportedMath) { null }
                if (reparsed != null) bad = DerivCheck.run(f, reparsed, v).mismatch
            }
            if (bad != null) chosen = raw
        }
        if (rawCheck.checked == 0) s.note("The derivative could not be cross-checked numerically (no sample point in the domain)")
        return Computed(raw, chosen, dz.rules.toList(), dz)
    }

    private fun differentiate(p: Problem): List<Node> {
        val out = ArrayList<Node>()
        var cur = p.f
        val v = p.v
        for (k in 1..p.order) {
            val c = compute(cur, v)
            if (k == 1) firstLevelSteps(p, cur, c) else laterLevelSteps(p, k, cur, c)
            for (u in c.dz.absArgs) absNote(u, v)
            cur = c.simp
            out += cur
        }
        return out
    }

    /** u in `(u)'v`: a function call would swallow the next factor. */
    private fun wrapL(n: Node) = if (n is Node.Sum || n is Node.Neg || n is Node.Trig || n is Node.Func || n is Node.Product || n is Node.Quotient) "(${DTex.tex(n)})" else DTex.tex(n)
    private fun wrapR(n: Node) = if (n is Node.Sum || n is Node.Neg || n is Node.Product || n is Node.Quotient) "(${DTex.tex(n)})" else DTex.tex(n)
    private fun wrap(n: Node) = if (n is Node.Variable || n is Node.Number || n is Node.Const) DTex.tex(n) else "(${DTex.tex(n)})"
    private fun primeOf(n: Node) = if (n is Node.Variable || n is Node.Number) "${DTex.tex(n)}'" else "(${DTex.tex(n)})'"

    private fun captionOf(rules: Collection<String>, fallback: String) = if (rules.isEmpty()) fallback else rules.joinToString(", ")

    private fun firstLevelSteps(p: Problem, f: Node, c: Computed) {
        val v = p.v
        val dd = "\\frac{d}{d$v}"
        val head = if (p.kind == Kind.Operator) "$dd\\left(${DTex.tex(f)}\\right)" else "${lhs(p, 1)}=$dd\\left(${DTex.tex(f)}\\right)"
        step(head, "Differentiate with respect to $v")
        var core = if (f is Node.Neg) f.arg else f
        val rawTex = DTex.tex(c.raw)
        val simpTex = DTex.tex(c.simp)
        if (core is Node.Sum && core.terms.size >= 2) {
            for (t in core.terms) {
                val tz = Differentiator(v)
                val dt = tz.d(t)
                step("$dd\\left(${DTex.tex(t)}\\right)=${DTex.tex(dt)}", captionOf(tz.rules, if (v in t.variableNames()) Differentiator.POWER else "Constant rule"))
            }
            step(rawTex, "Add the derivatives of the terms (sum rule)")
        } else {
            var rules = c.rules
            if (core is Node.Product) {
                val varying = core.factors.filter { v in it.variableNames() }
                if (varying.size == 2 && core.factors.size == 2) {
                    val (u, w) = core.factors
                    step("${primeOf(u)}${wrapR(w)}+${wrapL(u)}${primeOf(w)}", Differentiator.PRODUCT)
                    rules = rules.filter { it != Differentiator.PRODUCT }
                }
            } else if (core is Node.Quotient && v in core.num.variableNames() && v in core.den.variableNames()) {
                step("\\frac{${primeOf(core.num)}${wrap(core.den)}-${wrap(core.num)}${primeOf(core.den)}}{${wrap(core.den)}^{2}}", Differentiator.QUOTIENT)
                rules = rules.filter { it != Differentiator.QUOTIENT }
            }
            val fallback = if (v !in f.variableNames()) "Constant rule" else if (core is Node.Variable) Differentiator.POWER else "Differentiate each part"
            step(rawTex, captionOf(rules, fallback))
        }
        if (simpTex != rawTex) step(simpTex, "Simplify")
        if (p.order > 1) step("${label(p, 1)}=$simpTex", "${ordinal(1)} derivative")
    }

    private fun laterLevelSteps(p: Problem, k: Int, prev: Node, c: Computed) {
        val v = p.v
        val rawTex = DTex.tex(c.raw)
        val simpTex = DTex.tex(c.simp)
        val name = "${ordinal(k)} derivative"
        if (rawTex != simpTex) {
            step("\\frac{d}{d$v}\\left(${DTex.tex(prev)}\\right)=$rawTex", "Differentiate again: " + captionOf(c.rules, Differentiator.POWER))
            step("${label(p, k)}=$simpTex", "$name: simplify")
        } else {
            step("${label(p, k)}=$simpTex", "$name: " + captionOf(c.rules, Differentiator.POWER))
        }
    }

    private fun absNote(u: Node, v: Char) {
        val shown = plainOf(DTex.tex(u))
        val roots: List<Val> = try {
            val rf = Alg(v).asRatFn(u)
            if (rf != null && rf.den.isConstant && !rf.num.isConstant) (s.polyRoots(rf.num, v) ?: emptyList()) else emptyList()
        } catch (_: UnsupportedMath) { emptyList() }
        val where = if (shown == v.toString()) "" else " (where $shown = 0)"
        if (roots.isNotEmpty()) {
            s.note("Undefined at " + roots.joinToString(" and ") { "$v = ${plainOf(it.texOrDecimal())}" } + where)
        } else if (Alg(v).asRatFn(u)?.den?.isConstant != true) {
            s.note("Undefined where $shown = 0")
        }
    }

    // ------------------------------------------------------------------ evaluation at a point

    private class PointVal(val exact: Surd?, val d: Double)

    private fun evalAt(n: Node, v: Char, a: PointVal): PointVal {
        if (a.exact != null) {
            try { val r = NodeExact.eval(n, mapOf(v to a.exact)); return PointVal(r, r.toDouble()) } catch (_: NotExact) {} catch (_: UnsupportedMath) {} catch (_: ArithmeticException) {}
        }
        return PointVal(null, NodeNum.eval(n, mapOf(v to a.d)))
    }

    /** `= 0.5` when the decimal ends, `\approx 0.866` (4 places) when it does not. */
    private fun decimalOf(s: Surd): String? {
        if (s.isRational && s.rational.isInteger) return null
        if (s.isRational) {
            val bd = try { BigDecimal(s.rational.num).divide(BigDecimal(s.rational.den)) } catch (_: ArithmeticException) { null }
            if (bd != null && bd.scale() <= 6) return "=" + bd.stripTrailingZeros().toPlainString()
        }
        return "\\approx" + Printer.num(s.toDouble(), 4)
    }

    private fun valueTex(lhs: String, x: PointVal): String {
        if (x.exact == null) return "$lhs\\approx${Printer.num(x.d, 4)}"
        return "$lhs=${Printer.surd(x.exact)}" + (decimalOf(x.exact) ?: "")
    }

    private fun pointOf(src: String): PointVal? {
        val n = parseExpr(src)
        if (n.variableNames().isNotEmpty()) return null
        return try {
            val e = NodeExact.eval(n, emptyMap())
            PointVal(e, e.toDouble())
        } catch (_: NotExact) {
            PointVal(null, NodeNum.eval(n, emptyMap()))
        } catch (u: Undefined) {
            throw SolveFailure(u.why)
        }
    }

    private fun atPoint(p: Problem, levels: List<Node>, argSrc: String): SolveResult {
        val v = p.v
        val last = levels.last()
        val shownArg = DTex.tex(parseExpr(argSrc))
        val a = pointOf(argSrc)
        if (a == null) {
            // symbolic argument: f'(a) is the derivative with the variable renamed
            val argNode = parseExpr(argSrc)
            val sub = last.rewrite { if (it is Node.Variable && it.name == v) argNode else null }
            val vars = argNode.variableNames()
            val simp = try { Alg(vars.firstOrNull() ?: v).simplify(sub) } catch (e: UnsupportedMath) { sub }
            val shown = DTex.tex(simp)
            step("${lhs(p, p.order, shownArg)}=${DTex.tex(sub)}", "Substitute $v = ${plainOf(shownArg)}")
            val a1 = Answer("${lhs(p, p.order, shownArg)}=$shown", plainOf("${lhs(p, p.order, shownArg)}=$shown"))
            return SolveResult.Solved(listOf(a1), s.steps, s.notes)
        }
        try {
            evalAt(p.f, v, a)
        } catch (u: Undefined) {
            throw SolveFailure("the function isn't defined at $v = ${plainOf(shownArg)}: ${u.why}")
        }
        val sub = last.rewrite { if (it is Node.Variable && it.name == v) parseExpr(argSrc) else null }
        step("${lhs(p, p.order, shownArg)}=${DTex.tex(sub)}", "Substitute $v = ${plainOf(shownArg)}")
        val value = try { evalAt(last, v, a) } catch (u: Undefined) {
            throw SolveFailure("${plainOf(lhs(p, p.order, shownArg))} doesn't exist: " + if (p.f.hasAbs()) "the absolute value has a corner there" else u.why)
        }
        val tex = valueTex(lhs(p, p.order, shownArg), value)
        step(tex, "Evaluate")
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }

    // ------------------------------------------------------------------ f'(x) = c

    private fun equation(p: Problem, last: Node, rhs: String): SolveResult {
        val eq = "${DTex.tex(last)}=$rhs"
        val sub = SolveSession(null, pm)
        val r = sub.run(eq)
        if (r !is SolveResult.Solved) throw SolveFailure((r as SolveResult.Failed).reason)
        val label = if (p.kind == Kind.Operator) "\\frac{d}{d${p.v}}" else lhs(p, p.order)
        step("${DTex.tex(last)}=$rhs", "Set ${plainOf(label)} = ${plainOf(rhs)} and solve")
        for (st in r.steps) s.steps += st
        for (n in r.notes) s.note(n)
        return SolveResult.Solved(r.answers, s.steps, s.notes)
    }

    // ------------------------------------------------------------------ tangent line

    private fun tangent(p: Problem, levels: List<Node>, pt: Point): SolveResult {
        if (p.order != 1) throw UnsupportedMath("a tangent line from a higher derivative")
        val v = p.v
        val df = levels[0]
        val aSrc = pt.value
        val a = pointOf(aSrc) ?: throw UnsupportedMath("a tangent point that still has an unknown")
        val aTex = DTex.tex(parseExpr(aSrc))
        val name = p.name!!
        val fa: PointVal
        val m: PointVal
        try {
            fa = evalAt(p.f, v, a)
            m = evalAt(df, v, a)
        } catch (u: Undefined) {
            throw SolveFailure("no tangent at $v = ${plainOf(aTex)}: ${u.why}")
        }
        step(valueTex("$name($aTex)", fa), "Height of the point: the function at $v = ${plainOf(aTex)}")
        step(valueTex("$name'($aTex)", m), "Slope: the derivative at $v = ${plainOf(aTex)}")
        val tex: String
        if (fa.exact != null && m.exact != null && a.exact != null) {
            val mm = m.exact
            val b = fa.exact - mm * a.exact
            fun signed(x: Surd) = if (x.signum() < 0) "-" + Printer.surd(-x) else "+" + Printer.surd(x)
            val mShown = when { mm == Surd.ONE -> ""; mm == -Surd.ONE -> "-"; mm.isSingleTerm || mm.isRational -> Printer.surd(mm); else -> "(${Printer.surd(mm)})" }
            val shift = Printer.poly(Poly.variable(v) - Poly.const(a.exact))
            val shiftShown = if (a.exact.isZero && mShown.isEmpty()) shift else if (a.exact.isZero) shift else "($shift)"
            step("y=$mShown$shiftShown" + (if (fa.exact.isZero) "" else signed(fa.exact)), "Point-slope form: y = f'(a)(x − a) + f(a)")
            val line = Poly.of(Mono.of(v), mm) + Poly.const(b)
            tex = "y=" + Printer.poly(line)
        } else {
            val mm = m.d
            val b = fa.d - mm * a.d
            step("y=${Printer.num(mm, 4)}($v-${Printer.num(a.d, 4)})+${Printer.num(fa.d, 4)}", "Point-slope form: y = f'(a)(x − a) + f(a)")
            val sign = if (b < 0) "-" else "+"
            tex = "y\\approx ${Printer.num(mm, 4)}$v$sign${Printer.num(Math.abs(b), 4)}"
            s.note("The tangent is approximate (the values at $v = ${plainOf(aTex)} are not exact)")
        }
        step(tex, "Simplify: the tangent line")
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }

    // ------------------------------------------------------------------ critical points

    private fun critical(p: Problem, df: Node) {
        val v = p.v
        val rf = alg.asRatFn(df) ?: return
        if (rf.variables.any { it != v }) return
        val c = rf.simplified().value
        if (c.num.isZero) return
        val name = p.name ?: return
        val fp = "$name'($v)"
        val sub = SolveSession(null, pm)
        // 1. f'(x) = 0
        val numRoots: List<Val>
        step("$fp=0", "Critical points: solve $name'($v) = 0")
        if (c.num.isConstant) {
            step("${Printer.poly(c.num)}\\ne0", "$fp is never 0: no critical points")
            numRoots = emptyList()
        } else {
            val eq = Eq(RatFn(c.num, Poly.ONE), RatFn(Poly.ZERO, Poly.ONE), Printer.poly(c.num), "0")
            val roots = try { sub.solveOne(eq, v, check = false) } catch (e: UnsupportedMath) { return } catch (e: SolveFailure) { return }
            for (st in sub.steps) s.steps += st
            sub.steps.clear()
            numRoots = (roots as? Roots.Some)?.values ?: emptyList()
        }
        val denRoots = if (c.den.isConstant) emptyList() else s.rootsOrEmpty(c.den, v)
        val crit = numRoots.filter { r -> denRoots.none { it.sameAs(r) } }.sortedBy { it.d }
        val critTex = crit.joinToString(",\\ ") { "$v=${it.texOrDecimal()}" }
        if (crit.isNotEmpty()) step(critTex, "Critical points")
        // 2. sign chart of f'
        val ineq = Inequalities(sub)
        ineq.chartLabel = "$name'($v)"
        val zero = RatFn(Poly.ZERO, Poly.ONE)
        val src = Printer.ratFn(c)
        val inc: List<Seg>
        val dec: List<Seg>
        try {
            inc = ineq.setOf(c, ">", zero, v, src, "0")
            for (st in sub.steps) if (st.latex != critTex) s.steps += st
            sub.steps.clear()
            dec = sub.quiet { ineq.setOf(c, "<", zero, v, src, "0") }
        } catch (e: UnsupportedMath) { return } catch (e: SolveFailure) { return }
        for (n in sub.notes) s.note(n)
        if (inc.isNotEmpty()) step("\\text{increasing on }" + inc.joinToString("\\cup ") { it.tex() }, "Increasing: $fp > 0")
        if (dec.isNotEmpty()) step("\\text{decreasing on }" + dec.joinToString("\\cup ") { it.tex() }, "Decreasing: $fp < 0")
        // 3. local maxima and minima
        val f = p.f
        val breaks = (crit + denRoots).sortedBy { it.d }
        for (r in crit) {
            val i = breaks.indexOfFirst { it === r }
            val left = signAround(c, v, breaks, i, -1)
            val right = signAround(c, v, breaks, i, +1)
            val x = PointVal(r.exact, r.d)
            val fv: PointVal? = try { evalAt(f, v, x) } catch (_: Undefined) { null } catch (_: ArithmeticException) { null }
            val at = "$v=${r.texOrDecimal()}"
            val fText = fv?.let { valueTex("$name(${r.texOrDecimal()})", it) }
            val kind = when {
                left > 0 && right < 0 -> "Local maximum"
                left < 0 && right > 0 -> "Local minimum"
                else -> null
            }
            val label = when (kind) {
                "Local maximum" -> "\\text{local max at }"
                "Local minimum" -> "\\text{local min at }"
                else -> null
            }
            if (kind != null) step("$label$at" + (fText?.let { ",\\ $it" } ?: ""), kind + (if (r.isApprox) " (approximate)" else ""))
            else step(at + (fText?.let { ",\\ $it" } ?: ""), "Not an extremum: $fp does not change sign here")
        }
        if (denRoots.isNotEmpty()) s.note("The function is not defined at " + denRoots.joinToString(", ") { "$v = ${plainOf(it.texOrDecimal())}" })
    }

    /** Sign of f' just left (-1) or right (+1) of the break point [i]. */
    private fun signAround(c: RatFn, v: Char, breaks: List<Val>, i: Int, dir: Int): Int {
        val x = breaks[i].d
        val other = if (dir < 0) breaks.getOrNull(i - 1)?.d else breaks.getOrNull(i + 1)?.d
        val t = if (other != null) (x + other) / 2 else x + dir
        val q = Rational.parse(BigDecimal(t).toPlainString())
        val sn = c.num.substitute(v, Surd.of(q)).constant.signum() * c.den.substitute(v, Surd.of(q)).constant.signum()
        return sn
    }
}
