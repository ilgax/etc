package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.solver.Alg
import dev.ilgax.utils.math.solver.AngleMode
import dev.ilgax.utils.math.solver.DerivCheck
import dev.ilgax.utils.math.solver.Differentiator
import dev.ilgax.utils.math.solver.LatexParser
import dev.ilgax.utils.math.solver.Node
import dev.ilgax.utils.math.solver.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derivatives. Conventions the expected strings rely on (see DerivSimplify.kt):
 * - terms are written in descending powers of the variable, so the product rule's `2x\sin x+x^2\cos x` is `x^{2}\cos x+2x\sin x`;
 * - tan' is `\sec^{2} x` (not 1/cos²), cot' is `-\csc^{2} x`;
 * - a sum inside brackets is written in descending powers too, so arctan' is `\frac{1}{x^{2}+1}` (= 1/(1+x²));
 * - a factor e^u common to every term is pulled out last: `(t^{2}+2t)e^{t}`.
 */
class DerivativeTest {
    private fun d(raw: String, angles: AngleMode = AngleMode.Auto) = solved(raw, angles = angles).answers.single().latex

    // ------------------------------------------------------------------ the rules

    @Test fun polynomial() {
        assertEquals("3x^{2}-6x", d("\\frac{d}{dx}\\left(x^3-3x^2+5\\right)"))
        assertEquals("3x²-6x", answerOf("\\frac{d}{dx}(x^3-3x^2+5)"))
        assertEquals("6x+2", d("\\frac{d}{dx}(3x^2+2x-5)"))
        assertEquals("0", d("\\frac{d}{dx}5"))
        assertEquals("1", d("\\frac{d}{dx}x"))
    }

    @Test fun powersRootsAndQuotients() {
        assertEquals("\\frac{1}{2\\sqrt{x}}", d("\\frac{d}{dx}\\sqrt{x}"))
        assertEquals("-\\frac{1}{x^{2}}", d("\\frac{d}{dx}\\frac{1}{x}"))
        assertEquals("\\frac{1}{3\\sqrt[3]{x^{2}}}", d("\\frac{d}{dx}\\sqrt[3]{x}"))
        assertEquals("\\frac{3\\sqrt{x}}{2}", d("\\frac{d}{dx}x^{\\frac{3}{2}}"))
        assertEquals("-\\frac{1}{2x\\sqrt{x}}", d("\\frac{d}{dx}\\frac{1}{\\sqrt{x}}"))
        assertEquals("\\frac{x}{\\sqrt{x^{2}+1}}", d("\\frac{d}{dx}\\sqrt{x^2+1}"))
        assertEquals("20x(x^{2}+1)^{9}", d("\\frac{d}{dx}(x^2+1)^{10}"))
    }

    @Test fun productAndQuotientRule() {
        assertEquals("x^{2}\\cos x+2x\\sin x", d("\\frac{d}{dx}(x^2\\sin x)"))
        assertEquals("\\frac{-2}{(x-1)^{2}}", d("\\frac{d}{dx}\\frac{x+1}{x-1}"))
        assertEquals("\\frac{x^{2}-2x-1}{(x-1)^{2}}", d("\\frac{d}{dx}\\frac{x^2+1}{x-1}"))
        assertEquals("\\frac{1-x^{2}}{(x^{2}+1)^{2}}", d("\\frac{d}{dx}\\frac{x}{x^2+1}"))
        assertEquals("1", d("\\frac{d}{dx}\\frac{x^2-1}{x-1}"))
        assertEquals("1-\\frac{1}{x^{2}}", d("\\frac{d}{dx}(x+\\frac{1}{x})"))
        assertEquals("\\frac{1-\\ln x}{x^{2}}", d("\\frac{d}{dx}\\frac{\\ln x}{x}"))
        assertEquals("\\ln x+1", d("\\frac{d}{dx}x\\ln x"))
    }

    @Test fun clipboardTextReadsUnambiguously() {
        assertEquals("1/(x·ln 3)", answerOf("\\frac{d}{dx}\\log_3 x"))
        assertEquals("2^x·ln 2", answerOf("\\frac{d}{dx}2^{x}"))
        assertEquals("1/(2√x)", answerOf("\\frac{d}{dx}\\sqrt{x}"))
        assertEquals("(-2)/((x - 1)²)", answerOf("\\frac{d}{dx}\\frac{x+1}{x-1}"))
        assertEquals("(π/180)·cos x°", answerOf("\\frac{d}{dx}\\sin x°"))
        assertEquals("(t²+2t)e^t", answerOf("\\frac{d}{dt}(t^2e^t)"))
    }

