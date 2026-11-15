package dev.ilgax.utils.math.recognition

/** Minimal JSON reader/writer in pure Kotlin (no org.json, so it behaves the same in JVM tests). */
object Json {
    class ParseException(message: String) : Exception(message)

    /** Returns Map<String, Any?>, List<Any?>, String, Double, Long, Boolean or null. */
    fun parse(text: String): Any? {
        val p = Reader(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("Unexpected data after JSON value at ${p.i}")
        return v
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(m: String): Nothing = throw ParseException("$m at position $i")

        fun value(): Any? {
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return v
        }

        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number $t")
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                ws(); out += value(); ws()
                when (s.getOrNull(i++)) { ',' -> continue; ']' -> return out; else -> fail("Expected , or ]") }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') fail("Expected key")
                val k = str(); ws()
                if (s.getOrNull(i++) != ':') fail("Expected :")
                ws(); out[k] = value(); ws()
                when (s.getOrNull(i++)) { ',' -> continue; '}' -> return out; else -> fail("Expected , or }") }
            }
        }
    }
}
