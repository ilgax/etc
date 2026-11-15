package dev.ilgax.utils.math.recognition

import android.graphics.Bitmap

/** Software bitmap -> packed ARGB pixels for [ImagePreprocessor]. */
fun Bitmap.toPixelImage(): PixelImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return PixelImage(width, height, px)
}