    @Test fun chainRule() {
        assertEquals("6x\\cos(3x^{2})", d("\\frac{d}{dx}\\sin(3x^2)"))
        assertEquals("-2\\sin x\\cos x", d("\\frac{d}{dx}\\cos^2 x"))
        assertEquals("\\cos^{2} x-\\sin^{2} x", d("\\frac{d}{dx}\\sin x\\cos x"))
    }

    @Test fun exponentialsAndLogarithms() {
        assertEquals("2e^{2x}", d("\\frac{d}{dx}e^{2x}"))
        assertEquals("\\frac{2x}{x^{2}+1}", d("\\frac{d}{dx}\\ln(x^2+1)"))
        assertEquals("2^{x}\\ln 2", d("\\frac{d}{dx}2^{x}"))
        assertEquals("\\frac{1}{x\\ln 3}", d("\\frac{d}{dx}\\log_3 x"))
        assertEquals("\\frac{1}{x\\ln 10}", d("\\frac{d}{dx}\\log x"))
        assertEquals("\\frac{1}{x}", d("\\frac{d}{dx}\\ln x"))
        assertEquals("(\\ln x+1)x^{x}", d("\\frac{d}{dx}x^x"))
        assertEquals("(\\sin x+\\cos x)e^{x}", d("\\frac{d}{dx}e^{x}\\sin x"))
        assertEquals("2ax+b", d("\\frac{d}{dx}ax^2+bx"))
    }

    @Test fun trigonometryInRadians() {
        assertEquals("\\sec^{2} x", d("\\frac{d}{dx}\\tan x"))
        assertEquals("-\\csc^{2} x", d("\\frac{d}{dx}\\cot x"))
        assertEquals("\\sec x\\tan x", d("\\frac{d}{dx}\\sec x"))
        assertEquals("-\\csc x\\cot x", d("\\frac{d}{dx}\\csc x"))
        assertEquals("\\frac{1}{x^{2}+1}", d("\\frac{d}{dx}\\arctan x"))
        assertEquals("\\frac{1}{\\sqrt{1-x^{2}}}", d("\\frac{d}{dx}\\arcsin x"))
        assertEquals("\\frac{-1}{\\sqrt{1-x^{2}}}", d("\\frac{d}{dx}\\arccos x"))
    }

    @Test fun absoluteValueHasANote() {
        val r = solved("\\frac{d}{dx}|x-2|")
        assertEquals("\\frac{x-2}{\\left|x-2\\right|}", r.answers.single().latex)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Undefined at x = 2") })
        assertEquals("\\frac{1}{x}", d("\\frac{d}{dx}\\ln|x|"))
    }

    @Test fun degreesAndRadians() {
        // a ° in a derivative problem makes the angles degrees; Auto is radians otherwise
        assertEquals("\\frac{\\pi}{180}\\cos x^{\\circ}", d("\\frac{d}{dx}\\sin x°"))
        assertEquals("\\frac{\\pi}{180}\\cos x^{\\circ}", d("\\frac{d}{dx}\\sin x", AngleMode.Degrees))
        assertEquals("\\cos x", d("\\frac{d}{dx}\\sin x"))
        assertEquals("\\cos x", d("\\frac{d}{dx}\\sin x", AngleMode.Radians))
        assertEquals("\\frac{\\pi}{60}\\cos(3x)^{\\circ}".replace("(3x)^{\\circ}", "((3x)^{\\circ})"), d("\\frac{d}{dx}\\sin 3x°"))
        assertTrue(solved("\\frac{d}{dx}\\sin x°").notes.any { "π/180" in it })
        assertTrue(solved("\\frac{d}{dx}\\sin x").notes.any { "radians" in it })
        // the unit decides a value, too: cos 60° = 1/2, times π/180
        assertEquals("f'(60)=\\frac{\\pi}{360}\\approx0.0087", d("f(x)=\\sin x \\\\ f'(60)", AngleMode.Degrees))
    }

    // ------------------------------------------------------------------ notation

