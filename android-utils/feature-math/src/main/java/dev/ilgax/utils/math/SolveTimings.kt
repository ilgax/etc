package dev.ilgax.utils.math

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** The moments of one Solve run, in the order they normally happen. */
enum class Stage(val label: String) {
    ProcessStart("process start"),
    ActivityStart("activity start"),
    CaptureRequested("capture requested"),
    CaptureDone("capture done"),
    BitmapReady("bitmap ready"),
    LassoDone("lasso done"),
    SessionReady("model session ready"),
    EncoderDone("encoder done"),
    DecoderDone("decoder done"),
    SolveDone("solve done"),
    KatexRendered("KaTeX rendered"),
}

/**
 * Wall-clock timestamps ([System.currentTimeMillis]) of the stages of one Solve run. Every mark is logged under
 * [TAG] (`adb logcat -s SolveTimings`); [line] is the compact summary shown at the bottom of the result card.
 * Thread-safe; a stage keeps the first mark it gets, except [Stage.CaptureDone]'s method and the token count,
 * which are plain values.
 */
class SolveTimings(private val clock: () -> Long = System::currentTimeMillis) {
    private val marks = ConcurrentHashMap<Stage, Long>()
    @Volatile var captureMethod: String? = null
    @Volatile var tokens: Int? = null

    /** Called (on the marking thread) after a stage was recorded, so the card can refresh its timings line. */
    @Volatile var onMark: ((Stage) -> Unit)? = null

    fun mark(stage: Stage, at: Long = clock()) {
        if (marks.putIfAbsent(stage, at) != null) return
        val base = marks[Stage.ActivityStart] ?: marks[Stage.ProcessStart] ?: at
        val extra = when (stage) {
            Stage.CaptureDone -> captureMethod?.let { " ($it)" }
            Stage.DecoderDone -> tokens?.let { " ($it tokens)" }
            else -> null
        }.orEmpty()
        Log.i(TAG, "${stage.label}$extra  t=$at  +${at - base} ms")
        onMark?.invoke(stage)
    }

    fun snapshot(): Map<Stage, Long> = HashMap(marks)

    fun has(stage: Stage) = marks.containsKey(stage)

    fun line(): String? = format(snapshot(), tokens)

    /** Logs the compact line once the run is over. */
    fun logSummary() {
        format(snapshot(), tokens)?.let { Log.i(TAG, "summary: $it  [capture=${captureMethod ?: "?"}]") }
    }

    companion object {
        const val TAG = "SolveTimings"

        /** Timings of the Solve run in progress or the last one; read by the KaTeX view to mark "rendered". */
        @Volatile var current: SolveTimings = SolveTimings()

        /**
         * `cap 180 · ocr 420 (load 30, enc 190, dec 230, 14 tok) · solve 3 · tex 40 ms`. Stages that have not
         * happened yet are left out; null while nothing at all can be reported.
         *  - cap: capture requested to a displayable bitmap.
         *  - ocr: lasso finished to decoded text. load is the wait for the model session, enc/dec the model's own time.
         *  - solve: decoded text to answer; tex: answer to KaTeX painted.
         */
        fun format(m: Map<Stage, Long>, tokens: Int? = null): String? {
            val parts = ArrayList<String>()
            val capStart = m[Stage.CaptureRequested]
            val capEnd = m[Stage.BitmapReady] ?: m[Stage.CaptureDone]
            if (capStart != null && capEnd != null) parts += "cap ${capEnd - capStart}"
            val lasso = m[Stage.LassoDone]
            val dec = m[Stage.DecoderDone]
            if (lasso != null && dec != null) {
                val detail = ArrayList<String>()
                val session = m[Stage.SessionReady]
                if (session != null && session > lasso) detail += "load ${session - lasso}"
                val enc = m[Stage.EncoderDone]
                if (enc != null) {
                    detail += "enc ${enc - maxOf(lasso, session ?: lasso)}"
                    detail += "dec ${dec - enc}"
                }
                if (tokens != null) detail += "$tokens tok"
                parts += "ocr ${dec - lasso}" + if (detail.isEmpty()) "" else " (${detail.joinToString(", ")})"
            }
            val solve = m[Stage.SolveDone]
            if (dec != null && solve != null) parts += "solve ${solve - dec}"
            val tex = m[Stage.KatexRendered]
            if (solve != null && tex != null) parts += "tex ${tex - solve}"
            if (parts.isEmpty()) return null
            return parts.joinToString(" · ") + " ms"
        }
    }
}
