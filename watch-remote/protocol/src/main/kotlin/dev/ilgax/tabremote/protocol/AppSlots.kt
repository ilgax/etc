package dev.ilgax.tabremote.protocol

/** APPS characteristic payload: 4 UTF-8 labels joined by '\n'; an empty line is an unset slot. */
object AppSlots {
    const val MAX_LABEL = 24

    fun encode(labels: List<String?>): ByteArray =
        List(Protocol.APP_SLOTS) { clean(labels.getOrNull(it)) }.joinToString("\n").toByteArray(Charsets.UTF_8)

    /** Tolerates malformed input: always returns exactly [Protocol.APP_SLOTS] entries. */
    fun decode(data: ByteArray?): List<String> {
        val lines = String(data ?: ByteArray(0), Charsets.UTF_8).split('\n')
        return List(Protocol.APP_SLOTS) { clean(lines.getOrNull(it)) }
    }

    private fun clean(label: String?): String {
        val t = (label ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        // Truncate by code points so surrogate pairs are never split.
        val cut = if (t.codePointCount(0, t.length) > MAX_LABEL) t.substring(0, t.offsetByCodePoints(0, MAX_LABEL)) else t
        return cut.trim()
    }
}