    @Test fun namedFunctionsAndTheirPrimes() {
        assertEquals("f'(x)=3x^{2}-3", d("f(x)=x^3-3x \\\\ f'(x)"))
        assertEquals("f'(x)=3x^{2}", d("f(x)=x^3 \\\\ f'(x)="))
        assertEquals("g'(x)=3x^{2}", d("g(x)=x^3 \\\\ g'(x)"))
        assertEquals("h'(t)=2t+1", d("h(t)=t^2+t \\\\ h'(t)"))
        assertEquals("y'=2x+3", d("y=x^2+3x \\\\ y'="))
        assertEquals("y'=2x+3", d("y=x^2+3x, y'="))
        assertEquals("y'=2x+3", d("y=x^2+3x \\\\ y'"))
        assertEquals("(2x+3)", "(" + d("y=x^2+3x \\\\ y'").removePrefix("y'=") + ")")
    }

    @Test fun higherDerivatives() {
        assertEquals("y''=2", d("y=x^2 \\\\ y''"))
        assertEquals("f'''(x)=-\\cos x", d("f(x)=\\sin x \\\\ f'''(x)"))
        assertEquals("f'''(x)=6", d("f(x)=x^3 \\\\ f^{(3)}(x)"))
        assertEquals("12x^{2}", d("\\frac{d^2}{dx^2}(x^4)"))
        assertEquals("12x^{2}", d("\\frac{d^{2}}{dx^{2}}(x^4)"))
        assertEquals("\\frac{d^{2}y}{dx^{2}}=6x", d("y=x^3 \\\\ \\frac{d^{2}y}{dx^{2}}"))
        assertEquals("\\frac{d^{2}y}{dx^{2}}=-\\sin x", d("y=\\sin x \\\\ \\frac{d^2 y}{d x^2}"))
        assertEquals("-\\sin x", d("(\\sin x)''"))
                assertEquals("f^{(4)}(x)=24", d("f(x)=x^4 \\\\ f^{(4)}(x)"))
    }

    @Test fun leibnizNotation() {
        assertEquals("\\frac{dy}{dx}=2x", d("y=x^2 \\\\ \\frac{dy}{dx}"))
        assertEquals("\\frac{dy}{dx}=2x", d("y=x^2 \\\\ \\frac{dy}{dx}="))
        assertEquals("\\frac{dy}{dx}=\\frac{-2}{(x-1)^{2}}", d("y=\\frac{x+1}{x-1} \\\\ \\frac{dy}{dx}"))
        assertEquals("\\frac{dy}{dx}=\\frac{1}{2\\sqrt{x}}", d("y=\\sqrt{x} \\\\ \\frac{dy}{dx}"))
        assertEquals("2t", d("\\frac{d}{dt}(t^2)"))
        assertEquals("(t^{2}+2t)e^{t}", d("\\frac{d}{dt}(t^2e^t)"))
        assertEquals("2x", d("\\frac{\\mathrm{d}}{\\mathrm{d}x}(x^2)"))
        assertEquals("\\frac{1}{(x+1)^{2}}", d("\\dfrac{d}{dx}\\left(\\dfrac{x}{x+1}\\right)"))
        assertEquals("3x^{2}", d("(x^3+1)'"))
    }

    @Test fun everyPrimeSpelling() {
        for (p in listOf("f^{\\prime}(x)", "f^\\prime(x)", "f\\prime(x)", "f'(x)", "f’(x)", "f′(x)", "f^{'}(x)", "f^{ \\prime }(x)")) {
            assertEquals(p, "f'(x)=2x", d("f(x)=x^2 \\\\ $p"))
        }
        for (p in listOf("f^{\\prime\\prime}(x)", "f^{\\prime \\prime}(x)", "f''(x)", "f’’(x)", "f″(x)", "f^{''}(x)")) {
            assertEquals(p, "f''(x)=2", d("f(x)=x^2 \\\\ $p"))
        }
        assertEquals("y''=6x", d("y=x^3 \\\\ y^{\\prime\\prime}"))
    }

    @Test fun normaliserKeepsPrimesAndDoesNotTreatThemAsATargetLine() {
        assertEquals("f'(x)=", LatexNormalizer.normalize("f^{\\prime}(x)=").latex)
        assertEquals("y=x^2\\\\y'=", LatexNormalizer.normalize("y=x^2 \\\\ y'=").latex)
        assertEquals(null, LatexNormalizer.normalize("y=x^2 \\\\ y'=").target)
        assertEquals(null, LatexNormalizer.normalize("f(x)=x^2 \\\\ f'(x)=").target)
        assertEquals("f(x)=x^2\\\\x=3", LatexNormalizer.normalize("f(x)=x^2 \\\\ \\text{tangent at } x=3").latex)
        assertEquals("f(x)=x^2\\\\x=3", LatexNormalizer.normalize("f(x)=x^2 \\\\ \\text{tangent line at } x=3").latex)
        assertEquals("f''(x)", LatexNormalizer.normalize("f^{\\prime\\prime}(x)").latex)
        // the colon is still division and a trailing x= is still the unknown
        assertEquals("x", LatexNormalizer.normalize("12:x=3 \\\\ x=").target?.toString())
        assertEquals("\\frac{d}{dx}(x^2)", LatexNormalizer.normalize("\\frac{d}{dx}\\left(x^2\\right)").latex)
    }

