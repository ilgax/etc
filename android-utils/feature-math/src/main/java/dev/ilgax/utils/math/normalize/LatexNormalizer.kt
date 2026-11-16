package dev.ilgax.utils.math.normalize

/** What a "digit . digit" in the recognised text means. The user writes a raised dot to multiply. */
enum class DotMeaning { Multiply, Decimal }

/** A percent sign the normaliser rewrote: [number] is the digits, [prefix] says whether the sign came first (`%20`, as in Turkish). */
data class PercentUse(val number: String, val prefix: Boolean) {
    /** What was written, as LaTeX. */
    val written get() = if (prefix) "\\%$number" else "$number\\%"
    val plainWritten get() = if (prefix) "%$number" else "$number%"
}

/**
 * Cleaned LaTeX for the solver; [target] is the unknown named by a trailing "x=" line, if any. [percents] lists the
 * percentages that were turned into `(n/100)`, [notes] what was read in a particular way.
 */
data class NormalizedInput(val latex: String, val target: Char?, val percents: List<PercentUse> = emptyList(), val notes: List<String> = emptyList())

/**
 * Turns what a handwriting model typically says into what the user meant. Pure Kotlin, no Android.
 * The user never writes × for multiplication: `\times` is the variable x unless [timesIsVariable] is off.
 */
object LatexNormalizer {
    private val droppedCommands = Regex("""(?<!\\)\\(?:left|right|displaystyle|textstyle|scriptstyle|bigl|bigr|Bigl|Bigr|biggl|biggr|big|Big|bigg|Bigg|quad|qquad|hfill|hline|newline)(?![a-zA-Z])""")
    private val spacing = Regex("""(?<!\\)\\[,;:! ]|~""")
    private val wrappers = Regex("""(?<!\\)\\[\[\]()]""")
    private val envs = Regex("""\\(?:begin|end)\{(?:array|aligned|align\*?|gathered|gather\*?|matrix|split|cases|eqnarray\*?|equation\*?)\}(?:\{[lcr| ]*\})?""")
    private val textLike = Regex("""\\(mathrm|mathit|mathbf|text|textrm|operatorname)\s*\{([^{}]*)\}""")
    private val bareFunctions = Regex("""(?<![A-Za-z\\])(ln|log|exp|arcsin|arccos|arctan|sin|cos|tan|cot|sec|csc)(?![A-Za-z])""")
    private val degreeSigns = Regex("""\^\s*\{\s*\\circ\s*\}|\^\s*\\circ(?![a-zA-Z])|\\degree(?![a-zA-Z])|\\textdegree(?![a-zA-Z])|\\circ(?![a-zA-Z])|º""")
    private val normBars = Regex("""\\(?:lVert|rVert|Vert)(?![a-zA-Z])|\\\|""")
    private val bars = Regex("""\\(?:lvert|rvert|vert|mid)(?![a-zA-Z])""")
    /** `20%`, `20\%`, `20 \%`: the number comes first. */
    private val percentSuffix = Regex("""(\d+(?:\.\d+)?)\s*\\?[%％]""")
    /** `%20`, `\%20`, `\% 20` (Turkish: the sign comes first). */
    private val percentPrefix = Regex("""\\?[%％]\s*(\d+(?:\.\d+)?)""")
    private val digitDotDigit = Regex("""(?<=\d)\s*\.\s*(?=\d)""")
    private val targetLine = Regex("""^\s*([A-Za-z])\s*=\s*$""")

    fun normalize(raw: String, dot: DotMeaning = DotMeaning.Multiply, timesIsVariable: Boolean = true): NormalizedInput {
        var s = raw.replace("$", "").replace("\r", "\n")
        s = envs.replace(s, " ")
        s = s.replace("&", " ")
        s = wrappers.replace(s, " ")
        s = droppedCommands.replace(s, " ")
        s = spacing.replace(s, " ")
        // \mathrm{e} is Euler's number whatever the other letters are; other wrappers are dropped
        s = textLike.replace(s) {
            val inner = it.groupValues[2]
            if (inner.trim() == "e" && it.groupValues[1] in setOf("mathrm", "operatorname")) "\\mathrm{e}" else inner
        }
        s = bareFunctions.replace(s) { "\\" + it.groupValues[1] }
        s = degreeSigns.replace(s, "°")
        // vertical bars: every spelling becomes |; double bars (norms) are read as absolute values
        val notes = ArrayList<String>()
        if (normBars.containsMatchIn(s) || s.contains('‖')) notes += "Double bars ‖…‖ are read as an absolute value |…|"
        s = normBars.replace(s, "|").replace("‖", "|")
        s = bars.replace(s, "|")

        s = s.replace(Regex("""\\[dt]frac(?![a-zA-Z])"""), "\\\\frac")
        s = s.replace(Regex("""\\ast(?![a-zA-Z])"""), "\\\\cdot").replace("*", "\\cdot ")
        s = s.replace("·", "\\cdot ").replace("⋅", "\\cdot ").replace("∙", "\\cdot ")
        // division: \div, ÷ and the colon (`12 : 4`) all behave like `/`
        s = s.replace(Regex("""\\div(?![a-zA-Z])"""), "/").replace("÷", "/")
        s = s.replace(Regex("""\\colon(?![a-zA-Z])"""), "/")
        // relations
        s = s.replace(Regex("""\\(?:leqslant|leq|le)(?![a-zA-Z])""")) { "\\le " }.replace("≤", "\\le ").replace("<=", "\\le ")
        s = s.replace(Regex("""\\(?:geqslant|geq|ge)(?![a-zA-Z])""")) { "\\ge " }.replace("≥", "\\ge ").replace(">=", "\\ge ")
        s = s.replace(Regex("""\\lt(?![a-zA-Z])"""), "<").replace(Regex("""\\gt(?![a-zA-Z])"""), ">")
        s = s.replace("−", "-").replace("–", "-")
        val times = if (timesIsVariable) " x " else "\\cdot "
        s = s.replace(Regex("""\\times(?![a-zA-Z])""")) { times }.replace("×", times)
        if (dot == DotMeaning.Multiply) s = digitDotDigit.replace(s, "\\\\cdot ")
        // percent: 20% and %20 are both the exact fraction 20/100
        val percents = ArrayList<PercentUse>()
        s = percentSuffix.replace(s) { percents += PercentUse(it.groupValues[1], false); "(${it.groupValues[1]}/100)" }
        s = percentPrefix.replace(s) { percents += PercentUse(it.groupValues[1], true); "(${it.groupValues[1]}/100)" }

        // lines: `\\` and newlines separate them
        val lines = s.split("\\\\", "\n").map { it.trim().trimEnd('.', ',', ';', ':').trim().replace(':', '/') }.filter { it.isNotEmpty() }.toMutableList()
        var target: Char? = null
        if (lines.size > 1) {
            targetLine.matchEntire(lines.last())?.let { target = it.groupValues[1][0]; lines.removeAt(lines.size - 1) }
        }
        return NormalizedInput(lines.joinToString("\\\\") { compact(it) }, target, percents.distinct(), notes)
    }

    /** Removes spaces, keeping the one after a `\command` that is followed by a letter (`\cdot x`). */
    private fun compact(s: String): String {
        val out = StringBuilder()
        var inCommand = false
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) { pendingSpace = inCommand; i++; continue }
            if (pendingSpace && c.isLetter()) out.append(' ')
            pendingSpace = false
            if (c == '\\' && i + 1 < s.length && s[i + 1].isLetter()) {
                inCommand = true
            } else if (!(inCommand && c.isLetter())) {
                inCommand = false
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
