package dev.ilgax.utils.math.recognition

import java.io.ByteArrayOutputStream

/**
 * Id -> text for HF tokenizer.json files (decode only). Handles byte-level BPE (`Ġ`), SentencePiece
 * (`▁`, `<0xNN>` byte tokens) and plain vocabularies, and skips special tokens.
 */
class TokenizerDecoder private constructor(
    private val tokens: Array<String?>,
    private val special: Set<Int>,
    private val byteLevel: Boolean,
) {
    val vocabSize get() = tokens.size

    fun decode(ids: List<Int>, skipSpecial: Boolean = true): String {
        val pieces = ids.mapNotNull { id ->
            if (skipSpecial && id in special) null else tokens.getOrNull(id)
        }
        val joined = pieces.joinToString("")
        return if (byteLevel) decodeBytes(joined) else decodeSentencePiece(pieces)
    }

    private fun decodeBytes(s: String): String {
        val out = ByteArrayOutputStream()
        for (ch in s) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) out.write(b) else out.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun decodeSentencePiece(pieces: List<String>): String {
        val out = ByteArrayOutputStream()
        for (p in pieces) {
            val m = BYTE_TOKEN.matchEntire(p)
            if (m != null) out.write(m.groupValues[1].toInt(16)) else out.write(p.replace('▁', ' ').toByteArray(Charsets.UTF_8))
        }
        return out.toString(Charsets.UTF_8.name()).removePrefix(" ")
    }

    companion object {
        private val BYTE_TOKEN = Regex("""<0x([0-9A-Fa-f]{2})>""")

        /** GPT-2 byte <-> printable unicode table, inverted. */
        private val UNICODE_TO_BYTE: Map<Char, Int> by lazy {
            val bs = ArrayList<Int>()
            bs += 33..126; bs += 161..172; bs += 174..255
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }

        fun parse(json: String): TokenizerDecoder {
            val root = Json.parse(json) as? Map<*, *> ?: throw ManifestException("tokenizer.json must be a JSON object")
            val model = root["model"] as? Map<*, *> ?: throw ManifestException("tokenizer.json has no \"model\"")
            val vocab = model["vocab"]
            val special = HashSet<Int>()
            val added = (root["added_tokens"] as? List<*>) ?: emptyList<Any?>()
            var size = 0
            val entries = ArrayList<Pair<Int, String>>()
            when (vocab) {
                is Map<*, *> -> vocab.forEach { (k, v) -> entries += (v as Number).toInt() to k as String }
                is List<*> -> vocab.forEachIndexed { i, e -> entries += i to ((e as? List<*>)?.firstOrNull() as? String ?: "") }
                else -> throw ManifestException("tokenizer.json has no vocabulary")
            }
            for (a in added) {
                val m = a as? Map<*, *> ?: continue
                val id = (m["id"] as? Number)?.toInt() ?: continue
                entries += id to (m["content"] as? String ?: "")
                if (m["special"] == true) special += id
            }
            size = (entries.maxOfOrNull { it.first } ?: -1) + 1
            val tokens = arrayOfNulls<String>(size)
            for ((id, t) in entries) if (id >= 0) tokens[id] = t
            val decoderText = Json.quote("") + (root["decoder"]?.toString() ?: "")
            val byteLevel = decoderText.contains("ByteLevel") || (root["pre_tokenizer"]?.toString()?.contains("ByteLevel") == true)
            return TokenizerDecoder(tokens, special, byteLevel)
        }
    }
}