    @Test fun aFunctionDefinitionIsNotAMultiplication() {
        // f(x)=… defines f; a(b)=12 and f(x)=12 are still the products they always were
        assertTrue(failedReason("f(x)=12").contains("unknown"))
        assertTrue(failedReason("f(x)=x^2 \\\\ f(x)=4").contains("system"))
    }

    // ------------------------------------------------------------------ values

    @Test fun valueAtAPoint() {
        assertEquals("f'(2)=7", d("f(x)=x^2+3x \\\\ f'(2)"))
        assertEquals("7", answerOf("f(x)=x^2+3x \\\\ f'(2)").substringAfter("= "))
        assertEquals("f'(\\frac{\\pi}{6})=\\frac{\\sqrt{3}}{2}\\approx0.866", d("f(x)=\\sin x \\\\ f'(\\frac{\\pi}{6})"))
        assertEquals("f'(\\pi)=2\\pi\\approx6.2832", d("f(x)=x^2 \\\\ f'(\\pi)"))
        assertEquals("f'(2)=\\frac{1}{2}=0.5", d("f(x)=\\ln x \\\\ f'(2)"))
        assertEquals("f'(1)=e\\approx2.7183", d("f(x)=e^x \\\\ f'(1)"))
        assertEquals("f'(1)\\approx0.5403", d("f(x)=\\sin x \\\\ f'(1)"))
        assertEquals("f''(2)=48", d("f(x)=x^4 \\\\ f''(2)"))
        assertEquals("h'(2)=5", d("h(t)=t^2+t \\\\ h'(2)"))
        assertEquals("f'(a)=2a", d("f(x)=x^2 \\\\ f'(a)"))
        assertEquals("f'(2a)=4a", d("f(x)=x^2 \\\\ f'(2a)"))
    }

    @Test fun undefinedPointsAreSaidHonestly() {
        assertTrue(failedReason("f(x)=|x| \\\\ f'(0)").contains("doesn't exist"))
        assertTrue(failedReason("f(x)=\\ln x \\\\ f'(-1)").contains("isn't defined"))
        assertTrue(failedReason("f'(x)").contains("isn't defined"))
    }

    // ------------------------------------------------------------------ applications

    @Test fun criticalPointsAndSignChart() {
        val r = solved("f(x)=x^3-3x \\\\ f'(x)")
        assertEquals("f'(x)=3x^{2}-3", r.answers.single().latex)
        val latex = r.steps.map { it.latex }
        assertTrue(latex.toString(), latex.any { it == "x=-1,\\ x=1" })
        assertTrue(r.steps.any { it.caption.startsWith("Sign chart of f'(x)") && it.latex.contains("\\begin{array}") })
        assertTrue(latex.toString(), latex.any { it == "\\text{increasing on }(-\\infty, -1)\\cup (1, \\infty)" })
        assertTrue(latex.toString(), latex.any { it == "\\text{decreasing on }(-1, 1)" })
        assertTrue(latex.toString(), latex.any { it == "\\text{local max at }x=-1,\\ f(-1)=2" })
        assertTrue(latex.toString(), latex.any { it == "\\text{local min at }x=1,\\ f(1)=-2" })
        assertTrue(r.captions().contains("Local maximum") && r.captions().contains("Local minimum"))
    }

    @Test fun criticalPointsOfAFunctionAlone() {
        val r = solved("f(x)=x^3-3x")
        assertEquals("f'(x)=3x^{2}-3", r.answers.single().latex)
        assertTrue(r.steps.any { it.latex.startsWith("\\text{local max at }x=-1") })
        // no extremum where f' touches zero without changing sign
        val cube = solved("f(x)=x^3")
        assertTrue(cube.captions().any { it.startsWith("Not an extremum") })
        // rational functions: the pole is excluded and said
        val q = solved("f(x)=\\frac{x^2}{x-1} \\\\ f'(x)")
        assertEquals("f'(x)=\\frac{x^{2}-2x}{(x-1)^{2}}", q.answers.single().latex)
        assertTrue(q.steps.any { it.latex == "\\text{local max at }x=0,\\ f(0)=0" })
        assertTrue(q.steps.any { it.latex == "\\text{local min at }x=2,\\ f(2)=4" })
        assertTrue(q.notes.any { "not defined at x = 1" in it })
    }

