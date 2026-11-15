package dev.ilgax.utils.math

import android.content.Context
import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.recognition.ModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings of the module, backed by SharedPreferences and observable. */
class MathPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("math", Context.MODE_PRIVATE)

    private val _dot = MutableStateFlow(
        runCatching { DotMeaning.valueOf(prefs.getString(KEY_DOT, null) ?: "") }.getOrDefault(DotMeaning.Multiply),
    )
    val dot: StateFlow<DotMeaning> = _dot.asStateFlow()

    private val _corrections = MutableStateFlow(prefs.getBoolean(KEY_CORRECTIONS, true))
    val corrections: StateFlow<Boolean> = _corrections.asStateFlow()

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, ModelStore.BUNDLED_ID) ?: ModelStore.BUNDLED_ID)
    val selectedModel: StateFlow<String> = _model.asStateFlow()

    private val _showTimings = MutableStateFlow(prefs.getBoolean(KEY_TIMINGS, true))
    /** Compact stage timings at the bottom of the result card; on for now so real-device numbers can be reported. */
    val showTimings: StateFlow<Boolean> = _showTimings.asStateFlow()

    private val _xnnpack = MutableStateFlow(prefs.getBoolean(KEY_XNNPACK, false))
    /** ONNX Runtime XNNPACK execution provider. Off by default (not verified to be faster or identical); no UI, flip it with adb if wanted. */
    val xnnpack: StateFlow<Boolean> = _xnnpack.asStateFlow()

    private val _overlayAsked = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY_ASKED, false))
    /** The "Display over other apps" explanation has been shown once; do not nag again. */
    val overlayAsked: StateFlow<Boolean> = _overlayAsked.asStateFlow()

    fun setShowTimings(v: Boolean) { _showTimings.value = v; prefs.edit().putBoolean(KEY_TIMINGS, v).apply() }
    fun setOverlayAsked(v: Boolean) { _overlayAsked.value = v; prefs.edit().putBoolean(KEY_OVERLAY_ASKED, v).apply() }

    fun setDot(v: DotMeaning) { _dot.value = v; prefs.edit().putString(KEY_DOT, v.name).apply() }
    fun setCorrections(v: Boolean) { _corrections.value = v; prefs.edit().putBoolean(KEY_CORRECTIONS, v).apply() }
    fun setSelectedModel(id: String) { _model.value = id; prefs.edit().putString(KEY_MODEL, id).apply() }

    companion object {
        private const val KEY_DOT = "dot"
        private const val KEY_CORRECTIONS = "corrections"
        private const val KEY_MODEL = "model"
        private const val KEY_TIMINGS = "show_timings"
        private const val KEY_XNNPACK = "xnnpack"
        private const val KEY_OVERLAY_ASKED = "overlay_asked"

        @Volatile private var instance: MathPrefs? = null
        fun get(context: Context): MathPrefs = instance ?: synchronized(this) { instance ?: MathPrefs(context).also { instance = it } }
    }
}
