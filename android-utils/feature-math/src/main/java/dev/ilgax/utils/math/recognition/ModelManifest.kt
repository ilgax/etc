package dev.ilgax.utils.math.recognition

import java.io.File

enum class ResizeMode(val json: String) { Stretch("stretch"), KeepAspectPad("keep_aspect_pad") }
enum class DecoderCache(val json: String) { None("none"), MergedPast("merged_past") }

data class InputSpec(
    val width: Int,
    val height: Int,
    val channels: Int,
    val resize: ResizeMode,
    /** Gray level 0..255 used for padding (keep_aspect_pad). */
    val padColor: Int,
    val mean: List<Float>,
    val std: List<Float>,
)

data class PostprocessSpec(val joinSpacedTokens: Boolean, val stripWrappers: List<String>)

/** Contents of model.json: everything the generic runner needs to run an encoder/decoder pair. */
data class ModelManifest(
    val name: String,
    val encoder: String,
    val decoder: String,
    val tokenizer: String,
    val input: InputSpec,
    val bos: Int,
    val eos: Int,
    val pad: Int,
    val maxTokens: Int,
    val decoderCache: DecoderCache,
    val postprocess: PostprocessSpec,
) {
    companion object {
        const val FILE_NAME = "model.json"

        /** Parses and validates; throws [ManifestException] with a message fit for the user. */
        fun parse(text: String): ModelManifest {
            val root = try { Json.parse(text) } catch (e: Json.ParseException) { throw ManifestException("model.json is not valid JSON (${e.message})") }
            val o = root as? Map<*, *> ?: throw ManifestException("model.json must be a JSON object")

            fun str(m: Map<*, *>, key: String, where: String = ""): String =
                (m[key] as? String)?.takeIf { it.isNotBlank() } ?: throw ManifestException("Missing or empty \"$where$key\" in model.json")

            fun int(m: Map<*, *>, key: String, where: String = "", default: Int? = null): Int {
                val v = m[key] ?: return default ?: throw ManifestException("Missing \"$where$key\" in model.json")
                return (v as? Long)?.toInt() ?: throw ManifestException("\"$where$key\" must be an integer")
            }

            fun floats(m: Map<*, *>, key: String, channels: Int, default: Float): List<Float> {
                val v = m[key] ?: return List(channels) { default }
                val list = (v as? List<*>)?.map { (it as? Number)?.toFloat() ?: throw ManifestException("\"input.$key\" must be numbers") }
                    ?: throw ManifestException("\"input.$key\" must be a list of numbers")
                return when (list.size) {
                    1 -> List(channels) { list[0] }
                    channels -> list
                    else -> throw ManifestException("\"input.$key\" needs $channels values (or 1), found ${list.size}")
                }
            }

            val name = str(o, "name")
            val encoder = str(o, "encoder")
            val decoder = str(o, "decoder")
            val tokenizer = str(o, "tokenizer")
            for ((key, f) in listOf("encoder" to encoder, "decoder" to decoder, "tokenizer" to tokenizer)) {
                if (f.startsWith("/") || f.split('/', '\\').contains("..")) throw ManifestException("\"$key\" must be a relative file name inside the model folder")
            }
            val inp = o["input"] as? Map<*, *> ?: throw ManifestException("Missing \"input\" object in model.json")
            val channels = int(inp, "channels", "input.", 3)
            if (channels != 1 && channels != 3) throw ManifestException("\"input.channels\" must be 1 or 3")
            val width = int(inp, "width", "input.")
            val height = int(inp, "height", "input.")
            if (width !in 8..4096 || height !in 8..4096) throw ManifestException("\"input.width\" and \"input.height\" must be between 8 and 4096")
            val resizeText = (inp["resize"] as? String) ?: "stretch"
            val resize = ResizeMode.values().firstOrNull { it.json == resizeText }
                ?: throw ManifestException("\"input.resize\" must be \"stretch\" or \"keep_aspect_pad\", not \"$resizeText\"")
            val pad = when (val p = inp["padColor"]) {
                null -> 255
                is Number -> p.toInt()
                is List<*> -> (p.firstOrNull() as? Number)?.toInt() ?: 255
                else -> throw ManifestException("\"input.padColor\" must be a number")
            }.coerceIn(0, 255)
            val cacheText = (o["decoderCache"] as? String) ?: "none"
            val cache = DecoderCache.values().firstOrNull { it.json == cacheText }
                ?: throw ManifestException("\"decoderCache\" must be \"none\" or \"merged_past\", not \"$cacheText\"")
            val maxTokens = int(o, "maxTokens", default = 256)
            if (maxTokens !in 1..4096) throw ManifestException("\"maxTokens\" must be between 1 and 4096")
            val post = o["postprocess"] as? Map<*, *>
            val postprocess = PostprocessSpec(
                joinSpacedTokens = post?.get("joinSpacedTokens") as? Boolean ?: false,
                stripWrappers = (post?.get("stripWrappers") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            )
            return ModelManifest(
                name = name, encoder = encoder, decoder = decoder, tokenizer = tokenizer,
                input = InputSpec(width, height, channels, resize, pad, floats(inp, "mean", channels, 0f), floats(inp, "std", channels, 1f)),
                bos = int(o, "bos"), eos = int(o, "eos"), pad = int(o, "pad", default = 0),
                maxTokens = maxTokens, decoderCache = cache, postprocess = postprocess,
            ).also { m -> if (m.input.std.any { it == 0f }) throw ManifestException("\"input.std\" must not contain 0") }
        }

        /** Parses model.json in [dir] and checks that every referenced file exists. */
        fun load(dir: File): ModelManifest {
            val f = File(dir, FILE_NAME)
            if (!f.isFile) throw ManifestException("model.json not found")
            val m = parse(f.readText())
            for (name in listOf(m.encoder, m.decoder, m.tokenizer)) {
                if (!File(dir, name).isFile) throw ManifestException("model.json refers to \"$name\", but that file is missing")
            }
            return m
        }
    }
}

class ManifestException(message: String) : Exception(message)