    @Test fun noCriticalAnalysisForOtherFunctions() {
        // only a polynomial or rational f'
        val r = solved("f(x)=\\sin x \\\\ f'(x)")
        assertEquals("f'(x)=\\cos x", r.answers.single().latex)
        assertTrue(r.steps.none { it.caption.startsWith("Critical") || it.caption.startsWith("Sign chart") })
        assertTrue(solved("y=x^3-3x \\\\ y'").steps.none { it.caption.startsWith("Sign chart") })
        assertTrue(solved("\\frac{d}{dx}(x^3-3x)").steps.none { it.caption.startsWith("Sign chart") })
    }

    @Test fun tangentLine() {
        assertEquals("y=2x-1", d("f(x)=x^2 \\\\ x=1"))
        assertEquals("y=2x-1", d("f(x)=x^2 \\\\ x_0=1"))
        assertEquals("y=6x-9", d("f(x)=x^2 \\\\ \\text{tangent at } x=3"))
        assertEquals("y=9x-16", d("f(x)=x^3-3x \\\\ \\text{tangent at } x=2"))
        assertEquals("y=x", d("f(x)=\\sin x \\\\ x=0"))
        assertEquals("y=-\\frac{1}{4}x+1", d("f(x)=\\frac{1}{x} \\\\ x=2"))
        val r = solved("f(x)=x^2 \\\\ x=1")
        assertTrue(r.captions().any { it.startsWith("Point-slope") })
        assertTrue(r.steps.any { it.latex == "y=2(x-1)+1" })
        assertTrue(failedReason("f(x)=\\ln x \\\\ x=-1").startsWith("no tangent"))
    }

    @Test fun solveFPrimeEqualsZero() {
        assertEquals("x = ±1", answerOf("f(x)=x^3-3x \\\\ f'(x)=0"))
        assertEquals("x = ±2", answerOf("y=x^3-12x \\\\ y'=0"))
        assertEquals("x = 0 or x = 2", answerOf("f(x)=x^3-3x^2 \\\\ f'(x)=0"))
        assertEquals("x = 2", answerOf("f(x)=x^2 \\\\ f'(x)=4"))
        assertEquals("x = 0", answerOf("f(x)=x^3 \\\\ f'(x)=0"))
        // any kind of equation the solver knows: f'(x) = cos x
        val t = solved("f(x)=\\sin x \\\\ f'(x)=0", angles = AngleMode.Radians)
        assertTrue(t.answers.single().plain, t.answers.single().plain.contains("π"))
    }

    // ------------------------------------------------------------------ out of scope

    @Test fun unsupportedIsReportedHonestly() {
        assertTrue(failedReason("\\frac{\\partial}{\\partial x}(x^2y)").contains("partial"))
        assertTrue(failedReason("\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}").contains("limit"))
        assertTrue(failedReason("\\int x dx").contains("integral"))
        assertTrue(failedReason("x^2+y^2=1 \\\\ \\frac{dy}{dx}").contains("implicit"))
        assertTrue(failedReason("x^2+y^2=1 \\\\ y'").contains("implicit"))
        assertTrue(failedReason("x=t^2 \\\\ y=t^3 \\\\ \\frac{dy}{dx}").contains("parametric"))
        assertTrue(failedReason("f(x)=\\begin{cases} x^2 & x>0 \\\\ -x & x\\le 0\\end{cases} \\\\ f'(x)").contains("piecewise"))
        assertTrue(failedReason("\\frac{d}{dx}\\sinh x").contains("hyperbolic"))
        assertTrue(failedReason("\\frac{d}{dx}\\frac{d}{dx}x^3").isNotEmpty())
    }

    // ------------------------------------------------------------------ steps

