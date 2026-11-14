package dev.ilgax.portallogin

object UrlNormalizer {
    /** Trims the input and prefixes `http://` when no http(s) scheme is present. Null if blank. */
    fun normalize(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "http://$s"
    }
}
