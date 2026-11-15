package dev.ilgax.utils.math.recognition

/** Post-processing of raw model text (manifest `postprocess`). Pure Kotlin. */
object LatexJoin {
    /**
     * The model emits tokens separated by spaces (`\frac { 3 } { 4 }`). Joins them while keeping the one
     * space that LaTeX needs, after a `\command` followed by a letter: `\frac{3}{4}`, `\cdot x`.
     */
    fun join(text: String): String {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && endsWithCommandName(sb) && p[0].isLetter()) sb.append(' ')
            sb.append(p)
        }
        return sb.toString()
    }

    private fun endsWithCommandName(sb: CharSequence): Boolean {
        var i = sb.length - 1
        while (i >= 0 && sb[i].isLetter()) i--
        return i < sb.length - 1 && i >= 0 && sb[i] == '\\' && !(i > 0 && sb[i - 1] == '\\')
    }

    fun stripWrappers(text: String, wrappers: List<String>): String {
        var s = text.trim()
        var changed = true
        while (changed) {
            changed = false
            for (w in wrappers) {
                if (w.isEmpty()) continue
                if (s.startsWith(w)) { s = s.removePrefix(w).trim(); changed = true }
                if (s.endsWith(w)) { s = s.removeSuffix(w).trim(); changed = true }
            }
        }
        return s
    }
}