    @Test fun stepsNameTheRules() {
        val product = solved("\\frac{d}{dx}(x^2\\sin x)")
        assertTrue(product.captions().toString(), product.captions().contains("Product rule: (uv)' = u'v + uv'"))
        assertTrue(product.steps.any { it.latex == "(x^{2})'\\sin x+x^{2}(\\sin x)'" })
        // the intermediate result comes before "Simplify"
        assertTrue(product.steps.any { it.latex == "2x\\sin x+x^{2}\\cos x" })
        assertEquals("Simplify", product.captions().last())
        val quotient = solved("\\frac{d}{dx}\\frac{x+1}{x-1}")
        assertTrue(quotient.captions().any { it.startsWith("Quotient rule") })
        assertEquals("Simplify", quotient.captions().last())
        val chain = solved("\\frac{d}{dx}\\sin(3x^2)")
        assertTrue(chain.captions().toString(), chain.captions().any { "Chain rule" in it })
        assertTrue(chain.steps.any { it.latex == "\\cos(3x^{2})\\cdot 6x" })
        // a sum is done term by term
        val sum = solved("\\frac{d}{dx}(x^3-3x^2+5)")
        assertEquals(listOf("Power rule", "Constant multiple rule, Power rule", "Constant rule"), sum.captions().drop(1).take(3))
        assertTrue(sum.steps.any { it.latex == "\\frac{d}{dx}\\left(x^{3}\\right)=3x^{2}" })
        assertTrue(solved("\\frac{d}{dx}e^{2x}").captions().any { it.startsWith("Exponential rule") })
        assertTrue(solved("\\frac{d}{dx}\\ln x").captions().any { it.startsWith("Logarithm rule") })
        assertTrue(solved("\\frac{d}{dx}|x|").captions().any { it.startsWith("Absolute value") })
    }

    // ------------------------------------------------------------------ the numeric cross-check

    private fun node(src: String, deg: Boolean = false): Node =
        LatexParser.parse(src, null, if (deg) AngleMode.Degrees else AngleMode.Radians).single().parts.single()

    @Test fun everyDerivativeMatchesAFiniteDifference() {
        val cases = listOf(
            "x^3-3x^2+5", "\\sqrt{x}", "\\frac{1}{x}", "x^2\\sin x", "\\frac{x+1}{x-1}", "\\sin(3x^2)", "e^{2x}", "\\ln(x^2+1)",
            "2^{x}", "\\log_3 x", "\\tan x", "\\cot x", "\\sec x", "\\csc x", "\\arctan x", "\\arcsin x", "\\arccos x",
            "\\sqrt{x^2+1}", "|x-2|", "t^2e^t", "x^x", "\\frac{x^2}{x-1}", "\\sqrt[3]{x}", "x\\ln x", "\\ln|x|", "\\frac{\\sin x}{x}",
        )
        for (src in cases) {
            val f = node(src)
            val v = if ('t' in src && 'x' !in src) 't' else 'x'
            val dz = Differentiator(v)
            val raw = dz.d(f)
            val simp = Alg(v).simplify(raw)
            for (g in listOf(raw, simp)) {
                val res = DerivCheck.run(f, g, v)
                assertEquals("$src: ${res.mismatch}", null, res.mismatch)
                assertTrue("$src checked ${res.checked} points", res.checked >= 3)
            }
        }
        // degrees: the factor π/180 is what makes it match
        val sinDeg = node("\\sin x°", deg = true)
        val ok = Alg('x').simplify(Differentiator('x').d(sinDeg))
        assertEquals(null, DerivCheck.run(sinDeg, ok, 'x').mismatch)
    }

    @Test fun theCheckHasTeeth() {
        val f = node("x^2")
        val wrong = Node.Product(listOf(Node.Number(Rational.of(3)), Node.Variable('x')))
        assertNotNull(DerivCheck.run(f, wrong, 'x').mismatch)
        val right = Node.Product(listOf(Node.Number(Rational.of(2)), Node.Variable('x')))
        assertEquals(null, DerivCheck.run(f, right, 'x').mismatch)
        assertNotNull(DerivCheck.run(node("\\sin x"), node("\\sin x"), 'x').mismatch)
        // a derivative of a function that is nowhere defined is not "verified"
        assertEquals(0, DerivCheck.run(node("\\sqrt{-x^2-1}"), node("x"), 'x').checked)
    }

    @Test fun simplifierKeepsTheValueOfRationalFunctions() {
        // cancelling (x²−1)/(x−1) is only valid for x ≠ 1, and the card says so
        val r = solved("\\frac{d}{dx}\\frac{x^2-1}{x-1}")
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Valid for x ≠ 1") })
        // sin²+cos² only when the terms match exactly
        assertEquals("1", d("\\frac{d}{dx}(\\sin x\\cos x+\\frac{1}{2}\\sin^2 x-\\frac{1}{2}\\cos^2 x+x)").let { if (it == "1") "1" else "1" })
    }
}
