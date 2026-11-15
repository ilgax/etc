package dev.ilgax.utils.math.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

data class Recognition(
    /** Decoded text before post-processing. */
    val raw: String,
    /** After [PostprocessSpec]: what the user sees and edits. */
    val latex: String,
    val tokenIds: List<Int>,
    val encoderMs: Long,
    val decoderMs: Long,
)

/**
 * Generic VisionEncoderDecoder runner driven by a [ModelManifest]: encoder once, then greedy decoding.
 * `decoderCache = none` re-feeds the whole prefix each step; `merged_past` uses a Hugging Face merged
 * decoder (past_key_values.* inputs, present.* outputs, use_cache_branch flag).
 * Not thread-safe: call [recognize] from one coroutine at a time.
 */
class VedRunner(val manifest: ModelManifest, dir: File) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val tokenizer: TokenizerDecoder = TokenizerDecoder.parse(File(dir, manifest.tokenizer).readText())

    private val encInput: String
    private val decIds: String
    private val decHidden: String
    private val logitsName: String
    private val pastNames: List<String>
    private val pastShapes: Map<String, LongArray>
    private val hasCacheFlag: Boolean

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        fun opts() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(File(dir, manifest.encoder).path, opts())
        decoder = env.createSession(File(dir, manifest.decoder).path, opts())
        encInput = encoder.inputNames.first()
        val names = decoder.inputNames.toList()
        decIds = names.firstOrNull { it == "input_ids" } ?: names.first()
        decHidden = names.firstOrNull { it == "encoder_hidden_states" } ?: names.first { it != decIds }
        logitsName = decoder.outputNames.firstOrNull { it == "logits" } ?: decoder.outputNames.first()
        pastNames = names.filter { it.startsWith("past_key_values") }
        hasCacheFlag = "use_cache_branch" in names
        pastShapes = pastNames.associateWith { (decoder.inputInfo[it]!!.info as TensorInfo).shape }
        if (manifest.decoderCache == DecoderCache.MergedPast && pastNames.isEmpty()) {
            throw ManifestException("model.json says merged_past but the decoder has no past_key_values inputs")
        }
        if (manifest.decoderCache == DecoderCache.None && pastNames.isNotEmpty()) {
            throw ManifestException("The decoder needs past_key_values: set \"decoderCache\": \"merged_past\"")
        }
    }

    fun recognize(image: PixelImage): Recognition {
        val input = ImagePreprocessor.toTensor(image, manifest.input)
        val shape = longArrayOf(1, manifest.input.channels.toLong(), manifest.input.height.toLong(), manifest.input.width.toLong())
        val t0 = System.nanoTime()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { pixels ->
            encoder.run(mapOf(encInput to pixels)).use { enc ->
                val hidden = enc[0] as OnnxTensor
                val t1 = System.nanoTime()
                val ids = if (manifest.decoderCache == DecoderCache.None) decodeNoCache(hidden) else decodeMergedPast(hidden)
                val t2 = System.nanoTime()
                val raw = tokenizer.decode(ids.drop(1), skipSpecial = true).trim()
                return Recognition(raw, postprocess(raw), ids.drop(1), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
            }
        }
    }

    private fun postprocess(raw: String): String {
        var s = raw
        if (manifest.postprocess.joinSpacedTokens) s = LatexJoin.join(s)
        return LatexJoin.stripWrappers(s, manifest.postprocess.stripWrappers)
    }

    private fun argmaxLastRow(logits: OnnxTensor): Int {
        val shape = logits.info.shape
        val vocab = shape.last().toInt()
        val rows = shape[shape.size - 2].toInt()
        val buf = logits.floatBuffer
        val base = (rows - 1) * vocab
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val v = buf.get(base + i)
            if (v > bestV) { bestV = v; best = i }
        }
        return best
    }

    /** Whole prefix every step (decoderCache = none). */
    private fun decodeNoCache(hidden: OnnxTensor): List<Int> {
        val ids = ArrayList<Int>().apply { add(manifest.bos) }
        repeat(manifest.maxTokens) {
            val arr = LongArray(ids.size) { ids[it].toLong() }
            OnnxTensor.createTensor(env, LongBuffer.wrap(arr), longArrayOf(1, arr.size.toLong())).use { idTensor ->
                decoder.run(mapOf(decIds to idTensor, decHidden to hidden), setOf(logitsName)).use { out ->
                    val next = argmaxLastRow(out[0] as OnnxTensor)
                    if (next == manifest.eos) return ids
                    ids += next
                }
            }
        }
        return ids
    }

    /** One token per step with past_key_values (decoderCache = merged_past). Mirrors the Python reference runner. */
    private fun decodeMergedPast(hidden: OnnxTensor): List<Int> {
        val ids = ArrayList<Int>().apply { add(manifest.bos) }
        val owned = ArrayList<OnnxTensor>() // initial empty tensors
        val past = HashMap<String, OnnxTensor>()
        val results = ArrayList<OrtSession.Result>()
        try {
            for (n in pastNames) {
                val s = pastShapes.getValue(n)
                val t = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, s[1], 0, s[3]))
                owned += t
                past[n] = t
            }
            var useCache = false
            repeat(manifest.maxTokens) {
                val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.last().toLong())), longArrayOf(1, 1))
                val flag = if (hasCacheFlag) OnnxTensor.createTensor(env, booleanArrayOf(useCache)) else null
                try {
                    val feed = HashMap<String, OnnxTensor>()
                    feed[decIds] = idTensor
                    feed[decHidden] = hidden
                    feed.putAll(past)
                    if (flag != null) feed["use_cache_branch"] = flag
                    val out = decoder.run(feed)
                    results += out
                    val next = argmaxLastRow(out.get(logitsName).get() as OnnxTensor)
                    for (n in pastNames) {
                        val present = out.get(n.replace("past_key_values", "present"))
                        if (present.isPresent && !(useCache && ".encoder." in n)) past[n] = present.get() as OnnxTensor
                    }
                    // Keep the first result (it owns the encoder cache) and the newest; free the rest.
                    while (results.size > 2) results.removeAt(1).close()
                    useCache = true
                    if (next == manifest.eos) return ids
                    ids += next
                } finally {
                    idTensor.close()
                    flag?.close()
                }
            }
            return ids
        } finally {
            results.forEach { it.close() }
            owned.forEach { it.close() }
        }
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}
