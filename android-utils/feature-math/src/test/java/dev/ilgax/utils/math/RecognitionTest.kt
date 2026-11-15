package dev.ilgax.utils.math

import dev.ilgax.utils.math.recognition.DecoderCache
import dev.ilgax.utils.math.recognition.ImagePreprocessor
import dev.ilgax.utils.math.recognition.InputSpec
import dev.ilgax.utils.math.recognition.Json
import dev.ilgax.utils.math.recognition.ManifestException
import dev.ilgax.utils.math.recognition.ModelManifest
import dev.ilgax.utils.math.recognition.ModelStore
import dev.ilgax.utils.math.recognition.PixelImage
import dev.ilgax.utils.math.recognition.ResizeMode
import dev.ilgax.utils.math.recognition.TokenizerDecoder
import dev.ilgax.utils.math.recognition.VedRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RecognitionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fakeRoot = File("src/test/resources/fake")
    private val blank = PixelImage(30, 20, IntArray(600) { 0xFFFFFFFF.toInt() })

    private fun runner(name: String): VedRunner {
        val dir = File(fakeRoot, name)
        return VedRunner(ModelManifest.load(dir), dir)
    }

    // ---- the fake models: both cache modes must produce the same tokens ----
    @Test fun fakeNoCache() = runner("fake-none").use { r ->
        val out = r.recognize(blank)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), out.tokenIds)
        assertEquals("1+\\cdot x=2", out.latex)
    }

    /** merged_past is exercised only against this tiny fake graph, not against a real HF merged decoder. */
    @Test fun fakeMergedPast() = runner("fake-past").use { r ->
        assertEquals(DecoderCache.MergedPast, r.manifest.decoderCache)
        val out = r.recognize(blank)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), out.tokenIds)
        assertEquals("1+\\cdot x=2", out.latex)
    }

    @Test fun fakeRunnerIsReusable() = runner("fake-past").use { r ->
        assertEquals(r.recognize(blank).latex, r.recognize(blank).latex)
    }

    @Test fun wrongCacheModeIsRejected() {
        val dir = File(fakeRoot, "fake-past")
        val wrong = ModelManifest.load(dir).copy(decoderCache = DecoderCache.None)
        try { VedRunner(wrong, dir).close(); throw AssertionError("expected failure") } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("merged_past"))
        }
    }

    // ---- manifest ----
    private val goodManifest = """{"name":"m","encoder":"e.onnx","decoder":"d.onnx","tokenizer":"t.json",
        "input":{"width":384,"height":384,"channels":3,"resize":"stretch","mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},
        "bos":1,"eos":2,"pad":0,"maxTokens":256,"decoderCache":"none","postprocess":{"joinSpacedTokens":true,"stripWrappers":["\\[","\\]"]}}"""

    private fun bad(text: String, contains: String) {
        try { ModelManifest.parse(text); throw AssertionError("expected error containing '$contains'") } catch (e: ManifestException) {
            assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
        }
    }

    @Test fun manifestParses() {
        val m = ModelManifest.parse(goodManifest)
        assertEquals(384, m.input.width); assertEquals(ResizeMode.Stretch, m.input.resize)
        assertEquals(listOf("\\[", "\\]"), m.postprocess.stripWrappers); assertEquals(2, m.eos)
    }
    @Test fun manifestBroadcastsSingleMean() = assertEquals(listOf(0.5f, 0.5f, 0.5f), ModelManifest.parse(goodManifest.replace("[0.5,0.5,0.5],\"std\"", "[0.5],\"std\"")).input.mean)
    @Test fun manifestNotJson() = bad("{nope", "not valid JSON")
    @Test fun manifestMissingName() = bad(goodManifest.replace("\"name\":\"m\",", ""), "\"name\"")
    @Test fun manifestBadChannels() = bad(goodManifest.replace("\"channels\":3", "\"channels\":2"), "channels")
    @Test fun manifestBadResize() = bad(goodManifest.replace("stretch", "crop"), "resize")
    @Test fun manifestBadCache() = bad(goodManifest.replace("\"none\"", "\"kv\""), "decoderCache")
    @Test fun manifestBadMean() = bad(goodManifest.replace("[0.5,0.5,0.5],\"std\"", "[0.5,0.5],\"std\""), "mean")
    @Test fun manifestZeroStd() = bad(goodManifest.replace("\"std\":[0.5,0.5,0.5]", "\"std\":[0,0.5,0.5]"), "std")
    @Test fun manifestPathEscape() = bad(goodManifest.replace("e.onnx", "../e.onnx"), "relative")
    @Test fun manifestMissingBos() = bad(goodManifest.replace("\"bos\":1,", ""), "bos")

    // ---- tokenizer ----
    @Test fun tokenizerByteLevel() {
        val t = TokenizerDecoder.parse(File(fakeRoot, "fake-none/tokenizer.json").readText())
        assertEquals(" 1 + \\cdot x = 2", t.decode(listOf(1, 3, 4, 5, 6, 7, 8, 9, 0), skipSpecial = true))
        assertTrue(t.decode(listOf(1, 3), skipSpecial = false).startsWith("<s>"))
    }
    @Test fun tokenizerBundledModelDecodesSpacedTokens() {
        val t = TokenizerDecoder.parse(File(BUNDLED, "tokenizer.json").readText())
        // ids come from the model's own vocab: 21='1', 22='2'
        assertTrue(t.vocabSize > 1800)
        assertEquals("", t.decode(listOf(0, 1, 2)))
    }
    @Test fun tokenizerSentencePiece() {
        val json = """{"added_tokens":[{"id":0,"content":"<s>","special":true}],"decoder":{"type":"Metaspace"},
            "model":{"type":"Unigram","vocab":[["<s>",0.0],["▁x",-1.0],["▁=",-1.0],["<0x41>",-1.0],["é",-1.0]]}}"""
        val t = TokenizerDecoder.parse(json)
        assertEquals("x =Aé", t.decode(listOf(0, 1, 2, 3, 4)))
    }

    // ---- preprocessing ----
    private val spec = InputSpec(8, 4, 3, ResizeMode.Stretch, 255, listOf(0.5f, 0.5f, 0.5f), listOf(0.5f, 0.5f, 0.5f))

    @Test fun preprocessShapeAndRange() {
        val white = PixelImage(40, 20, IntArray(800) { 0xFFFFFFFF.toInt() })
        val t = ImagePreprocessor.toTensor(white, spec)
        assertEquals(3 * 8 * 4, t.size)
        assertTrue(t.all { it == 1f })
        val black = PixelImage(40, 20, IntArray(800) { 0xFF000000.toInt() })
        assertTrue(ImagePreprocessor.toTensor(black, spec).all { it == -1f })
    }
    @Test fun preprocessChannelOrderIsPlanar() {
        val red = PixelImage(8, 4, IntArray(32) { 0xFFFF0000.toInt() })
        val t = ImagePreprocessor.toTensor(red, spec)
        assertEquals(1f, t[0]); assertEquals(-1f, t[32]); assertEquals(-1f, t[64])
    }
    @Test fun preprocessKeepAspectPads() {
        val s = spec.copy(width = 8, height = 8, channels = 1, resize = ResizeMode.KeepAspectPad, padColor = 255, mean = listOf(0f), std = listOf(1f))
        val black = PixelImage(16, 4, IntArray(64) { 0xFF000000.toInt() })
        val t = ImagePreprocessor.toTensor(black, s)
        assertEquals(64, t.size)
        assertEquals(0f, t[0]); assertEquals(1f, t[63]) // image on top, padding below
    }
    @Test fun resizeDownscaleAveragesEdges() {
        // 4x1 -> 2x1 of [0,0,255,255] stays a clean step
        val out = ImagePreprocessor.resize(intArrayOf(0, 0, 255, 255), 4, 1, 2, 1)
        assertTrue(out[0] < 40 && out[1] > 215)
    }

    // ---- json ----
    @Test fun jsonRoundTrip() {
        val v = Json.parse("""{"a":[1,2.5,"x\né",true,null],"b":{}}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, "x\né", true, null), v["a"])
        assertEquals("\"a\\\"b\\\\\"", Json.quote("a\"b\\"))
    }

    // ---- import ----
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun fakeZip(prefix: String = "") = zip(*listOf("model.json", "encoder.onnx", "decoder.onnx", "tokenizer.json")
        .map { prefix + it to File(fakeRoot, "fake-none/$it").readBytes() }.toTypedArray())

    @Test fun importZipInstallsAndListsAndDeletes() {
        val store = ModelStore(tmp.newFolder("models"))
        val m = store.importZip(fakeZip().inputStream())
        assertEquals("fake-none", m.id)
        assertEquals(listOf("fake-none"), store.list().map { it.id })
        assertEquals(m.id, store.importZip(fakeZip().inputStream()).id.removeSuffix("-2"))
        assertTrue(store.delete("fake-none"))
        assertFalse(store.delete("../x"))
    }
    @Test fun importZipFromFolder() = assertNotNull(ModelStore(tmp.newFolder("m2")).importZip(fakeZip("pack/").inputStream()))
    @Test fun importedModelRuns() {
        val m = ModelStore(tmp.newFolder("m3")).importZip(fakeZip().inputStream())
        VedRunner(m.manifest, m.dir).use { assertEquals("1+\\cdot x=2", it.recognize(blank).latex) }
    }
    @Test fun importRejectsMissingManifest() = importError(zip("a.txt" to byteArrayOf(1)), "no model.json")
    @Test fun importRejectsMissingFile() = importError(
        zip("model.json" to File(fakeRoot, "fake-none/model.json").readBytes()), "missing",
    )
    @Test fun importRejectsPathTraversal() = importError(zip("../evil.txt" to byteArrayOf(1)), "unsafe")
    @Test fun importRejectsNotAZip() = importError("hello".toByteArray(), "empty")
    @Test fun failedImportLeavesNothing() {
        val root = tmp.newFolder("m4")
        try { ModelStore(root).importZip(zip("a.txt" to byteArrayOf(1)).inputStream()) } catch (_: ManifestException) {}
        assertEquals(0, root.listFiles()!!.size)
    }

    private fun importError(data: ByteArray, contains: String) {
        try { ModelStore(tmp.newFolder()).importZip(data.inputStream()); throw AssertionError("expected import error") } catch (e: ManifestException) {
            assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
        }
    }

    companion object {
        val BUNDLED = File("src/main/assets/models/p2t-mfr-1.5-int8")
    }
}
