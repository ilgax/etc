package dev.ilgax.utils.remote.macros

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class StepStatus { Ok, Failed, Skipped, NotRun }

class StepRecord(val index: Int, val actionId: String, val summary: String, val status: StepStatus, val message: String?, val durationMs: Long)

enum class Origin { Watch, Local }

/** One finished run. [failedStep] is 1-based, 0 when every step was fine (skipped steps are fine). */
class RunRecord(
    val startedAt: Long,
    val macroId: Int,
    val macroName: String,
    val origin: Origin,
    val failedStep: Int,
    val steps: List<StepRecord>,
) {
    val ok: Boolean get() = failedStep == 0
}

/** The last [MAX] runs with per-step results, newest first, kept in a small JSON file. */
class RunLog(private val file: File?) {
    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs.asStateFlow()

    init {
        load()
    }

    @Synchronized
    fun add(record: RunRecord) {
        _runs.value = (listOf(record) + _runs.value).take(MAX)
        save()
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (r in _runs.value) {
                arr.put(JSONObject().apply {
                    put("at", r.startedAt); put("id", r.macroId); put("name", r.macroName)
                    put("origin", r.origin.name); put("failed", r.failedStep)
                    put("steps", JSONArray().also { sa ->
                        r.steps.forEach { s ->
                            sa.put(JSONObject().apply {
                                put("i", s.index); put("a", s.actionId); put("s", s.summary); put("st", s.status.name)
                                put("m", s.message ?: JSONObject.NULL); put("ms", s.durationMs)
                            })
                        }
                    })
                })
            }
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("schema", 1).put("runs", arr).toString())
        } catch (_: Exception) {
            // The history is a convenience; never fail a run over it.
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            if (root.optInt("schema", 0) != 1) return
            val arr = root.getJSONArray("runs")
            _runs.value = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val sa = o.getJSONArray("steps")
                RunRecord(
                    o.getLong("at"), o.getInt("id"), o.getString("name"), Origin.valueOf(o.getString("origin")), o.getInt("failed"),
                    List(sa.length()) { j ->
                        val s = sa.getJSONObject(j)
                        StepRecord(
                            s.getInt("i"), s.getString("a"), s.getString("s"), StepStatus.valueOf(s.getString("st")),
                            if (s.isNull("m")) null else s.getString("m"), s.getLong("ms"),
                        )
                    },
                )
            }.take(MAX)
        } catch (_: Exception) {
            _runs.value = emptyList()
        }
    }

    companion object {
        const val MAX = 20
    }
}
