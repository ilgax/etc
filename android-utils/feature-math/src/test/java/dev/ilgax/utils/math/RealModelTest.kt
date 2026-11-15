package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.recognition.ModelManifest
import dev.ilgax.utils.math.recognition.PixelImage
import dev.ilgax.utils.math.recognition.VedRunner
import dev.ilgax.utils.math.recognition.toPixelImage
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.ilgax.utils.math.solver.MathSolver
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the real bundled Pix2Text-MFR int8 model (desktop ONNX Runtime) on three of the user's samples.
 * Python gave exactly these strings, so a mismatch means the Kotlin port (preprocess/decode) is wrong.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RealModelTest {
    /** Mirrors scripts/common.py::load_crop: ink bounding box (gray < 160) plus a white margin. */
    private fun loadCrop(name: String): PixelImage {
        val bmp = BitmapFactory.decodeStream(javaClass.getResourceAsStream("/samples/$name.jpg")!!)
        val img = bmp.toPixelImage()
        val w = img.width; val h = img.height
        fun gray(i: Int): Int {
            val p = img.argb[i]
            return (((p shr 16) and 255) * 19595 + ((p shr 8) and 255) * 38470 + (p and 255) * 7471 + 0x8000) shr 16
        }
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (gray(y * w + x) < 160) {
            x0 = minOf(x0, x); x1 = max(x1, x + 1); y0 = minOf(y0, y); y1 = max(y1, y + 1)
        }
        val m = max(6, (0.04 * max(y1 - y0, x1 - x0)).roundToInt())
        val ow = x1 - x0 + 2 * m
        val oh = y1 - y0 + 2 * m
        val out = IntArray(ow * oh) { 0xFFFFFFFF.toInt() }
        for (y in 0 until oh) for (x in 0 until ow) {
            val sx = x + x0 - m
            val sy = y + y0 - m
            if (sx in 0 until w && sy in 0 until h) out[y * ow + x] = img.argb[sy * w + sx] or (0xFF shl 24)
        }
        return PixelImage(ow, oh, out)
    }

    private fun check(sample: String, expectedLatex: String, expectedAnswer: String) {
        val out = runner.recognize(loadCrop(sample))
        println("REAL-MODEL $sample raw='${out.raw}' latex='${out.latex}' tokens=${out.tokenIds.size} encoder=${out.encoderMs}ms decoder=${out.decoderMs}ms")
        assertEquals(expectedLatex, out.latex)
        val norm = LatexNormalizer.normalize(out.latex)
        val res = MathSolver.solve(norm.latex, norm.target)
        assertTrue(res is SolveResult.Solved)
        println("REAL-MODEL $sample answer='${(res as SolveResult.Solved).answers.joinToString { it.plain }}'")
        assertEquals(expectedAnswer, res.answers[0].plain)
    }

    @Test fun sample17PlusX() = check("s03", "17+x=15+11", "x = 9")
    @Test fun sample18Plus12() = check("s05", "18+12+2x=60", "x = 15")
    @Test fun sample12x() = check("s08", "12x+3x+9x=96", "x = 4")

    @Test fun rawTokensMatchPython() {
        // Python printed the space-separated tokens, which the manifest joins.
        assertEquals("1 7 + x = 1 5 + 1 1", runner.recognize(loadCrop("s03")).raw)
    }

    companion object {
        lateinit var runner: VedRunner

        @BeforeClass @JvmStatic fun load() {
            val dir = File("src/main/assets/models/p2t-mfr-1.5-int8")
            runner = VedRunner(ModelManifest.load(dir), dir)
        }
    }
}
