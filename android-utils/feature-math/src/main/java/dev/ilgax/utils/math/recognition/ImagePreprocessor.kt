package dev.ilgax.utils.math.recognition

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Packed ARGB pixels, row-major (the layout of Bitmap.getPixels and BufferedImage.getRGB). */
class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) }
}

/**
 * Image -> NCHW float tensor as described by [InputSpec]. Pure Kotlin so Android and the JVM tests share
 * it. The resize is a port of Pillow's separable BICUBIC resampling (with antialiasing when shrinking,
 * 8-bit intermediate rounding), which is what the reference Python pipeline used.
 */
object ImagePreprocessor {
    fun toTensor(image: PixelImage, spec: InputSpec): FloatArray {
        val out = FloatArray(spec.channels * spec.width * spec.height)
        fill(image, spec, java.nio.FloatBuffer.wrap(out))
        return out
    }

    /** Same as [toTensor] but writes into [out] (absolute puts, capacity must be channels*width*height) so the caller can reuse one buffer. */
    fun fill(image: PixelImage, spec: InputSpec, out: java.nio.FloatBuffer) {
        require(out.capacity() >= spec.channels * spec.width * spec.height) { "tensor buffer too small" }
        val planes = planes(image, spec.channels)
        val w = spec.width
        val h = spec.height
        val resized: List<IntArray>
        when (spec.resize) {
            ResizeMode.Stretch -> resized = planes.map { resize(it, image.width, image.height, w, h) }
            ResizeMode.KeepAspectPad -> {
                val scale = min(w.toDouble() / image.width, h.toDouble() / image.height)
                val nw = max(1, (image.width * scale).roundToInt()).coerceAtMost(w)
                val nh = max(1, (image.height * scale).roundToInt()).coerceAtMost(h)
                resized = planes.map { plane ->
                    val small = resize(plane, image.width, image.height, nw, nh)
                    val out = IntArray(w * h) { spec.padColor }
                    for (y in 0 until nh) System.arraycopy(small, y * nw, out, y * w, nw)
                    out
                }
            }
        }
        for (c in 0 until spec.channels) {
            val mean = spec.mean[c]
            val std = spec.std[c]
            val plane = resized[c]
            val base = c * w * h
            for (i in 0 until w * h) out.put(base + i, (plane[i] / 255f - mean) / std)
        }
        out.clear()
    }

    /** 8-bit planes (R,G,B) or one luma plane (PIL "L"), composited over white. */
    private fun planes(img: PixelImage, channels: Int): List<IntArray> {
        val n = img.width * img.height
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val p = img.argb[i]
            val a = p ushr 24
            var rr = (p shr 16) and 0xFF; var gg = (p shr 8) and 0xFF; var bb = p and 0xFF
            if (a != 255) {
                rr = (rr * a + 255 * (255 - a) + 127) / 255
                gg = (gg * a + 255 * (255 - a) + 127) / 255
                bb = (bb * a + 255 * (255 - a) + 127) / 255
            }
            r[i] = rr; g[i] = gg; b[i] = bb
        }
        if (channels == 3) return listOf(r, g, b)
        return listOf(IntArray(n) { (r[it] * 19595 + g[it] * 38470 + b[it] * 7471 + 0x8000) shr 16 })
    }

    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        var cur = src
        var curW = sw
        if (dw != sw) { cur = pass(cur, sw, sh, dw, horizontal = true); curW = dw }
        if (dh != sh) cur = pass(cur, curW, sh, dh, horizontal = false)
        return cur
    }

    private fun filter(x0: Double): Double {
        val a = -0.5
        val x = abs(x0)
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1
            x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
            else -> 0.0
        }
    }

    /** One Pillow resampling pass along one axis. */
    private fun pass(src: IntArray, w: Int, h: Int, outSize: Int, horizontal: Boolean): IntArray {
        val inSize = if (horizontal) w else h
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val starts = IntArray(outSize)
        val weights = arrayOfNulls<DoubleArray>(outSize)
        for (o in 0 until outSize) {
            val center = (o + 0.5) * scale
            val xmin = max(0, (center - support + 0.5).toInt())
            val xmax = min(inSize, (center + support + 0.5).toInt())
            val ww = DoubleArray(xmax - xmin)
            var sum = 0.0
            for (x in ww.indices) { ww[x] = filter((x + xmin - center + 0.5) / filterScale); sum += ww[x] }
            if (sum != 0.0) for (x in ww.indices) ww[x] /= sum
            starts[o] = xmin
            weights[o] = ww
        }
        val out = if (horizontal) IntArray(outSize * h) else IntArray(w * outSize)
        if (horizontal) {
            for (y in 0 until h) for (o in 0 until outSize) {
                var acc = 0.0
                val ww = weights[o]!!
                val x0 = starts[o]
                for (k in ww.indices) acc += src[y * w + x0 + k] * ww[k]
                out[y * outSize + o] = clip(acc)
            }
        } else {
            for (o in 0 until outSize) {
                val ww = weights[o]!!
                val y0 = starts[o]
                for (x in 0 until w) {
                    var acc = 0.0
                    for (k in ww.indices) acc += src[(y0 + k) * w + x] * ww[k]
                    out[o * w + x] = clip(acc)
                }
            }
        }
        return out
    }

    private fun clip(v: Double): Int = floor(v + 0.5).toInt().coerceIn(0, 255)
}
