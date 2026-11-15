package dev.ilgax.utils.core.action

import kotlin.math.exp
import kotlin.math.roundToInt

/** Percent to the integer ranges Android's settings and audio APIs use. Pure, so it is unit-tested. */
object Mappings {
    /** Lowest and highest value of Settings.System.SCREEN_BRIGHTNESS (a documented 0..255 range; 0 can mean a black screen). */
    const val BRIGHTNESS_MIN = 1
    const val BRIGHTNESS_MAX = 255

    // The constants of Android's brightness slider curve (BrightnessUtils, an HLG-style transfer function).
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /**
     * What the stock Android brightness slider does: slider position (0..1) to linear brightness (0..1). Equal slider
     * steps look like equal brightness steps, so "40%" matches the slider at 40% rather than 40% of the light output.
     */
    internal fun sliderToLinear(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        val ret = if (p <= R) (p / R) * (p / R) else exp((p - C) / A) + B
        return ret.coerceIn(0f, 12f) / 12f
    }

    /**
     * [percent] of the brightness slider as a Settings.System.SCREEN_BRIGHTNESS value in [BRIGHTNESS_MIN]..[BRIGHTNESS_MAX].
     * Assumes the device uses the documented 0..255 range and the stock slider curve (verified on no device).
     */
    fun brightnessSetting(percent: Int): Int {
        val linear = sliderToLinear(percent.coerceIn(0, 100) / 100f)
        return (BRIGHTNESS_MIN + linear * (BRIGHTNESS_MAX - BRIGHTNESS_MIN)).roundToInt().coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
    }

    /** [percent] of a stream's volume steps between [min] and [max]; 0% is the stream's lowest step. */
    fun volumeIndex(percent: Int, min: Int, max: Int): Int {
        if (max <= min) return min
        return (min + (max - min) * percent.coerceIn(0, 100) / 100f).roundToInt().coerceIn(min, max)
    }
}
